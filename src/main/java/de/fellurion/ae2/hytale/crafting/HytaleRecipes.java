/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.items.PatternEncodingTermMenu – Kodieren von Crafting-/Processing-Patterns).
 */
package de.fellurion.ae2.hytale.crafting;

import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import de.fellurion.ae2.core.crafting.CraftingPattern;
import de.fellurion.ae2.core.crafting.ProcessingPattern;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.GenericStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Hytale-Rezepte → AE2-Patterns.
 *
 * <p>AE2-Crafting-Patterns sind ein 3×3-Raster (je 1 Item). Hytale-Rezepte sind Zutatenlisten mit Mengen
 * und teils Ressourcentypen („beliebiger Stamm“). Abbildung:
 * <ul>
 *   <li>Werkbank-Rezepte (Typ Crafting/Diagram/Structural) → {@link CraftingPattern}: jede Zutat wird so oft
 *       eingetragen wie ihre Menge (der Kern fasst gleiche Zutaten wieder zusammen), Ressourcentypen werden zu
 *       Ersatz-Items (Substitutes). Ausgeführt vom Molecular Assembler.</li>
 *   <li>Verarbeitungs-Rezepte (Typ Processing, z. B. Ofen) → {@link ProcessingPattern}: Zutaten/Ausgaben mit
 *       Mengen. Ausgeführt von der Maschine vor dem Pattern Provider.</li>
 * </ul>
 */
public final class HytaleRecipes {

    private HytaleRecipes() {
    }

    /** Ein Rezept, das ein bestimmtes Item herstellt. */
    public record Option(CraftingRecipe recipe, boolean processing, String benchId) {
        public String id() {
            return recipe.getId();
        }
    }

    private static Map<String, List<String>> itemsByResourceType;

    /** Alle Items mit Ressourcentyp (für Ersatz-Zutaten), einmalig aufgebaut. */
    private static synchronized Map<String, List<String>> resourceIndex() {
        if (itemsByResourceType == null) {
            Map<String, TreeSet<String>> idx = new HashMap<>();
            for (Item item : Item.getAssetMap().getAssetMap().values()) {
                ItemResourceType[] types = item.getResourceTypes();
                if (types == null) {
                    continue;
                }
                for (ItemResourceType t : types) {
                    if (t != null && t.id != null) {
                        idx.computeIfAbsent(t.id, k -> new TreeSet<>()).add(item.getId());
                    }
                }
            }
            Map<String, List<String>> out = new HashMap<>();
            idx.forEach((k, v) -> out.put(k, List.copyOf(v)));
            itemsByResourceType = out;
        }
        return itemsByResourceType;
    }

    /** Rezepte, deren Hauptausgabe {@code itemId} ist (Werkbank zuerst, dann Verarbeitung). */
    public static List<Option> recipesFor(String itemId) {
        List<Option> out = new ArrayList<>();
        for (CraftingRecipe r : CraftingRecipe.getAssetMap().getAssetMap().values()) {
            MaterialQuantity primary = r.getPrimaryOutput();
            if (primary == null || !itemId.equals(primary.getItemId())) {
                continue;
            }
            BenchRequirement[] benches = r.getBenchRequirement();
            BenchRequirement bench = benches == null || benches.length == 0 ? null : benches[0];
            boolean processing = bench != null && bench.type == BenchType.Processing;
            out.add(new Option(r, processing, bench == null ? "" : bench.id));
        }
        out.sort((a, b) -> Boolean.compare(a.processing(), b.processing()));
        return out;
    }

    /** Kodiert ein Rezept als Pattern-Schlüssel (Item {@code AE2_Crafting_Pattern} / {@code AE2_Processing_Pattern}). */
    @Nullable
    public static AEItemKey encode(Option option) {
        CraftingRecipe r = option.recipe();
        MaterialQuantity[] inputs = r.getInput();
        MaterialQuantity primary = r.getPrimaryOutput();
        if (inputs == null || inputs.length == 0 || primary == null || primary.getItemId() == null) {
            return null;
        }
        if (option.processing()) {
            List<GenericStack> in = new ArrayList<>();
            for (MaterialQuantity mq : inputs) {
                AEItemKey k = keyFor(mq);
                if (k == null) {
                    // Zutat nicht abbildbar: kein Pattern, statt eines, dem eine Zutat fehlt (der Auftrag hinge fest)
                    return null;
                }
                in.add(new GenericStack(k, Math.max(1, mq.getQuantity())));
            }
            List<GenericStack> out = new ArrayList<>();
            MaterialQuantity[] outputs = r.getOutputs();
            if (outputs != null && outputs.length > 0) {
                for (MaterialQuantity mq : outputs) {
                    if (mq != null && mq.getItemId() != null) {
                        out.add(new GenericStack(AEItemKey.of(mq.getItemId()), Math.max(1, mq.getQuantity())));
                    }
                }
            } else {
                out.add(new GenericStack(AEItemKey.of(primary.getItemId()), Math.max(1, primary.getQuantity())));
            }
            return in.isEmpty() || out.isEmpty() ? null : ProcessingPattern.encode(in, out);
        }
        List<AEItemKey> grid = new ArrayList<>();
        List<List<AEItemKey>> substitutes = new ArrayList<>();
        boolean anySubstitute = false;
        for (MaterialQuantity mq : inputs) {
            AEItemKey k = keyFor(mq);
            if (k == null) {
                return null; // Zutat (z. B. Tag) nicht abbildbar
            }
            List<AEItemKey> alt = new ArrayList<>();
            if (mq.getItemId() == null && mq.getResourceTypeId() != null) {
                for (String id : resourceIndex().getOrDefault(mq.getResourceTypeId(), List.of())) {
                    if (!mq.isItemExcluded(id)) {
                        alt.add(AEItemKey.of(id));
                    }
                }
                anySubstitute |= alt.size() > 1;
            }
            for (int i = 0; i < Math.max(1, mq.getQuantity()); i++) {
                grid.add(k);
                substitutes.add(alt);
            }
        }
        GenericStack output = new GenericStack(AEItemKey.of(primary.getItemId()), Math.max(1, primary.getQuantity()));
        return CraftingPattern.encode(grid, output, anySubstitute, anySubstitute ? substitutes : null);
    }

    /** Item, das für eine Zutat angezeigt wird (bei Ressourcentyp das erste passende Item), oder null. */
    @Nullable
    public static String displayItem(MaterialQuantity mq) {
        AEItemKey k = keyFor(mq);
        return k == null ? null : k.itemId();
    }

    private static java.util.Set<String> outputs;

    /** Alle Item-IDs, die irgendein Rezept herstellt (für die Suche im Pattern-Terminal). */
    public static synchronized java.util.Set<String> craftableOutputs() {
        if (outputs == null) {
            java.util.Set<String> s = new java.util.TreeSet<>();
            for (CraftingRecipe r : CraftingRecipe.getAssetMap().getAssetMap().values()) {
                MaterialQuantity p = r.getPrimaryOutput();
                if (p != null && p.getItemId() != null) {
                    s.add(p.getItemId());
                }
            }
            outputs = s;
        }
        return outputs;
    }

    /**
     * Schlüssel einer Zutat: festes Item oder erstes Item des Ressourcentyps. Wie Hytale selbst
     * ({@code CraftingRecipe#applyOutputExclusions}) zählen die Ergebnisse des Rezepts nicht als Zutat – sonst
     * stellte ein Pattern ein Item aus sich selbst her.
     */
    @Nullable
    private static AEItemKey keyFor(MaterialQuantity mq) {
        if (mq == null) {
            return null;
        }
        if (mq.getItemId() != null) {
            return AEItemKey.of(mq.getItemId());
        }
        if (mq.getResourceTypeId() != null) {
            for (String id : resourceIndex().getOrDefault(mq.getResourceTypeId(), List.of())) {
                if (!mq.isItemExcluded(id)) {
                    return AEItemKey.of(id);
                }
            }
        }
        return null;
    }
}
