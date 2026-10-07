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

Press **Reload** in the Formats view (the **Open packs folder** button opens the folder). The mod ships its own packs the same way: `core` (themes), `generic_d20` and `percentile`, under `src/main/resources/assets/tacticalcombat/sheets/`. A built-in pack also has a `pack.json` (`name`, `format`, `themes`, `characters` - paths inside the pack) and is listed in `packs.json`; example characters are copied to the characters folder on first run. Copying `generic_d20/` into your packs folder and editing it is the quickest way to start a new game.

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

Every stored name a widget uses should have a `defaults` entry (or a character value).

A **button** has `before` (text before the number), `value` (+ `signed`), `label` (text after), `roll`, `rollLabel`.

## Formulas

Numbers, names, `+ - * / ( )`, and `floor() ceil() round() abs() min() max()`.

Roll formulas additionally contain exactly one dice term, which may only be added: `d20 + str_mod + prof`, `2d6 + 3`, `d%`. Dice are d4, d6, d8, d10, d% (d100), d12 and d20. Single d20 rolls obey the window's Normal / Advantage / Disadvantage setting.

See `generic_d20/format.json` and `percentile/format.json` under `src/main/resources/assets/tacticalcombat/sheets/` for two complete examples.

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
