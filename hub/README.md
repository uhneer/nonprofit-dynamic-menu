# nDM Skin Hub backend (scaffold)

This folder is the server side of the Skin Hub. It is not deployed yet; the mod already speaks
this API and degrades gracefully while it is offline.

## Why a Cloudflare Worker

- Free tier covers a community this size: 100k requests/day, R2 gives 10 GB storage and free
  egress. No machine to maintain, nothing that can be left running by accident.
- Every public GET is edge-cached, so even a popular skin costs roughly one R2 read per day.

## Cost and abuse controls (the actual point)

- Uploads: 30 MB hard cap, magic-byte check (must be a real ZIP), 3 uploads per IP per day.
  Skin packages are already DEFLATE-compressed by the mod, so there is nothing to recompress.
- Downloads: served with long cache headers; jsDelivr-style hot paths never hit storage twice.
- Votes/favorites: one per player via a salted hash of username+UUID (no accounts, no login,
  nothing personal stored). Vote writes go to KV keys; the public index is recomputed on a cron,
  so vote spam cannot amplify into storage rewrites.
- Moderation: nothing becomes public without an accept. Every upload pings a Discord webhook;
  the (future) bot DMs the maintainer an embed preview with every asset named and two buttons.
  Accept assigns the public 8-character skin code, deny deletes the pending object.

## Deploying later

1. `wrangler kv namespace create META`, `wrangler r2 bucket create ndm-skins`
2. Secrets: `wrangler secret put MOD_SECRET / DISCORD_WEBHOOK / VOTE_SALT`
3. `wrangler deploy` — the mod's `SkinHub.API` already points at the workers.dev route.

## API the mod uses

| Method | Path             | Purpose                                   |
| ------ | ---------------- | ----------------------------------------- |
| GET    | `/skins`         | accepted-skins index (JSON)               |
| GET    | `/skin/<code>`   | download a skin zip by share code         |
| POST   | `/upload`        | submit a pending skin (`?name&author&cats`) |
| POST   | `/favorite/<code>` | toggle a player's favorite              |
| POST   | `/moderate`      | bot-only accept/deny (Bearer secret)      |
