/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.inv.SimpleSlotAccess;
import de.fellurion.ae2.core.inv.SimpleStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The name press: an inscriber job that is no recipe (AE2: {@code InscriberRecipes.makeNamePressRecipe}). */
class InscriberNamePressTest {
    private static final String PRESS = InscriberRecipes.NAME_PRESS_ITEM_ID;
    private static final String RECIPES_JSON = """
            [
              {"id": "calc_print", "mode": "inscribe",
               "top": {"ItemId": "AE2_Calculation_Processor_Press", "Quantity": 1},
               "middle": {"ItemId": "AE2_Certus_Quartz_Crystal", "Quantity": 1},
               "bottom": null,
               "output": {"ItemId": "AE2_Printed_Calculation_Processor", "Quantity": 1}}
            ]
            """;

    /**
     * Stand-in for the integration layer: a press carries its name as metadata {@code name=...}; an item's metadata
     * is a list of {@code key=value} pairs separated by ';' of which {@code name} is the custom name.
     */
    private static final InscriberRecipes.NameHandler HANDLER = new InscriberRecipes.NameHandler() {
        @Override
        public String nameOf(SimpleStack press) {
            return press.nbt() == null ? null : press.nbt().substring("name=".length());
        }

        @Override
        public SimpleStack rename(SimpleStack item, String name) {
            var sb = new StringBuilder();
            if (item.nbt() != null) {
                for (String part : item.nbt().split(";")) {
                    if (!part.startsWith("name=")) {
                        sb.append(sb.isEmpty() ? "" : ";").append(part);
                    }
                }
            }
            if (name != null) {
                sb.append(sb.isEmpty() ? "" : ";").append("name=").append(name);
            }
            return new SimpleStack(item.itemId(), sb.isEmpty() ? null : sb.toString(), 1);
        }
    };

    private MEWorld world;
    private InscriberRecipes recipes;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        InscriberRecipes.setNameHandler(HANDLER);
        world = new MEWorld();
        recipes = InscriberRecipes.fromJson(RECIPES_JSON);
        var cell = EnergyCellLogic.denseEnergyCell();
        cell.setAECurrentPower(100_000);
        world.addNode(BlockPos.asLong(0, 0, 0), NodeType.DENSE_ENERGY_CELL, null, null, cell);
    }

    @AfterEach
    void tearDown() {
        InscriberRecipes.setNameHandler(null);
    }

    private static SimpleStack press(String name) {
        return new SimpleStack(PRESS, name == null ? null : "name=" + name, 1);
    }

    private InscriberLogic placeInscriber(SimpleSlotAccess inv) {
        var logic = new InscriberLogic(inv, recipes);
        world.addNode(BlockPos.asLong(1, 0, 0), NodeType.INSCRIBER, Direction.EAST, null, logic);
        return logic;
    }

    private GridNode node() {
        return world.getNode(1, 0, 0);
    }

    private void run(InscriberLogic logic, int ticks) {
        for (int i = 0; i < ticks; i++) {
            logic.tickingRequest(node(), 1);
        }
    }

    @Test
    void aNamePressOnTopOrBottomRenamesAnything() {
        var stone = SimpleStack.of("Rock_Stone", 5);
        var job = recipes.findRecipe(stone, press("Alpha"), null);
        assertNotNull(job);
        assertTrue(InscriberRecipes.isNamePressRecipe(job));
        assertEquals(InscriberRecipe.ProcessType.INSCRIBE, job.mode(), "the press is kept");
        assertEquals(1, job.middle().quantity());
        assertEquals(new SimpleStack("Rock_Stone", "name=Alpha", 1),
                InscriberRecipes.namePressResult(stone, press("Alpha"), null));
        // bottom only
        assertTrue(InscriberRecipes.isNamePressRecipe(recipes.findRecipe(stone, null, press("Beta"))));
        assertEquals("name=Beta", InscriberRecipes.namePressResult(stone, null, press("Beta")).nbt());
        // both: top name, then bottom name
        assertEquals("name=Alpha Beta", InscriberRecipes.namePressResult(stone, press("Alpha"), press("Beta")).nbt());
        assertEquals("name=Beta Alpha", InscriberRecipes.namePressResult(stone, press("Beta"), press("Alpha")).nbt());
        assertEquals("name=Beta", InscriberRecipes.namePressResult(stone, press(null), press("Beta")).nbt());
    }

    @Test
    void otherMetadataSurvivesAndABlankPressRemovesTheName() {
        var cell = new SimpleStack("AE2_Item_Storage_Cell_1k", "cell=full;name=Old", 1);
        assertEquals(new SimpleStack("AE2_Item_Storage_Cell_1k", "cell=full;name=New", 1),
                InscriberRecipes.namePressResult(cell, press("New"), null));
        assertEquals(new SimpleStack("AE2_Item_Storage_Cell_1k", "cell=full", 1),
                InscriberRecipes.namePressResult(cell, press(null), null), "AE2: a press without name removes it");
        assertEquals(SimpleStack.of("Rock_Stone", 1),
                InscriberRecipes.namePressResult(new SimpleStack("Rock_Stone", "name=X", 9), null, press(null)));
    }

    @Test
    void aNamePressNextToAnotherPlateIsNoJob() {
        var stone = SimpleStack.of("Rock_Stone", 1);
        var other = SimpleStack.of("AE2_Calculation_Processor_Press", 1);
        assertNull(recipes.findRecipe(stone, press("A"), other));
        assertNull(recipes.findRecipe(stone, other, press("A")));
        assertNull(InscriberRecipes.namePressResult(stone, press("A"), other));
        assertNull(InscriberRecipes.namePressResult(stone, null, null));
        assertNull(InscriberRecipes.namePressResult(null, press("A"), null));
        assertNull(recipes.findRecipe(null, press("A"), null));
        // normal recipes are untouched
        assertEquals("calc_print", recipes.findRecipe(SimpleStack.of("AE2_Certus_Quartz_Crystal", 1), other, null).id());
        assertFalse(InscriberRecipes.isNamePressRecipe(recipes.getRecipes().getFirst()));
        assertFalse(InscriberRecipes.isNamePressRecipe(null));
    }

    @Test
    void slotFilterAlwaysTakesNamePressesAndThenAnythingInTheMiddle() {
        var other = SimpleStack.of("AE2_Calculation_Processor_Press", 1);
        var stone = SimpleStack.of("Rock_Stone", 1);
        assertTrue(recipes.allowInsert(InscriberLogic.SLOT_TOP, press("A"), null, stone, null));
        assertTrue(recipes.allowInsert(InscriberLogic.SLOT_BOTTOM, press("A"), other, null, null));
        assertTrue(recipes.allowInsert(InscriberLogic.SLOT_MIDDLE, stone, press("A"), null, null));
        assertTrue(recipes.allowInsert(InscriberLogic.SLOT_MIDDLE, stone, null, null, press(null)));
        assertFalse(recipes.allowInsert(InscriberLogic.SLOT_MIDDLE, stone, other, null, null));
        assertFalse(recipes.allowInsert(InscriberLogic.SLOT_BOTTOM, other, press("A"), null, null));
    }

    @Test
    void withoutANameHandlerTheNamePressDoesNothing() {
        InscriberRecipes.setNameHandler(null);
        var stone = SimpleStack.of("Rock_Stone", 1);
        assertNull(recipes.findRecipe(stone, press("A"), null));
        assertNull(InscriberRecipes.namePressResult(stone, press("A"), null));
        assertFalse(recipes.allowInsert(InscriberLogic.SLOT_TOP, press("A"), null, null, null));
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        inv.set(InscriberLogic.SLOT_TOP, press("A"));
        inv.set(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("Rock_Stone", 2));
        logic.onInventoryChanged();
        run(logic, 300);
        assertNull(inv.get(InscriberLogic.SLOT_OUTPUT));
        assertEquals(2, inv.get(InscriberLogic.SLOT_MIDDLE).count());
    }

    @Test
    void theInscriberRenamesOneItemPerCycleAndKeepsThePress() {
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        inv.set(InscriberLogic.SLOT_TOP, press("Depot"));
        inv.set(InscriberLogic.SLOT_MIDDLE, new SimpleStack("Furniture_Chest", "tag=7", 3));
        logic.onInventoryChanged();
        int ticks = 0;
        while (inv.get(InscriberLogic.SLOT_OUTPUT) == null && ticks < 500) {
            logic.tickingRequest(node(), 1);
            ticks++;
        }
        assertEquals(109, ticks, "same time as any other inscriber job");
        assertEquals(new SimpleStack("Furniture_Chest", "tag=7;name=Depot", 1), inv.get(InscriberLogic.SLOT_OUTPUT));
        assertEquals(new SimpleStack("Furniture_Chest", "tag=7", 2), inv.get(InscriberLogic.SLOT_MIDDLE));
        assertEquals(press("Depot"), inv.get(InscriberLogic.SLOT_TOP));
        run(logic, 2 * 117);
        assertEquals(new SimpleStack("Furniture_Chest", "tag=7;name=Depot", 3), inv.get(InscriberLogic.SLOT_OUTPUT));
        assertNull(inv.get(InscriberLogic.SLOT_MIDDLE));
        assertEquals(press("Depot"), inv.get(InscriberLogic.SLOT_TOP), "nothing consumed but the items");
        assertFalse(logic.isWorking() && logic.getProcessingTime() > 0);
    }

    @Test
    void swappingThePressRestartsWithTheNewName() {
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        inv.set(InscriberLogic.SLOT_TOP, press("One"));
        inv.set(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("Rock_Stone", 2));
        logic.onInventoryChanged();
        run(logic, 60);
        assertTrue(logic.getProcessingTime() > 0);
        inv.set(InscriberLogic.SLOT_TOP, press("Two"));
        logic.onInventoryChanged();
        assertEquals(0, logic.getProcessingTime(), "a different press is a different job");
        run(logic, 117);
        assertEquals(new SimpleStack("Rock_Stone", "name=Two", 1), inv.get(InscriberLogic.SLOT_OUTPUT));
        // a different name does not stack onto the output: the inscriber waits
        inv.set(InscriberLogic.SLOT_TOP, press("Three"));
        logic.onInventoryChanged();
        run(logic, 300);
        assertEquals(new SimpleStack("Rock_Stone", "name=Two", 1), inv.get(InscriberLogic.SLOT_OUTPUT));
        assertEquals(1, inv.get(InscriberLogic.SLOT_MIDDLE).count());
    }

    @Test
    void anAlreadyNamedItemPassesThroughUnchangedInIdentity() {
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        inv.set(InscriberLogic.SLOT_BOTTOM, press("Same"));
        inv.set(InscriberLogic.SLOT_MIDDLE, new SimpleStack("Rock_Stone", "name=Same", 1));
        logic.onInventoryChanged();
        run(logic, 117);
        assertEquals(new SimpleStack("Rock_Stone", "name=Same", 1), inv.get(InscriberLogic.SLOT_OUTPUT));
        assertNull(inv.get(InscriberLogic.SLOT_MIDDLE));
    }
}
