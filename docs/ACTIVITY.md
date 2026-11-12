# Activity add-on (optional)

The channel bot is the complete game. The **Activity** is an extra: it opens the same private
panel as an embedded web app inside Discord (the 🚀 Activities button in voice channels and
DMs), with a larger map. It uses the same controller as the bot, so every action behaves
identically, and turn changes are still announced in the game thread.

It needs a **public HTTPS address** that reaches your bot, because Discord loads the page through
its own proxy.

## Steps

1. **Expose the bot's web port** (default 8787) over HTTPS. For example, with a free
   [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/):
   ```bash
   cloudflared tunnel --url http://localhost:8787
   ```
   Note the `https://….trycloudflare.com` address it prints.
2. **Developer Portal → your app → Activities:**
   - Enable Activities.
   - **URL Mappings:**
     - `/` → your public host from step 1 (without `https://`)
     - `/npm` → `cdn.jsdelivr.net/npm` (lets the page load Discord's Embedded App SDK)
3. **Developer Portal → OAuth2:** copy the **Client ID** and **Client Secret**.
4. Add the following to `.env`, then restart the bot:
   ```
   ACTIVITY_PORT=8787
   DISCORD_CLIENT_ID=...
   DISCORD_CLIENT_SECRET=...
   ```
5. In Discord, start the Activity from a voice channel's 🚀 menu. It signs you in, finds your
   games and shows your panel.

## How it works

| Endpoint | Purpose |
|---|---|
| `GET /` | the page ([activity/index.html](../activity/index.html)) |
| `POST /api/token` | exchanges the Activity's OAuth code for a token |
| `GET /api/games` | your running games |
| `GET /api/panel?gid=` | the panel as Discord-style JSON (embed and components) |
| `GET /api/map?gid=` | the current map PNG |
| `POST /api/action` | performs a click, returns the updated panel |

Requests are authenticated with your Discord token, checked against Discord's API, so players
only ever see their own panel.
