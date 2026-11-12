# How to play

Everything happens inside Discord. You don't install anything: you click buttons, pick from
dropdowns and read map images. If you've played Civilization V, you already know the rules.
CivBot uses the real Unciv engine.

- [Starting a game](#starting-a-game)
- [How turns work](#how-turns-work)
- [Your private panel](#your-private-panel)
- [Reading the map](#reading-the-map)
- [The tabs](#the-tabs): [Units](#️-units) · [Cities](#️-cities) · [Citizens](#-citizens) · [Military](#️-military) · [Research](#-research) · [Policies](#-policies) · [Religion](#-religion) · [Beliefs](#-beliefs) · [Diplomacy](#-diplomacy) · [City-states](#-city-states) · [Espionage](#️-espionage) · [World Congress](#️-world-congress) · [Inbox](#-inbox) · [Overview](#-overview) · [Victory](#-victory) · [Civilopedia](#-civilopedia) · [Map](#️-map)
- [Undo](#️-undo)
- [Auto-pick and ending your turn](#auto-pick-and-ending-your-turn)
- [Tips](#tips)

---

## Starting a game

1. In any channel the bot can see, type **`/civ new`**. Optional extras appear as you type:
   `name`, `speed`, `city_states`, `barbarians`, `fog`, `thread`.
2. The bot posts a **lobby**:
   - **➕ Join** takes a seat (the person who ran `/civ new` is already in, as host 👑).
   - **🏳️ Pick nation** chooses your civilization, or leave it on 🎲 random.
   - **Leave** gives your seat back.
3. The **host** sets map size, map type, difficulty and the number of AI opponents in the
   dropdowns. **⚙️ Settings** has more options (see [HOSTING.md](HOSTING.md)).
4. The host clicks **▶️ Start**. The world is generated and the game moves into its own
   **thread**, with a pinned **board** showing the turn, year, scoreboard and recent events.

You can play solo (just you against AIs) or with as many friends as you like, up to 12
civilizations in total.

## How turns work

Turns are taken **one player at a time**, like a board game played by mail:

1. The bot pings the current player in the game thread: *"⏰ @you, it's your turn"*.
2. That player clicks **🎮 Play my turn**, makes their moves and clicks **✅ End turn**.
3. All AI civilizations move automatically, then the next human is pinged.

There's no time pressure: a game can run over an evening or over weeks. If someone takes more
than 12 hours, the bot reminds them. The host can also turn on AFK auto-skip.

Not your turn? You can still open your panel and look around. Actions stay greyed out until
it's your turn.

## Your private panel

Click **🎮 Play my turn** on any turn ping, **Open my panel** on the board, or type `/civ play`.
The panel is **only visible to you** ("Only you can see this"), so your map, units and plans
stay secret.

The panel has:

- **Title:** your leader, the turn number and the year.
- **Status line:** whether it's your turn, then your 💰 gold, 🔬 science, 🎭 culture, 😊
  happiness and 🙏 faith.
- **Messages** at the top: the result of what you just did, e.g. *"⚔️ Warrior attacked: dealt 34, took 12"*.
- **📰 News:** what happened since your last turn.
- **📝 This turn:** a checklist of things you probably want to do (pick research, set
  production, units awaiting orders…).
- **The map image** for the current tab.
- **Controls:** the tab dropdown at the top, then that tab's buttons and menus.

Each click updates the same panel in place. If you leave it for a long time and a click stops
working, just open it again.

## Long lists

Discord dropdowns hold 25 entries. Longer lists, such as a big army, a late-game city's build
options or a long trade list, are split into pages. Pick **▶ More…** at the bottom of the dropdown
for the next page, or **◀ Previous page** at the top to go back. In the multi-pick trade lists,
what you picked on other pages is kept, and the dropdown tells you how many.

## Reading the map

- Every tile has a **code** like `D5`: a column letter and a row number, like a chess board.
  Codes belong to the image you're looking at; they shift when the view moves.
- **Coloured edges** are borders, in each civilization's colour.
- **Circles with a figure** are units, filled with the owner's colour. A green bar under a unit
  is its health.
- **Banners** like `3 Rome ★` are cities: population, name, and a ★ for capitals. The bar
  under a banner is the city's health.
- **Highlights** when a unit is selected: lighter tiles are reachable this turn, red outlines
  can be attacked, and the gold outline is the selected unit or tile.
- **Fog:** dark tiles are unexplored. Dimmed tiles were seen before but aren't visible now, so
  enemy units there are hidden.

## The tabs

Switch tabs with the **dropdown at the top** of the panel.

### ⚔️ Units

- **Select a unit** from the first dropdown. Units still waiting for orders come first.
  ⏭️ **Next** jumps to the next unit that needs orders, and after a unit runs out of moves the
  panel selects the next one automatically.
- **Arrow buttons** (↖ ⬆ ↗ ↙ ⬇ ↘) move one tile in that direction. Moving into an enemy you can
  attack makes the attack.
- **🎯 Go to…** opens a box: type a tile code (`F4`) or the name of a city you've seen. Long
  trips continue automatically on later turns.
- **Unit actions** dropdown, showing only what's possible right now:
  - **Found city** (settlers)
  - **Build …** (workers: farm, mine, road, lumber mill, remove forest…) or **Stop building**
  - **Attack …** lists every enemy in range, with their health
  - **Promote: …** when a unit has enough experience
  - **Upgrade**, **Pillage**, **Paradrop**, **Set up**, **Guard**, **Repair**
  - **Great People:** hurry research or production, conduct a trade mission, create great improvements
  - **Religious units:** found or enhance a religion, spread religion, remove heresy
  - **Escort with …** pairs a civilian with a military unit on the same tile, so they move together
  - **Fortify**, **Sleep**, **Fortify / Sleep until healed** (wakes up at full health), **Explore**
    (automatic scouting), **Automate** (the AI runs the unit), **Wake up** (cancels standing
    orders), **Skip this turn**, **Disband**
  - **🛣️ Connect road to…** (workers): type a tile code or a city name and the worker builds a road
    all the way there over the next turns
  - **Queue next: …** / **Remove Forest, then build Farm**: workers can line up a second improvement
  - **Gift to city-state** (in a city-state's land), **✏️ Rename unit**
  - **☢️ Nuke a tile…**, **✈️ Air sweep…**, **🎯 Attack target…**, **🛬 Rebase to …** for nukes and aircraft
    (see [Military](#️-military))

> You can only attack civilizations you're **at war** with. Barbarians are always fair game.
> Declare war in 🤝 **Diplomacy**.

### 🏙️ Cities

- **Select a city**, or use ◀️ / ▶️ to cycle through them.
- The info card shows yields, growth, what's being built and how long it'll take, the queue,
  citizen focus, health, religion and buildings.
- **Change production:** units 🪖, buildings 🏠 and wonders 🏛️, with turns to build. You can
  also convert production into gold or science.
- **📋 Queue: on** makes production picks join the build queue instead of replacing the
  current item.
- **City actions** dropdown:
  - **Buy … for N gold** (and **Buy … with faith** for religious units)
  - **Bombard …:** the city shoots an enemy unit in range
  - **Focus:** food, production, gold, science, culture, faith or default (citizens re-arrange to match)
  - **Buy tile Xn:** the cheapest tiles you can buy, labelled with their map code
  - **Annex** a puppet city, **Raze** a city, **Stop razing**, **Clear the build queue**
  - **💸 Sell …** a building (one per city per turn), **✏️ Rename** the city

### 👥 Citizens

Fine control over who works where, like Unciv's city screen.
- **Pick a city.** The map shades the tiles it works, and the info card lists citizens, unemployed
  citizens, yields and specialists.
- **Tile menu:** ⬜ **Work** a tile (it gets locked so the city keeps working it), 🔒 **Lock** /
  🔓 **Unlock**, or ✖️ **Stop working** (the citizen becomes unemployed).
- **Specialist menu:** ➕ / ➖ scientists, merchants, engineers and artists in the buildings that have
  slots.
- **🔄 Reset** returns everything to automatic. **🚫 Avoid growth** stops the city growing.
  **✋ / 🤖 Specialists** switches between manual and automatic specialists.

### 🎖️ Military

- An overview of your army by type, the wars you're in, enemies in range, and which fighters are
  ready to intercept. Unciv intercepts automatically with any fighter that hasn't moved or
  attacked this turn.
- **☢️ Nukes:** choose *Nuke a tile…* and type a tile code. The map outlines the blast in red, and
  the panel lists every city, unit and civilization caught in it, including your own and anyone
  you'd go to war with. Nothing happens until you pick **☢️ CONFIRM nuke**, and you can cancel.
- **✈️ Air sweep…** clears enemy interceptors from a tile. **🎯 Attack target…** makes an air or
  ranged strike on a tile. **🛬 Rebase to …** moves a plane to a city or carrier in range.

### 🔬 Research

- **Research next** lists the techs available now, with turns and what each one unlocks.
- **Set a long-term goal** queues every tech needed to reach a later tech.
- Free techs (from Great Scientists, wonders and so on) show up here; just pick one.

### 📜 Policies

When you have enough 🎭 culture, the dropdown lists every policy you can adopt. 📖 opens a new
policy tree, and 📜 continues one you've already opened.

### 🙏 Religion

- Shows your faith, your pantheon or religion, and how many religions can still be founded.
- **Pantheon:** once you have enough faith, pick a pantheon belief from the dropdown.
- **Founding a religion:** use a Great Prophet's *Found a religion* action (⚔️ Units tab),
  then come here. Pick a **name** and your **beliefs**, then click **⛪ Found religion**.
  Enhancing works the same way with an *Enhance religion* action.
- If you get more beliefs than the panel can show at once (some bonuses grant extra), press
  🤖 **Auto-pick** and the advisor chooses them.

### ✨ Beliefs

Appears only when there's something to pick. Some civilizations can **expand their pantheon** with
a second belief, and some bonuses grant **extra beliefs for your religion**. Choose them here.

### Unit extras

- **🔄 Swap** places with one of your units next to you. Go to onto your own unit swaps too.
- **👥 Move a group…** sends several nearby units to one tile.
- A moving unit shows where it's heading, on the map and in its status.
- **⭐ Promote toward …** takes several promotion steps at once. **🎖️ Save promotions as default** applies them to future units of that type built in this city.
- **⬆️ Upgrade all** upgrades every unit of that type you can afford.
- **🤖 Automate military / civilians / all once** lets the AI move those units for this turn only.
- **Disband** shows the gold you'd get and asks to confirm.

### 🤝 Diplomacy

- Lists every civilization you've met, with your relationship and their score.
- **Choose a civilization**, then build a deal:
  - **You give…** and **You ask for…** let you pick several items each: gold (preset amounts or
    **✏️ Custom amount…**, which asks you for any number), gold per turn (same), luxury resources
    (one copy per deal, like in Unciv), strategic resources, **cities**, embassies, open borders,
    research agreements, defensive pacts, **declaring war on a third civ**, introductions, and peace
    when at war. (This version of Unciv doesn't trade technologies.)
  - Treaties (peace, research agreement, defensive pact) are signed by **both** sides, so picking one
    puts it on both sides of the deal. A research agreement costs both of you gold; if your partner
    can't afford it, the deal adds the missing gold from you automatically (and the other way round).
  - **🤝 Propose deal:** AIs answer right away (an AI signs a defensive pact only if it sees you as an
    ally). A human player gets the offer in the game thread with **Accept / Decline** buttons.
- **🔥 War** first shows what declaring war would cause — broken promises, their defensive-pact
  allies joining in, your own pacts being cancelled — and asks you to **⚠️ confirm**. While a peace
  treaty is running the button shows the turns left (`War (5⏳)`).
- **🕊️ Peace** proposes peace when you're at war. After a declaration of war nobody can talk peace
  for **10 turns**; the button shows the turns left (`Peace (7⏳)`).
- **💛 Friendship** proposes a Declaration of Friendship; **😠 Denounce** (30 turns, asks you to
  confirm first) sits next to it. Each is greyed out when it isn't possible.

### 🧭 Relations

- **How they see you:** their opinion of you and every reason behind it (gifts, friendship,
  warmongering, broken promises…).
- **Promises** both sides made (not to settle nearby, not to spy, not to spread religion) with the
  turns left, and demands still waiting for an answer.
- **Deals and treaties:** running deals (resources, gold per turn, research agreements, pacts) with
  the turns left, friendship and denunciations.
- **World politics:** who is at war with whom, defensive pacts, friendships, denunciations and
  city-state alliances among the civilizations you know.
- **📢 Make a demand:** ask them to stop settling near you, spreading religion or spying. An AI
  answers on its turn; a human finds it in their 📨 Inbox.

### 🏰 City-states

- Pick a city-state to see its type and personality, your **influence**, its **ally** (and the
  ally's influence), protectors, how many turns until your relationship drops a level, the
  **bonuses** it gives friends and allies, the resources its ally gets, its unique unit, and its
  **quests** with their influence reward and turns left (including requests to fight a major civ).
- **💰 Tribute willingness** breaks down how willing they are to pay you (0 or more: gold; 30 or
  more and a size-4 city: a worker).
- **Actions:** gift 250/500/1000 gold, **gift an improvement** for one of their resources (200
  gold, needs 60 influence), pledge or withdraw protection, demand gold or a worker as tribute, and
  — for civilizations with that ability — a **💍 Diplomatic Marriage** that brings an allied
  city-state's cities into your empire.
- **War / Peace** with city-states is here too. War asks you to confirm; peace is accepted at once,
  except while you're at war with the city-state's ally.

### 🕵️ Espionage

Only in games where the host turned spies on (`/civ espionage enabled:True` in the lobby).
- Lists your spies with their rank (⭐), where they are and what they're doing.
- **Select a spy → Send to…:**
  - one of **your cities** to guard it against enemy spies
  - a **foreign capital or city** to steal technology
  - a **city-state** to rig its elections
- **✊ Stage coup** tries to take over a city-state's alliance. **🏠 Hideout** brings the spy home.
- Once you move a spy yourself, the advisor leaves your spies alone.

### 🗳️ World Congress

Shows who owns the UN (it counts double), turns to the next vote, the votes needed to win, and
the last result. When a vote is open, pick a civilization to vote for, or abstain.

### 📨 Inbox

Everything the AI has asked of you: **demands** (stop settling near us, stop spying, …) to agree to
or refuse, **friendship offers**, and **trade offers** to accept or decline. There's also a news list
you can clear. Anything you leave unanswered is handled by the advisor when you end your turn.

### 📊 Overview

Your empire at a glance: cities, population, units, era, golden age progress, income per turn,
the happiness breakdown, resources, Great People points, rankings against everyone else, which
victory types are in play, and recent history.

When you earn a **free Great Person**, choose it here.

### 🏆 Victory

Progress towards every victory type in this game: spaceship parts, completed policy trees,
original capitals held, World Congress votes, and turns left. You see your own details plus what
you know about the others, and the score ranking.

### 📖 Civilopedia

Browse every unit, building, wonder, technology, policy, belief, resource, improvement, terrain,
promotion and nation, with Unciv's descriptions and pixel art. Pick a category, flip pages with
◀ / ▶, or **🔍 Search…** (typos are fine). `/civ wiki query:…` does the same lookup anywhere in
your server, even outside a game.

### 🗺️ Map

- Arrow buttons **scroll** the view, and 🏠 **Home** jumps back to your capital.
- **🔍 Inspect…** opens a box: type a tile code to see its terrain, yields, resource,
  improvement, owner and any units on it.
- **🌍 World** shows everything you've explored on one image; **🔎 Zoomed** goes back.

### More tabs

- **🏗️ Production:** the build queue for a city. Pick an entry, then move it up, down, to the top or to the end, remove it, or buy it with gold, faith or another allowed currency. Add items to the end or the top, in this city or all cities, and disable items you never want. **📈 Details** shows turns to the next border tile, We Love the King Day, resistance, local resources, great-person points, religion pressure, a breakdown of any stat, and why something can't be built.
- **🏙️ Cities overview / 🏛️ Wonders:** all your cities side by side (sortable), and every wonder in the game: who built it, and which of your cities could.
- **🧭 Relations:** why the selected civ likes or dislikes you, promises on both sides, active deals with turns left, and who's at war or allied with whom. **📢 Make a demand** asks an AI to stop settling near you, stop spying, and so on. It answers on its turn.
- **📊 Stats:** per-source breakdowns of gold, science, culture, faith and happiness, the score breakdown, unit supply, resources by origin, every religion, global politics, demographics, 📈 **charts** of any stat over time, and the full notification log.
- **🎞️ Replay:** step through the world map turn by turn (from turn 5).
- **📰 News:** every notification. Picking one jumps to the unit, city or tile it mentions.
- **🔬 Research queue:** shown when several techs are queued. **➕ Add goal to queue** on the Research tab lines up more goals.
- **⛪ Found religion:** while founding or enhancing, name your religion (✏️ custom names too) and pick any number of beliefs.

## ↩️ Undo

Made a mistake? **↩️ Undo last action** is the first entry in the Unit actions and City actions
menus. It goes back up to 10 steps within your current turn. Once you end the turn, there's no undo.

## Auto-pick and ending your turn

- **🤖 Auto-pick** asks Unciv's AI to fill in anything you haven't chosen: research,
  production in idle cities, a policy you can afford, religious beliefs. It never moves your units.
- The advisor also always takes care of spies, World Congress votes and replies to AI demands,
  since those have no screen in the bot.
- **✅ End turn:** if the 📝 checklist still has items, the first click warns you and the
  button turns into **⚠️ End anyway**. Click again to end regardless.
- Anything you left unpicked is filled in by the advisor when the turn ends, so the game never
  gets stuck.

## Captured cities

When you capture a city, Unciv first applies its default (usually puppet, or annex for your
old capital). The panel then asks what you want:

- **Keep it as is**
- **Annex** (full control, more unhappiness)
- **Raze** (burn it down over several turns)
- **Spare it** (stop razing)
- **Liberate** (give it back to its founder for a big relationship boost)

## Tips

- **Automate workers** (Unit actions → Automate) to save lots of clicks.
- **Explore** sends a scout out on its own, and **Go to** handles long trips over several turns.
- **Set a research goal** so you don't have to pick a tech every few turns.
- **Turn on 📋 Queue** to plan several items per city at once.
- **The 🗺️ Map tab's 🔍 Inspect** is the quickest way to judge a city site before founding.
- Lost track of the game? `/civ games` lists yours, and `/civ board` re-posts the board.
- Can't continue? `/civ leave` hands your civilization to the AI, so the others can keep playing.
