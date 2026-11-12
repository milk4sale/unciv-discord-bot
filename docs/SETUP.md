# Setup guide

This gets CivBot running on your own computer (or any server) and into your Discord server.
Allow about 15 minutes, most of it waiting for the first build.

Works on **Windows**, **Linux** and **macOS**. Each step below has a Windows part and a
Linux/macOS part.

**You need:**
- **Java 21 or newer** (a JDK). On Windows, `setup.cmd` offers to install it for you. On
  Linux/macOS, `setup.sh` tells you the install command.
- **git** on Linux/macOS. Windows doesn't need it.
- About **1.5 GB** of free disk space for Unciv, Gradle and the libraries
- Any computer that's **on while people play**. The bot answers clicks from wherever it runs, so
  a gaming PC, an old laptop or a Raspberry Pi 4/5 all work.

---

## 1. Create the Discord application

1. Go to <https://discord.com/developers/applications> and click **New Application**. Give it
   a name (players will see this name) and accept the terms.
2. Optional: on **General Information**, upload an icon and description.
3. Open the **Bot** page:
   - Click **Reset Token**, then **Copy**. This token is the bot's password, so keep it secret.
     You'll paste it into `.env` in step 4.
   - **Privileged Gateway Intents:** leave all three **off**. CivBot doesn't need them.
4. On **General Information**, copy the **Application ID** for the invite link.

## 2. Invite the bot to your server

Open this link after replacing `APP_ID` with your Application ID, then pick your server:

```
https://discord.com/oauth2/authorize?client_id=APP_ID&scope=bot+applications.commands&permissions=309237763136
```

That link asks for exactly these permissions:

| Permission | Why the bot needs it |
|---|---|
| View Channels | see the channel you play in |
| Send Messages | post the game board and turn pings |
| Embed Links | the board, lobby and panels are embeds |
| Attach Files | map images |
| Read Message History | edit its own board message |
| Add Reactions | small extras |
| Create Public Threads | each game gets its own thread |
| Send Messages in Threads | post inside that thread |

No admin rights, no role management and no message reading are needed. Private panels use
ephemeral replies, which need no permission.

**Prefer the URL Generator?** Developer Portal → **OAuth2** → **URL Generator** → tick scopes
**`bot`** and **`applications.commands`** → tick the permissions above → open the generated URL.

> If your server limits channels with permission overrides, make sure the bot's role is allowed
> in the channel you want to play in. `/civ new` checks this and tells you what's missing.

## 3. Download and build

### Windows

1. On the [GitHub page](https://github.com/milk4sale/unciv-discord-bot), click the green
   **Code** button, then **Download ZIP**.
2. Right-click the zip, choose **Extract All…**, and put the folder somewhere easy, like
   `Documents\CivBot`.
3. Open the folder and double-click **`setup.cmd`**.
   - If Windows warns about running it (*"Open File - Security Warning"* or *"Windows
     protected your PC"*), click **Run**, or **More info** then **Run anyway**. Windows warns
     about every script downloaded from the internet.
   - If Java 21 isn't installed, it asks **"Install Java 21 now?"**. Press Enter.
   - At the end it asks for your **bot token** (from step 1). Right-click to paste it (it stays
     hidden), then press Enter. It saves the token in `.env`, so you can skip step 4.

(If you have git, `git clone` works too, and makes updating easier.)

### Linux / macOS

```bash
git clone https://github.com/milk4sale/unciv-discord-bot.git
cd unciv-discord-bot
./setup.sh
```

### What setup does

The setup script downloads Unciv at a tested commit, connects the bot to it, applies a one-line
stability patch and builds everything. The first build downloads Gradle and libraries and takes a
few minutes; later builds are quick.

To check everything works *before* touching Discord, run `run.cmd --selftest` (Windows, in a
Command Prompt in the folder) or:

```bash
./run.sh --selftest
```

This plays about 20 game turns with two simulated players and three AIs, exercises every
feature, and ends with a checklist and `Errors: 0`. Panel images are written to `selftest-out/`.

## 4. Add your token

**Windows:** `setup.cmd` already asked for it. To change it later, run `setup.cmd` again
after deleting `.env`, or open `.env` in Notepad and edit the `DISCORD_TOKEN=` line.

**Linux / macOS:**

```bash
cp .env.example .env
nano .env        # or any text editor
```

Set the token on the `DISCORD_TOKEN` line:

```
DISCORD_TOKEN=your-token-here
```

`.env` is in `.gitignore`, so it's never committed. Don't share it or paste it in chats.

## 5. Run it

### Windows

- **Simplest:** double-click **`run.cmd`**. A window opens; wait for
  `Logged in as YourBot#1234`, then type **`/civ new`** in a channel. Keep the window open
  while people play. Closing it stops the bot.
- **In the background:** open the `scripts\windows` folder and double-click
  **`install-service.cmd`**. The bot then runs hidden, starts whenever you log in to Windows,
  and restarts itself if it crashes.

| Task | How |
|---|---|
| logs | `civbot.log` and `civbot-error.log` in the CivBot folder |
| stop and uninstall | in `scripts\windows`, run `install-service.cmd -Remove` from a Command Prompt |
| restart (after updating) | run `install-service.cmd` again |

Use **either** `run.cmd` **or** the background task, never both. Two copies of the bot
would both answer every click.

### Linux / macOS

#### Option A: in a terminal (simplest, good for testing)

```bash
./run.sh
```

Wait for `Logged in as YourBot#1234` and `Slash commands ready in <your server>`. Then type
**`/civ new`** in a channel. Closing the terminal (or Ctrl+C) stops the bot.

#### Option B: in the background (Linux, recommended)

```bash
./scripts/install-service.sh
```

This installs a **systemd user service**. The bot starts when you log in, restarts if it
crashes, and runs at a slightly lower CPU priority so it doesn't compete with your games.

| Task | Command |
|---|---|
| live logs | `journalctl --user -u civbot -f` |
| restart (after updating) | `systemctl --user restart civbot` |
| stop / start | `systemctl --user stop civbot` / `systemctl --user start civbot` |
| uninstall | `./scripts/install-service.sh --remove` |
| keep running while logged out | `loginctl enable-linger` (once) |

Run **either** the service **or** `./run.sh`, never both. Two copies of the bot would both
answer every click.

#### macOS

`./run.sh` works. For a background service, use `launchd` or a terminal multiplexer (`tmux`).

## 6. Updating

**Windows:** download the new ZIP and extract it over your CivBot folder (your `.env` and
`data` folder are kept), or `git pull` if you used git. Then run `setup.cmd` again and restart
the bot.

**Linux / macOS:**

```bash
git pull
./setup.sh                          # rebuilds (and moves Unciv to the new pinned commit if it changed)
systemctl --user restart civbot     # or restart ./run.sh
```

Only restart **after** the build finishes. Starting the bot while it's still building can make
commands fail with "The application did not respond".

## Where things are stored

| Path | Contents |
|---|---|
| `data/<id>.meta.json` | game metadata: players' Discord IDs, settings, channel/thread |
| `data/<id>.civ` | the Unciv save (zipped) |
| `data/finished/` | games that ended |
| `.env` | your token (and optional Activity settings) |

To back up, copy the `data/` folder. To use a different folder, set `CIVBOT_DATA=/path` in `.env`.

## Configuration (`.env`)

| Variable | Meaning |
|---|---|
| `DISCORD_TOKEN` | **required**: the bot token |
| `CIVBOT_DATA` | where to keep saves (default `./data`) |
| `UNCIV_DIR` | Unciv checkout location (default `./unciv`) |
| `ACTIVITY_PORT`, `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET` | optional [Activity add-on](ACTIVITY.md) |
