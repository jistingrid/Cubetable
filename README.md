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
| Mobs | Still walk freely (6 blocks per turn), not yet square-by-square. |
| Combat end | All enemies dead or out of range, or all players dead. |

Tunables are in `combat/CombatConfig.java`.

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
- Mobs are not grid-locked yet; next: mob turns square by square, a bonus action / abilities bar, config file.
