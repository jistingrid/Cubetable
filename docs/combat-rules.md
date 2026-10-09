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

## Hit points

```json
"hp": { "now": "hp", "max": "hp_max", "temp": "hp_temp" }
```

| Key | Meaning |
|---|---|
| `now` | Id of the stored sheet value that holds current hit points. This is the value combat changes. |
| `max` | Formula for the maximum. |
| `temp` | Optional id of a stored value of temporary hit points; damage drains it first. |

A player with an **Active Actor** whose format has this block uses the sheet value as their hit points, in and
out of combat, **instead of Minecraft health**:

- Damage of any kind (mobs, falls, fire ...) lowers the sheet value, temp first, and the change is saved on the
  server and sent to the player's own sheet. The usual hurt flash plays, but Minecraft takes no damage.
- The vanilla health bar shows the share left (never fully empty), and is re-synced every second, so a DM who
  edits the sheet changes the bar too. The initiative bar and the DM's list show the sheet values.
- At 0 the sheet value stays at 0; dying, healing and rests come later.
- Without an Active Actor, or in a format with no `hp` block, Minecraft health applies as before.
- For now every point of Minecraft damage is one hit point of the sheet (a zombie hit of 3 costs 3); attack
  rolls and rolled damage replace that in the next step.

## Initiative

```json
"initiative": { "roll": "d20 + init", "mob": "d20", "order": "high", "tiebreak": "init" }
```

| Key | Meaning |
|---|---|
| `roll` | Formula the player rolls for their initiative (dice are shown to everyone). A formula without dice, such as `"dex"`, is just calculated. |
| `mob` | Formula for creatures that have no sheet (default `"d20"`). |
| `order` | `"high"` (default): highest result goes first. `"low"`: lowest first. |
| `tiebreak` | Formula; on equal results the higher value goes first. Players go before enemies after that. |

**No `initiative` block = this game has no initiative roll.** The encounter window shows no roll buttons
and the Dungeon Master puts everyone in order by hand (see the `percentile` pack).

### The encounter window

When a fight starts, an encounter window opens (`J`, or `/encounter`, opens it again later): the party on the
left, the enemies on the right, and the turn order along the bottom.

- **Players** press *Roll* for their own initiative (only if the pack has a roll).
- **The Dungeon Master** presses *Roll enemies*, can change any value with `-` / `+` (Shift = 5), moves
  anyone earlier or later in the order with `<` `>`, and presses *Start combat*. Moving someone by hand
  freezes the order against later rolls until *Sort by initiative* is pressed (or a value is changed).
- **After the turns start** the window closes for players. For a Dungeon Master it turns into the combat
  management list: everyone in turn order with their hit points. Hover a name to outline that model in the
  world, click it to open the sheet (closing the sheet brings you back to the list). `J` / `/encounter` opens
  it again.
- Everyone waits in place until the turns start. Anyone who has not rolled stays at the end of the order.
- With **no Dungeon Master online** the server rolls the enemies itself and starts the fight as soon as every
  player who has a roll has rolled.
- Someone who joins a fight that is already running rolls automatically when their game has a roll, and
  otherwise goes last.

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
