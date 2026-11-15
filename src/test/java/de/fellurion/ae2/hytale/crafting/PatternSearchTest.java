/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.crafting;

import de.fellurion.ae2.core.crafting.CraftingPattern;
import de.fellurion.ae2.core.crafting.IPatternDetails;
import de.fellurion.ae2.core.crafting.PatternDetailsHelper;
import de.fellurion.ae2.core.crafting.ProcessingPattern;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Suche des Pattern-Zugriffsterminals: Provider-Name, Ergebnisse und Zutaten der Patterns (ohne Server). */
class PatternSearchTest {

    private static final Map<String, String> NAMES = Map.of(
            "Ingredient_Bar_Iron", "Eisenbarren",
            "Tool_Pickaxe_Iron", "Eisenspitzhacke",
            "Wood_Oak_Trunk", "Eichenstamm",
            "Wood_Birch_Trunk", "Birkenstamm",
            "Ingredient_Charcoal", "Holzkohle");
    private static final Function<String, String> NAME = id -> NAMES.getOrDefault(id, id);

    private static AEItemKey key(String id) {
        return AEItemKey.of(id);
    }

    /** Spitzhacke aus 3 Eisenbarren und 2 Stämmen (Eiche, ersatzweise Birke). */
    private static IPatternDetails pickaxe() {
        List<AEItemKey> grid = List.of(key("Ingredient_Bar_Iron"), key("Ingredient_Bar_Iron"), key("Ingredient_Bar_Iron"),
                key("Wood_Oak_Trunk"), key("Wood_Oak_Trunk"));
        List<AEItemKey> wood = List.of(key("Wood_Oak_Trunk"), key("Wood_Birch_Trunk"));
        AEItemKey pattern = CraftingPattern.encode(grid, new GenericStack(key("Tool_Pickaxe_Iron"), 1), true,
                List.of(List.of(), List.of(), List.of(), wood, wood));
        IPatternDetails details = PatternDetailsHelper.decodePattern(pattern);
        assertNotNull(details);
        return details;
    }

    /** Holzkohle aus einem Eichenstamm (Ofen). */
    private static IPatternDetails charcoal() {
        AEItemKey pattern = ProcessingPattern.encode(List.of(new GenericStack(key("Wood_Oak_Trunk"), 1)),
                List.of(new GenericStack(key("Ingredient_Charcoal"), 2)));
        IPatternDetails details = PatternDetailsHelper.decodePattern(pattern);
        assertNotNull(details);
        return details;
    }

    @Test
    void suchtextEnthaeltErgebnisseUndZutaten() {
        String text = PatternSearch.text(pickaxe(), NAME);

        assertTrue(text.contains("eisenspitzhacke"));       // Ergebnis, übersetzter Name
        assertTrue(text.contains("tool_pickaxe_iron"));     // Ergebnis, Id
        assertTrue(text.contains("tool pickaxe iron"));     // Id mit Leerzeichen
        assertTrue(text.contains("eisenbarren"));           // Zutat
        assertTrue(text.contains("eichenstamm"));           // Hauptzutat
        assertFalse(text.contains("birkenstamm"), "Ersatz-Items zählen nicht zur Suche");
        assertEquals("", PatternSearch.text(null, NAME));
    }

    @Test
    void patternPasstNachErgebnisOderZutat() {
        String pick = PatternSearch.text(pickaxe(), NAME);
        String coal = PatternSearch.text(charcoal(), NAME);

        assertTrue(PatternSearch.matchesPattern(PatternSearch.normalize("  SPITZhacke "), pick));
        assertTrue(PatternSearch.matchesPattern("eisenbarren", pick));
        assertFalse(PatternSearch.matchesPattern("holzkohle", pick));
        assertTrue(PatternSearch.matchesPattern("holzkohle", coal));
        assertTrue(PatternSearch.matchesPattern("eiche", coal));
        assertTrue(PatternSearch.matchesPattern("", ""), "ohne Suchtext passt alles");
        assertFalse(PatternSearch.matchesPattern("eisen", ""), "leeres Feld passt zu keiner Suche");
        assertFalse(PatternSearch.matchesPattern("eisen", null));
    }

    @Test
    void providerBleibtBeiNamenOderPatternTreffer() {
        List<String> patterns = List.of(PatternSearch.text(pickaxe(), NAME), "", PatternSearch.text(charcoal(), NAME));
        String name = "ME Pattern Provider 12, 64, -30";

        assertTrue(PatternSearch.matchesProvider("", name, patterns));
        assertTrue(PatternSearch.matchesProvider("pattern provider", name, patterns));   // Name
        assertTrue(PatternSearch.matchesProvider("64, -30", name, patterns));            // Position
        assertTrue(PatternSearch.matchesProvider("holzkohle", name, patterns));          // Ergebnis eines Patterns
        assertTrue(PatternSearch.matchesProvider("eisenbarren", name, patterns));        // Zutat eines Patterns
        assertFalse(PatternSearch.matchesProvider("gold", name, patterns));
        assertFalse(PatternSearch.matchesProvider("eisen", name, List.of("", "")), "leerer Provider, Name passt nicht");
        assertFalse(PatternSearch.matchesProvider("eisen", null, List.of()));
    }

    @Test
    void suchtextWirdNormalisiert() {
        assertEquals("", PatternSearch.normalize(null));
        assertEquals("", PatternSearch.normalize("   "));
        assertEquals("eisen barren", PatternSearch.normalize(" Eisen Barren "));
    }
}
