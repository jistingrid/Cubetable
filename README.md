# Tactical Combat (Fabric 1.21.1)

Baldur's Gate 3 style turn-based combat for Minecraft. When a hostile mob locks onto a player, combat starts:
the camera rises to show the battlefield, initiative is rolled, and everyone acts one at a time.

## Build & run

Requires JDK 21 and Gradle 8.10+ (no wrapper is included; run `gradle wrapper` once, or copy the `gradlew` files
from the official Fabric example mod).

```
gradle build        # jar ends up in build/libs/tactical-combat-0.1.0.jar
gradle runClient    # launch a dev client
```

Needs Fabric Loader and Fabric API on the client **and** the server (the mod syncs combat state with custom packets).
If a version in `gradle.properties` is rejected, pick the current 1.21.1 values from https://fabricmc.net/develop/.

## What phase 1 does

| System | Behaviour |
|---|---|
| Combat start | A hostile (`Monster`) mob targeting a player within 16 blocks starts a fight. Players within 24 blocks of the trigger and aggroed hostiles within 24 blocks join. Later arrivals are recruited every 0.5 s. |
| Initiative | d20 per combatant, highest first, players win ties. Shown as a bar at the top of the screen (spawn-egg icons, initiative number, health bar; green = party, red = enemy, gold = active). |
| Turns | One combatant acts at a time; everybody else is frozen in place (server snaps them back, the client also blocks input). |
| Movement | 8 blocks per turn for players, 6 for mobs (horizontal distance). Blue bar in the HUD; you are stopped at the limit. |
| Action | One attack per turn. Further attacks are cancelled. Mobs likewise get one hit, then their turn ends. Bonus action exists as a resource but nothing spends it yet. |
| Ending a turn | **Enter** (rebindable: "End Turn") or `/tbc endturn`. Mob turns end automatically (attack done, movement spent, or 5 s cap). |
| Combat end | All enemies dead, all players dead, or every enemy farther than 48 blocks. |
| Camera | Eases up to 16 blocks behind the player and tilts the view to ~50° down. It looks along your aim direction, so the crosshair still matches what you hit. Camera collides with blocks. |
| Commands | `/tbc start` (op, forces a fight with nearby hostiles), `/tbc end` (op), `/tbc endturn`. |

Tunables are in `combat/CombatConfig.java`.

## Layout

```
src/main/java/dev/tacticalcombat/
  TacticalCombatMod.java        entry point: events, packets, commands
  combat/Combat.java            one fight: initiative, turns, freezing, movement budget
  combat/CombatManager.java     all fights, auto-start detection, rule hooks
  combat/Combatant.java         per-participant turn resources
  combat/CombatConfig.java      constants
  net/*Payload.java             S2C state snapshot, C2S end-turn
src/client/java/dev/tacticalcombat/
  client/TacticalCombatClient.java   keybind, packet receiver, HUD hook
  client/ClientCombatState.java      mirrored state + camera blend
  client/CombatHud.java              initiative bar + turn panel
  mixin/client/CameraMixin.java      tactical camera
  mixin/client/GameRendererMixin.java  hides floating hand
  mixin/client/KeyboardInputMixin.java blocks walking out of turn
```

## Known limitations / next phases

- Written against Yarn 1.21.1 mappings but **not yet compiled or play-tested**. If something fails to compile, it will most likely be a mapping name in one of the three client mixins (`Camera`, `GameRenderer#renderHand`, `KeyboardInput#tick`).
- The camera follows your aim; there is no free pan/zoom or click-to-move yet.
- Waiting players rubber-band if the server has to snap them back (the client-side input lock avoids this in normal play).
- Ranged attacks use the same one-hit-per-turn rule (extra arrows are fired but do no damage).

Suggested next steps: click-to-move with a path preview and distance ring, click-to-target attacks with hit/damage rolls, a real bonus action / abilities bar, free camera pan, config file, per-turn timer.
