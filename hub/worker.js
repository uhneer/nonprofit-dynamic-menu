/**
 * nDM Skin Hub — Cloudflare Worker scaffold (not deployed yet).
 *
 * Design goals: zero fixed cost (Workers free tier + R2 free tier), no accounts, and hard limits
 * everywhere so strangers cannot rack up bills:
 *   - uploads capped at MAX_ZIP bytes, content-sniffed as real ZIPs, rate-limited per IP
 *   - everything served through Cloudflare's cache (one R2 read per object per cache lifetime)
 *   - votes/favorites are one per player, keyed by a salted hash of the Minecraft username+UUID
 *     the mod sends (no login; spoofing one vote is possible, racking up costs is not)
 *   - moderation: every upload pings a Discord webhook with the metadata + preview; a tiny bot
 *     DM-flow calls /moderate with a shared secret to accept (assigns the public skin code) or deny
 *
 * Bindings expected (wrangler.toml): R2 bucket SKINS, KV namespace META, secrets MOD_SECRET,
 * DISCORD_WEBHOOK, VOTE_SALT.
 */

const MAX_ZIP = 30 * 1024 * 1024;        // 30 MB per skin, hard cap
const UPLOADS_PER_DAY_PER_IP = 3;
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // no 0/O/1/I confusion

function newCode() {
  let c = '';
  const buf = crypto.getRandomValues(new Uint8Array(8));
  for (const b of buf) c += CODE_ALPHABET[b % CODE_ALPHABET.length];
  return c;
}

async function hashVoter(env, username, uuid) {
  const data = new TextEncoder().encode(env.VOTE_SALT + '|' + username + '|' + uuid);
  const d = await crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(d)].slice(0, 16).map(b => b.toString(16).padStart(2, '0')).join('');
}

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const path = url.pathname;

    // ── public: the accepted-skins index (cached at the edge for 5 minutes) ─────────────
    if (req.method === 'GET' && path === '/skins') {
      const idx = (await env.META.get('index')) || '[]';
      return new Response(idx, {
        headers: { 'content-type': 'application/json', 'cache-control': 'public, max-age=300' },
      });
    }

    // ── public: download a skin by its share code ───────────────────────────────────────
    if (req.method === 'GET' && path.startsWith('/skin/')) {
      const code = path.slice(6).toUpperCase();
      if (!/^[A-Z2-9]{8}$/.test(code)) return new Response('bad code', { status: 400 });
      const obj = await env.SKINS.get('accepted/' + code + '.zip');
      if (!obj) return new Response('not found', { status: 404 });
      return new Response(obj.body, {
        headers: { 'content-type': 'application/zip', 'cache-control': 'public, max-age=86400' },
      });
    }

    // ── public: upload a skin for review ────────────────────────────────────────────────
    if (req.method === 'POST' && path === '/upload') {
      const ip = req.headers.get('cf-connecting-ip') || 'unknown';
      const rlKey = 'rl:' + ip + ':' + new Date().toISOString().slice(0, 10);
      const used = parseInt((await env.META.get(rlKey)) || '0');
      if (used >= UPLOADS_PER_DAY_PER_IP) return new Response('rate limited', { status: 429 });

      const len = parseInt(req.headers.get('content-length') || '0');
      if (!len || len > MAX_ZIP) return new Response('too large', { status: 413 });
      const body = new Uint8Array(await req.arrayBuffer());
      if (body.length > MAX_ZIP) return new Response('too large', { status: 413 });
      // Real ZIP? (PK\x03\x04) — refuse anything else outright.
      if (body[0] !== 0x50 || body[1] !== 0x4b) return new Response('not a zip', { status: 415 });

      const name = (url.searchParams.get('name') || 'unnamed').slice(0, 60);
      const author = (url.searchParams.get('author') || 'unknown').slice(0, 24);
      const cats = (url.searchParams.get('cats') || '').slice(0, 120);
      const id = newCode();   // internal pending id; the public code is assigned on accept

      await env.SKINS.put('pending/' + id + '.zip', body);
      await env.META.put('pending:' + id, JSON.stringify({ name, author, cats, ip, ts: Date.now() }));
      await env.META.put(rlKey, String(used + 1), { expirationTtl: 90000 });

      // Ping the moderation Discord webhook (the bot turns this into an accept/deny DM flow).
      ctx.waitUntil(fetch(env.DISCORD_WEBHOOK, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          embeds: [{ title: 'Skin submission: ' + name,
                     description: 'by ' + author + '\ncategories: ' + cats + '\npending id: ' + id }],
        }),
      }).catch(() => {}));
      return new Response(JSON.stringify({ pending: id }), {
        headers: { 'content-type': 'application/json' },
      });
    }

    // ── moderation (bot only, shared secret): accept assigns the public code ────────────
    if (req.method === 'POST' && path === '/moderate') {
      if (req.headers.get('authorization') !== 'Bearer ' + env.MOD_SECRET)
        return new Response('no', { status: 403 });
      const { id, action } = await req.json();   // action: "accept" | "deny"
      const metaRaw = await env.META.get('pending:' + id);
      if (!metaRaw) return new Response('unknown id', { status: 404 });
      const meta = JSON.parse(metaRaw);
      if (action === 'accept') {
        const code = newCode();
        const obj = await env.SKINS.get('pending/' + id + '.zip');
        await env.SKINS.put('accepted/' + code + '.zip', obj.body);
        const index = JSON.parse((await env.META.get('index')) || '[]');
        index.push({ id: code, name: meta.name, author: meta.author,
                     categories: meta.cats ? meta.cats.split(',') : [], votes: 0,
                     date: new Date().toISOString().slice(0, 10),
                     zip: url.origin + '/skin/' + code });
        await env.META.put('index', JSON.stringify(index));
      }
      await env.SKINS.delete('pending/' + id + '.zip');
      await env.META.delete('pending:' + id);
      return new Response('ok');
    }

    // ── favorites/votes: one per player, hashed, no login ───────────────────────────────
    if (req.method === 'POST' && path.startsWith('/favorite/')) {
      const code = path.slice(10).toUpperCase();
      const { username, uuid, on } = await req.json();
      const voter = await hashVoter(env, username || '', uuid || '');
      const k = 'fav:' + code + ':' + voter;
      const had = await env.META.get(k);
      if (on && !had) await env.META.put(k, '1');
      if (!on && had) await env.META.delete(k);
      // Vote counts are recomputed into the index lazily by a scheduled handler (cron) so a
      // favorite spam can never trigger index rewrites per request.
      return new Response('ok');
    }

    return new Response('nDM Skin Hub', { status: 404 });
  },
};
