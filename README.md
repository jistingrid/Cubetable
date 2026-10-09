# Tactical Combat (Fabric 1.21.1)

Baldur's Gate 3 / Fire Emblem style turn-based combat for Minecraft. When a hostile mob locks onto a player,
combat starts: the camera rises to show the battlefield, initiative is rolled, and everyone acts one at a time.
Players move square by square on a grid.

## Build & run

Requires JDK 21 and Gradle 8.10.x (Loom 1.8 does not work reliably with Gradle 9).

```
gradle wrapper --gradle-version 8.10.2   # once
./gradlew build                          # mod jar: build/libs/tactical-combat-0.1.0.jar (NOT the -dev / -sources jars)
./gradlew runClient                      # dev client
```

Needs Fabric Loader and Fabric API on the client **and** the server.

## Controls during combat

| Input | Action |
|---|---|
| Mouse | free cursor (no mouse-look) |
| Left click on a blue square | walk there (server validates and slides you along the path) |
| Left click on a hostile | melee attack it (uses your action; must be within ~3 blocks, so move first) |
| Enter (rebindable "End Turn") or `/tbc endturn` | end your turn |
| W A S D | pan the camera away from the active unit |
| Mouse wheel | zoom |
| Middle mouse drag / Left-Right arrows | rotate (drag up/down also tilts) |
| 1-9, E, T, / , Esc | hotbar, inventory, chat, command, pause menu still work |
| `K` (rebindable) or `/sheet` | open the character sheet window (draggable and resizable; works in and out of combat; New / Edit create and change characters and save them to disk). Games are not built in: they are packs in `config/tacticalcombat/packs/<game>/` (copy the ones you want from this repo's `packs/` folder: `generic_d20`, `percentile`); characters in `config/tacticalcombat/characters/`, tables of items / weapons / skills with their own columns and roll buttons; see [docs/sheet-formats.md](docs/sheet-formats.md) |
| `/tbc roll <dice>` | roll dice, e.g. `d20`, `d%`, `2d6`, `d20+5` (d4 d6 d8 d10 d% d12 d20, up to 10 at once); a 2D tumbling animation plays on every player's screen, then the result is printed in chat |
| `/tbc start`, `/tbc end` (op) | force a fight with nearby hostiles / end it |

## What is implemented

| System | Behaviour |
|---|---|
| Combat start | A hostile (`Monster`) targeting a player within 16 blocks (and within 6 blocks above/below) starts a fight. Players and aggroed hostiles within 16 blocks horizontally and 6 vertically join, later arrivals are recruited every 0.5 s. Enemies in caves far below (or high above) never join. |
| Leaving | An enemy that ends up more than 24 blocks away horizontally, or more than 10 blocks above/below every player, is removed from the fight (larger than the join limits so it does not flip in and out). |
| Initiative | d20 per combatant, highest first, players win ties. Top bar with spawn-egg icons, initiative number and health bar. |
| Turns | One combatant acts at a time; everyone else is held in place by the server. |
| Grid | One square = one block position. 4-directional steps. Up 1 block per step, down up to 3. Blocked by solid blocks, water, lava, fire, cactus, magma, berry bushes, cobwebs, powder snow, and by enemies. Allies can be walked through but not stood on. |
| Movement | 8 squares per turn for players. Leftover squares can be used in several clicks. Blue = reachable, white = your square, red = squares enemies can reach or hit next turn, cyan line = path preview, bright frame = hovered square. |
| Camera | Rises when combat starts and follows whoever's turn it is (so you watch enemy turns too). Independent of where you look. It never collides with terrain: blocks between the camera and the active unit fade to ~30% opacity instead (a cone that is narrow at the unit and wide at the camera; the ground under the unit never fades). Clicks and the square highlights look straight through faded blocks. |
| Action | One melee attack per turn (click an enemy). Mobs get one hit, then their turn ends. |
| Mobs | Move on the same grid as players: on its turn a ground mob picks the reachable square (up to 6 squares) that gets it closest to the nearest player, walks there square by square, then acts. Melee mobs (zombies, spiders...) wait a moment, then hit once if in reach; archers aim for about 6 blocks of distance and shoot with their normal AI; creepers use their normal AI. The red area shown on your turn is exactly where they can reach and hit. Flying and swimming mobs (phantoms, ghasts, guardians...) can not use the ground grid and still walk freely under a 6-block budget. |
| Combat end | All enemies dead or out of range, or all players dead. |

Tunables are in `combat/CombatConfig.java`.

## Game rules in the pack

A format's `combat` block says how its sheets drive combat: distance per square, how much you may move, the
per-turn resources (D&D's action + bonus action, Pathfinder 2e's three actions) and how to buy more movement
(Dash, Stride). See [docs/combat-rules.md](docs/combat-rules.md).

## Bars and hit points

A format's `combat` block names the sheet values that are tracked as bars: `hp` for the health bar, `bars` for
any others (sanity, stamina, mana ...). A player with an Active Actor uses the health bar instead of Minecraft
health: damage lowers the sheet, and the vanilla bar shows the percentage left. See
[docs/combat-rules.md](docs/combat-rules.md#bars-hit-points-sanity-stamina-).

## Encounter window

A fight starts in a planning phase with an encounter window (`J` / `/encounter`): players roll initiative if
their game has one, the Dungeon Master rolls the enemies, orders the turns and starts the combat. See
[docs/combat-rules.md](docs/combat-rules.md#initiative). Once the turns run, the Dungeon Master has a compact combat tracker on the screen (movable, resizable, foldable): everyone in turn order with hit points, hover to outline a model, click to open the sheet, plus End turn / End combat.

## Shared characters and the Dungeon Master

- **Link to server** (Edit page of a character): the server keeps a copy, saved with the world (`tacticalcombat/characters.json`). Every save you make is sent to it; changes made by a DM come back to your own file. **Unlink** stops sharing (your file stays).
- Everyone sees the characters other players have linked in the sheet's character switcher, as `name (owner)`. They are read-only unless you are a DM. Characters stay visible when their owner is offline.
- `/tbc dm add|remove|list <player>` (operators) manages Dungeon Masters. A DM sees a "DM" tag in the sheet, may edit or delete any shared character, and later gets the combat controls.
- **Active Actor:** the *Active Actor* button (title bar, for your own linked characters) makes your player model stand for that character in and out of combat. Combat reads its numbers from it. Click again to clear.
- **Who sees what:** by default a player only sees the characters they own. A DM sees everything, and uses the **Share** button in the title bar (shown for linked characters) to choose *Don't share*, *Share with all*, or specific online players. Players it is shared with see the sheet read-only; the server only sends a character to those allowed to see it.
- A shared character must fit in about 30 KB compressed. There is no validation: the group is trusted.

## Layout

```
src/main/java/dev/tacticalcombat/
  TacticalCombatMod.java        entry point: events, packets, commands
  grid/Grid.java                square rules, BFS reachable squares, surface height
  combat/Combat.java            one fight: initiative, turns, freezing, grid moves, attacks
  combat/CombatManager.java     all fights, auto-start detection, rule hooks
  combat/Combatant.java         per-participant turn resources + current path
  combat/CombatConfig.java      constants
  net/                          CombatState, Grid (S2C); EndTurn, MoveRequest, AttackRequest (C2S)
src/client/java/dev/tacticalcombat/
  client/TacticalCombatClient.java   keybind, receivers, screen management, camera keys
  client/TacticalScreen.java         invisible screen giving a free cursor; clicks, scroll, drag
  client/MousePicker.java            mouse -> ray -> square / enemy
  client/GridRenderer.java           ground highlights, path line, cursor
  client/ClientGrid.java             received squares + hover state
  client/ClientCombatState.java      mirrored fight state + camera state
  client/CombatHud.java              initiative bar + turn panel
  mixin/client/CameraMixin.java      tactical camera
  mixin/client/GameRendererMixin.java  hides floating hand
  mixin/client/InGameHudMixin.java     hides the crosshair
  mixin/client/KeyboardInputMixin.java blocks free walking
```

## Known limitations / next steps

- Block fading rebuilds the affected chunk sections (a few times a second while the camera moves) and needs a Fabric Rendering API renderer (built-in Indigo, or Sodium with FRAPI support). Shaderpacks that replace the translucent pass may draw faded blocks differently.
- Using items (bow, potions, food) is not possible from the combat screen yet. Only melee attacks via click.
- The cursor ray uses the FOV setting; speed effects that change the FOV can shift the pick slightly.
- Stairs count as full blocks, so highlights above stairs sit half a block high. Fences and walls are not walkable.
- The walk is server-driven (one position update per tick), so it looks slightly steppy.
- Mobs ignore walls for wide hitboxes while sliding along a path (they are moved by the server, not by pathfinding), and do not avoid being hit when choosing a square. Next: a bonus action / abilities bar, config file.
