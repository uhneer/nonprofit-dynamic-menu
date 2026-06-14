# nDM Skin Hub backend

The server side of the Skin Hub. The mod already speaks this API and degrades gracefully while it
is offline, so this can be deployed whenever. **To take it live, follow [DEPLOY.md](DEPLOY.md).**

## What's here

- `worker.js` — the entire backend in one Cloudflare Worker: public index, downloads by share
  code, upload intake, client status polling, favorites, and a self-contained Discord moderation
  flow (button interactions are Ed25519-verified and handled in the worker — no separate bot host).
- `wrangler.toml` — bindings (R2 + KV) and the favorites cron.
- `DEPLOY.md` — the step-by-step go-live checklist (all free tier).

## Why a Cloudflare Worker

- Free tier covers a community this size; every public GET is edge-cached, so a popular skin costs
  roughly one R2 read per day.
- No machine to maintain, nothing that can be left running by accident.

## Cost & abuse controls (the actual point)

- Uploads: 30 MB hard cap, magic-byte ZIP check, 3 uploads per IP per day. Skin packages are
  already DEFLATE-compressed by the mod, so there is nothing to recompress.
- Downloads: long cache headers; hot paths never hit storage twice.
- Votes/favorites: one per player via a salted hash of username+UUID (no accounts, no login,
  nothing personal stored). Counts are folded into the index by a 30-minute cron, so vote spam
  cannot amplify into per-request storage rewrites.
- Moderation: nothing becomes public without an Accept. Every upload posts a Discord card with
  every asset named and Accept/Deny buttons; Accept assigns the public 8-character skin code.

## API the mod uses

| Method | Path               | Purpose                                       |
| ------ | ------------------ | --------------------------------------------- |
| GET    | `/skins`           | accepted-skins index (JSON)                   |
| GET    | `/skin/<code>`     | download a skin zip by share code             |
| GET    | `/status/<id>`     | poll a pending submission (pending/accepted/denied) |
| POST   | `/upload`          | submit a pending skin (`?name&author&cats&assets`, zip body) |
| POST   | `/favorite/<code>` | toggle a player's favorite                    |
| POST   | `/discord`         | Discord interactions endpoint (Accept/Deny)   |
| POST   | `/moderate`        | optional CLI accept/deny (Bearer secret)      |
