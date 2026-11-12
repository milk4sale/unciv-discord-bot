# Troubleshooting

## In Discord

### "The application did not respond"
The bot didn't answer within 3 seconds. Usually one of these:
- **The bot isn't running.** Check that the `run.cmd` / `./run.sh` window is still open, or run
  `systemctl --user status civbot` (Linux).
- **It was started while a build was still writing files.** Restart it once the build has
  finished.
- **Two copies are running** (for example the service *and* `./run.sh`). Stop one.

Look at the logs (`journalctl --user -u civbot -f`, or the terminal) for the actual error.

### "This command is outdated, please try again in a few minutes"
Discord cached an older version of the commands. Press **Ctrl+R** in Discord (or restart the
app). The bot registers commands per server on startup, so they update almost immediately.
For a few minutes after an upgrade you may see `/civ` twice; either works, and the duplicate
disappears on its own.

### "Missing permission: MESSAGE_SEND" (or another permission)
The bot can answer slash commands without permissions, but posting the board needs them.
- Re-open the invite link from [SETUP.md](SETUP.md#2-invite-the-bot-to-your-server). Re-authorizing
  updates the bot's permissions.
- Or: channel ⚙️ **Edit Channel** → **Permissions** → add the bot → allow View Channel, Send
  Messages, Embed Links, Attach Files, Read Message History, Create Public Threads and Send
  Messages in Threads.

Then type `/civ board` in the channel to bring the game back.

### A button does nothing / "Unknown interaction"
Your private panel is old. Open a fresh one with **🎮 Play my turn** or `/civ play`.

### "It's not your turn — you can look around, but not act"
Exactly that. The board shows whose turn it is.

### "This offer has expired"
Deal, peace and friendship offers between players live in memory. If the bot restarted, send
the offer again.

### The bot says the engine hit an error while ending the turn
Nothing was lost: the game is restored to how it was before you clicked **End turn**. Try again,
or move a unit differently first. If it keeps happening, please open an issue with the log lines.

## Running the bot

### `Java not found` / `Java 17 found, but Java 21 or newer is required`
Install JDK 21 (`setup.sh` prints the command for common systems; on Windows, `setup.cmd`
offers to install it), then run the setup script again.
On Arch-based systems with several JDKs installed: `sudo archlinux-java set java-21-openjdk`.

### `DISCORD_TOKEN is not set`
Create `.env` (`cp .env.example .env`) and put your token on the `DISCORD_TOKEN=` line. On
Windows, run `setup.cmd` again and paste the token when it asks.

## Windows

### "Windows protected your PC" / "Open File - Security Warning"
Windows shows this for any script downloaded from the internet. Click **Run**, or **More info**
then **Run anyway**.

### The window closes straight away / nothing happens
Open the CivBot folder, click the address bar, type `cmd` and press Enter. In the Command Prompt
that opens, type `setup.cmd` (or `run.cmd`) and press Enter. The window then stays open and
shows the error.

### Java is still "not installed" right after installing it
Close the window and run `setup.cmd` again. New programs only show up in windows opened after
the install.

### Where's the log when it runs in the background?
`civbot.log` and `civbot-error.log` in the CivBot folder.

### `Login failed` / `401 Unauthorized`
The token is wrong or was reset. Copy a fresh one from Developer Portal → Bot → Reset Token.

### `Start the bot from <unciv>/android/assets (use run.sh)`
Always start the bot with `./run.sh` (or the service), not by running the Java binary directly.

### `Not built yet`
Run `setup.cmd` (Windows) or `./setup.sh`.

### The build fails
- Check your internet connection. The first build downloads Gradle and libraries.
- Check you have about 1.5 GB of free disk space.
- Delete `bot/build` and `unciv/.gradle`, then run the setup script again.

### It's slow / my games stutter while AIs move
AI turns use CPU for a moment. The service runs at lower priority (`Nice=5`). Large maps with
many AIs take longer per turn; Small or Medium maps with up to 6 civilizations stay snappy.
