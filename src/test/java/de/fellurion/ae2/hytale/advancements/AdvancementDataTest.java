/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.advancements;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import de.fellurion.ae2.hytale.ItemCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Passen advancements.json, items.json und ae2adv.lang zusammen? Stimmt die Erfolgslogik (UND/ODER)? */
class AdvancementDataTest {

    /** Vanilla-Items, die als Kriterium vorkommen (nicht in items.json). */
    private static final Set<String> VANILLA = Set.of("Ingredient_Bar_Copper");

    private static AdvancementRegistry registry;
    /** Alle portierten ItemIds aus items.json. */
    private static final Set<String> ITEMS = new HashSet<>();

    @BeforeAll
    static void load() {
        for (JsonElement e : JsonParser.parseString(ItemCatalog.resource("items.json")).getAsJsonArray()) {
            ITEMS.add(e.getAsJsonObject().get("id").getAsString());
        }
        registry = AdvancementRegistry.get();
    }

    @Test
    void allAe2AdvancementsInTreeOrder() {
        assertEquals(26, registry.size());
        Advancement root = registry.all().getFirst();
        assertEquals("root", root.id());
        assertNull(root.parent());
        assertFalse(root.toast());
        assertEquals(7, registry.byId("network3").depth()); // root>charger>charged_quartz>fluix>glass_cable>network1>2>3
        for (Advancement a : registry.all()) {
            if (a.parent() != null) {
                assertEquals(registry.byId(a.parent()).depth() + 1, a.depth(), a.id());
            }
        }
    }

    @Test
    void iconsAndItemsExist() {
        for (Advancement a : registry.all()) {
            assertTrue(ITEMS.contains(a.icon()), "Symbol fehlt: " + a.icon());
            for (Advancement.Criterion c : a.criteria().values()) {
                for (String item : c.items()) {
                    assertTrue(VANILLA.contains(item) || ITEMS.contains(item), a.id() + ": " + item);
                }
            }
        }
        assertEquals(17, registry.byId("glass_cable").criteria().get("certus").items().size());
        assertEquals(1, registry.criteriaForItem("AE2_Fluix_Glass_Cable").size()); // nur glass_cable
    }

    @Test
    void customTriggers() {
        List<Advancement.Criterion> net = registry.criteria(Trigger.NETWORK_CHANNELS);
        assertEquals(3, net.size());
        assertEquals(Set.of(8, 128, 2048), Set.of(net.get(0).channels(), net.get(1).channels(), net.get(2).channels()));
        assertEquals("recursive", registry.criteria(Trigger.RECURSIVE_NETWORKING).getFirst().advancementId());
        assertEquals("spatial_explorer", registry.criteria(Trigger.SPATIAL_EXPLORER).getFirst().advancementId());
        assertFalse(registry.byId("spatial_explorer").available());
        assertFalse(registry.byId("facade").available());
    }

    @Test
    void translationsComplete() throws IOException {
        Map<String, String> en = lang("en-US");
        Map<String, String> de = lang("de-DE");
        assertEquals(en.keySet(), de.keySet(), "en-US und de-DE haben unterschiedliche Schlüssel");
        for (Advancement a : registry.all()) {
            assertTrue(en.containsKey(a.titleKey()), a.titleKey());
            assertTrue(en.containsKey(a.descKey()), a.descKey());
        }
        for (String k : List.of("page.title", "page.progress", "state.done", "state.open", "state.partial",
                "state.pending", "toast", "announce", "command.reset")) {
            assertTrue(en.containsKey("ae2adv." + k), k);
        }
    }

    @Test
    void requirementsAreAndOfOr() {
        Advancement presses = registry.byId("presses");
        PlayerAdvancements p = new PlayerAdvancements();
        assertEquals(PlayerAdvancements.Result.PROGRESS, p.award(presses, "logic", 1));
        assertEquals(PlayerAdvancements.Result.NOTHING, p.award(presses, "logic", 2));
        assertEquals(PlayerAdvancements.Result.PROGRESS, p.award(presses, "silicon", 3));
        assertEquals(2, presses.requirementsMet(p.criteriaOf("presses")));
        assertEquals(PlayerAdvancements.Result.PROGRESS, p.award(presses, "calculation", 4));
        assertEquals(PlayerAdvancements.Result.COMPLETED, p.award(presses, "engineering", 5));
        assertTrue(p.isDone("presses"));
        assertEquals(5, p.completedAt("presses"));

        Advancement cells = registry.byId("storage_cell");
        assertEquals(PlayerAdvancements.Result.COMPLETED, p.award(cells, "c64k", 6)); // eine Zelle genügt
        assertEquals(PlayerAdvancements.Result.NOTHING, p.award(cells, "c1k", 7));
        assertEquals(PlayerAdvancements.Result.NOTHING, p.award(cells, "unknown", 8));
    }

    @Test
    void progressJsonRoundTrip() {
        PlayerAdvancements p = new PlayerAdvancements();
        p.award(registry.byId("root"), "copper", 42);
        p.award(registry.byId("presses"), "logic", 43);
        PlayerAdvancements q = PlayerAdvancements.fromJson(p.toJson());
        assertTrue(q.isDone("root"));
        assertEquals(42, q.completedAt("root"));
        assertFalse(q.isDone("presses"));
        assertEquals(Set.of("logic"), q.criteriaOf("presses"));
        assertEquals(1, q.completedCount());
    }

    private static Map<String, String> lang(String locale) throws IOException {
        String path = "/Server/Languages/" + locale + "/ae2adv.lang";
        try (InputStream in = AdvancementDataTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            Map<String, String> out = new LinkedHashMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                assertTrue(eq > 0, "Zeile ohne '=': " + line);
                String key = "ae2adv." + line.substring(0, eq).strip();
                assertNull(out.put(key, line.substring(eq + 1).strip()), "doppelt: " + key);
            }
            return out;
        }
    }
}
