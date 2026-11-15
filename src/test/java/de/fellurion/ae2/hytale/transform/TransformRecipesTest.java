/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.transform;

import de.fellurion.ae2.core.machines.Ingredient;
import de.fellurion.ae2.hytale.transform.TransformRecipes.Match;
import de.fellurion.ae2.hytale.transform.TransformRecipes.Pile;
import de.fellurion.ae2.hytale.transform.TransformRecipes.Recipe;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** In-Welt-Umwandlungen: Rezeptdatei und Zutatensuche (AE2: TransformLogic.tryTransform). */
class TransformRecipesTest {

    private static final String CHARGED = "AE2_Charged_Certus_Quartz_Crystal";
    private static final String RED = "Ingredient_Crystal_Red";
    private static final String WHITE = "Ingredient_Crystal_White";
    private static final String FLUIX = "AE2_Fluix_Crystal";

    private static TransformRecipes shipped() throws IOException {
        try (InputStream in = TransformRecipesTest.class.getResourceAsStream("/ae2data/transform.json")) {
            assertNotNull(in, "ae2data/transform.json");
            return TransformRecipes.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void mitgelieferteRezepteWerdenGelesen() throws IOException {
        TransformRecipes recipes = shipped();
        assertEquals(8, recipes.recipes().size());
        assertEquals(6, recipes.recipes().stream().filter(Recipe::isFluid).count());
        assertEquals(2, recipes.recipes().stream().filter(Recipe::isExplosion).count());
        // Auslöser ist nur die erste Zutat (AE2: collectFirstNonCustomIngredient)
        assertTrue(recipes.isFluidTrigger(CHARGED));
        assertFalse(recipes.isFluidTrigger(RED));
        assertFalse(recipes.isFluidTrigger("AE2_Singularity"), "Explosions-Rezepte lösen im Wasser nichts aus");
        assertFalse(recipes.isFluidTrigger(null));
    }

    @Test
    void fluixAusDreiZutatenImWasser() throws IOException {
        TransformRecipes recipes = shipped();
        List<Pile> piles = List.of(new Pile(CHARGED, 1), new Pile(WHITE, 5), new Pile("Rock_Stone", 9), new Pile(RED, 2));
        Match m = recipes.match("Water_Source", piles);
        assertNotNull(m);
        assertEquals(FLUIX, m.recipe().output().itemId());
        assertEquals(2, m.recipe().output().quantity(), "AE2: 2 Fluix-Kristalle");
        assertArrayEquals(new int[]{1, 1, 0, 1}, m.consumed(), "je Zutat genau eine, Unbeteiligtes bleibt liegen");
    }

    @Test
    void ohneAlleZutatenPassiertNichts() throws IOException {
        TransformRecipes recipes = shipped();
        assertNull(recipes.match("Water", List.of(new Pile(CHARGED, 4), new Pile(RED, 4))), "weiße Splitter fehlen");
        assertNull(recipes.match("Water", List.of(new Pile(RED, 1), new Pile(CHARGED, 1), new Pile(WHITE, 1))),
                "der Auslöser muss die erste Zutat sein");
        assertNull(recipes.match("Water", List.of()));
        assertNull(recipes.match("Water", List.of(new Pile(CHARGED, 0), new Pile(RED, 1), new Pile(WHITE, 1))),
                "aufgebrauchter Stapel zählt nicht");
    }

    @Test
    void nurInDerPassendenFluessigkeit() throws IOException {
        TransformRecipes recipes = shipped();
        List<Pile> piles = List.of(new Pile(CHARGED, 1), new Pile(RED, 1), new Pile(WHITE, 1));
        for (String water : new String[]{"Water", "Water_Source", "Water_Finite"}) {
            assertNotNull(recipes.match(water, piles), water);
            assertTrue(recipes.canTransformIn(CHARGED, water), water);
        }
        for (String other : new String[]{"Lava", "Lava_Source", "Waterfall", "Poison", null}) {
            assertNull(recipes.match(other, piles), String.valueOf(other));
            assertFalse(recipes.canTransformIn(CHARGED, other), String.valueOf(other));
        }
        assertFalse(recipes.canTransformIn(RED, "Water"));
    }

    @Test
    void buddingQuarzWirdRepariert() throws IOException {
        TransformRecipes recipes = shipped();
        Match damaged = recipes.match("Water", List.of(new Pile(CHARGED, 1), new Pile("AE2_Quartz_Block", 1)));
        assertNotNull(damaged);
        assertEquals("AE2_Damaged_Budding_Quartz", damaged.recipe().output().itemId());
        Match chipped = recipes.match("Water", List.of(new Pile(CHARGED, 1), new Pile("AE2_Damaged_Budding_Quartz", 1)));
        assertNotNull(chipped);
        assertEquals("AE2_Chipped_Budding_Quartz", chipped.recipe().output().itemId());
        Match flawed = recipes.match("Water", List.of(new Pile(CHARGED, 1), new Pile("AE2_Chipped_Budding_Quartz", 1)));
        assertNotNull(flawed);
        assertEquals("AE2_Flawed_Budding_Quartz", flawed.recipe().output().itemId());
        assertNull(recipes.match("Water", List.of(new Pile(CHARGED, 1), new Pile("AE2_Flawed_Budding_Quartz", 1))),
                "makelloser Budding-Quarz lässt sich nicht herstellen (AE2)");
    }

    @Test
    void wiederholtesUmwandelnVerbrauchtNieMehrAlsDaIst() throws IOException {
        TransformRecipes recipes = shipped();
        int[] left = {3, 5, 2}; // geladen, rot, weiß
        String[] ids = {CHARGED, RED, WHITE};
        int fluix = 0;
        for (int round = 0; round < 10; round++) {
            Match m = recipes.match("Water", List.of(new Pile(ids[0], left[0]), new Pile(ids[1], left[1]),
                    new Pile(ids[2], left[2])));
            if (m == null) {
                break;
            }
            for (int i = 0; i < left.length; i++) {
                left[i] -= m.consumed()[i];
                assertTrue(left[i] >= 0);
            }
            fluix += m.recipe().output().quantity();
        }
        assertArrayEquals(new int[]{1, 3, 0}, left, "weiß reicht für zwei Durchgänge");
        assertEquals(4, fluix);
    }

    @Test
    void mengenUndGleicheZutatenWerdenUeberStapelVerteilt() {
        TransformRecipes recipes = new TransformRecipes().add(new Recipe("x",
                List.of(Ingredient.of("A", 2), Ingredient.of("B", 3), Ingredient.of("A", 1)), "fluid:water",
                Ingredient.of("C", 1)));
        Match m = recipes.match("Water", List.of(new Pile("A", 1), new Pile("B", 2), new Pile("A", 5), new Pile("B", 1)));
        assertNotNull(m);
        assertArrayEquals(new int[]{1, 2, 2, 1}, m.consumed());
        assertNull(recipes.match("Water", List.of(new Pile("A", 2), new Pile("B", 3))), "dritte Zutat fehlt");
    }

    @Test
    void kaputteEintraegeWerdenUebersprungen() {
        TransformRecipes recipes = TransformRecipes.fromJson("""
                [ {"id": "ok", "inputs": [{"ItemId": "A"}], "circumstance": "fluid:water", "output": {"ItemId": "B", "Quantity": 2}},
                  {"id": "noOutput", "inputs": [{"ItemId": "A"}], "circumstance": "fluid:water"},
                  {"id": "noInputs", "inputs": [], "circumstance": "fluid:water", "output": {"ItemId": "B"}},
                  {"id": "badInput", "inputs": [{"Quantity": 3}], "circumstance": "fluid:water", "output": {"ItemId": "B"}},
                  {"id": "noCircumstance", "inputs": [{"ItemId": "A"}], "output": {"ItemId": "B"}},
                  17 ]""");
        assertEquals(1, recipes.recipes().size());
        assertEquals("ok", recipes.recipes().getFirst().id());
        assertEquals(0, TransformRecipes.fromJson("{}").recipes().size());
    }

    // ------------------------------------------------------------------ Explosionen (AE2: handleExplosion)

    private static final String SINGULARITY = "AE2_Singularity";
    private static final String ENDER_DUST = "AE2_Ender_Dust";
    private static final String ENTANGLED = "AE2_Quantum_Entangled_Singularity";

    @Test
    void jedeZutatEinesExplosionsRezeptsLoestAus() throws IOException {
        TransformRecipes recipes = shipped();
        assertTrue(recipes.isExplosionTrigger(SINGULARITY));
        assertTrue(recipes.isExplosionTrigger(ENDER_DUST), "AE2: nicht nur die erste Zutat");
        assertTrue(recipes.isExplosionTrigger("Ingredient_Void_Essence"));
        assertFalse(recipes.isExplosionTrigger(CHARGED), "Wasser-Rezepte lösen bei Explosionen nichts aus");
        assertFalse(recipes.isExplosionTrigger(null));
    }

    @Test
    void singularitaetUndEnderDustWerdenVerschraenkt() throws IOException {
        TransformRecipes recipes = shipped();
        // getroffen wurde der Staub; die Singularität liegt daneben
        List<Pile> piles = List.of(new Pile(ENDER_DUST, 5), new Pile("Rock_Stone", 3), new Pile(SINGULARITY, 2));
        Match m = recipes.matchExplosion(piles);
        assertNotNull(m);
        assertEquals(ENTANGLED, m.recipe().output().itemId());
        assertEquals(2, m.recipe().output().quantity(), "AE2: ein Paar");
        assertArrayEquals(new int[]{1, 0, 1}, m.consumed(), "je Zutat ein Stück");
        // auch mit der Singularität als Treffer
        assertNotNull(recipes.matchExplosion(List.of(new Pile(SINGULARITY, 1), new Pile(ENDER_DUST, 1))));
    }

    @Test
    void ohnePartnerOderImWasserPassiertNichts() throws IOException {
        TransformRecipes recipes = shipped();
        assertNull(recipes.matchExplosion(List.of(new Pile(SINGULARITY, 4))), "der Staub fehlt");
        assertNull(recipes.matchExplosion(List.of(new Pile(ENDER_DUST, 1), new Pile(ENDER_DUST, 1))));
        assertNull(recipes.matchExplosion(List.of(new Pile("Rock_Stone", 1), new Pile(SINGULARITY, 1), new Pile(ENDER_DUST, 1))),
                "der getroffene Gegenstand muss selbst Zutat sein");
        assertNull(recipes.matchExplosion(List.of()));
        // Explosions-Rezepte gelten nicht im Wasser und umgekehrt
        assertNull(recipes.match("Water_Source", List.of(new Pile(SINGULARITY, 1), new Pile(ENDER_DUST, 1))));
        assertNull(recipes.matchExplosion(List.of(new Pile(CHARGED, 1), new Pile(RED, 1), new Pile(WHITE, 1))));
    }

    @Test
    void zweitesRezeptMitLeerenessenz() throws IOException {
        Match m = shipped().matchExplosion(List.of(new Pile(SINGULARITY, 1), new Pile("Ingredient_Void_Essence", 1)));
        assertNotNull(m);
        assertEquals("AE2_Transform_entangled_singularity_from_pearl", m.recipe().id());
    }
}
