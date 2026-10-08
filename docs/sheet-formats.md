# Sheet formats

A sheet format is a `.json` file that describes one game's character sheet. The in-game window (`K`, or `/sheet`) knows nothing about any particular game; it draws whichever format the selected character uses.

Folders (created on first run, under the game's `config/` folder):

- `config/tacticalcombat/packs/` - **game packs**, one folder per game (see below). A pack whose format has the same `id` as an earlier one replaces it.
- `config/tacticalcombat/sheets/` and `themes/` - loose format and theme files (the older layout; still read, after the packs).
- `config/tacticalcombat/characters/` – one `.json` per character. Press **Reload** in the window after adding files. **New** in the window's top bar asks for a format and fills in a character from the format's `fields`; **Edit** changes the open one. Both save to the characters folder.

## Game packs

A game gets its own folder, so adding a game never touches code or anyone else's files:

```
config/tacticalcombat/packs/my_game/
  format.json          the sheet format (this document)
  themes/ember.json    optional colour themes for it
```

Press **Reload** in the Formats view (the **Open packs folder** button opens the folder). The mod itself ships no game, only the colour themes (`core`). The games that come with the project live in the repository's top-level `packs/` folder (`packs/generic_d20`, `packs/percentile`): copy the folders you want into `config/tacticalcombat/packs/` and press Reload. A pack may also contain `characters/*.json` examples; they are copied once into the characters folder when it is empty. Copying `generic_d20/` and editing it is the quickest way to start a new game. (For development, `./gradlew installPacks` copies the repository's `packs/` into `run/config/tacticalcombat/packs/`, and `runClient` does it automatically.)

## Character file

```json
{
  "format": "generic_d20",
  "text":   { "name": "Brannoc Veyle", "subtitle": "Human Fighter 3" },
  "values": { "str": 16, "dex": 14, "prof": 2, "hp": 25, "hp_max": 32, "sp_str": 1 }
}
```

`values` are the raw numbers; the format does the maths. Names are lower case.

## Format file

| key | meaning |
| --- | --- |
| `id`, `name`, `description` | identity (`id` is what characters refer to) |
| `fields` | the inputs of the New / Edit form: `id`, `label`, `type` (`number`, or `text` for name-like values), `group` (heading) and `default`. If left out, the form is built from the names your formulas use |
| `defaults` | values a character may leave out, e.g. `"sp_dex": 0` for "not proficient" |
| `derived` | calculated values, `name: formula`, in any order; they may use each other |
| `theme` | id of the colour theme this format prefers (see Themes) |
| `sheets` | the kinds of sheet this game has, e.g. `character` and `npc`. Each has `kind`, `name`, a `header` and `pages`. (A format with just a top-level `header` and `pages` is one `character` sheet.) |
| `header` | `title` / `subtitle` templates (`$name`, `$subtitle` come from the character's `text`), `bars` (`label`, `value`, `max`, `temp`, `color`) and `badges` (`label`, `value`, `signed`). A bar whose `value` is a stored number gets -/+ buttons; negative changes are soaked up by the `temp` value first |
| `pages` | tabs: `id`, `title`, optional `enabled`; each has `columns` (with a `weight`), each with `sections` (`id`, `title`, optional `enabled`, `items`) |

An **item** is one row: `label`, `sub` (small text), `text` (a wrapped paragraph), `value` (formula shown on the right, `signed` adds +/-), `mark` (formula; a gold square when above 0), `roll` (formula; clicking the row rolls it), `rollLabel` (name shown in chat), and `buttons`.

**Widgets** (`widget` on an item):

| widget | what it does | keys |
| --- | --- | --- |
| `pips` | a row of squares for a stored number (spell slots, inspiration, death saves...); click a pip to set it | `store`, `max`, `color` |
| `counter` | a stored number with -/+ (exhaustion, hit dice...); Shift = 5, Ctrl = 10 | `store`, `max` |
| `rollmode` | a Normal / Advantage / Disadvantage row for single d20 rolls. Only formats that place it get advantage; others always roll normally | none |
| `cycle` | a normal row with a clickable proficiency marker that steps through values | `store`, `cycle` (e.g. `[0, 0.5, 1, 2]`) |
| `button` | a button that sets stored values from formulas when pressed: a rest, a reset. `label`, optional `sub` (tooltip), `set`: `{ "slots_1": "slots_1_max", "hp": "hp_max" }` | `set` |
| `table` | a table of rows the character owns (weapons, gear, skills...), see Collections | `collection` (id) |

Every stored name a widget uses should have a `defaults` entry (or a character value).

A **button** has `before` (text before the number), `value` (+ `signed`), `label` (text after), `roll`, `rollLabel`.

## Collections (tables)

A collection is a list of rows the character owns: gear, weapons, spells, skills. The format declares it once; every character stores its own rows. Declare collections at the top level of the format and show one with a `table` widget:

```json
"collections": [
  { "id": "gear", "label": "Gear", "addLabel": "Item",
    "columns": [
      { "id": "name",   "label": "Item", "type": "text", "width": 2.6 },
      { "id": "qty",    "label": "Qty",  "type": "number", "default": 1 },
      { "id": "weight", "label": "Wt",   "type": "number" },
      { "id": "total",  "label": "Total","type": "computed", "value": "row.qty * row.weight" },
      { "id": "notes",  "label": "Notes","type": "note" }
    ],
    "footer": [ { "label": "Carried", "value": "gear.sum.total", "max": "carry_cap" } ] }
],
... { "widget": "table", "collection": "gear" }
```

and in the character file:

```json
"collections": { "gear": [ { "name": "Rope", "qty": 2, "weight": 5 } ] }
```

Column types:

| type | shows | stored |
| --- | --- | --- |
| `text` | a short text (the first one is the row's name, or set `nameColumn`) | text |
| `note` | long text on a second line under the row | text |
| `number` | a number; click = +1, right-click = -1 (Shift 5, Ctrl 10); `default`, `signed` | number |
| `toggle` | a checkbox (equipped, proficient...); `default` | 0 / 1 |
| `choice` | one of `options`, click cycles; stores the option's index | number |
| `computed` | the result of `value`, a formula | nothing |
| `roll` | a button showing `value`; clicking rolls `roll`. `rollLabel` may contain `{name}` | nothing |
| `cast` | a button that spends a resource, then rolls: `spend` is the stored-value prefix (`"slots_"`), `level` the column holding the row's level (0 = free, a cantrip), optional `dice` the dice column to roll. Left-click spends a slot of that level; right-click the lowest higher slot that is left. With no dice it prints a "casts" line in chat | nothing |
| `dice` | the row's own dice text, e.g. `1d8`, as a button. `modifier` is a formula added when rolled | text |

A collection can set `groupBy` to a column (usually a `choice`): the table gets a heading per value, in order. On a choice column, `groupLabels` gives the heading for each option ("Cantrips", "1st level", ...).

`enabled` on a column is a row formula: the cell only shows when it is above 0, e.g. `"enabled": "row.attack"` on a roll button, with a hidden `attack` toggle column that is switched in the entry editor.

`hidden: true` on a column removes it from the sheet entirely (it is only in the entry editor), e.g. a spell's level when the table is already grouped by level.

`detail: true` on a column keeps it out of the table: its value is summarised in small text under the row (toggles show their label when on, numbers when not 0) and is edited in the row editor. Use it for setup columns so the main row stays readable, e.g. a weapon row shows name, hit and damage, with `STR - Prof` underneath.

Tables are also tools for the player (nothing to declare in the format, none of it is saved):

- **Filter** - a table with 5 or more entries gets a Filter box; click it and type to show only entries whose text (or choice) matches. Enter / Esc / clicking elsewhere leaves the box, `x` clears it.
- **Sort** - click a column header to sort by it (ascending, then descending, then back to the stored order). Sorting only changes what is shown.
- **Fold** - when an entry's second line (details and notes) is longer than one line, it starts folded to one line; click the line or the entry's name to read all of it (`+` / `-` at the end of the line).
- **Order** - the `^` / `v` buttons in the entry editor change the stored order.

Inside a column formula, `row.<column>` is that row's value: `d20 + pick(row.ability, str_mod, dex_mod) + prof * row.prof`. Everything else (derived values, stored values) works as usual.

Totals over a collection can be used in any formula: `<id>.count`, `<id>.count.<col>` (rows where the column is above 0), `<id>.sum.<col>`, `<id>.max.<col>`, `<id>.sumif.<flagcol>.<col>`, e.g. `gear.sumif.equipped.total`. Rows are added and edited with the **+** button and the **...** button on each row (which also deletes).

## Formulas

Numbers, names, `+ - * / ( )`, and `floor() ceil() round() abs() min() max()` (min / max take any number of arguments), and `pick(index, a, b, c...)`, which returns the option at that index (used with `choice` columns).

Roll formulas additionally contain exactly one dice term, which may only be added: `d20 + str_mod + prof`, `2d6 + 3`, `d%`. Dice are d4, d6, d8, d10, d% (d100), d12 and d20. Single d20 rolls obey the window's Normal / Advantage / Disadvantage setting.

See `packs/generic_d20/format.json` and `packs/percentile/format.json` `packs/` in the repository for two complete examples.

## `enabled`

Pages, sections and items may carry an `enabled` formula; they are shown when it is above 0. Use it to show a spell page only for casters: `"enabled": "spell_slots_total"`.

## Themes

A theme is a `.json` of colour overrides (`bg`, `panel`, `panel_hover`, `edge`, `accent`, `muted`, `dim`, `banner_a`, `banner_b`, `roll_bg`, `roll_edge`; hex colours):

```json
{ "id": "ember", "name": "Ember", "colors": { "banner_a": "#4a1a05", "banner_b": "#b5470f", "accent": "#ffb347" } }
```

Put it in a pack's `themes/` folder (or the loose `config/tacticalcombat/themes/`). A character uses the theme chosen with **Layout** > **Theme** on the sheet, else its format's `theme`, else crimson. Built in: crimson, forest, azure, violet.

## Layout mode

**Layout** in the tab bar lets each character hide tabs and sections (the eye icons), move sections up and down, and change theme. These choices are saved in the character's file under `ui`.

## Translation keys

Any text in a format that looks like a translation key (`mygame.skill.stealth`: no spaces, contains a dot) is looked up in the game's language files and shown translated; otherwise it is shown as written.

## Sheet kinds

When a format has more than one sheet kind, **New** lists each (e.g. "Generic d20 - NPC / monster"). A character remembers its kind in `"kind"`.
