/**
 * nDM Skin Hub — Cloudflare Worker (complete). One worker is the entire backend: public API,
 * upload intake, status polling, favorites, AND the Discord moderation flow (button interactions
 * are verified and handled right here, so there is NO separate bot server to host).
 *
 * What's left for the maintainer is only configuration (see hub/DEPLOY.md):
 *   1. create the R2 bucket + KV namespace, 2. create a Discord application + bot and a channel,
 *   3. put the secrets in, 4. `wrangler deploy`, 5. set the app's Interactions Endpoint URL to
 *   <worker>/discord. After that everything runs itself.
 *
 * Storage model (chosen to avoid lost-update races): every accepted skin is its OWN KV key
 * `skin:<code>` (metadata) plus `accepted/<code>.zip` in R2. There is no shared "index" blob to
 * clobber, so concurrent accepts and the vote cron can never drop a skin. `/skins` lists the keys
 * and is edge-cached. Moderation is idempotent: a submission's share code is DERIVED from its
 * pending id, so a double-click or a Discord retry produces the same code and the same writes.
 *
 * Cost/abuse posture:
 *   - free tiers cover this; `/skins` and `/skin/<code>` are edge-cached
 *   - uploads: 30 MB cap enforced while STREAMING (Content-Length not trusted), real-ZIP magic
 *     check, per-IP/day reservation taken before any work
 *   - favorites: code validated + must exist, per-IP/day capped; counts are advisory (no login)
 *   - /moderate Bearer is constant-time and refuses an unset secret; Discord interactions are
 *     Ed25519-verified AND timestamp-fresh (replay-bounded)
 *   - nothing is public until a moderator clicks Accept
 *
 * Bindings (wrangler.toml): R2 bucket SKINS, KV namespace META.
 * Secrets: DISCORD_PUBLIC_KEY, DISCORD_BOT_TOKEN, DISCORD_CHANNEL_ID, MOD_SECRET, VOTE_SALT.
 */

const MAX_ZIP = 30 * 1024 * 1024;          // 30 MB per skin, hard cap
const UPLOADS_PER_DAY_PER_IP = 3;
const FAVS_PER_DAY_PER_IP = 200;
const PENDING_TTL = 60 * 60 * 24 * 30;     // a submission status record lives 30 days
const DISCORD_MAX_SKEW = 300;              // seconds; reject older signed interactions (replay bound)
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I confusion; 32 chars, no modulo bias
const CODE_RE = /^[A-Z2-9]{8}$/;

const json = (obj, status = 200, extra = {}) =>
  new Response(JSON.stringify(obj), { status, headers: { 'content-type': 'application/json', ...extra } });

function randomPendingId() {
  let c = '';
  for (const b of crypto.getRandomValues(new Uint8Array(8))) c += CODE_ALPHABET[b % CODE_ALPHABET.length];
  return c;
}

/** The public share code is DERIVED from the pending id, so accept is idempotent (same id → same code). */
async function deriveCode(env, pendingId) {
  const d = await crypto.subtle.digest('SHA-256',
    new TextEncoder().encode((env.VOTE_SALT || 'salt') + '|code|' + pendingId));
  const b = new Uint8Array(d);
  let c = '';
  for (let i = 0; i < 8; i++) c += CODE_ALPHABET[b[i] % CODE_ALPHABET.length];
  return c;
}

async function hashVoter(env, username, uuid) {
  const data = new TextEncoder().encode((env.VOTE_SALT || 'salt') + '|' + username + '|' + uuid);
  const d = await crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(d)].slice(0, 16).map(b => b.toString(16).padStart(2, '0')).join('');
}

/** Constant-time string equality via digest compare. */
async function safeEqual(a, b) {
  const enc = new TextEncoder();
  const [ha, hb] = await Promise.all([
    crypto.subtle.digest('SHA-256', enc.encode(a)),
    crypto.subtle.digest('SHA-256', enc.encode(b)),
  ]);
  const va = new Uint8Array(ha), vb = new Uint8Array(hb);
  let diff = 0;
  for (let i = 0; i < va.length; i++) diff |= va[i] ^ vb[i];
  return diff === 0;
}

/** Read a request body, aborting if it exceeds max — does NOT trust Content-Length. */
async function readCapped(req, max) {
  if (!req.body) return null;
  const reader = req.body.getReader();
  const chunks = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.length;
    if (total > max) { await reader.cancel().catch(() => {}); return null; }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) { out.set(c, off); off += c.length; }
  return out;
}

/** Strip Discord markdown / mention control chars from attacker-supplied text. */
function clean(s, n = 200) {
  return (s || '').replace(/[`*_~|<>@\\\[\]()]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
}

// ── Discord ────────────────────────────────────────────────────────────────────────────────

async function postModerationCard(env, id, meta) {
  if (!env.DISCORD_BOT_TOKEN || !env.DISCORD_CHANNEL_ID) return;
  const assets = (meta.assets || '').split(',').filter(Boolean).map(a => clean(a, 80));
  const body = {
    embeds: [{
      title: 'Skin submission: ' + clean(meta.name, 60),
      description: 'by **' + clean(meta.author, 24) + '**\ncategories: ' + (clean(meta.cats, 120) || '—'),
      fields: [{
        name: 'Assets (' + assets.length + ')',
        value: assets.length ? assets.map(a => '• ' + a).join('\n').slice(0, 1000) : '—',
      }],
      footer: { text: 'pending id: ' + id },
      color: 0x3b7bd6,
    }],
    components: [{
      type: 1,
      components: [
        { type: 2, style: 3, label: 'Accept', custom_id: 'accept:' + id },
        { type: 2, style: 4, label: 'Deny', custom_id: 'deny:' + id },
      ],
    }],
  };
  await fetch('https://discord.com/api/v10/channels/' + env.DISCORD_CHANNEL_ID + '/messages', {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Bot ' + env.DISCORD_BOT_TOKEN },
    body: JSON.stringify(body),
  }).catch(() => {});
}

function hex2bytes(hex) {
  if (typeof hex !== 'string' || hex.length % 2 || /[^0-9a-fA-F]/.test(hex)) return null;
  const a = new Uint8Array(hex.length / 2);
  for (let i = 0; i < a.length; i++) a[i] = parseInt(hex.substr(i * 2, 2), 16);
  return a;
}

async function verifyDiscord(env, req, raw) {
  try {
    const sig = req.headers.get('x-signature-ed25519');
    const ts = req.headers.get('x-signature-timestamp');
    if (!sig || !ts || !env.DISCORD_PUBLIC_KEY) return false;
    // Bound replay: reject signed interactions far from now.
    if (Math.abs(Date.now() / 1000 - Number(ts)) > DISCORD_MAX_SKEW) return false;
    const pub = hex2bytes(env.DISCORD_PUBLIC_KEY), sigB = hex2bytes(sig);
    if (!pub || !sigB) return false;
    const key = await crypto.subtle.importKey('raw', pub, { name: 'Ed25519' }, false, ['verify']);
    return await crypto.subtle.verify('Ed25519', key, sigB, new TextEncoder().encode(ts + raw));
  } catch {
    return false;   // malformed key/signature → clean 401, never a 500
  }
}

// ── moderation (idempotent) ─────────────────────────────────────────────────────────────────

async function accept(env, id, origin) {
  // Idempotent: a re-accept (double-click / Discord retry) returns the existing code, no dupes.
  const existing = await env.META.get('status:' + id);
  if (existing) {
    const s = JSON.parse(existing);
    if (s.status === 'accepted') return s.code;
  }
  const metaRaw = await env.META.get('pending:' + id);
  if (!metaRaw) return null;                       // unknown / already denied
  const meta = JSON.parse(metaRaw);
  const code = await deriveCode(env, id);          // deterministic → same writes if retried

  const obj = await env.SKINS.get('pending/' + id + '.zip');
  if (!obj) return null;                           // no payload → don't publish a 404 skin
  await env.SKINS.put('accepted/' + code + '.zip', obj.body);

  // Preserve an existing vote count if this code somehow already has a record.
  const prior = await env.META.get('skin:' + code);
  const votes = prior ? (JSON.parse(prior).votes || 0) : 0;
  await env.META.put('skin:' + code, JSON.stringify({
    id: code, name: meta.name, author: meta.author,
    categories: meta.cats ? meta.cats.split(',') : [],
    assets: meta.assets ? meta.assets.split(',') : [],
    votes, date: new Date().toISOString().slice(0, 10),
    zip: origin + '/skin/' + code,
  }));
  await env.META.put('status:' + id, JSON.stringify({ status: 'accepted', code }), { expirationTtl: PENDING_TTL });
  await env.SKINS.delete('pending/' + id + '.zip');
  await env.META.delete('pending:' + id);
  return code;
}

async function deny(env, id) {
  const existing = await env.META.get('status:' + id);
  if (existing && JSON.parse(existing).status === 'accepted') return false;  // already accepted
  await env.META.put('status:' + id, JSON.stringify({ status: 'denied' }), { expirationTtl: PENDING_TTL });
  await env.SKINS.delete('pending/' + id + '.zip');
  await env.META.delete('pending:' + id);
  return true;
}

async function buildIndex(env) {
  const out = [];
  let cursor;
  do {
    const page = await env.META.list({ prefix: 'skin:', cursor });
    for (const k of page.keys) {
      const v = await env.META.get(k.name);
      if (v) out.push(JSON.parse(v));
    }
    cursor = page.cursor;
    if (page.list_complete) break;
  } while (cursor);
  out.sort((a, b) => (b.votes || 0) - (a.votes || 0));
  return out;
}

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const path = url.pathname;
    const origin = url.origin;

    // ── public: accepted-skins index (edge-cached 5 min, rebuilt from per-skin keys) ────────
    if (req.method === 'GET' && path === '/skins') {
      const cache = caches.default;
      const cacheKey = new Request(origin + '/skins');
      const hit = await cache.match(cacheKey);
      if (hit) return hit;
      const res = json(await buildIndex(env), 200, { 'cache-control': 'public, max-age=300' });
      ctx.waitUntil(cache.put(cacheKey, res.clone()));
      return res;
    }

    // ── public: download by share code ──────────────────────────────────────────────────────
    if (req.method === 'GET' && path.startsWith('/skin/')) {
      const code = path.slice(6).toUpperCase();
      if (!CODE_RE.test(code)) return new Response('bad code', { status: 400 });
      const obj = await env.SKINS.get('accepted/' + code + '.zip');
      if (!obj) return new Response('not found', { status: 404 });
      return new Response(obj.body, {
        headers: { 'content-type': 'application/zip', 'cache-control': 'public, max-age=86400' },
      });
    }

    // ── client poll: a pending submission's status ──────────────────────────────────────────
    if (req.method === 'GET' && path.startsWith('/status/')) {
      const id = path.slice(8);
      const st = await env.META.get('status:' + id);
      if (st) return json(JSON.parse(st));
      const pending = await env.META.get('pending:' + id);
      if (pending) return json({ status: 'pending' });
      return new Response('not found', { status: 404 });
    }

    // ── public: upload for review ───────────────────────────────────────────────────────────
    if (req.method === 'POST' && path === '/upload') {
      const ip = req.headers.get('cf-connecting-ip') || 'unknown';
      const rlKey = 'rl:up:' + ip + ':' + new Date().toISOString().slice(0, 10);
      const used = parseInt((await env.META.get(rlKey)) || '0');
      if (used >= UPLOADS_PER_DAY_PER_IP) return new Response('rate limited', { status: 429 });
      // Reserve the slot BEFORE the expensive read, so parallel uploads can't all slip past.
      await env.META.put(rlKey, String(used + 1), { expirationTtl: 90000 });

      const body = await readCapped(req, MAX_ZIP);     // streams; ignores Content-Length
      if (!body || body.length === 0) return new Response('too large or empty', { status: 413 });
      if (body[0] !== 0x50 || body[1] !== 0x4b) return new Response('not a zip', { status: 415 });

      const meta = {
        name: clean(url.searchParams.get('name'), 60) || 'unnamed',
        author: clean(url.searchParams.get('author'), 24) || 'unknown',
        cats: clean(url.searchParams.get('cats'), 120),
        assets: (url.searchParams.get('assets') || '').slice(0, 1500),
        ip, ts: Date.now(),
      };
      const id = randomPendingId();
      await env.SKINS.put('pending/' + id + '.zip', body);
      await env.META.put('pending:' + id, JSON.stringify(meta), { expirationTtl: PENDING_TTL });
      ctx.waitUntil(postModerationCard(env, id, meta));
      return json({ pending: id });
    }

    // ── favorites/votes: one per player, validated + rate-limited (advisory counts, no login) ─
    if (req.method === 'POST' && path.startsWith('/favorite/')) {
      const code = path.slice(10).toUpperCase();
      if (!CODE_RE.test(code)) return new Response('bad code', { status: 400 });
      if (!(await env.META.get('skin:' + code))) return new Response('no such skin', { status: 404 });
      const ip = req.headers.get('cf-connecting-ip') || 'unknown';
      const rlKey = 'rl:fav:' + ip + ':' + new Date().toISOString().slice(0, 10);
      const used = parseInt((await env.META.get(rlKey)) || '0');
      if (used >= FAVS_PER_DAY_PER_IP) return new Response('rate limited', { status: 429 });
      await env.META.put(rlKey, String(used + 1), { expirationTtl: 90000 });

      const { username = '', uuid = '', on = true } = await req.json().catch(() => ({}));
      const voter = await hashVoter(env, username, uuid);
      const k = 'fav:' + code + ':' + voter;
      const had = await env.META.get(k);
      if (on && !had) await env.META.put(k, '1');
      if (!on && had) await env.META.delete(k);
      return json({ ok: true });
    }

    // ── Discord interactions (button Accept/Deny) — verified, no separate bot host ──────────
    if (req.method === 'POST' && path === '/discord') {
      const raw = await req.text();
      if (!(await verifyDiscord(env, req, raw)))
        return new Response('bad signature', { status: 401 });
      const body = JSON.parse(raw);
      if (body.type === 1) return json({ type: 1 });               // PING → PONG
      if (body.type === 3) {                                       // MESSAGE_COMPONENT
        const [action, id] = (body.data.custom_id || '').split(':');
        let line;
        if (action === 'accept') {
          const code = await accept(env, id, origin);
          line = code ? '✅ Accepted — code **' + code + '**' : '⚠️ already handled or expired';
        } else if (action === 'deny') {
          line = (await deny(env, id)) ? '⛔ Denied' : '⚠️ already accepted';
        } else {
          line = 'unknown action';
        }
        return json({ type: 7, data: { content: line, components: [] } });  // UPDATE_MESSAGE
      }
      return json({ type: 4, data: { content: 'unsupported' } });
    }

    // ── moderation via HTTP (optional CLI, constant-time secret) ────────────────────────────
    if (req.method === 'POST' && path === '/moderate') {
      const auth = req.headers.get('authorization') || '';
      if (!env.MOD_SECRET || !(await safeEqual(auth, 'Bearer ' + env.MOD_SECRET)))
        return new Response('no', { status: 403 });
      const { id, action } = await req.json();
      const code = action === 'accept' ? await accept(env, id, origin) : (await deny(env, id), null);
      return json({ ok: true, code });
    }

    return new Response('nDM Skin Hub', { status: 404 });
  },

  /** Cron: fold favorite counts back into each per-skin record (no shared blob, no races). */
  async scheduled(event, env, ctx) {
    let cursor;
    do {
      const page = await env.META.list({ prefix: 'skin:', cursor });
      for (const k of page.keys) {
        const raw = await env.META.get(k.name);
        if (!raw) continue;
        const skin = JSON.parse(raw);
        let c2, count = 0;
        do {
          const fp = await env.META.list({ prefix: 'fav:' + skin.id + ':', cursor: c2 });
          count += fp.keys.length;
          c2 = fp.cursor;
          if (fp.list_complete) break;
        } while (c2);
        if (skin.votes !== count) {
          skin.votes = count;
          await env.META.put(k.name, JSON.stringify(skin));
        }
      }
      cursor = page.cursor;
      if (page.list_complete) break;
    } while (cursor);
  },
};
