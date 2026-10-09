# The `combat` block of a sheet format

A game pack tells the mod how to turn its character sheets into combat numbers with a `combat` block in
`format.json`. The engine only knows generic ideas (a grid, a movement pool, per-turn resources, ways to buy
more movement); every number is a formula evaluated on the character's sheet, so a sheet change (new boots,
exhaustion ...) takes effect on the next turn without code changes. This page covers movement and the
per-turn resources; attacks, defense and hit points come in later steps.

```json
"combat": {
  "grid": { "square": 5, "unit": "ft" },
  "resources": { "action": "1", "bonus": "1" },
  "movement": {
    "speed": "speed",
    "pool": "speed",
    "moves": [
      { "id": "dash", "label": "Dash", "cost": { "action": 1 }, "grants": "speed", "auto": false, "unused": "keep" }
    ]
  }
}
```

| Key | Meaning |
|---|---|
| `grid.square` | How many `unit`s one grid square is. A square is always one block in the world. `speed 30` with `square 5` gives 6 squares (rounded down). |
| `grid.unit` | Name of the unit, only used for display ("20 ft"). Leave empty to show plain squares. |
| `resources` | Per-turn resources, each a formula for how many you get. They refill when your turn starts. `action` is the one attacks spend. Default: `action 1`, `bonus 1`. |
| `movement.speed` | Formula: the distance of one full move (in `unit`s). |
| `movement.pool` | Formula: distance you may walk as soon as the turn starts. Default: the speed. Use `"0"` when you must pay to move. |
| `movement.moves` | Ways to buy more movement with resources (see below). |

## Moves you can buy

Each entry of `moves`:

| Key | Meaning |
|---|---|
| `id`, `label` | Name used by the button. |
| `cost` | Resources spent, e.g. `{ "action": 1 }`. |
| `grants` | Formula: distance gained (default: the speed). |
| `auto` | `true`: bought on its own when you click a square you cannot otherwise reach (the blue area includes it). `false`: the player presses a button next to the movement bar. |
| `unused` | `"keep"` (default): leftover distance stays usable. `"lost"`: whatever you do not walk in that move is gone. |

## Two games, same engine

**D&D style**: you walk part of your speed, act, then walk the rest. Dash is a button that spends the action
to add another full speed:

```json
"resources": { "action": "1", "bonus": "1" },
"movement": { "speed": "speed", "pool": "speed",
  "moves": [ { "id": "dash", "label": "Dash", "cost": { "action": 1 }, "grants": "speed", "auto": false } ] }
```

**Pathfinder 2e style**: three actions per turn and nothing is free. Moving is a Stride that costs one
action and is over once you stop, even if you did not use all of your speed:

```json
"resources": { "action": "3" },
"movement": { "speed": "speed", "pool": "0",
  "moves": [ { "id": "stride", "label": "Stride", "cost": { "action": 1 }, "grants": "speed", "auto": true, "unused": "lost" } ] }
```

Clicking a square pays for a Stride by itself; clicking again after the walk pays for another one.

## Notes

- The server needs the pack too (packs are read from `config/tacticalcombat/packs` on the server as well).
  Use `/tbc reload` after editing a pack on a running server.
- Players use their **Active Actor**: the character chosen with the *Active Actor* button in the sheet's title bar
  (it has to be linked to the server). Without one, the first of their characters whose format has a
  `combat` block is used. Characters without a `combat` block, and mobs, use 8 / 6 squares with one
  action and one bonus action.
- Not here yet: initiative, attacks and damage, conditions, diagonal and difficult-terrain costs.
