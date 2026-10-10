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
| `defense` | What an attack roll has to beat on the target's sheet (see Targets, attacks and damage). |

## Bars (hit points, sanity, stamina ...)

A game can have as many tracked bars as it needs. `hp` is the shorthand for the one health bar; `bars` lists
any others:

```json
"hp":   { "now": "hp", "max": "hp_max", "temp": "hp_temp" },
"bars": [
  { "id": "sanity",  "label": "Sanity",  "now": "sanity",  "max": "sanity_max", "color": "purple" },
  { "id": "stamina", "label": "Stamina", "now": "stam",    "max": "stam_max",   "color": "#33CC99" }
]
```

| Key | Meaning |
|---|---|
| `id` | Name of the bar (what effects refer to). `hp` always has the id `hp`. |
| `label` | Shown next to the bar (default: the id). |
| `now` | Id of the stored sheet value that holds the current amount. This is the value combat changes. |
| `max` | Formula for the maximum. |
| `temp` | Optional id of a stored value of temporary points; losses drain it first. |
| `color` | `red`, `green`, `blue`, `purple`, `gold`, `orange`, `gray` or `#RRGGBB`. |
| `vital` | `true` on the bar that is the character's health (the `hp` shorthand is vital). Only one is; a later one replaces an earlier one. |

A player with an **Active Actor** gets all of their format's bars tracked, in and out of combat:

- The **vital** bar is their hit points and replaces **Minecraft health**. Damage of any kind (mobs, falls,
  fire ...) lowers the sheet value, temp first, and the change is saved on the server and sent to the player's
  own sheet. The usual hurt flash plays, but Minecraft takes no damage. The vanilla health bar shows the share
  left (never fully empty) and is re-synced every second, so a DM editing the sheet changes the bar too.
- **All bars** are drawn bottom right on the combat HUD for the player. The initiative bar and the DM's list
  show the vital bar.
- Other bars change through `SheetHealth.change(server, player, barId, amount)` (negative = loss, positive =
  gain up to the maximum); spells and conditions in later steps will use it. A bar can also be edited on the
  sheet like any value.
- At 0 a bar stays at 0; dying, healing and rests come later.
- Without an Active Actor, or in a format with no `hp` / `bars`, Minecraft health applies as before.
- For now every point of Minecraft damage is one point off the vital bar (a zombie hit of 3 costs 3); attack
  rolls and rolled damage replace that in the next step.

## The action bar

During a fight every player has one bar at the bottom centre of the screen: your tracked bars (hit points ...),
the resources of the turn as pips, the movement bar with the moves you can buy, a slot for each action the
pack lists, and End Turn. A game with no bars or no actions just gets a smaller bar. Number keys `1` to `9`
select slots; a slot is dimmed when you cannot pay for it (it is not your turn, a resource is spent, no spell
slot is left). Selecting a slot does not do anything yet: attacks are still a click on the target.

The slots come from the `actions` block:

```json
"actions": {
  "showCost": "auto",
  "sources": [
    { "collection": "weapons", "cost": { "action": "1" }, "icon": "minecraft:iron_sword" },
    { "collection": "spells", "when": "max(row.prepared, 1 - min(row.level, 1))", "cost": { "action": "1" },
      "slot": { "store": "slots_", "level": "level" }, "icon": "minecraft:enchanted_book" },
    { "collection": "features", "icon": "minecraft:nether_star" }
  ]
}
```

| Key | Meaning |
|---|---|
| `collection` | The sheet collection whose rows become slots, in the order of the sources. |
| `label` | The column holding the name (default `name`); rows without a name get no slot. |
| `when` | Formula on the row; the row gets a slot only when it is not 0. Empty: every row. |
| `cost` | Resource id to a formula on the row (`row.actions` works), what using it spends. Empty: free. |
| `slot` | `store` is the prefix of the stored counters (`slots_` means `slots_1`, `slots_1_max` ...), `level` the column holding the level. Level 0 spends nothing. |
| `icon` | An item id drawn on the slot (default: paper). |
| `showCost` | `auto`, `always` or `never` - here for every source, or inside a source to override. |

`auto` writes a cost only when it is not the plain default: one of the first resource (an attack costing one
action) shows nothing, a bonus action shows `B`, three actions shows `3`. `always` writes every cost (a good
fit for three-action games), `never` none. Slots that do not fit on a narrow screen are left off; the bar shows
how many.

## Targets, attacks and damage

In a fight, clicking a creature or a player character targets it (click it again to drop the target). An arrow
floats over every targeted model: a blue one for your own target, gray ones for other players' targets; several
arrows on the same model sit side by side. Targets are only for the creatures in the fight.

What a roll does with the target comes from the format. A collection can be marked in its JSON:

```json
{ "id": "weapons", "targetable": true, "damage": true, ... }
```

| Key | Meaning |
|---|---|
| `targetable` | Rolls of this collection are aimed at your target. Its `roll` columns are attack rolls. |
| `damage` | Only with `targetable`: its `dice` columns (and a spell's cast dice) are damage. |

and the combat block says what an attack roll has to beat:

```json
"defense": { "value": "ac", "label": "AC", "hit": "gte" }
```

`value` is a formula on the **target's** sheet, `label` is how it is written in chat, `hit` is `gte` (meet or beat,
the default), `gt`, `lte` or `lt` (roll-under games). Without a `defense` the roll is only shown against the target.

- **Attack roll:** after the dice, chat gets a line such as `17 vs AC 15: hits Goblin` (green) or `misses` (red).
  Nothing is spent or blocked by a miss; the player decides whether to roll damage.
- **Damage roll:** the owner of the target (the player of a player character, a Dungeon Master for everything else)
  gets a small popup, `Goblin takes 5 damage`, with **Full**, **Half** (rounded down), **Heal** and **Custom** (a number,
  then Damage or Heal). Whatever they press is applied (the sheet's health bar when the target has one) and chat says
  `Goblin received 5 damage` or `Goblin healed 5 HP`. With no Dungeon Master online, damage to a creature is applied in full.
- A roll with no target picked is an ordinary roll, with a reminder in chat.

- **Slots roll too:** on the action bar, clicking a weapon or spell slot rolls its attack (the first `roll` column);
  right-click rolls its damage (the first `dice` column). The slot's `cost` is spent from whoever acts, and a
  levelled spell uses up a slot. Outside the acting combatant's turn the cost is refused.

### Actors: the creatures of a fight

Every enemy (and every NPC that fills a scene) is an **Actor**, made by a Dungeon Master and placed like a token. An
Actor has:

- a **sheet**: a server character (for example the wolf in `packs/generic_d20/characters/wolf.json`, linked to the
  server first). *Own copy* gives the Actor a private copy, so three wolves have three separate hit-point pools;
  *Shared* makes it use the character itself (good for a named NPC);
- a **model**: any Minecraft mob, a player skin, or a floating item or block, changeable at any time;
- a **side**: hostile, neutral or friendly. Hostile Actors nearby join an encounter when it starts;
- **DM control**: when on, the DM walks and acts for it on its turn whatever the Auto movement switch says.

Create and manage Actors in the Actors window (DM tools tab, or `/actors`) or with `/actor create|spawn|recall|model|sheet|...`.
Vanilla mobs that are not Actors no longer take part in fights.

- **DM attacking a player:** on the turn of an Actor the DM controls, the action bar shows the Actor's sheet, slot
  clicks roll with its numbers, chat names the Actor as the roller, and costs come from its own resources.
- **Anyone attacking an Actor:** the attack is compared with the `defense` formula on the Actor's sheet.

### The Stage

`G` (or `/stage`, or the Stage row in the DM tools tab) gives a DM a free cursor over the world, like Foundry's token
layer. Click an Actor to select it (an arrow and its name show over it), drag it to walk it, or right-click the ground
to send the selected Actor there. A small panel offers *DM control*, *Add to fight*, *Recall* and the Actors window.
Inside a fight an Actor moves on the grid, not by dragging. When it is the selected Actor's turn and it is walking by
itself (Auto movement on), the panel's *Take over* button (or `Y`, for whatever creature is on turn) cancels the walk
where it stands and hands the creature to the DM, who walks it by hand from then on (its DM control switch is turned on).

Not there yet: critical hits, saving throws, advantage on bar rolls, and anything at 0 hit points.

## At 0 hit points

The format's `combat.downed` block says what happens when the vital bar reaches 0, whatever lowered it (a damage
prompt, an edit by the DM, a rest gone wrong):

```json
"downed": {
  "label": "Downed",
  "applies": "pc",
  "save": { "label": "Death save", "dice": "d20", "modifier": "0", "target": 10, "hit": "gte",
            "successes": 3, "failures": 3, "natSuccess": 20, "natHeal": 1, "natFailure": 1, "natFailures": 2,
            "damageFails": 1 }
}
```

| Key | Meaning |
|---|---|
| `label` | The state's name, shown in the card and in chat ("Downed", "Dying" ...). |
| `applies` | `pc` (default): player characters go down, a creature's sheet (kind `npc`) is simply dead at 0. `all`: every sheet goes down. |
| `save` | The death save. Leave it out for a game without one: the character then stays down until healed or until a DM steps in. |

The death save rolls `dice` plus `modifier` (a formula on the sheet) and succeeds when it `hit`s `target`
(`gte` default, `gt`, `lte`, `lt`). `successes` before `failures` makes the character **stable**, the other way round
**dead**. A natural `natSuccess` gets the character up with `natHeal` points; a natural `natFailure` counts
`natFailures` failures; each hit taken while down counts `damageFails` failures (a stable character that is hit is
dying again). Any healing above 0 gets a character up.

What the people at the table see:

- The model of a downed, stable or dead character or Actor **lies on the ground** for everyone, and a downed player
  cannot walk or touch blocks. Chat says who went down and how each save went.
- The player gets a **card** (the look of the shown-entry cards) with the save buttons and the successes and failures
  so far. In a fight the save is rolled **on their own turn**, once, and ends the turn; stable and dead combatants
  lose their turns, a downed one has nothing to spend.
- A Dungeon Master gets a card for everyone who goes down, with **Stable**, **Kill** and **Revive** (1 point) buttons,
  and rolls the save for a downed Actor.

Not there yet: dead Actors stay in the initiative order (their turns are skipped), and what dead means for a
player's character (a new sheet, a revival) is up to the table.

## The DM screen

A Dungeon Master can open the encounter window at any time with `J` (or `/encounter`; `J` again closes it). It has
two tabs.

- **Encounter**: with no fight running it lists the players online, and **Start encounter** gathers the party around
  the DM (or the nearest player) with every hostile creature nearby. During a fight it is the initiative window
  (see below) with an **End encounter** button (click twice) next to Start combat.
- **DM tools**: switches for the DM. *Auto movement* is off by default: while a Dungeon Master is connected they walk the creatures. On, creatures take their own turns (they always do when no DM is connected). Off, the DM walks
  each creature: on its turn the squares it can reach are shown, click one to walk there, then press End turn in the
  tracker. Creatures do not attack on their own while it is off. *Combat tracker* shows or hides the corner list.

- **Time pause** (also in the DM tools tab): while it is on, players cannot walk, jump or touch blocks (break, place,
  use), and a "TIME PAUSED" banner shows. DMs are not held, and neither are players inside a fight (the grid
  rules their movement). Looking around, the sheet, dice and chat keep working. Like the other settings it lasts
  until the server stops.

A Dungeon Master who is not one of the fighters still follows the fight (camera, tracker, grid) and runs it. With
several fights at once they follow the oldest. The settings last until the server stops.

### Possessing an Actor

Outside a fight a DM can **possess** an Actor: select it on the Stage and press *Possess*. The DM then walks as the
Actor in first person, breaks and places blocks and uses things as usual, while everyone else sees the Actor doing it.
`P` (or the same on-screen hint at the top) releases: the DM returns to where they started and the Actor stays where it
was left.

How it works: Minecraft cannot steer another entity, so the DM's own player does the walking. It is moved next to the
Actor and made invisible, and the Actor's body is held on the DM's position, look direction and head every tick. So
the DM keeps their own inventory, game mode and hit points while possessing. Possession ends by itself when a fight
begins, when the Actor is recalled or deleted, or when the DM leaves its dimension.

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
- **After the turns start** the window closes. Players see nothing more of it. The Dungeon Master gets the
  **combat tracker**, a compact panel that stays on the screen for the whole fight (top right at first):
  everyone in turn order, a marker and underline on whose turn it is, hit points as numbers and a thin bar.
  Hover a name to outline that model in the world, click it to open the sheet (closing the sheet brings you
  back to the fight). Drag the title bar to move it, the corner to resize it, `_` to fold it down to one line
  (it then shows the round and whose turn it is), `x` or `J` / `/encounter` to hide and show it. *End turn*
  finishes the current turn for whoever has it; *End combat* (click twice) stops the fight.
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
