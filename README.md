# Applied Energistics 2 for Hytale

**Ported to Hytale by Fellurion from [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2)**
(original mod by AlgorithmX2 et al.).

A port of the Minecraft mod Applied Energistics 2 to Hytale: ME networks, storage cells, terminals, machines and
autocrafting. This is an **unofficial** port and is not affiliated with the AE2 team.

One jar = Java server plugin **and** asset pack (`IncludesAssetPack: true`).

## Project layout

| Path | Contents |
|---|---|
| `src/main/java/de/fellurion/ae2/core/` | Platform-independent AE2 logic (storage, cells, channels, energy, machines, crafting) – ported 1:1 from AE2, with tests |
| `src/main/java/de/fellurion/ae2/` (rest) | Hytale integration: plugin, block components, tick systems, interactions, UI pages |
| `src/main/resources/Server/` | Item/block definitions, recipes, translations (en-US, de-DE) |
| `src/main/resources/Common/` | Models (`.blockymodel`, editable in Blockbench with the Hytale plugin), textures, icons, UI |
| `src/main/resources/ae2data/` | Machine recipes (Inscriber, Charger, …) for the Java logic |

## Building

Requirements: Java 25 and a local Hytale installation. The default is the macOS path
`~/Library/Application Support/Hytale`; if Hytale is installed elsewhere, set `hytaleHome` in
`~/.gradle/gradle.properties`.

```bash
./gradlew build
```

The jar ends up in `build/libs/`.

## Installing

Copy the jar into your Hytale mods folder (`UserData/Mods`) or into the `mods` folder of your server and restart it.

## Language

AE2 is in **English** by default. Every player can switch the language for themselves:

| Command | Language |
|---|---|
| `/ae2 language de` | German (Deutsch) |
| `/ae2 language en` | English |

The choice is saved per player and applies to everything from AE2: item and block names, terminals and other
windows, the look-at info, messages and commands. It does not change the language of Hytale itself.

Server owners can change the default for all players in `mods/de.fellurion_AppliedEnergistics2/language.json`
(created on first start): `"default": "de-DE"` or `"default": "en-US"`.

## Differences from Minecraft

- **Parts become blocks:** In AE2, buses, terminals, panels etc. attach to cable sides. In Hytale they are thin
  blocks of their own that can be placed in all 6 directions. Their back side connects to the network.
- **Cables** adapt their shape to their neighbours in code (64 shapes per cable type).
- **Own workbench:** All AE2 recipes are crafted at the *Energistics Workbench*.
- **Ingredients** from Minecraft are mapped to matching Hytale items.
- **No meteorites yet;** inscriber presses, sky stone and certus quartz have temporary recipes for now.
- No redstone in Hytale: redstone cards and emitters work with Hytale's own mechanics or have no function yet.

## Status

Working (verified on a test server):

- ME network with cables (shape adapts), channels, controller, energy (Vibration Chamber, Crystal Resonance
  Generator, energy cells, creative energy cell, crank for Inscriber/Charger)
- ME Drive / ME Chest with storage cells (contents stored in the item metadata), cells visible in the drive;
  tooltips on filled cells with bytes, types and the largest contents, like in AE2
- Terminal (search, sorting, extract, insert), Cell Workbench (partitions, upgrade cards, creative cell)
- Import/Export/Storage Bus, Inscriber, Charger, Condenser
- **Autocrafting:** Pattern Encoding Terminal (Hytale recipes → crafting/processing patterns), Pattern Provider,
  Molecular Assembler, Crafting CPU, craft requests and status
- Connected quartz glass, Mysterious Cube (drops the presses), 26 AE2 achievements (`/ae2 advancements`)
- **Look-at info panel** (like Jade/WAILA): channels, energy, cell bytes and machine progress when you look at an
  AE2 block
- **Access protection:** An ME network belongs to the player who placed its oldest block. Only that player and the
  players they add under "Access" in the terminal (search + dropdown) can use terminals, drives, machines etc.
  Admins (`ae2.admin`) can access everything. AE2 does not protect against breaking blocks – that is the job of
  area-protection plugins.

Not ported yet: P2P, Spatial IO, Quantum Bridge, meteorites, Level Emitter, IO Port, Wireless Terminal, fluid cells,
the crafting grid of the Crafting Terminal and the Pattern Access Terminal view (both open the normal terminal view
for now).

## Commands

| Command | Who | What it does |
|---|---|---|
| `/ae2 info x y z` | everyone | Network info for an AE2 block (own networks only) |
| `/ae2 advancements` | everyone | AE2 achievements (alias `/ae2 erfolge`) |
| `/ae2 language en\|de` | everyone | Switch your AE2 language (default English) |
| `/ae2 craft x y z item amount` | admin (`ae2.admin`) | Start a crafting job |
| `/ae2 cpus x y z`, `/ae2 count x y z item` | admin | CPUs / item amount in the network |
| `/ae2 insert x y z item amount` | admin | Put items into the network (creative) |
| `/ae2 setslot x y z slot item`, `/ae2 pattern x y z slot item` | admin | Build test setups from the console |
| `/ae2 keeploaded x y z` | admin | Keep a chunk loaded |
| `/ae2 perf` | admin | AE2 tick times for this world (avg/max per phase); starts a 10 s measurement per block kind |
| `/ae2 stress x y z types ops seconds` | admin | Load test: extract/insert items every tick (test servers only) |

As in Minecraft, AE2 machines only run while a player is nearby (Hytale switches chunks without players to
"not ticking"); running crafting jobs are saved and continue afterwards.

## Licenses

| Part | License | Authors |
|---|---|---|
| Code | [LGPL-3.0](https://www.gnu.org/licenses/lgpl-3.0.html) (`LICENSE`) | (c) 2013–2020 AlgorithmX2 et al.; port (c) Fellurion |
| API | [MIT](https://opensource.org/licenses/MIT) (`LICENSE-API.md`) | (c) 2013–2020 AlgorithmX2 et al. |
| Textures/models | [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/) (`LICENSE-ASSETS.md`) | (c) 2020 Ridanisaurus Rid, (c) 2013–2020 AlgorithmX2 et al. |
| Texts/translations | [CC0](https://creativecommons.org/publicdomain/zero/1.0/) | AE2 contributors |

What this means in practice (details in `NOTICE.md`):

- **Attribution:** These credits stay in the README, on the mod page and in the jar. If you reuse this port, credit
  the AE2 authors **and Fellurion** as the author of the Hytale port.
- **Source code:** If you distribute the jar, you must make the source code available under LGPL-3.0
  (e.g. a link to this repository on the mod page).
- **Textures/models are non-commercial:** Do not sell the mod or its assets, and do not put the assets behind a
  paywall (e.g. as part of a paid server rank or package).
- **ShareAlike:** Modified textures/models are also licensed under CC BY-NC-SA 3.0.
- **Changes are marked:** The assets were converted and adjusted for Hytale (see `LICENSE-ASSETS.md`).
