# Notice / Credits

**Applied Energistics 2 for Hytale** – ported by **Fellurion** from
**Applied Energistics 2**: https://github.com/AppliedEnergistics/Applied-Energistics-2

Unofficial port from Minecraft (NeoForge) to Hytale; not affiliated with the AE2 team.

## Licenses

| Part | License | Authors | File |
|---|---|---|---|
| Code | LGPL-3.0 | (c) 2013–2020 AlgorithmX2 et al.; changes/port (c) Fellurion | `LICENSE` |
| API | MIT | (c) 2013–2020 AlgorithmX2 et al. | `LICENSE-API.md` |
| Textures and models | CC BY-NC-SA 3.0 | (c) 2020 Ridanisaurus Rid, (c) 2013–2020 AlgorithmX2 et al.; Hytale adaptation by Fellurion | `LICENSE-ASSETS.md` |
| Texts and translations | CC0 1.0 (no restrictions) | AE2 contributors | – |

## What was changed

- **Code:** The game logic (ME network, cells, channels, energy, machines, crafting) was ported from the AE2 source
  code to `de.fellurion.ae2.core`; the Hytale integration (`de.fellurion.ae2.hytale`) is newly written.
  Every ported file states its AE2 origin in its header.
- **Textures/models:** Converted from the AE2 assets to Hytale's formats (`.blockymodel`, 32 px textures,
  inventory icons), upscaled 2×, colours slightly adjusted; AE2 "parts" turned into standalone block models.
- **Texts:** AE2 translations (en, de) taken over into Hytale's `.lang` format, partly extended.

## Obligations when redistributing

1. Include this file, `LICENSE`, `LICENSE-API.md` and `LICENSE-ASSETS.md` (they are also inside the jar).
2. LGPL-3.0: Make the source code (including changes) available, e.g. with a link to the repository.
3. CC BY-NC-SA 3.0: Give attribution as described here, **no commercial use** of the textures/models,
   derived assets under the same license.

## Attribution when reusing this Hytale port

If you redistribute, modify or reuse this Hytale port or any part of it, you must credit **both** the original
authors of Applied Energistics 2 (AlgorithmX2 et al.; textures/models by Ridanisaurus Rid) **and Fellurion as the
author of the Hytale port**.

- **Code:** Additional term under section 7(b) of the GNU GPL v3 (incorporated by the GNU LGPL v3) for all material
  added or modified by Fellurion in this port: you must preserve the author attributions "Portierung (c) Fellurion" /
  "port to Hytale (c) Fellurion" in the file headers, this file (`NOTICE.md`), and the attribution in the
  Appropriate Legal Notices (credits/about) of any work that contains this code.
- **Textures and models:** The Hytale versions are an adaptation by Fellurion and are themselves licensed under
  CC BY-NC-SA 3.0. If you use them, credit AE2 **and** Fellurion wherever credits are shown (e.g. the mod page).
- **Please** also link back on your mod page or README:
  "Based on the Hytale port by Fellurion: https://github.com/Fellurion/Applied-Energistics-2-Unofficial-Hytale-Port-"
