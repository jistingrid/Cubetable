# Sheet formats

A sheet format is a `.json` file that describes one game's character sheet. The in-game window (`K`, or `/sheet`) knows nothing about any particular game; it draws whichever format the selected character uses.

Folders (created on first run, under the game's `config/` folder):

- `config/tacticalcombat/sheets/` – your format files. A file with the same `id` as a built-in format replaces it.
- `config/tacticalcombat/characters/` – one `.json` per character. Press **Reload** in the window after adding files.

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
| `defaults` | values a character may leave out, e.g. `"sp_dex": 0` for "not proficient" |
| `derived` | calculated values, `name: formula`, in any order; they may use each other |
| `header` | `title` / `subtitle` templates (`$name`, `$subtitle` come from the character's `text`), `bars` (`label`, `value`, `max`, `color`) and `badges` (`label`, `value`, `signed`) |
| `pages` | tabs; each has `columns` (with a `weight`), each with `sections` (`title`, `items`) |

An **item** is one row: `label`, `sub` (small text), `text` (a wrapped paragraph), `value` (formula shown on the right, `signed` adds +/-), `mark` (formula; a gold square when above 0), `roll` (formula; clicking the row rolls it), `rollLabel` (name shown in chat), and `buttons`.

A **button** has `before` (text before the number), `value` (+ `signed`), `label` (text after), `roll`, `rollLabel`.

## Formulas

Numbers, names, `+ - * / ( )`, and `floor() ceil() round() abs() min() max()`.

Roll formulas additionally contain exactly one dice term, which may only be added: `d20 + str_mod + prof`, `2d6 + 3`, `d%`. Dice are d4, d6, d8, d10, d% (d100), d12 and d20. Single d20 rolls obey the window's Normal / Advantage / Disadvantage setting.

See `src/main/resources/assets/tacticalcombat/sheets/` for two complete examples.
