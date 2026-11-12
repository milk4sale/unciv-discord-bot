# CivBot: play Unciv inside Discord

**Full 4X Civilization games, played entirely inside a Discord channel.**
CivBot runs the real [Unciv](https://github.com/yairm210/Unciv) engine (the open-source Civ V
remake) and turns it into buttons, dropdowns and map images. You get Unciv's rules, maps, AI
opponents, city-states, barbarians, religion, diplomacy and victories. Everyone plays from
Discord, with nothing to install as a player.

![Map views rendered by the bot](docs/images/hero.png)

- 🧑‍🤝‍🧑 **Multiplayer in a channel:** friends join a lobby and take turns. The bot pings whoever is up.
- 🌫️ **Real fog of war:** each player's panel is private (ephemeral), so only you see your empire.
- 🗺️ **Proper map images** drawn with Unciv's pixel-art tileset, with chess-style tile codes (`D5`).
- 🤖 **AI opponents and an AI advisor** that can fill in any decision you'd rather skip.
- 💾 **Saves after every action**, so restarting the bot never loses a game.
- 🧪 **Offline self-test** that plays a whole game and checks every feature without Discord.

> CivBot is a fan project. It isn't affiliated with Unciv, Firaxis or Discord.

---

## What you can do

| Panel tab | Features |
|---|---|
| ⚔️ **Units** | move with arrow buttons or 🎯 *Go to* a tile code / city; found cities; build roads, farms, mines…; fortify, sleep, explore, automate; attack; promotions and upgrades; escort pairs; pillage; paradrop; Great Person abilities; missionaries and prophets |
| 🏙️ **Cities** | production with an optional 📋 queue; buy with gold or faith; city bombard; citizen focus; buy tiles; annex puppets; raze / stop razing |
| 🔬 **Research** | pick the next tech (each option lists what it unlocks) or set a far-off goal and the whole path is queued |
| 📜 **Policies** | adopt social policies when your culture allows |
| 🙏 **Religion** | found a pantheon, name your religion, choose founder / follower / enhancer beliefs |
| 🤝 **Diplomacy** | a deal builder (gold, gold per turn, luxuries, strategic resources, techs, embassies, open borders, research agreements, defensive pacts, peace), war, friendship, denouncing |
| 🏰 **City-states** | gold gifts, pledge protection, demand tribute, see influence, allies and quests |
| 📊 **Overview** | empire stats, happiness breakdown, golden ages, resources, rankings, Great People, recent history |
| 🗺️ **Map** | scroll anywhere, 🔍 inspect any tile, 🌍 view the whole explored world |

Plus a lobby with full game settings, one thread per game, turn pings and reminders, optional
AFK auto-skip, deals and peace offers between human players with Accept/Decline buttons,
captured-city decisions, spectating, and host tools. An optional **Discord Activity** add-on
shows the same panel as an embedded web app.

## Quick start

You need **Java 21**, **git**, and a computer that stays on while people play. Your own PC works
fine.

```bash
git clone https://github.com/milk4sale/unciv-discord-bot.git
cd unciv-discord-bot
./setup.sh                    # downloads Unciv, builds the bot (first run: a few minutes)
cp .env.example .env          # paste your bot token into .env
./run.sh                      # or ./scripts/install-service.sh to run it in the background
```

Then type **`/civ new`** in any channel of your server.

Creating the Discord bot and inviting it takes about five minutes. **[docs/SETUP.md](docs/SETUP.md)**
walks you through it with every click.

## Documentation

| Guide | For |
|---|---|
| [**Setup**](docs/SETUP.md) | creating the Discord app, inviting the bot, installing, running in the background, updating |
| [**How to play**](docs/PLAYING.md) | players: starting a game, the panel, every tab and action, diplomacy, tips |
| [**Hosting games**](docs/HOSTING.md) | game settings, host commands, AFK players, threads, spectators |
| [**Troubleshooting**](docs/TROUBLESHOOTING.md) | "did not respond", "command is outdated", missing permissions, build problems |
| [**Activity add-on**](docs/ACTIVITY.md) | optional embedded web app version of the panel |
| [**Development**](docs/DEVELOPMENT.md) | how it works, code layout, self-test, updating Unciv |

## Commands at a glance

| Command | What it does |
|---|---|
| `/civ new` | open a game lobby in this channel |
| `/civ play` | open your private panel |
| `/civ board` | re-post the game board |
| `/civ games` | list your games |
| `/civ spectate` | watch a game you're not in |
| `/civ leave` | hand your civilization to the AI |
| `/civ kick @player` · `/civ host @player` · `/civ end` | host tools |
| `/civ help` | in-Discord quick guide |

## Credits and license

- Game engine, rules and art: **[Unciv](https://github.com/yairm210/Unciv)** by yairm210 and
  contributors (Mozilla Public License 2.0). The map tiles are Unciv's *FantasyHex* tileset; see
  Unciv's credits for the artists. CivBot downloads Unciv at build time and doesn't redistribute it.
- Discord library: [JDA](https://github.com/discord-jda/JDA).
- CivBot itself is released under the **Mozilla Public License 2.0** (see [LICENSE](LICENSE)),
  the same license as Unciv.
