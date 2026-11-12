# Hosting games

The person who runs `/civ new` is the game's **host** 👑. The host sets up the game and has a
few extra commands. Any member can host; it doesn't have to be the person running the bot.

## Lobby settings

On the lobby message (host only):

| Setting | Options |
|---|---|
| Map size | Tiny · Small · Medium · Large · Huge (a **Custom** size is set with `/civ setup`) |
| Map type | Continent and Islands · Pangaea · Two Continents · Archipelago · Inner Sea · Lakes · Small Continents · Fractal · Perlin · Three Continents · Four Corners · Spiral · Boreal |
| Difficulty | the difficulties of the selected rules: Settler … Deity in Gods & Kings, plus any a mod adds |
| AI opponents | 0 up to what the map holds (see the size caps below) |

The lists for difficulty, speed, starting era, victory types, nations and the city-state maximum
come from the lobby's rules: the base ruleset plus the selected mods (`/civ lobby`). When you change
the mods, a value the new rules don't have goes back to a default (Prince, Quick, the first era, all
victories), and the bot tells you what changed.

**🏳️ Pick nation** lists every nation the rules let you choose, with its leader and unique
ability. Nations a mod marks *"Will not be chosen for new games"* are hidden, as in Unciv.
**📖 Nation details…** shows a nation's full text from Unciv's nation picker: leader, personality,
unique ability, start bias, and unique units, buildings and improvements. From there you can press
**Play as …**.

**⚙️ Settings** opens more options (only the host sees them):

| Setting | Options | Notes |
|---|---|---|
| Speed | Quick · Standard · Epic · Marathon (+ modded speeds) | **Quick** is best for Discord: fewer turns overall |
| City-states | 0 up to what the rules have (40 in Gods & Kings) | default 4; ◀ / ▶ entries page through the numbers |
| Starting era | Ancient → Information (+ modded eras) | later eras start with more techs and units |
| Victory types | Scientific · Cultural · Domination · Diplomatic · Time (+ modded victories) | pick any combination |
| Barbarians | on / off | |
| Fog of war | on / off | **off** puts the full world map on the public board, which is fun for casual or streamed games |
| World wrap | on / off | the map wraps east–west |
| AFK auto-skip | off · 12 h · 24 h · 48 h | automatically ends a player's turn after that long |
| Cancel game | | deletes the lobby |

Most of these can also be set up front: `/civ new name:… speed:… city_states:… barbarians:… fog:… thread:…`.
`city_states:` takes any number up to the Gods & Kings maximum (40).

**Size guide:** Tiny or Small with 2–4 civilizations makes a quick game over an evening or two.
Medium or Large with 6–10 is a long campaign.

## Every other game option: `/civ setup`

`/civ setup` (host, lobby only) sets every remaining option from Unciv's *New game* screen.
Without options it shows the current values. You can set several options in one command, and
`off` / `default` / `random` puts an option back to its default. The choices are checked the
way Unciv's new-game screen checks them, and a change that can't work is refused with a reason
(nothing is changed then).

| Option | Values | What it does |
|---|---|---|
| `map_size:` | Tiny · Small · Medium · Large · **Huge** · **Custom** | Tiny holds up to 8 civilizations, Small 12, Medium 16, Large 22, Huge 28 (players + AI); a custom size holds as many as the largest standard size it covers |
| `radius:` | 2–500 | **custom size** for hexagonal and flat-earth maps: tiles from the centre (Tiny is 10, Huge 40) |
| `width:` + `height:` | at least 3 each, at most 16:1 | **custom size** for rectangular maps (Tiny is 23×15, Huge 87×57); with world wrap the width must be at least 32 |
| `map_type:` | all 13 Unciv generators, or **Random** | Random picks a generator when the game starts |
| `random_types:` | e.g. `Pangaea,Archipelago`, or `all` | the generators **Random** may pick from (Unciv's checkbox list) |
| `shape:` | Hexagonal · Rectangular · Flat Earth Hexagonal | |
| `resources:` | Sparse · Default · Abundant | resource abundance |
| `mirroring:` | None · Left-right · Top-bottom · Four-way · Around center tile | mirrored map; Unciv's editor only offers Left-right, the rest are experimental |
| `seed:` | a number, or `random` | the same seed and options give the same map |
| `map_sliders:` | `water=-0.1–0.1 elevation=0.6–0.8 temperature=0.4–0.8 shift=-0.4–0.4 vegetation=0–1 rare=0–0.5 richness=0–0.5 coast=1–5 biome=1–15`, or `reset` | Unciv's water level (sea level) and advanced map sliders; give only the ones you want to change |
| `map_options:` | e.g. `no_ruins=on no_wonders=on strategic_balance=on legendary_start=on`, or `default` | no ancient ruins; no natural wonders; strategic resources near every start; extra-good starting spots |
| `one_city:` | True / False | One City Challenge |
| `raging_barbarians:` | True / False | more, earlier and stronger barbarians |
| `nukes:` | True / False | nuclear weapons (on by default) |
| `no_razing:` · `no_start_bias:` | True / False | cities can't be razed; nations ignore their preferred start terrain |
| `shuffle_order:` | True / False | shuffle the turn order at start |
| `max_turns:` | 100–1500 (default 500) | turn limit for **Time** victory |
| `ai:` | 0 up to the map's limit | AI opponent count (the lobby dropdown shows up to 24) |
| `ai_nations:` | e.g. `Rome,Greece`, or `random` | pick specific AI nations, matched against the selected rules and mods; raises the AI count if needed |
| `random_civs:` | e.g. `3-8`, or `off` | random number of civilizations (the AI count is then ignored) |
| `random_city_states:` | e.g. `2-6`, or `off` | random number of city-states |
| `nations_pool:` | e.g. `Rome,Greece,Egypt,China`, or `off` | random nations are drawn only from this list (it must have enough nations for every random pick) |
| `stats:` | e.g. `demographics=on rankings=off charts=off own_only=on extra_types=on`, `off`, `default` | Unciv's civilization stats screens (`civ_stats`, `rankings`, `charts`, `demographics`, `own_only`, `extra_types`) |

Examples: `/civ setup map_size:Huge ai:14 ai_nations:Rome,Greece,Egypt` ·
`/civ setup one_city:True raging_barbarians:True nukes:False` · `/civ setup random_civs:4-8 nations_pool:Rome,Greece,Egypt,China,Persia,Japan` ·
`/civ setup map_size:Custom radius:25` · `/civ setup shape:Rectangular width:60 height:36` · `/civ setup map_type:Random random_types:Pangaea,Archipelago`.

**Custom sizes** are checked with Unciv's rules: too small, too big (a radius over 500), sides more
than 16:1 apart, or too narrow for world wrap are refused with the reason. From 4,000 tiles the bot
repeats Unciv's warning that a large map may cause latency issues. Unciv warns that maps from 16,000
tiles may cause out-of-memory crashes, so the bot refuses those.

On a custom map the map-generation options are ignored. With a scenario or an imported save,
all of these settings are ignored, because those bring their own setup.

## Saves: `/civ save`

| Command | Who | What it does |
|---|---|---|
| `/civ save export` | a player, once the game has ended · the host mid-game, if the lobby turned `allow_export` on · a lone human playing only against the AI | replies (only to you) with the game as an Unciv save file (`.civ`), in Unciv's own save format |
| `/civ save import file:<save>` | host or *Manage Server*, in the lobby | starts this lobby's game from an Unciv save instead of a new world |
| `/civ save import remove:True` | host or *Manage Server* | drops the imported save again |

**Why exports are limited:** a save file holds the whole map without fog of war, plus every other
civ's cities, units and gold. Mid-game it would let one player scout everyone else. So exports are
open to every player once the game has ended (also after it moved to `data/finished/`). Mid-game,
only the host may export, and only if the host ran `/civ lobby allow_export:True` before the start.
The lobby shows 💾 when that's on, so every player knows before joining. A single human playing
against only AI civs can always export. There is no export vote.

**Playing an export in desktop Unciv:** *Load game* → *Load from custom location* → pick the file.
It opens as a **hotseat** game: every human civ is played on that one computer, and AI civs stay AI.
Install the same mods in Unciv first (the export message lists the rules it uses).

**Importing:** the save is checked when you upload it. It is refused if it was made by a newer
Unciv than the bot runs, if it needs mods the server doesn't have (they're listed, so a server
manager can install them with `/civ mod`), if the game is already over, or if it has fewer
playable civilizations than players in the lobby. The upload is kept in `data/imports/` until
the host presses **Start**. Each player then gets back the civ they played here before (when a
CivBot export is re-imported), or else a civ matching their 🏳️ nation pick, or else one of the
save's human civs. Every other civ is run by the AI. The lobby's map, AI and option settings
don't apply to an imported game.

## Mods, custom maps and scenarios

CivBot can use [Unciv mods](https://github.com/topics/unciv-mod): new nations, units, rules, or
whole rulesets. It can also use maps made in Unciv's map editor, and scenarios shipped in mods.

**Server admins** (members with *Manage Server*):

| Command | What it does |
|---|---|
| `/civ mod action:list` | installed mods (base ruleset or extension, contents, scenarios) |
| `/civ mod action:browse query:<words>` | anyone: searches GitHub for Unciv mods (repositories with the `unciv-mod` topic, most stars first, as Unciv's mod manager does) and lists them with the install command. Without `query:` it lists the most-starred mods |
| `/civ mod action:install url:https://github.com/<owner>/<repo>` | downloads a mod from GitHub (max 50 MB) and loads it |
| `/civ mod action:remove name:<mod>` | removes a mod (refused while a game uses it) |
| `/civ map action:upload file:<map file>` | adds a map made in Unciv's map editor (max 10 MB) |
| `/civ map action:list` · `action:remove name:<map>` | list or delete maps |

**The host**, in the lobby:

| Command | What it does |
|---|---|
| `/civ lobby` | shows what's selected and what's available |
| `/civ lobby add_mod:<mod>` / `remove_mod:<mod>` / `clear_mods:True` | choose extension mods |
| `/civ lobby base:<mod>` | play on a different base ruleset (`default` = Civ V Gods & Kings) |
| `/civ lobby map:<map>` | play on a custom map (`generated` to go back) |
| `/civ lobby scenario:<scenario>` | start from a scenario in an installed mod (`none` to go back) |
| `/civ lobby accept_mod_errors:True` | play a mod combination that Unciv reports **errors** for, as Unciv's *Accept* button allows. The errors are shown, the lobby shows a 🚨 warning, and the game may crash or misbehave. `False` turns it off again |
| `/civ lobby allow_export:True` | let the host export the save mid-game (see *Saves*); shown as 💾 in the lobby |
| `/civ espionage enabled:True` | turn spies on for this game |

Incompatible mod combinations, maps that are too small for the number of civs, and scenarios
with fewer playable civs than players are all rejected with an explanation before the game
starts. A combination Unciv reports errors for is refused too, unless the host accepts the errors
with `accept_mod_errors:True`. Combinations that can't be loaded at all can't be accepted. A map's required mods are added automatically. **Map editor:** Discord has no room for
one, so make maps in Unciv on desktop or Android, then upload the `.map` file with
`/civ map action:upload`.

## Threads

By default each game is played in a **thread** created from the lobby message, which keeps your
channel tidy. The board is pinned in the thread if the bot has the *Manage Messages* (or *Pin Messages*) permission; otherwise it just stays at the top. Use `thread:False` on `/civ new` to play
directly in the channel instead. The bot also falls back to the channel automatically if it
can't create threads there.

Several games can run at once, in the same channel or in different ones.

## Host commands

| Command | What it does |
|---|---|
| `/civ kick @player` | removes a player: the AI takes over their civilization and the game continues |
| `/civ host @player` | hands host rights to another player in the game |
| `/civ end` | ends the game for everyone (asks for confirmation) |
| `/civ rollback` · `/civ rollback turn:<n>` | lists the saved turns, or goes back to the start of turn *n* (see *Rolling back*) |
| `/civ lobby …`, `/civ setup …`, `/civ save import …`, `/civ espionage …` | lobby only: mods, map, scenario, every other option, saves, spies (see above) |

Any player can use `/civ leave` to hand their own civilization to the AI.

## Keeping games moving

- After **12 hours** on one turn, the bot pings the player again.
- With **AFK auto-skip** on, the turn is ended automatically after 12, 24 or 48 hours. The
  advisor fills in their research and production, and their units stay where they are.
- Someone gone for good? `/civ kick @player`.

## Rolling back

Like Unciv's autosaves, the bot keeps a snapshot from the start of each of the last **10** turns,
in `data/<game id>.history/`. Older snapshots are deleted.

- `/civ rollback` (any player) lists the turns you can go back to.
- `/civ rollback turn:<n>` (host) first shows what would be lost: how many turns, and each player's
  cities, units, techs and gold then and now. The host confirms with **⏪ Roll back**.
- If 2 or more humans are still playing, the bot then posts a vote in the game channel, and
  **every human player** must press **✅ Accept rollback** (the host's confirmation counts as
  theirs). One **Reject** cancels it. A vote expires after 24 hours or when the bot restarts. If
  anyone ends a turn before everyone has voted, the rollback is refused and the host has to start
  it again.
- When the vote passes, the game goes back to that turn, the newer snapshots are deleted, and the
  bot announces the rollback and pings whoever's turn it is. Players who were handed to the AI
  meanwhile stay with the AI. Messages already posted in the channel stay there.

## Spectators

Anyone not playing can type `/civ spectate` in the game channel or thread to see the
scoreboard. With fog of war **off** (or once the game is over), they also see the whole world map.

## When the game ends

When someone wins (or every human is defeated), the bot posts the result with the full world
map revealed and moves the save to `data/finished/`.
