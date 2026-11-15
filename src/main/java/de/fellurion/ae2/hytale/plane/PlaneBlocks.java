/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.automation.ItemPickupStrategy#canHandleBlock / #obtainBlockDrops).
 */
package de.fellurion.ae2.hytale.plane;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.HarvestingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.SoftBlockDropType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTool;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemToolSpec;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * Was eine Annihilation Plane über einen Blocktyp wissen muss: ob er sich abbauen lässt, wie „hart“ er ist und
 * welche Drop-Regel gilt.
 *
 * <p>Hytale kennt keine Härte je Block. Abbaubar ist ein Block, wenn er eine Sammelregel hat ({@code Gathering}):
 * <ul>
 *   <li>{@code Breaking} mit einer Werkzeugart, für die es überhaupt ein Werkzeug (oder die bloße Hand) gibt – die
 *   Art {@code Unbreakable} hat keines; die Güte des Werkzeugs spielt keine Rolle (AE2 nimmt ein
 *   Diamantwerkzeug),</li>
 *   <li>sonst {@code Soft} (mit der Hand zerschlagbar) oder {@code Harvest} (aufsammelbar).</li>
 * </ul>
 * Blöcke ganz ohne Sammelregel (Bedrock) sind unzerstörbar – genau so entscheidet Hytale beim Abbauen durch
 * Spieler.
 */
final class PlaneBlocks {

    /** Werkzeugarten, die irgendein Werkzeug oder die Hand abbauen kann (einmal aus den Assets gelesen). */
    @Nullable
    private static volatile Set<String> gatherTypes;
    /** Sperrliste je Block-Id: 0 = unbekannt, 1 = frei, 2 = gesperrt. Wird ersetzt, nie verändert. */
    private static volatile Int2ByteOpenHashMap blocked = new Int2ByteOpenHashMap();

    private PlaneBlocks() {
    }

    static void forgetCaches() {
        gatherTypes = null;
        blocked = new Int2ByteOpenHashMap();
    }

    /** Die Drop-Regel, nach der ein Block abgebaut wird. */
    record DropRule(int quantity, @Nullable String itemId, @Nullable String dropListId) {
    }

    /** Lässt sich der Block mit einem normalen Werkzeug abbauen? */
    static boolean breakable(BlockType type) {
        return dropRule(type) != null;
    }

    /** Drop-Regel wie beim Abbauen mit einem passenden Werkzeug; null = unzerstörbar. */
    @Nullable
    static DropRule dropRule(BlockType type) {
        BlockGathering gathering = type.getGathering();
        if (gathering == null) {
            return null;
        }
        BlockBreakingDropType breaking = gathering.getBreaking();
        if (breaking != null && breaking.getGatherType() != null && gatherTypes().contains(breaking.getGatherType())) {
            return new DropRule(Math.max(1, breaking.getQuantity()), breaking.getItemId(), breaking.getDropListId());
        }
        SoftBlockDropType soft = gathering.getSoft();
        if (soft != null) {
            return new DropRule(1, soft.getItemId(), soft.getDropListId());
        }
        HarvestingDropType harvest = gathering.getHarvest();
        if (harvest != null) {
            return new DropRule(1, harvest.getItemId(), harvest.getDropListId());
        }
        return null;
    }

    /**
     * Ersatz für Minecrafts Härte (Teil der Energiekosten): Schläge mit der bloßen Hand geteilt durch 20, höchstens
     * 50. Das trifft Minecrafts Werte recht genau – Stein 1,43 (Minecraft 1,5), Erde 0,5 (0,5), Holz 1,67 (2,0) –,
     * Erze und Metalle liegen mit 50 bei Obsidian. Weiche Blöcke (Pflanzen) haben 0, Arten ohne Handwert 3.
     */
    static float hardness(BlockType type) {
        BlockGathering gathering = type.getGathering();
        BlockBreakingDropType breaking = gathering == null ? null : gathering.getBreaking();
        String gatherType = breaking == null ? null : breaking.getGatherType();
        if (gatherType == null || !gatherTypes().contains(gatherType)) {
            return 0f;
        }
        ItemToolSpec unarmed = ItemToolSpec.getAssetMap().getAsset(gatherType);
        if (unarmed == null || unarmed.getPower() <= 0) {
            return 3f;
        }
        return Math.min(50f, 1f / (20f * unarmed.getPower()));
    }

    /** Steht der Blocktyp auf der Sperrliste aus {@code planes.json}? */
    static boolean blacklisted(int blockId, BlockType type) {
        Int2ByteOpenHashMap cache = blocked;
        byte known = cache.get(blockId);
        if (known == 0) {
            Item item = type.getItem();
            known = PlaneConfig.blockBlocked(type.getId(), item == null ? null : item.getId()) ? (byte) 2 : (byte) 1;
            synchronized (PlaneBlocks.class) {
                // mehrere Welten: jede auf ihrem Thread – die Karte wird kopiert statt geteilt beschrieben
                Int2ByteOpenHashMap copy = new Int2ByteOpenHashMap(blocked);
                copy.put(blockId, known);
                blocked = copy;
            }
        }
        return known == 2;
    }

    private static Set<String> gatherTypes() {
        Set<String> known = gatherTypes;
        if (known != null) {
            return known;
        }
        Set<String> found = new HashSet<>();
        // bloße Hand: Item/Unarmed/Gathering/<Art>.json
        found.addAll(ItemToolSpec.getAssetMap().getAssetMap().keySet());
        for (Item item : Item.getAssetMap().getAssetMap().values()) {
            ItemTool tool = item.getTool();
            ItemToolSpec[] specs = tool == null ? null : tool.getSpecs();
            if (specs == null) {
                continue;
            }
            for (ItemToolSpec spec : specs) {
                if (spec != null && spec.getGatherType() != null && spec.getPower() > 0) {
                    found.add(spec.getGatherType());
                }
            }
        }
        found.remove("Unbreakable");
        gatherTypes = Set.copyOf(found);
        return gatherTypes;
    }
}
