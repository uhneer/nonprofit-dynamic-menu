# nDM Skin Hub — deploy guide

Everything is built. This is the configuration checklist to take it live. All of it is free tier.
When you finish, the mod that's already in players' hands lights up on its own: queued uploads
submit themselves, pending ones flip to accepted/denied, and the index starts serving.

## 0. Prerequisites

- A free Cloudflare account.
- `npm i -g wrangler` then `wrangler login`.
- From this folder: `npm install`.

## 1. Storage

```bash
wrangler r2 bucket create ndm-skins
wrangler kv namespace create META
```

The KV command prints an `id`. Paste it into `wrangler.toml` under `[[kv_namespaces]]`.

## 2. Discord application (the moderation bot)

1. https://discord.com/developers/applications → New Application.
2. **Bot** tab → Add Bot → copy the **token**.
3. **General Information** tab → copy the **Public Key**.
4. Invite the bot to your server with the `bot` scope (OAuth2 → URL Generator → scope `bot`,
   permission "Send Messages"). Open the generated URL, add it to your server.
5. Make/choose a private channel for submissions, enable Developer Mode in Discord
   (Settings → Advanced), right-click the channel → Copy Channel ID.

## 3. Secrets

```bash
wrangler secret put DISCORD_PUBLIC_KEY     # the Public Key from step 2.3
wrangler secret put DISCORD_BOT_TOKEN      # the Bot token from step 2.2
wrangler secret put DISCORD_CHANNEL_ID     # the channel id from step 2.5
wrangler secret put MOD_SECRET             # any long random string (optional CLI moderation)
wrangler secret put VOTE_SALT              # any long random string
```

## 4. Deploy

```bash
wrangler deploy
```

It prints your worker URL, e.g. `https://ndm-hub.<you>.workers.dev`.

## 5. Point the bot's buttons back at the worker

In the Discord application → **General Information** → **Interactions Endpoint URL**, set:

```
https://ndm-hub.<you>.workers.dev/discord
```

Discord sends a signed PING to verify it; the worker answers it automatically. Save.

## 6. Point the mod at the worker

If your worker URL is not the default `https://ndm-hub.uhneer.workers.dev`, edit `SkinHub.API`
in `src/main/java/dev/nonprofit/modularbg/background/SkinHub.java` and ship a new mod build.
(The default is already that route, so if you name the worker `ndm-hub` under the `uhneer`
account, you don't need to touch the mod at all.)

## How moderation works once live

1. A player uploads a skin in-game → the worker stores it as `pending/` and posts a card to your
   Discord channel: name, author, categories, and the full asset list, with **Accept** / **Deny**
   buttons.
2. You click a button. Discord calls `<worker>/discord`; the worker verifies the signature,
   then either promotes the zip to `accepted/<CODE>.zip` and adds it to the public index (Accept),
   or drops it (Deny). The card updates in place to show the result and the assigned share code.
3. The player's game polls on its next launches and flips their upload button to green Accepted
   (with the code) or red Denied — no action needed from them.

## Cost & abuse controls (already in the worker)

- 30 MB upload cap, real-ZIP magic-byte check, 3 uploads/IP/day.
- Public reads are edge-cached, so storage is barely touched.
- Favorites/votes are one-per-player via a salted hash of username+UUID (no login, nothing
  personal stored); counts are folded into the index by a 30-minute cron, never per request.
- Nothing is public until you Accept it.

## Local test

```bash
wrangler dev
curl http://127.0.0.1:8787/skins        # -> []
```
