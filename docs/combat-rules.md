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
| `resources` | Per-turn resources, each a formula for how many you get. They refill when your turn starts. Default: `action 1`, `bonus 1`. |
| `attackResource` | The resource an attack spends (one per attack, from any combatant). Default: `action` when the game has it, else its first resource. `""` makes attacks cost nothing of the turn (a game with no turn economy). |
| `resourceStyles` | How a resource looks on the turn panel: `{ "bonus": { "label": "Bonus", "color": "#5fb7a8", "shape": "diamond" } }`. `label` is its name, `color` a name or `#RRGGBB`, `shape` `circle` or `diamond`. Anything left out keeps the default look (`action` gold circles, `bonus` teal diamonds, others grey). |
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

During a fight every player has one bar at the bottom of the screen: your tracked bars (hit points ...), the
resources of the turn as pips, the movement bar with the moves you can buy, a slot for each action the pack puts
on the bar, **tray buttons**, End Turn and a three-dot settings button. The bar glows on your turn. A game with
no bars or no actions just gets a smaller bar.

- **Slots:** number keys `1` to `9` (or a click) use a slot: its attack roll, or its dice when it has no attack;
  right click rolls its damage (free). A slot is dimmed when you cannot pay for it. Shift + click rolls critical
  damage on purpose (see Critical hits).
- **Cards:** rolling an entry of a collection with `"share": true` (from the bar or the sheet) also shows its card to
  everyone, like the Show button. One entry shows once however many rolls follow (attack, then damage); rolling
  another entry, or the same one a minute later, shows it again.
- **Trays:** a source with a `tray` does not take a slot; its entries live in a pop-up list opened by the tray's
  button. Entries can be grouped under headings, show their
  icon, level, cost and spell-slot dots, and the star on an entry **pins** it to the bar (pins are remembered per
  character). Click an entry to use it, right click for its damage. `Esc` or a click elsewhere closes the tray.
- **Settings** (the three dots): size, opacity, and whether to show health bars, resources, movement and key
  numbers. Drag the bar's background to move it, right click the background to put it back. Everything is saved
  in `config/tacticalcombat/actionbar.json`.

The slots and trays come from the `actions` block:

```json
"actions": {
  "showCost": "auto",
  "trays": [
    { "id": "spells", "label": "Spells", "icon": "minecraft:enchanted_book" },
    { "id": "features", "label": "Features", "icon": "minecraft:nether_star" }
  ],
  "sources": [
    { "collection": "weapons", "cost": { "action": "1" }, "icon": "minecraft:iron_sword" },
    { "collection": "spells", "when": "max(row.prepared, 1 - min(row.level, 1))", "cost": { "action": "1" },
      "slot": { "store": "slots_", "level": "level" }, "icon": "minecraft:enchanted_book",
      "tray": "spells", "group": "level" },
    { "collection": "features", "icon": "minecraft:nether_star", "tray": "features" }
  ]
}
```

| Key | Meaning |
|---|---|
| `trays` | Optional list of `{id, label, icon}`. A source naming an undeclared tray gets one made for it. |
| `collection` | The sheet collection whose rows become slots, in the order of the sources. |
| `label` | The column holding the name (default `name`); rows without a name get no slot. |
| `when` | Formula on the row; the row gets a slot only when it is not 0. Empty: every row. |
| `cost` | Resource id to a formula on the row (`row.actions` works), what using it spends. Empty: free. |
| `slot` | `store` is the prefix of the stored counters (`slots_` means `slots_1`, `slots_1_max` ...), `level` the column holding the level. Level 0 spends nothing. |
| `icon` | An item id drawn on the slot (default: paper). |
| `tray` | Tray id: the rows go in that tray instead of on the bar. Empty: on the bar. |
| `group` | Column whose value heads the sections of the tray (`level` gives "Level 1", "Level 2" ...). |
| `info` | Column shown as the description in the tooltip. |
| `showCost` | `auto`, `always` or `never` - here for every source, or inside a source to override. |

`auto` writes a cost only when it is not the plain default: one of the first resource (an attack costing one
action) shows nothing, a bonus action shows `B`, three actions shows `3`. `always` writes every cost (a good
fit for three-action games), `never` none. Slots that do not fit on a narrow screen are left off; the bar shows
how many.

## Targets, attacks and damage

In a fight, clicking a creature or a player character targets it (click it again to drop the target). An arrow
floats over every targeted model: a blue one for your own target, gray ones for other players' targets; several
arrows on the same model sit side by side. Targets are only for the creatures in the fight.

The model whose turn it is turns to face its target: when you pick a new target on your turn, and again when it
finishes walking. This covers a player, and a creature the Dungeon Master walks (the DM's target is used). Other
players see the model turn; your own view is not rotated.

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

### Critical hits

Criticals are defined by the pack, per column, so a game without them never shows one. A roll column says what a
critical is, a dice column says what a critical does to its damage:

```json
{ "id": "hit", "type": "roll", "roll": "d20 + ...", "crit": "row.crit", "fumble": "1" },
{ "id": "dmg", "type": "dice", "critDamage": "double" },
{ "id": "crit", "type": "number", "label": "Crit range (lowest natural roll)", "default": 20, "hidden": true }
```

| Key | Meaning |
|---|---|
| `crit` | Roll column. A formula on the row: the **natural** roll (the dice, without the modifier; the kept die with advantage) that is a critical hit, or higher. Usually a hidden number column, so each weapon or spell can have its own range (19 for a keen blade) in its entry editor (the `...` button). |
| `critMode` | `gte` (default: the natural roll must reach `crit`) or `lte` (roll-under games: it must be at most `crit`). |
| `fumble` | Roll column. A formula: the natural roll that is a fumble, at the other end (1, or 96 or more for roll-under). |
| `critDamage` | Dice column. `double` (twice the dice, the modifier once), `x2` ... `x10` (the dice and the modifier times that), or `max` (the modifier grows by the most the dice can show). |

- A critical hit always hits and a fumble always misses, whatever the target's defense. Chat says `Critical hit!` or
  `Critical fail!` after the roll, and the line under it says so (`27 vs AC 15: critical hit on Goblin`). With no target
  the roll is still marked.
- **Critical damage is earned, not asked for.** After a critical hit on a target, your next damage roll on that target is
  rolled as a critical (`Critical damage!` in chat, the dice doubled or as the rule says) if its dice have a
  `critDamage`; the line under the attack says so. Rolling any damage, or a new attack, ends it, and it lapses after two
  minutes. **Shift** + click (or Shift + right-click on the action bar) rolls critical damage on purpose; **Ctrl** +
  click rolls it normally even after a critical hit.
- Dice without a `critDamage` never ask, never mark and never change; rolls from columns without `crit` never say
  `Critical hit`. (A natural 20 on a saving throw or a skill is not a critical.)

### Actors: the creatures of a fight

Every enemy (and every NPC that fills a scene) is an **Actor**, made by a Dungeon Master and placed like a token. An
Actor has:

- a **sheet**: a server character (for example the wolf in `packs/generic_d20/characters/wolf.json`, linked to the
  server first). *Own copy* gives the Actor a private copy, so three wolves have three separate hit-point pools;
  *Shared* makes it use the character itself (good for a named NPC);
- a **model**: any Minecraft mob, a player skin, or a floating item or block, changeable at any time;
- a **side**: hostile, neutral or friendly. Hostile Actors nearby join an encounter when it starts;
- **DM control**: when on, the DM walks and acts for it on its turn whatever the Auto movement switch says.

Create and manage Actors in the **Actors tab** of the DM window (`J`, then the third tab; or `/actors`) or with `/actor create|spawn|recall|model|sheet|...`.
Vanilla mobs that are not Actors no longer take part in fights.

**Their own space.** The private copy of an Actor's sheet is kept out of the normal sheet lists (the character window and
the pickers), so a bestiary of fifty goblins does not bury your party's sheets. Open it from the Actors tab (*Open sheet*)
or by clicking the creature in the tracker. A sheet the DM has shared with players still shows in the lists.

**Folders and tags.** The left list of the Actors tab is a folder tree. *+ Folder* makes an empty folder (click it to
rename or delete it; deleting never deletes the Actors in it); an Actor's *Folder* button files it. Tags are free labels
(`undead`, `act 2`): every tag in use is a switch on the Actor, and the box adds a new one. The search box finds by
name, folder or tag; start with `#` to match tags only (`#undead`). Folders are one level deep. Commands:
`/actor folder <actor> <name>`, `/actor unfile <actor>`, `/actor tags <actor> a,b,c`.

**Import.** *Import sheets...* lists every sheet the server knows (yours and the players') with a check box each. Pick
some, choose Own copy or Shared, a side, a folder and tags for the whole batch, and press *Import*: each becomes an
Actor named after its sheet, looking like the Minecraft creature of that name when there is one (a sheet called
"Wolf" gives a wolf), otherwise a villager - change the model afterwards.

- **DM attacking a player:** on the turn of an Actor the DM controls, the action bar shows the Actor's sheet, slot
  clicks roll with its numbers, chat names the Actor as the roller, and costs come from its own resources.
- **Anyone attacking an Actor:** the attack is compared with the `defense` formula on the Actor's sheet.

### The Stage

`G` (or `/stage`, or the Stage row in the DM tools tab) gives a DM a free cursor over the world, like Foundry's token
layer. Click an Actor to select it (an arrow and its name show over it), drag it to walk it, or right-click the ground
to send the selected Actor there. A line on the ground shows which way the selected Actor faces.

Posing a scene:

| Do this | To |
|---|---|
| `Q` / `E`, the `<` `>` buttons, or the mouse wheel over the Actor | turn it 15 degrees left / right (hold Shift, or use `<<` `>>`, for 90) |
| Shift + right-click the ground | make it face that spot |
| *Face me* | turn it toward you |
| *Snap to block* | centre it on its block and straighten it to the nearest 45 degrees |
| Arrow keys | nudge it one block (Shift: half a block); up is away from your camera |
| *Clone beside* | copy it (with its own sheet, folder and tags) one block to its right |
| *Lock* | stop drags, turns and nudges on it until unlocked (kept for this session) |
| `Tab` / Shift+`Tab`, or *Prev* / *Next* | select the next / previous placed Actor |
| `Delete` | recall it (take it out of the world) |
| *Snap drag* (top left) | dragging puts the Actor in the middle of a block (on by default) |

The panel also has *DM control*, *Add to fight*, *Recall* / *Take over*, *Possess* and the Actors tab. Turning works in a
fight; moving does not (the grid does that). `/actor turn <actor> <degrees>` does the same from chat.
Inside a fight an Actor moves on the grid, not by dragging. When it is the selected Actor's turn and it is walking by
itself (Auto movement on), the panel's *Take over* button (or `Y`, for whatever creature is on turn) cancels the walk
where it stands and hands the creature to the DM, who walks it by hand from then on (its DM control switch is turned on).

Not there yet: saving throws and advantage on bar rolls.

### Which bar damage lowers

By default damage lowers the health bar and healing raises it. A game can point damage at another bar and name it:
`"damage": { "bar": "stress", "noun": "stress" }` in the `combat` block (`bar` is the id of one of the `bars`, `noun`
is the word in "X takes 5 stress"; chat says "X healed 3 Stress" with the bar's label). Damage prompts, damage from
rules, healing and the floating numbers all follow it. Going down (below) still works from the health bar.

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

- **Encounter**: with no fight running it lists everyone who can take part - every player online (spectators
  excluded) and every Actor placed in the world - **Party** on the left, **Enemies** (hostile Actors) on the right,
  each with a checkbox. Ticked ones are in the fight; click a row to tick or untick it, or use **All** / **None**.
  By default every player and every hostile Actor near a player is ticked. **Start encounter** begins the fight with
  the ticked ones (it needs at least one player and one other). During a fight the window is the initiative window
  (see below); its **Participants** button shows the same checklist, where ticking someone adds them to the running
  fight (they roll initiative and wait at the end of the order) and unticking takes them out, also from the middle of
  a fight (if it was their turn, the next one begins). An unticked player is not pulled back in by walking close
  to the fight. The ticks reset when the fight ends. **End encounter** (click twice) sits next to Start combat.
- **DM tools**: switches for the DM. *Auto movement* is off by default: while a Dungeon Master is connected they walk the creatures. On, creatures take their own turns (they always do when no DM is connected). Off, the DM walks
  each creature: on its turn the squares it can reach are shown, click one to walk there, then press End turn in the
  tracker. Creatures do not attack on their own while it is off. *Combat tracker* shows or hides the corner list.

- **Pause game** (in the DM tools tab): while it is on, players cannot walk, jump or touch blocks (break, place,
  use), and a "Game Paused" banner shows. DMs are not held, and neither are players inside a fight (the grid
  rules their movement). Looking around, the sheet, dice and chat keep working. Like the other settings it lasts
  until the server stops.

The DM window is **resizable** (drag its right edge, bottom edge or the corner; each tab remembers its own size until the
game is closed), and the DM tools tab is a compact list: each tool has a **?** that shows what it does when hovered and its
switch on the right. The window has three tabs: Encounter, DM tools and Actors.

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

## Conditions

The format's `combat.conditions` names the collection that lists conditions and how its durations count:

```json
"conditions": { "collection": "conditions", "duration": "duration", "unit": "unit", "turns": "turns", "seconds": "seconds" }
```

`duration` is a number column (0 or less = until removed), `unit` a choice column, and `turns` / `seconds` are the
options of that column that count each way. A condition measured in **turns** loses 1 when its owner's turn ends in a
fight (so "1 turn" lasts until the end of that creature's next turn); one in **seconds** loses 1 every real second
(it waits while time is paused). At 0 it is taken off the sheet and chat says "Name: Prone ended." This works for a
player's Active Actor and for Actors alike.

### Condition rules

A condition can do things: give its collection a column of type `rules` and each row carries a list of rules
**When X happens, if Y, do Z**. In the row's edit panel, "+ Rule" adds one; click the When / Do buttons to change
them. A pack's presets can ship rules, e.g. `"rules": "turn_start||damage||1d4"` (a line per rule,
`when|if|effect|target|value`).

| When | |
|---|---|
| `turn_start` | the owner's turn begins (after its resources and movement are set) |
| `turn_end` | the owner's turn ends (before durations count down) |
| `expired` | the condition's duration ran out |
| `roll` | the owner makes a roll (attack rolls and plain rolls such as checks; not damage) |
| `attack` | the owner makes an attack roll |
| `attacked` | an attack roll is aimed at the owner |
| `round_start` | a new round begins |
| `moved` | the owner finished a move (`moved` = squares walked) |
| `hit` / `miss` | the owner's attack hits / misses its target (`distance`) |
| `struck` / `dodged` | an attack on the owner hits / misses (`distance`) |
| `damaged` / `healed` | a damage prompt was applied to the owner (`damage` = the amount) |
| `downed` | the owner dropped to 0 and is down |
| `applied` | this condition was just added (from the sheet or by another rule) |
| `used` | (on an entry) it was used: its Use button or slot, a plain roll of it, or its damage roll |
| `check_passed` / `check_failed` | a `check` effect of the same rule list was rolled and met / missed its DC |

| Do | target | value |
|---|---|---|
| `damage` / `heal` | bar id (blank: the main health bar) | number or dice formula (`1d4 + 2`), rolled by the server |
| `resource` | resource id (`action`) | what the owner has of it this turn (`0` = loses it) |
| `speed` | - | the movement of this turn in the game's unit; may use sheet values (`speed / 2`) |
| `message` | - | text for chat |
| `check` | check id (`str_save`) | asks the creature to roll that check against the DC in the value (a formula; blank: no DC) - see Roll requests |
| `apply` | condition name | adds the pack's preset condition of that name to the owner |
| `remove` | condition name (blank: this one) | removes that condition; its `expired` rules run |
| `advantage` / `disadvantage` | - | on a single d20; the roll is made with two dice. One of each cancels out, as does any number of both. |
| `bonus` | - | a number added to the roll (`-2`), or a formula |

`if` is a formula, true when not 0 (blank: always), e.g. `hp < hp_max / 2`. On `attack` / `attacked` it can use
`distance`, the squares between attacker and target (Prone: `attacked`, `distance <= 1`, advantage). Roll rules work
on rolls made from the sheet or the action bar, and chat names the conditions that changed the roll. On event triggers the names in the
table (`damage`, `moved`, `distance`) can be used in `if` and values, and `{damage}` in a message. Effects only work
where they make sense: `resource` and `speed` on turn triggers, `advantage` / `disadvantage` / `bonus` on `roll`,
`attack` and `attacked`; the others anywhere else. A rule that does not fit its trigger does nothing; rules that add
conditions that add conditions stop after three levels. The generic d20 pack uses them for
Poisoned (disadvantage on rolls), Stunned (no action or movement, attacks against it have advantage), Prone,
Restrained, Blinded, Invisible and Paralyzed. Add `turn_start||damage||1d4` to a condition for damage over time.
Unconscious adds Prone when applied, and Concentrating reminds
its owner of the save when they take damage and ends when they go down. Quick conditions that need no tracking: Frightened,
Grappled, Incapacitated and Petrified. Ones that tick: Burning (1d6 a turn for 3 turns), Bleeding (1d4 at turn end),
Regenerating (heal 5), Slowed, Hasted and Dodging (1 turn). Not yet: saving throws and rules on spending resources.

### Rules on weapons, features and spells

Any collection can have a `rules` column too. Its rules run when the entry is rolled with a target: `hit` / `miss`
when its attack roll hits or misses, `used` when its damage roll is made. Formulas (damage, DC) use the roller's sheet;
the effects (`damage`, `heal`, `apply`, `remove`, `check`, `message`) happen to the **target**. The wolf's Bite:

```
hit   | | check | str_save | 11        My attack hits: ask for a Strength save, DC 11
check_failed | | apply | Prone |      Check failed: add Prone
```

A DC from the sheet works the same: `8 + prof + str_mod`. The rules ride along with the roll request, so the bar and
the sheet both trigger them.

### Features and special attacks

A format describes an ability that does more than roll dice with columns on its collection:

| Column | |
|---|---|
| `rules` (type `rules`) | what it does: When / If / Do rules, edited as a list in the entry |
| `use` (type `activate`, optional `uses`, `max`) | a **Use** button on the sheet row; `uses` / `max` name the number columns that count its uses ("Use 1/1"); each use takes one, and the button greys at 0 |
| `recharge` (a choice column: Never / Short rest / Long rest) | which rest gives the feature its uses back: the pack's **Short rest** button (a `button` widget with `refill`, see sheet-formats) refills the features set to Short rest, **Long rest** those set to Short or Long rest. The Uses left counter on the sheet row follows |
| cost | the source in `combat.actions` gives `cost` formulas on the row (`"bonus": "row.bonus_cost"`), paid when it is used |

Clicking its action-bar slot (or tray entry) does the same as the Use button for an entry that has an activate column or an "I use it" rule and nothing to attack with. Using it pays the cost, says so in chat and runs the rules whose When is **I use it**. Their effects happen to the user (Auto = Self when you use it; for an attack's rules Auto = the target); each rule can be switched to **Self** or **Target** with the button on its first line. Formulas are the sheet's formulas: dice (`1d10`), sheet values (`level`, `str_mod`), the entry's own columns (`row.uses`, `row.bonus`) and, in `if` and values, the resources left this turn (`action`, `bonus`).

```
Second Wind   used | | heal | | 1d10 + level | self           heals the user; the dice are shown
Action Surge  used | | resource | action | action + 1 | self   one more action this turn
Wolf bite     hit  | | check | str_save | 11                   Strength save, DC 11 ...
              check_failed | | apply | Prone |                  ... or fall prone
```

Anything a rule could not do shows to the Dungeon Masters as a `[rules]` line in chat (a condition name the pack does
not have, a formula that does not work, a game pack the server lacks) instead of silently doing nothing.

### One roll at a time

A roll that is already on its way can not be started again: from the sheet or the action bar, the same roll (an
attack, its damage, a skill, a spell's dice) is ignored until its dice have finished tumbling. Different rolls are not
affected, and a roll the server never answered (not your turn) frees itself after ten seconds. Using a feature and
casting a spell without dice are held for a moment, so a double click does not use or spend twice.

### Roll requests

A `check` effect asks the affected creature to roll: the owner (a player their own character, the Dungeon Master a
creature) gets a prompt with **Roll / Advantage / Disadvantage**, like the damage prompt. The server rolls it with
that creature's sheet, shows the dice to everyone with "DC 11: success / failure", then fires `check_passed` or
`check_failed`. The checks come from the format's `combat.checks`:

```json
"checks": [ { "id": "str_save", "label": "Strength save", "roll": "d20 + save_str" }, ... ]
```

The creature's own conditions can change the roll (Poisoned's `roll` disadvantage applies to its saves). Other
mechanics can use the same request later (`Checks.request` on the server), in a fight or out of one.

**Roll request tool (Dungeon Master).** In the DM tools (the Encounter window, "Roll request" > Open) tick who to ask
(players online and Actors placed in the world; buttons for All players / Everyone / None), click the roll to cycle
through every check the game packs offer (or a plain roll), type a DC (blank: none) and choose whether the players see
it, then Request. Each creature's owner gets the same Roll / Advantage / Disadvantage prompt; a player rolls their own
character, the DM rolls for creatures. The dice show to everyone with success or failure when there is a DC. Your
choices are kept while the game is open, so asking the same table again takes one click.

## Advantage and disadvantage

Which rolls can be rolled twice, and which way the better result goes, is the game's: `"rollMode": { "die": "d20",
"better": "high" }` in the `combat` block (this is the default). A lone die of that kind rolled with advantage (the
sheet's Normal / Advantage / Disadvantage control, or an `advantage` rule of a condition) is rolled twice and the
higher is kept; disadvantage keeps the lower. `"better": "low"` turns it round for a game where low is good
(`"die": "d%"` in the percentile pack: advantage keeps the lower). `"die": ""` means the game has no such thing. The
dice window says which result was kept ("keeping the highest / lowest"). The control itself is the `rollmode` widget;
give it `"names": ["Norm", "Boon", "Bane"]` to rename its three buttons.

## The default die

A game names its own default die with `"defaultDie": "d%"` in the `combat` block (default `d20`). It is rolled when
nothing says otherwise: a requested check that has no formula (or an unknown id), and the initiative of a creature
whose `initiative` block gives no `mob` roll.

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
| `grouping` | Who takes turns together. Left out: everyone has their own place in the order. `"side"`: the players act one after another, then the enemies (or the other way round), whichever side has the best result first (Fire Emblem phases). `"players_first"` / `"enemies_first"`: the same with a fixed first side. Inside a side the order is the initiative order. Works without a `roll` too: `"initiative": { "grouping": "players_first" }` is a game with no initiative roll whose sides simply take phases. |

**No `initiative` block (or one with no `roll`) = this game has no initiative roll.** The encounter window shows no roll buttons
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

- The server needs the pack too (packs are read from `config/tacticalcombat/packs` on the server as well). Without
  it, sheets of that game show `0/0` hit points and the bars stay empty. A Dungeon Master does not have to copy
  files: when the server has characters of a pack it lacks, the DM's client sends its own copy by itself (chat says
  `[Packs] ... sent yours`), and the **Game packs** row of the DM tools tab has a *Send* button that replaces the
  server's copy of every pack (use it after editing a pack). Packs over about 29 KB compressed must be copied by hand.
  Use `/tbc reload` after editing a pack on a running server.
- Players use their **Active Actor**: the character chosen with the *Active Actor* button in the sheet's title bar
  (it has to be linked to the server). Without one, the first of their characters whose format has a
  `combat` block is used. Characters without a `combat` block, and mobs, use 8 / 6 squares with one
  action and one bonus action.
- Not here yet: initiative, attacks and damage, conditions, diagonal and difficult-terrain costs.

## Cards for new conditions

Adding a condition shows its card to everyone, as if Show was pressed: from the quick-condition buttons, from a new
entry saved in the conditions list, and when a rule applies one (the server builds a plain card from the entry's
text, number and note columns, sent under the name "Rules"). Changing an existing condition does not show it again.

## Floating numbers

Whenever a creature loses or gains something, a number floats up over it for everyone within 96 blocks who has the mod:

- **Damage** is red, minus sign. The shade follows how much of the creature's maximum health the hit was: a light
  red for a scratch, a deep dark red at half its health or more in one blow (bigger hits also print larger).
- **Healing** is green, plus sign.
- **Other bars** (mana, sanity ...) show `+3 Mana` / `-2 Sanity` in the bar's own colour.
- **Statuses** show the condition's name in gold when it is added and `-Name` in grey when it ends.

It follows the health bar of the sheet, so it also covers rule damage, downed-save healing and Actors; creatures with
plain Minecraft health get it from damage dealt through a damage prompt.
