# Hosting games

The person who runs `/civ new` is the game's **host** 👑. The host sets up the game and has a
few extra commands. Any member can host; it doesn't have to be the person running the bot.

## Lobby settings

On the lobby message (host only):

| Setting | Options |
|---|---|
| Map size | Tiny · Small · Medium · Large |
| Map type | Continent and Islands · Pangaea · Two Continents · Archipelago · Inner Sea · Lakes · Small Continents · Fractal |
| Difficulty | Settler · Chieftain · Warlord · Prince · King · Emperor · Immortal · Deity |
| AI opponents | 0–7 |

**⚙️ Settings** opens more options (only the host sees them):

| Setting | Options | Notes |
|---|---|---|
| Speed | Quick · Standard · Epic · Marathon | **Quick** is best for Discord: fewer turns overall |
| City-states | 0–12 | default 4 |
| Starting era | Ancient → Information | later eras start with more techs and units |
| Victory types | Scientific · Cultural · Domination · Diplomatic · Time | pick any combination |
| Barbarians | on / off | |
| Fog of war | on / off | **off** puts the full world map on the public board, which is fun for casual or streamed games |
| World wrap | on / off | the map wraps east–west |
| AFK auto-skip | off · 12 h · 24 h · 48 h | automatically ends a player's turn after that long |
| Cancel game | | deletes the lobby |

Most of these can also be set up front: `/civ new name:… speed:… city_states:… barbarians:… fog:… thread:…`.

**Size guide:** Tiny or Small with 2–4 civilizations makes a quick game over an evening or two.
Medium or Large with 6–10 is a long campaign.

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

Any player can use `/civ leave` to hand their own civilization to the AI.

## Keeping games moving

- After **12 hours** on one turn, the bot pings the player again.
- With **AFK auto-skip** on, the turn is ended automatically after 12, 24 or 48 hours. The
  advisor fills in their research and production, and their units stay where they are.
- Someone gone for good? `/civ kick @player`.

## Spectators

Anyone not playing can type `/civ spectate` in the game channel or thread to see the
scoreboard. With fog of war **off** (or once the game is over), they also see the whole world map.

## When the game ends

When someone wins (or every human is defeated), the bot posts the result with the full world
map revealed and moves the save to `data/finished/`.
