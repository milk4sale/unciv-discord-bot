# Development

## How it works

```
 Discord  ──clicks──▶  Bot.kt ──▶ Controller.kt ──▶ GameOps / Features ──▶ Unciv engine (headless)
   ▲                                                                          │
   └──── panel embed + components + map PNG ◀── Panel.kt ◀── MapRenderer.kt ◀─┘
```

- **Unciv runs headless.** `Engine.kt` boots the engine with libGDX's headless file backend
  (no window, no GPU) and runs **all** game logic on a single thread, because Unciv keeps global
  state. Every Discord handler hands its work to that thread.
- **Hotseat rotation.** Each Discord user is a human civ in one Unciv game with
  `isOnlineMultiplayer = false`. `GameInfo.nextTurn()` ends the current human's turn, runs every AI
  and stops at the next human, exactly like Unciv's hotseat mode.
- **Private panels** are ephemeral interaction replies. Every button and dropdown carries a
  component id like `c|<gameId>|<verb>|<args…>`, which `Controller.apply()` turns into a game action.
  The Activity add-on calls the same `apply()`.
- **Maps** are drawn with Java2D from Unciv's raw *FantasyHex* PNG tiles, following Unciv's own
  layer rules (combined images, rule variants, era-specific city art) and respecting each
  player's fog of war.
- **Saves** use Unciv's own format (`data/<id>.civ`) plus a small JSON with Discord data, written
  after every action. Before each end turn the game is snapshotted in memory; if the engine
  throws, the snapshot is restored, so a turn is never half-applied.
- **The advisor** reuses Unciv's AI (`NextTurnAutomation`, `ReligionAutomation`, …) for
  decisions that have no screen in the bot. A few of those helpers are private in Unciv and are
  called through reflection (`Engine.callHidden`), so if Unciv renames one, that call is skipped
  with a warning instead of crashing.

## Extensions (feature modules)

Most features beyond the basics live in **extension objects**, one file each, registered in
`Extensions.all` (`Ext.kt`). An extension can contribute:
- panel tabs
- entries in the Unit and City action menus
- panel verbs
- text-input popups (`ask:<verb>` values or `askButton`)
- direct actions (`do:<verb>` values)
- `/civ` subcommands
- game-setup hooks (`applyToSetup`, `createGame`, `onEngineInit`, `lobbyLines`)
- turn hooks (`beforeMutation` for undo, `onTurnEnded`, `advisorSkips`)
- its own `selfTest`

| File | Features |
|---|---|
| `CityPlus.kt` | 👥 Citizens, 🏗️ Production (queue editing, buying, details), 🏙️ Cities overview and 🏛️ Wonders tabs, sell, rename, worker queue, ↩️ undo |
| `UnitsPlus.kt` | swap, group move, destinations, promotion paths and defaults, disband confirm, upgrade all, AutoPlay once, 📰 News tab, end-turn checklist extras |
| `DiplomacyPlus.kt` | war confirmation and peace lock, two-sided treaties, 🧭 Relations tab, player demands, custom deal amounts, city-state details, gifts and marriage |
| `SetupPlus.kt` | `/civ setup` (all remaining game options), `/civ save export` / `import` |
| `MilitaryPlus.kt` | 🎖️ Military tab, nukes with blast preview and confirm, air sweep / strike / rebase |
| `StatecraftPlus.kt` | 🕵️ Espionage, 🗳️ World Congress, 📨 Inbox (incl. random events), 🔬 Research queue and ⛪ Found religion tabs, policy branch progress, `/civ espionage` |
| `InfoPlus.kt` (+ `InfoPlusStats.kt`) | 🏆 Victory, 📖 Civilopedia, ✨ Beliefs, 📊 Stats (breakdowns, demographics, charts…) and 🎞️ Replay tabs, ranking privacy, `/civ wiki` |
| `ModsPlus.kt` | `/civ mod`, `/civ map`, `/civ lobby` (mods, base ruleset, custom maps, scenarios) |

To add a feature area, create `XyzPlus.kt` with `object XyzPlus : Extension`, add it to
`Extensions.all`, prefix your verbs and choice ids (e.g. `xy_` / `xy:`), and cover it in `selfTest`.

## Code layout

```
bot/
  build.gradle.kts      subproject of Unciv's Gradle build (shares its version catalog)
  src/main/kotlin/civbot/
    Main.kt             entry point, .env loading, --selftest
    Engine.kt           headless Unciv, engine thread, save/load, reflection helpers
    GameOps.kt          units, combat, cities, tech, policies, war/peace, advisor, end turn
    Features.kt         map look-around, trade builder, city-states, city management,
                        religion, great people, overview, captured cities
    Controller.kt       maps component ids to actions; end-turn snapshot/retry; proposals
    Panel.kt            builds the private panel for each tab
    MapRenderer.kt      PNG rendering with tile codes
    Bot.kt              JDA: slash commands, lobby, board, turn pings, reminders, modals
    Store.kt            persistence
    ActivityServer.kt   optional Ktor server for the Activity
    SelfTest.kt         offline end-to-end test
    Ext.kt              extension API + registry
    CityPlus.kt, MilitaryPlus.kt, StatecraftPlus.kt, InfoPlus.kt, ModsPlus.kt   feature extensions
activity/index.html     Activity front-end
scripts/install-service.sh
setup.sh / run.sh
```

## Building and testing

```bash
./setup.sh                                          # first time
./unciv/gradlew -p unciv :civbot:installDist        # rebuild after changes
./run.sh --selftest                                 # offline end-to-end test
```

The self-test uses a fixed map seed (`SELFTEST_SEED`, default 424242; `SELFTEST_SEED=random` for a random
map) so every run and CI see the same world. It plays about 15 game turns with two scripted humans and three AIs through the
real `Controller`, then runs a scenario that sets up hard-to-reach situations: workers, escorts,
city-state gifts, pantheon, free Great Person, and deals, war and peace between two humans. It
prints a feature checklist and `Errors: N`, and writes every panel tab to `selftest-out/*.png`
so you can check the visuals. CI runs it on every push.

To try changes in Discord, use a separate test bot token and server.

## Unciv version and patch

`setup.sh` pins Unciv to a specific commit (`UNCIV_COMMIT`) because the bot calls engine APIs
directly. To move to a newer Unciv:

1. Change `UNCIV_COMMIT` in `setup.sh` and run it.
2. Fix any compile errors (usually renamed functions).
3. Run `./run.sh --selftest` until it reports `Errors: 0`.

`setup.sh` also changes one line in Unciv: the `throw Exception("City within distance")` in
`SpecificUnitAutomation.kt` becomes `return`. That assertion fires when a human founds a city
right next to an AI's starting settler before the AI has moved, because the AI picks settling
spots from its fog-limited view. In Unciv's own game it would crash the turn; here the settler
just waits.

## Adding a feature

1. Put the game logic in `GameOps` or `Features`. It runs on the engine thread, so it can touch
   Unciv freely.
2. Add a verb to `Controller.apply()`.
3. Add the control in `Panel.render()`, or better, in an extension, keeping Discord's limits in mind: **5 rows** per message,
   **5 buttons** per row, **25 options** per dropdown, 100-character labels and ids.
4. Exercise it in `SelfTest` and check `Errors: 0`.
