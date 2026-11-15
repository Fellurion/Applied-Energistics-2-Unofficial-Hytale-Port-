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
- **No crafting grid:** Hytale recipes are ingredient lists bound to a bench, so the Crafting Terminal offers a
  recipe list instead of the 3×3 grid.
- **No ores,** exactly like current AE2: certus quartz, sky stone and the inscriber presses come from
  **meteorites** (see below). Recipes for them exist as well, for worlds that were generated before this mod.
- **AE2 signal instead of redstone:** Hytale has no redstone, so AE2 brings its own signal. Level Emitters and the
  Light Detecting Fixture send it to the blocks next to them; buses and the IO Port with a Redstone Card, Toggle
  Buses, the lock modes of the Pattern Provider, Tiny TNT and the Spatial IO Port react to it, and a Redstone P2P
  Tunnel carries it somewhere else.
- **Use, sneak + use, right click:** Hytale's slot windows have no extra buttons. *Use* opens the slots or the
  terminal of a block; *sneak + use* opens its upgrade cards and settings (machines, Pattern Provider, IO Port,
  priority of ME Drive and ME Chest). Tools and handheld devices act with a *right click*, as in Minecraft.
- **Spatial IO without a second dimension:** a stored region is kept in a file per storage cell instead of a
  separate world. Players and creatures inside the region are not moved.
- **Fluids:** Hytale has one fluid container, the water bucket. Other fluids (lava, poison, slime, tar) enter and
  leave the network through planes, tanks and buses.
- **No external energy:** there is no energy system of other mods to convert, so the Energy Acceptor and the FE P2P
  Tunnel can be built but have nothing to do.

## Status

Working (verified on a test server):

- ME network with cables (shape adapts), channels, controller, energy (Vibration Chamber, Crystal Resonance
  Generator, energy cells, creative energy cell, crank for Inscriber/Charger)
- ME Drive / ME Chest with storage cells (contents stored in the item metadata), cells visible in the drive;
  tooltips on filled cells with bytes, types and the largest contents, like in AE2
- Terminal (search, sorting, extract, insert), Cell Workbench (partitions, upgrade cards, creative cell)
- **ME Crafting Terminal:** terminal view plus a crafting area – pick a recipe (search, "craftable now" filter) and
  craft it ×1/×10/stack/max straight from the network; missing ingredients come from your inventory. Offers pocket
  crafting, the basic Workbench (tier 1) and the Energistics Workbench.
- **ME Pattern Access Terminal:** all pattern providers of the network with their 9 pattern slots; take and insert
  encoded patterns remotely, search by provider, output or ingredient.
- Import/Export/Storage Bus with a configuration page (filter slots, upgrade cards, fuzzy mode; Storage Bus also
  priority and access mode), **ME Interface** (9 stock slots with amounts, 9 storage slots), Inscriber, Charger,
  Condenser; priority of ME Drive / ME Chest by sneak + use (the ME Chest's page also takes the cell out)
- **Fluix crystals** like in AE2: drop a Charged Certus Quartz Crystal together with a Red and a White Crystal Shard
  into water – after a few seconds they turn into 2 Fluix Crystals. The same way repairs budding quartz
  (quartz block + charged certus → damaged → chipped → flawed)
- **Autocrafting:** Pattern Encoding Terminal (Hytale recipes → crafting/processing patterns), Pattern Provider,
  Molecular Assembler, Crafting CPU, craft requests and status
- Connected quartz glass, Mysterious Cube (drops the presses), 26 AE2 achievements (`/ae2 advancements`)
- **Meteorites** in newly generated terrain, the **impact event**, the **Meteorite Compass** and **certus quartz
  growth** (budding quartz, Growth Accelerator) – see "Meteorites"
- **"AE2 Basics" book:** craft it at a normal Workbench (4 plant fibre, 1 light hide), right or left click to open it: the first steps from the Energistics Workbench to the first network, and every Inscriber recipe with pictures. The Guide from the Mysterious Cube opens the same book.
- **Look-at info panel** (like Jade/WAILA): channels, energy, cell bytes and machine progress when you look at an
  AE2 block
- **Access protection:** An ME network belongs to the player who placed its oldest block. Only that player and the
  players they add under "Access" in the terminal (search + dropdown) can use terminals, drives, machines etc.
  Admins (`ae2.admin`) can access everything. AE2 does not protect against breaking blocks – that is the job of
  area-protection plugins.

- **Upgrade cards and settings in machines** (sneak + use): Acceleration Cards in Inscriber, Molecular Assembler and
  Vibration Chamber (also Energy Cards), the Inscriber's automation settings, Condenser output, blocking mode,
  crafting lock and priority of the Pattern Provider. Buses at an Inscriber follow AE2's rules: ingredients in, only
  the result out.
- **P2P tunnels:** ME (up to 32 channels through one channel), item, fluid, signal and light tunnels. Link two
  tunnels with the Memory Card; the other tunnel types are made, as in AE2, by using an ME P2P Tunnel with a matching
  item in hand (chest or bus, bucket, red crystal, torch, energy cell). **Quartz Fiber** shares energy between two
  networks without joining them, **Toggle Bus** and Inverted Toggle Bus connect and separate network sections.
- **Quantum Network Bridge:** eight Quantum Rings around a Quantum Link Chamber; two bridges holding the two halves
  of a pair of Quantum Entangled Singularities become one network with 32 channels, at any distance within one world.
  Both ends must be loaded. The pair is made by blowing up a Singularity together with Ender Dust (Tiny TNT).
- **Level Emitter, Energy Level Emitter, Light Detecting Fixture** and the **Redstone Card** (see "AE2 signal" above).
- **Storage Monitor and Conversion Monitor:** show an item and its amount in the look-at panel and as a floating item
  in front of the block; the Conversion Monitor also stores and hands out its item.
- **ME IO Port:** empties storage cells into the network or fills them from it, with Acceleration and Redstone Cards.
- **Wireless:** ME Wireless Access Point with Wireless Boosters, Wireless Terminal and Wireless Crafting Terminal
  (link by right-clicking the access point, battery, range by booster count), **portable cells** that open in your
  hand. All of them are charged in the Charger; Energy Cards and other cards for handheld devices are installed at
  the Cell Workbench. The ME Chest's terminal shows the content of its own cell.
- **Annihilation Plane** (breaks the block in front and collects items lying there, only if the network can store
  everything) and **Formation Plane** (a storage that places what is stored in it as a block, or drops it as an
  item; filter, cards and priority on its settings page).
- **Fluids:** fluid storage cells and portable fluid cells, fluids in the terminals (click a fluid to fill a water
  bucket, right click a filled bucket to empty it into the network), the **Sky Stone Tank** (16 buckets), Import,
  Export and Storage Bus on tanks, fluid filters, Fluid P2P Tunnel, and planes that pick up and place fluid sources.
- **Spatial IO:** frame up to 128³ blocks with Spatial Pylons, store them in a Spatial Storage Cell at the Spatial IO
  Port (sneak + use the port, or send it a signal) and bring them back at any frame of the same size – blocks,
  rotation, fluids and the contents of containers and machines included. A **Spatial Anchor** keeps the chunks of
  its network loaded and running.
- **Tools:** the quartz **Wrenches** (right click rotates an ME device, sneak + right click dismantles it), the
  **Network Tool** (network status on a network block, a toolbox for upgrade cards otherwise), the **Memory Card**
  (sneak + right click copies a device's settings, right click pastes them) and the **Cutting Knives** (cut an
  Inscriber Name Press that renames items in the Inscriber).
- **Powered tools** (charged in the Charger): **Entropy Manipulator** (heats and cools blocks and fluids),
  **Charged Staff**, **Matter Cannon** (matter balls, metal bars or charcoal as ammunition, paint balls recolour
  cables) and **Color Applicator** (paints ME cables with paint balls, snow cleans them). **Tiny TNT** is lit by
  using it, by a signal or by fire.

Where a device changes the world on its own (planes, Spatial IO, Tiny TNT), it respects claims of ClaimPro,
OrbisGuard and CoreSys: inside a claim it only works if the device itself stands in a claim. Tools used by a player
go through Hytale's normal breaking and placing, so area-protection plugins decide as usual.

Not ported: view cells, facades, smithing and stonecutting patterns, fluids in the ME
Interface and in patterns. The Energy Acceptor and the FE P2P Tunnel exist but have nothing to convert (see
"Differences from Minecraft").

Settings of these features live next to `meteorites.json` in `mods/de.fellurion_AppliedEnergistics2/`:
`gadgets.json` (block damage of Matter Cannon and Tiny TNT), `planes.json` (blocks and items planes must not
touch), `fluids.json` (which fluids can be stored), `spatial/config.json` (blocks Spatial IO leaves in place) and
`monitors.json` (floating item display of monitors, switch with `/ae2 monitordisplay on|off`).

## Meteorites

This AE2 version has no ores. Like in Minecraft, everything starts with a meteorite: a body of **sky stone** in a
crater, with a small chamber in the middle that holds the **Mysterious Cube** (drops all four inscriber presses) on a
floor of certus quartz and **budding certus quartz**.

- **World generation (on by default):** newly generated terrain gets meteorites with AE2's rules – one candidate per
  512×512 blocks, half of them become a meteorite, radius 2–8, crater type by climate, debris around it, buried in
  the sea floor under oceans. Where they are depends only on the world seed. Terrain that already existed before the
  mod was installed is not changed.
- **Impact event (off by default):** when switched on for a world, a meteorite falls from the sky near a player now
  and then – warning, fire trail, impact, fresh crater. Switch it on with `/ae2 meteorites impacts on`.
- **Build protection:** an impact is only allowed in untouched nature. It never happens
  - where a player has ever placed or mined a block (tracked per chunk since the mod is installed, plus one chunk
    margin),
  - where anything built stands: bricks, planks, stairs, roofs, paths, tilled fields, furniture, chests, benches,
    lamps, AE2 blocks – including the blocks of building mods,
  - inside claims (ClaimPro, OrbisGuard, CoreSys), near beds, near the world spawn, near players, in water or on top
    of another meteorite.

  Blocks of other mods count as terrain when they are tagged as rock, soil, ore or plant, or when they sit fully
  enclosed in solid ground like an ore. If a world-generation mod uses other blocks on the surface, the `spawn`
  command names the block that stopped the impact; allow it with `/ae2 meteorites allow <block>` (end with `*` for a
  whole group, e.g. `Arkanum_*`). The place is checked again right before the impact; if it is no longer free, the
  meteorite burns up in the air. `protection strict` additionally limits impacts to terrain that was generated while
  this mod was installed. Other plugins can veto impacts with `BuildProtection.addVeto`.
- **Budding certus quartz** grows buds on its sides (small → medium → large → cluster) and wears out while doing so
  (flawed → chipped → damaged → plain quartz block); flawless budding quartz never wears out and only comes from
  meteorites. Buds drop certus dust, clusters drop 4 crystals. A powered **Growth Accelerator** next to it speeds
  growth up massively.
- **Creative mode:** Hytale drops nothing when you break a block in creative mode. The Mysterious Cube is the
  exception – AE2 lets it drop its presses in every game mode. For all other blocks `/ae2 drops x y z` shows what
  they would drop in adventure mode.
- **Meteorite Compass:** hold it to see the nearest meteorite that still contains its Mysterious Cube as a marker in
  the compass bar and on the map (range about 2,768 blocks).

Settings are stored in `mods/de.fellurion_AppliedEnergistics2/meteorites.json` (global defaults and per world).

| Command (admin) | What it does |
|---|---|
| `/ae2 meteorites status` | Settings and known meteorites of the world |
| `/ae2 meteorites worldgen on\|off` | Meteorites during world generation |
| `/ae2 meteorites impacts on\|off` | Impact event |
| `/ae2 meteorites interval <minutes>` | Average time between two impacts (default 60) |
| `/ae2 meteorites protection normal\|strict` | Build protection level |
| `/ae2 meteorites spawn` | Trigger an impact near you now (build protection still applies); tells you where it lands or what stopped it |
| `/ae2 meteorites allow <block>` | Treat a block of another mod as natural terrain |
| `/ae2 meteorites nearest` | Coordinates of the nearest meteorite |

All of them act on your current world; add `--world <name>` for another one.

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
| `/ae2 grow x y z ticks` | admin | Make budding quartz grow now (random ticks) |
| `/ae2 drops x y z` | admin | Show what a block drops when mined (Hytale drops nothing in creative mode) |
| `/ae2 perf` | admin | AE2 tick times for this world (avg/max per phase); starts a 10 s measurement per block kind |
| `/ae2 stress x y z types ops seconds` | admin | Load test: extract/insert items every tick (test servers only) |
| `/ae2 monitordisplay on\|off` | admin | Floating item display of Storage and Conversion Monitors |
| `/ae2 config`, `/ae2 tool`, `/ae2 p2p`, `/ae2 quantum`, `/ae2 plane`, `/ae2 fluid`, `/ae2 spatial`, `/ae2 ioport`, `/ae2 gadget` | admin | Inspect and operate the individual devices from the console (for test setups) |

As in Minecraft, AE2 machines only run while a player is nearby (Hytale switches chunks without players to
"not ticking"); running crafting jobs are saved and continue afterwards. A powered Spatial Anchor keeps the chunks
of its network running without players.

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
