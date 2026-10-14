# The Codex

The codex is a library of ready-made entries (today: **monsters**) that a game pack carries. Think of Foundry's compendium: the entries sit in files, out of everyone's way, until the Dungeon Master decides to bring one into the game.

- Entries live **inside the game's pack**, in `config/tacticalcombat/packs/<game>/codex/monsters/`. They never appear among your own characters or in anyone's sheet list.
- Open the **Codex** tab of the DM window (`J`, then the Codex tab, or `/codex`).
- **Add as Actor** puts the entry into the game and makes an Actor from it in one click. **Add to game** only sends it to the server, where the Actors tab can then pick it as a sheet.
- What a game calls its entries, and which numbers the list shows beside each one, comes from the game's pack, not from the mod.

## Using it

| You want to | Do this |
| --- | --- |
| Find a monster | Type in the search box: a name, a folder, or `#tag`. Click a tag chip to find everything with it. |
| Read it | Click it. The right side shows its figures (chosen by the pack), its tags and the names of its weapons, features and so on. |
| Make Actors | Set how many (1-20) and the side, then **Add as Actor**. Each Actor gets its own copy of the sheet, with its own hit points, named *Wolf*, *Wolf 2*, *Wolf 3* ... |
| Add several kinds at once | Tick the boxes in the list (**All shown** / **None** help), then **Add N as Actors**. |
| Put it in the game without an Actor | **Add to game**. Its sheet is held by the server and only Dungeon Masters can see it. The Actors tab lists it among the sheets. |
| Take it out again | **Remove from game** (click twice). Actors already made from it keep their own copy. An Actor that *shares* the sheet stops the removal until it is given another sheet. |
| Add your own monsters | Drop `.json` files (or a folder of them) onto the Codex tab, or press **Open folder** and copy them in, then **Rescan**. |

A small mark beside an entry tells you where it stands: **in game** (the server has it) or **update** (you changed the file since it was added; **Update in game** sends the new version. Actors already made keep the copy they have).

Everything is sent to the server only when you press one of the add buttons, so a pack's codex can be as large as you like without costing anything. An entry is limited to about 29 KB compressed.

## Where the files go

```
config/tacticalcombat/packs/my_game/
  format.json
  codex/
    monsters/
      wolf.json                one entry (an ordinary character file)
      undead/                  a sub-folder becomes a folder in the list
        skeleton.json
        zombie.json
      bandits.json             several entries in one file (see below)
```

Add the folder to a copy of the repository's `packs/` (`generic_d20` and `percentile` ship a few examples) and press **Rescan**.

## An entry

An entry is a normal character file (see [sheet-formats.md](sheet-formats.md#character-file)) with one optional extra block, `codex`, that the codex reads and then throws away:

```json
{
  "format": "generic_d20",
  "kind": "npc",
  "codex": {
    "model": "mob:minecraft:wolf",
    "side": "hostile",
    "tags": ["beast", "pack"],
    "folder": "beasts"
  },
  "text": { "name": "Wolf", "subtitle": "Medium beast, unaligned (CR 1/4)" },
  "values": { "str": 12, "dex": 15, "hp": 11, "hp_max": 11, "ac": 13 },
  "collections": { "weapons": [ { "name": "Bite", "dmg": "2d4" } ] }
}
```

| key | meaning |
| --- | --- |
| `format` | the game. May be left out when the file is inside that game's pack. |
| `kind` | the sheet layout. May be left out: the pack's `codex.monsters.kind`, else the format's `npc` layout, else its first. |
| `text.name` | the entry's name. A one-entry file without one is named after its file (`giant_rat.json` becomes *Giant Rat*). Names must be unique within a game. |
| `codex.model` | what its Actors look like: `mob:minecraft:wolf`, `skin:<player>`, `item:...` or `block:...`. Left out, the Actors tab's guess is used (a creature with the entry's name when Minecraft has one, else a villager). |
| `codex.side` | `hostile` (default), `neutral` or `friendly`. |
| `codex.tags` | a list, or one comma-separated string. Up to 8, 20 characters each. They become the Actors' tags. |
| `codex.folder` | overrides the folder the file sits in (`beasts`, `undead/lesser`). |

### Several entries in one file

A file may hold a list of entries, or a bundle that gives the shared parts once:

```json
{
  "format": "generic_d20",
  "folder": "bandits",
  "entries": [
    { "text": { "name": "Bandit" }, "values": { "hp_max": 11, "ac": 12 } },
    { "text": { "name": "Bandit Captain" }, "values": { "hp_max": 65, "ac": 15 }, "codex": { "side": "neutral" } }
  ]
}
```

## What a game's pack decides

The `codex` block of the game's `format.json` (all optional):

```json
"codex": {
  "monsters": {
    "label": "Bestiary",
    "kind": "npc",
    "columns": [
      { "label": "HP", "value": "hp_max" },
      { "label": "AC", "value": "ac" },
      { "label": "CR", "text": "cr" }
    ]
  }
}
```

| key | meaning |
| --- | --- |
| `label` | what the game calls the entries ("Bestiary", "Creatures", "Adversaries"). Shown beside the game's name. |
| `kind` | the sheet layout its entries use when they do not say (see above). |
| `columns` | the figures shown under every name in the list and as chips in the detail view. `value` is a formula over the sheet, worked out with the entry's own numbers exactly like any sheet formula (`hp_max`, `floor((dex - 10) / 2)`); `text` shows one of the entry's texts (`"text": "cr"` reads `text.cr`). `signed` adds a `+` to positive numbers. |

A game that says nothing gets the label "Monsters" and no figures. A formula that does not work shows `?` and is reported once in the tab, not once per entry.

## Problems

Files that cannot be used are listed at the bottom of the tab with the reason, so a typo is easy to find: broken JSON, an entry with no name, a sheet kind the game does not have, a game whose pack is not installed, a second entry with the same name. The other entries still load.

## Online play

The codex is read from the **Dungeon Master's own computer**, so a pack's entries only need to be on the DM's side; the server never has to carry the files. An entry reaches the server only when added, as a sheet only Dungeon Masters can see (players are sent nothing, unless the DM shares that sheet with them like any other). Because the server reads hit points and combat rules from the game's pack, the usual "the server lacks this game pack" prompt applies: the DM's client sends the pack by itself.

## Coming later

The same structure will hold **weapons**, **features** and **spells**: more folders next to `monsters/`, named by the pack, added to a character's table from the sheet instead of becoming Actors.
