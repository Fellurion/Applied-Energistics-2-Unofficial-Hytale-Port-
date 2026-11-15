/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.crafting;

import de.fellurion.ae2.hytale.crafting.CraftPlanner.Ingredient;
import de.fellurion.ae2.hytale.crafting.CraftPlanner.Plan;
import de.fellurion.ae2.hytale.crafting.CraftPlanner.Source;
import de.fellurion.ae2.hytale.crafting.CraftPlanner.Stock;
import de.fellurion.ae2.hytale.crafting.CraftPlanner.Stocks;
import de.fellurion.ae2.hytale.crafting.CraftPlanner.Take;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zutaten des Crafting-Terminals: was passt, was ist vorhanden, woher wird genommen (ohne Server). */
class CraftPlannerTest {

    private static Stock net(int ref, String item, long amount) {
        return new Stock(Source.NETWORK, ref, item, true, amount);
    }

    private static Stock inv(int ref, String item, long amount) {
        return new Stock(Source.INVENTORY, ref, item, true, amount);
    }

    /** Entnahmen als Vorrats-Index → Menge. */
    private static Map<Integer, Long> takes(Plan plan) {
        Map<Integer, Long> out = new HashMap<>();
        for (Take t : plan.takes()) {
            assertFalse(out.containsKey(t.stock()), "jeder Vorrat höchstens einmal im Plan");
            out.put(t.stock(), t.amount());
        }
        return out;
    }

    @Test
    void netzZuerstDannInventar() {
        Stocks stocks = CraftPlanner.index(List.of(inv(0, "Iron", 10), net(0, "Iron", 3)));
        Plan plan = CraftPlanner.plan(List.of(Ingredient.of("Iron", 5)), stocks, 1);

        assertTrue(plan.complete());
        assertEquals(Map.of(1, 3L, 0, 2L), takes(plan)); // 3 aus dem Netz, die fehlenden 2 aus dem Inventar
        CraftPlanner.Line line = plan.lines().getFirst();
        assertEquals(5, line.need());
        assertEquals(3, line.network());
        assertEquals(10, line.inventory());
        assertEquals(13, line.have());
        assertTrue(line.satisfied());
        assertEquals(1, line.firstUsed());
    }

    @Test
    void fehlendeZutatWirdGemeldetUndNichtsErfunden() {
        Stocks stocks = CraftPlanner.index(List.of(net(0, "Iron", 2), net(1, "Copper", 9)));
        Plan plan = CraftPlanner.plan(List.of(Ingredient.of("Iron", 5), Ingredient.of("Copper", 1)), stocks, 1);

        assertFalse(plan.complete());
        assertEquals(3, plan.lines().get(0).missing());
        assertFalse(plan.lines().get(0).satisfied());
        assertEquals(2, plan.lines().get(0).covered());
        assertTrue(plan.lines().get(1).satisfied());
        assertEquals(0, CraftPlanner.maxCrafts(List.of(Ingredient.of("Iron", 5), Ingredient.of("Copper", 1)), stocks, 64));
    }

    @Test
    void unbekanntesItemPasstNicht() {
        Stocks stocks = CraftPlanner.index(List.of(net(0, "Gold", 99)));
        Plan plan = CraftPlanner.plan(List.of(Ingredient.of("Iron", 1)), stocks, 1);

        assertFalse(plan.complete());
        assertEquals(0, plan.lines().getFirst().have());
        assertEquals(-1, plan.lines().getFirst().firstUsed());
        assertTrue(plan.takes().isEmpty());
    }

    @Test
    void itemsMitMetadatenWerdenNieVerbraucht() {
        // z. B. seelengebundene Items, gefüllte Speicherzellen, kodierte Patterns
        Stocks stocks = CraftPlanner.index(List.of(new Stock(Source.NETWORK, 0, "Cell", false, 5), inv(0, "Cell", 1)));
        Plan plan = CraftPlanner.plan(List.of(Ingredient.of("Cell", 1)), stocks, 1);

        assertTrue(plan.complete());
        assertEquals(Map.of(1, 1L), takes(plan));
        assertEquals(0, plan.lines().getFirst().network());
        assertEquals(1, CraftPlanner.maxCrafts(List.of(Ingredient.of("Cell", 1)), stocks, 64));
    }

    @Test
    void ressourcentypZaehltEinheitenWieHytale() {
        // "beliebiges Holz": ein Stamm ist 4 Einheiten wert, ein Brett 1
        Ingredient wood = new Ingredient(6, Map.of("Trunk", 4, "Plank", 1));
        Stocks stocks = CraftPlanner.index(List.of(net(0, "Trunk", 1), net(1, "Plank", 10)));
        Plan plan = CraftPlanner.plan(List.of(wood), stocks, 1);

        assertTrue(plan.complete());
        assertEquals(Map.of(0, 1L, 1, 2L), takes(plan)); // 1 Stamm (4) + 2 Bretter (2)
        assertEquals(14, plan.lines().getFirst().have());

        // Nur Stämme: ceil(6 / 4) = 2 Stück, der Überschuss verfällt wie an der Werkbank
        Stocks trunks = CraftPlanner.index(List.of(net(0, "Trunk", 5)));
        assertEquals(Map.of(0, 2L), takes(CraftPlanner.plan(List.of(wood), trunks, 1)));
        assertEquals(3, CraftPlanner.maxCrafts(List.of(wood), trunks, 64)); // 5 Stämme = 20 Einheiten = 3 × 6
    }

    @Test
    void festesItemGehtVorRessourcentyp() {
        // Beide Zutaten könnten den Stamm nehmen; das feste Item hat keine Ausweichmöglichkeit
        Ingredient anyWood = new Ingredient(1, Map.of("Trunk", 1, "Plank", 1));
        Ingredient trunk = Ingredient.of("Trunk", 1);
        Stocks stocks = CraftPlanner.index(List.of(net(0, "Trunk", 1), net(1, "Plank", 1)));
        Plan plan = CraftPlanner.plan(List.of(anyWood, trunk), stocks, 1);

        assertTrue(plan.complete());
        assertEquals(Map.of(0, 1L, 1, 1L), takes(plan));
        assertEquals(1, plan.lines().get(0).firstUsed()); // "beliebiges Holz" weicht auf das Brett aus
        assertEquals(0, plan.lines().get(1).firstUsed());
    }

    @Test
    void umkaempfterVorratZuletzt() {
        // Zutat A: Stamm oder Brett, Zutat B: Stamm oder Ast – beide greifen zuerst zu dem, was nur sie brauchen
        Ingredient a = new Ingredient(1, Map.of("Trunk", 1, "Plank", 1));
        Ingredient b = new Ingredient(1, Map.of("Trunk", 1, "Branch", 1));
        Stocks stocks = CraftPlanner.index(List.of(net(0, "Trunk", 5), net(1, "Plank", 5), net(2, "Branch", 5)));
        Plan plan = CraftPlanner.plan(List.of(a, b), stocks, 3);

        assertTrue(plan.complete());
        assertEquals(Map.of(1, 3L, 2, 3L), takes(plan));
    }

    @Test
    void gleicheZutatZweimalTeiltSichDenVorrat() {
        List<Ingredient> twice = List.of(Ingredient.of("Iron", 2), Ingredient.of("Iron", 3));
        Stocks stocks = CraftPlanner.index(List.of(net(0, "Iron", 4), inv(0, "Iron", 7)));

        Plan plan = CraftPlanner.plan(twice, stocks, 1);
        assertTrue(plan.complete());
        assertEquals(Map.of(0, 4L, 1, 1L), takes(plan)); // zusammengefasst: 4 aus dem Netz, 1 aus dem Inventar
        assertEquals(2, CraftPlanner.maxCrafts(twice, stocks, 64)); // 11 Stück reichen für 2 × 5
        assertFalse(CraftPlanner.plan(twice, stocks, 3).complete());
    }

    @Test
    void hoechstmengeBeachtetGrenzeUndMenge() {
        List<Ingredient> recipe = List.of(Ingredient.of("Iron", 2), Ingredient.of("Copper", 1));
        Stocks stocks = CraftPlanner.index(List.of(net(0, "Iron", 100), net(1, "Copper", 7), inv(0, "Copper", 4)));

        assertEquals(11, CraftPlanner.maxCrafts(recipe, stocks, 999)); // Kupfer begrenzt: 7 + 4
        assertEquals(10, CraftPlanner.maxCrafts(recipe, stocks, 10));  // Wunschmenge begrenzt
        assertEquals(0, CraftPlanner.maxCrafts(recipe, stocks, 0));
        Plan plan = CraftPlanner.plan(recipe, stocks, 11);
        assertTrue(plan.complete());
        assertEquals(Map.of(0, 22L, 1, 7L, 2, 4L), takes(plan));
        assertEquals(22, plan.lines().get(0).need());
    }

    @Test
    void leererVorratUndNullHerstellungen() {
        Stocks none = CraftPlanner.index(List.of());
        assertEquals(0, CraftPlanner.maxCrafts(List.of(Ingredient.of("Iron", 1)), none, 64));
        Plan zero = CraftPlanner.plan(List.of(Ingredient.of("Iron", 1)), none, 0);
        assertTrue(zero.complete());
        assertTrue(zero.takes().isEmpty());
    }
}
