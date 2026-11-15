/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.items.CraftingTermMenu – das Crafting-Terminal ist AE2s Werkbank am ME-Netz).
 */
package de.fellurion.ae2.hytale.crafting;

import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.universe.world.World;
import org.bson.BsonDocument;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Welche Hytale-Rezepte das Crafting-Terminal anbietet.
 *
 * <p>AE2s Crafting-Terminal ist die Minecraft-Werkbank mit Zugriff auf das ME-Netz. Hytale kennt kein
 * Rasterhandwerk: Rezepte sind Zutatenlisten, die an eine Werkbank gebunden sind. Das Terminal bietet deshalb an,
 * was der Werkbank von Minecraft entspricht:
 * <ul>
 *   <li>die Taschenwerkbank ({@code Fieldcraft}, überall verfügbar),</li>
 *   <li>die einfache Werkbank ({@code Workbench}),</li>
 *   <li>die Energistics-Werkbank ({@code AE2_Workbench}) mit allen AE2-Rezepten.</li>
 * </ul>
 * Spezialwerkbänke (Rüstung, Waffen, Alchemie, Kochen, Möbel, Farm …) und Verarbeitung (Ofen, Gerberei …) bleiben
 * ihren Blöcken vorbehalten.
 *
 * <p>Hytales Einschränkungen gelten wie an der Werkbank ({@code CraftingManager#isValidBenchForRecipe}):
 * <ul>
 *   <li><b>Stufe:</b> Das Terminal hat keine Werkbankstufe und zählt wie eine frisch gebaute Werkbank
 *       (Stufe {@value #BENCH_TIER}); Rezepte für ausgebaute Werkbänke bietet es nicht an.</li>
 *   <li><b>Wissen:</b> Rezepte mit {@code KnowledgeRequired} nur, wenn der Spieler sie kennt.</li>
 *   <li><b>Erinnerungen:</b> Rezepte mit {@code RequiredMemoriesLevel} erst ab dieser Stufe der Welt.</li>
 * </ul>
 * Anders als an der Werkbank dauert das Herstellen keine Zeit (wie in AE2), und Zutaten werden auch im Baumodus
 * verbraucht.
 */
public final class TerminalRecipes {

    /** Werkbänke, deren Rezepte das Terminal anbietet, in Anzeigereihenfolge. */
    public static final List<String> BENCHES = List.of(CraftingRecipe.FIELDCRAFT_REQUIREMENT, "Workbench",
            "AE2_Workbench");

    /** Werkbankstufe, als die das Terminal zählt (frisch gebaute Werkbank). */
    public static final int BENCH_TIER = 1;

    private TerminalRecipes() {
    }

    /**
     * Eine Zutat.
     *
     * @param displayItem    Item, das stellvertretend angezeigt wird (bei Ressourcentyp das erste passende)
     * @param resourceTypeId Ressourcentyp oder null bei einem festen Item
     * @param quantity       Menge je Herstellung
     * @param ingredient     Bedarf für {@link CraftPlanner}
     */
    public record Input(String displayItem, @Nullable String resourceTypeId, int quantity,
                        CraftPlanner.Ingredient ingredient) {
    }

    /** Ein Ergebnis je Herstellung. */
    public record Output(String itemId, int quantity, @Nullable BsonDocument metadata) {
    }

    /**
     * Ein Rezept, das das Terminal herstellen kann.
     *
     * @param benchId        Werkbank, zu der das Rezept gehört (aus {@link #BENCHES})
     * @param outputItem     Hauptergebnis
     * @param outputQuantity Stückzahl des Hauptergebnisses je Herstellung
     */
    public record Entry(CraftingRecipe recipe, String benchId, String outputItem, int outputQuantity,
                        List<Input> inputs, List<Output> outputs) {

        public String id() {
            return recipe.getId();
        }

        public List<CraftPlanner.Ingredient> ingredients() {
            List<CraftPlanner.Ingredient> out = new ArrayList<>(inputs.size());
            for (Input in : inputs) {
                out.add(in.ingredient());
            }
            return out;
        }
    }

    /**
     * Was der Spieler freigeschaltet hat.
     *
     * @param knownItems    Items, deren Rezept der Spieler kennt
     * @param memoriesLevel Erinnerungsstufe der Welt
     */
    public record Unlocks(Set<String> knownItems, int memoriesLevel) {
    }

    private static Map<String, Entry> catalog;
    private static int catalogSize = -1;
    private static Map<String, Map<String, Integer>> resources;
    private static final Map<Integer, Map<String, Integer>> TAGS = new HashMap<>();

    // ------------------------------------------------------------------ Katalog

    /** Alle Rezepte des Terminals (ohne Rücksicht auf den Spieler), nach Rezept-Id. */
    public static synchronized Map<String, Entry> catalog() {
        Map<String, CraftingRecipe> assets = CraftingRecipe.getAssetMap().getAssetMap();
        if (catalog != null && catalogSize == assets.size()) {
            return catalog;
        }
        // Assets wurden (neu) geladen: Indexe neu aufbauen
        resources = null;
        TAGS.clear();
        Map<String, Entry> out = new LinkedHashMap<>();
        for (CraftingRecipe recipe : new TreeMap<>(assets).values()) {
            Entry e;
            try {
                e = entry(recipe);
            } catch (RuntimeException ex) {
                e = null; // unvollständiges Rezept eines anderen Packs
            }
            if (e != null) {
                out.put(e.id(), e);
            }
        }
        catalog = out;
        catalogSize = assets.size();
        return out;
    }

    /** Rezept {@code id}, wenn das Terminal es anbietet und {@code unlocks} es erlaubt; sonst null. */
    @Nullable
    public static Entry resolve(@Nullable String id, Unlocks unlocks) {
        Entry e = id == null ? null : catalog().get(id);
        return e != null && unlocked(e.recipe(), unlocks) ? e : null;
    }

    /** Alle Rezepte, die der Spieler am Terminal sieht. */
    public static List<Entry> visible(Unlocks unlocks) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : catalog().values()) {
            if (unlocked(e.recipe(), unlocks)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Freischaltungen des Spielers, wie Hytale sie beim Herstellen prüft. */
    public static Unlocks unlocks(@Nullable Player player, @Nullable World world) {
        Set<String> known = Set.of();
        try {
            if (player != null) {
                known = Set.copyOf(player.getPlayerConfigData().getKnownRecipes());
            }
        } catch (RuntimeException e) {
            known = Set.of();
        }
        return new Unlocks(known, memoriesLevel(world));
    }

    /** Wissen und Erinnerungsstufe ({@code CraftingManager#isValidBenchForRecipe}). */
    static boolean unlocked(CraftingRecipe recipe, Unlocks unlocks) {
        if (recipe.isKnowledgeRequired()) {
            MaterialQuantity primary = recipe.getPrimaryOutput();
            String itemId = primary == null ? null : primary.getItemId();
            if (itemId == null || !unlocks.knownItems().contains(itemId)) {
                return false;
            }
        }
        int required = recipe.getRequiredMemoriesLevel();
        return required <= 1 || unlocks.memoriesLevel() >= required;
    }

    /** Erinnerungsstufe der Welt; ohne das Erinnerungs-Modul gilt die Startstufe. */
    private static int memoriesLevel(@Nullable World world) {
        if (world == null) {
            return 1;
        }
        try {
            var memories = com.hypixel.hytale.builtin.adventure.memories.MemoriesPlugin.get();
            return memories == null ? 1 : memories.getMemoriesLevel(world.getGameplayConfig());
        } catch (RuntimeException | LinkageError e) {
            return 1;
        }
    }

    // ------------------------------------------------------------------ Rezept → Eintrag

    @Nullable
    private static Entry entry(CraftingRecipe recipe) {
        String bench = benchOf(recipe.getBenchRequirement());
        MaterialQuantity primary = recipe.getPrimaryOutput();
        MaterialQuantity[] in = recipe.getInput();
        if (bench == null || recipe.getId() == null || primary == null || primary.getItemId() == null
                || in == null || in.length == 0 || Item.getAssetMap().getAsset(primary.getItemId()) == null) {
            return null;
        }
        List<Input> inputs = new ArrayList<>(in.length);
        for (MaterialQuantity mq : in) {
            Input input = input(mq);
            if (input == null) {
                return null; // Zutat nicht abbildbar – Rezept nicht anbieten statt falsch herstellen
            }
            inputs.add(input);
        }
        List<Output> outputs = new ArrayList<>();
        MaterialQuantity[] out = recipe.getOutputs();
        if (out != null) {
            for (MaterialQuantity mq : out) {
                if (mq != null && mq.getItemId() != null && Item.getAssetMap().getAsset(mq.getItemId()) != null) {
                    outputs.add(new Output(mq.getItemId(), Math.max(1, mq.getQuantity()), mq.getMetadata()));
                }
            }
        }
        if (outputs.isEmpty()) {
            outputs.add(new Output(primary.getItemId(), Math.max(1, primary.getQuantity()), primary.getMetadata()));
        }
        int quantity = 0;
        for (Output o : outputs) {
            if (o.itemId().equals(primary.getItemId())) {
                quantity += o.quantity();
            }
        }
        return new Entry(recipe, bench, primary.getItemId(), quantity > 0 ? quantity : Math.max(1, primary.getQuantity()),
                List.copyOf(inputs), List.copyOf(outputs));
    }

    /** Erste Werkbank aus {@link #BENCHES}, an der das Rezept mit Stufe {@value #BENCH_TIER} geht; sonst null. */
    @Nullable
    static String benchOf(@Nullable BenchRequirement[] requirements) {
        if (requirements == null) {
            return null;
        }
        String best = null;
        for (BenchRequirement r : requirements) {
            if (r == null || r.type != BenchType.Crafting || r.requiredTierLevel > BENCH_TIER) {
                continue;
            }
            int i = BENCHES.indexOf(r.id);
            if (i >= 0 && (best == null || i < BENCHES.indexOf(best))) {
                best = r.id;
            }
        }
        return best;
    }

    /** Zutat als Bedarf; null, wenn sie sich nicht abbilden lässt (Metadaten, unbekannter Typ, nichts passt). */
    @Nullable
    private static Input input(@Nullable MaterialQuantity mq) {
        if (mq == null || mq.getQuantity() <= 0 || (mq.getMetadata() != null && !mq.getMetadata().isEmpty())) {
            return null;
        }
        int quantity = mq.getQuantity();
        // Reihenfolge wie in Hytales ItemContainer: festes Item, sonst Tag, sonst Ressourcentyp
        if (mq.getItemId() != null) {
            if (Item.getAssetMap().getAsset(mq.getItemId()) == null) {
                return null;
            }
            return new Input(mq.getItemId(), null, quantity, CraftPlanner.Ingredient.of(mq.getItemId(), quantity));
        }
        Map<String, Integer> all;
        if (mq.getTagIndex() != Integer.MIN_VALUE) {
            all = itemsWithTag(mq.getTagIndex());
        } else if (mq.getResourceTypeId() != null) {
            all = resourceIndex().getOrDefault(mq.getResourceTypeId(), Map.of());
        } else {
            return null;
        }
        // Hytale schließt die Ergebnisse eines Rezepts als Ersatz-Zutat aus (kein Item stellt sich selbst her)
        Map<String, Integer> units = new TreeMap<>();
        for (Map.Entry<String, Integer> e : all.entrySet()) {
            if (!mq.isItemExcluded(e.getKey())) {
                units.put(e.getKey(), e.getValue());
            }
        }
        if (units.isEmpty()) {
            return null;
        }
        String display = units.keySet().iterator().next();
        return new Input(display, mq.getResourceTypeId(), quantity,
                new CraftPlanner.Ingredient(quantity, Collections.unmodifiableMap(units)));
    }

    /** Ressourcentyp → Items mit ihrem Wert in Einheiten ({@code ItemResourceType.quantity}). */
    private static Map<String, Map<String, Integer>> resourceIndex() {
        if (resources == null) {
            Map<String, Map<String, Integer>> idx = new HashMap<>();
            for (Item item : Item.getAssetMap().getAssetMap().values()) {
                ItemResourceType[] types = item.getResourceTypes();
                if (types == null || item.getId() == null) {
                    continue;
                }
                for (ItemResourceType t : types) {
                    if (t != null && t.id != null && t.quantity > 0) {
                        idx.computeIfAbsent(t.id, k -> new TreeMap<>()).putIfAbsent(item.getId(), t.quantity);
                    }
                }
            }
            resources = idx;
        }
        return resources;
    }

    /** Items, die einen Tag tragen (je Stück eine Einheit). */
    private static Map<String, Integer> itemsWithTag(int tagIndex) {
        return TAGS.computeIfAbsent(tagIndex, t -> {
            Map<String, Integer> out = new TreeMap<>();
            for (Item item : Item.getAssetMap().getAssetMap().values()) {
                try {
                    if (item.getId() != null && item.getData() != null
                            && item.getData().getExpandedTagIndexes().contains(t.intValue())) {
                        out.put(item.getId(), 1);
                    }
                } catch (RuntimeException ignored) {
                    // Item ohne Tag-Daten
                }
            }
            return out;
        });
    }
}
