/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.inv.SimpleSlotAccess;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.inv.SlotInventories;
import de.fellurion.ae2.core.stacks.AEItemKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InscriberLogicTest {
    private static final String RECIPES_JSON = """
            [
              {"id": "calc_processor", "mode": "press",
               "top": {"ItemId": "AE2_Printed_Calculation_Processor", "Quantity": 1},
               "middle": {"ItemId": "Ingredient_Crystal_Red", "Quantity": 1},
               "bottom": {"ItemId": "AE2_Printed_Silicon", "Quantity": 1},
               "output": {"ItemId": "AE2_Calculation_Processor", "Quantity": 1}},
              {"id": "calc_print", "mode": "inscribe",
               "top": {"ItemId": "AE2_Calculation_Processor_Press", "Quantity": 1},
               "middle": {"ItemId": "AE2_Certus_Quartz_Crystal", "Quantity": 1},
               "bottom": null,
               "output": {"ItemId": "AE2_Printed_Calculation_Processor", "Quantity": 1}},
              {"id": "press_copy", "mode": "inscribe",
               "top": {"ItemId": "AE2_Calculation_Processor_Press", "Quantity": 1},
               "middle": {"ItemId": "Ingredient_Bar_Iron", "Quantity": 9},
               "bottom": null,
               "output": {"ItemId": "AE2_Calculation_Processor_Press", "Quantity": 1}}
            ]
            """;

    private MEWorld world;
    private InscriberRecipes recipes;
    private EnergyCellLogic cell;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
        recipes = InscriberRecipes.fromJson(RECIPES_JSON);
        cell = EnergyCellLogic.denseEnergyCell();
        cell.setAECurrentPower(100_000);
        world.addNode(BlockPos.asLong(0, 0, 0), NodeType.DENSE_ENERGY_CELL, null, null, cell);
    }

    private InscriberLogic placeInscriber(SimpleSlotAccess inv) {
        var logic = new InscriberLogic(inv, recipes);
        world.addNode(BlockPos.asLong(1, 0, 0), NodeType.INSCRIBER, Direction.EAST, null, logic);
        return logic;
    }

    private GridNode node() {
        return world.getNode(1, 0, 0);
    }

    @Test
    void recipeLookupSupportsFlippedPlatesAndFilters() {
        assertEquals(3, recipes.getRecipes().size());
        var silicon = SimpleStack.of("AE2_Printed_Silicon", 1);
        var calc = SimpleStack.of("AE2_Printed_Calculation_Processor", 1);
        var redstone = SimpleStack.of("Ingredient_Crystal_Red", 1);
        assertEquals("calc_processor", recipes.findRecipe(redstone, calc, silicon).id());
        assertEquals("calc_processor", recipes.findRecipe(redstone, silicon, calc).id(), "plates may be flipped");
        assertNull(recipes.findRecipe(redstone, calc, null));
        // inscribe recipe with the press in the bottom slot works too
        var press = SimpleStack.of("AE2_Calculation_Processor_Press", 1);
        assertEquals("calc_print",
                recipes.findRecipe(SimpleStack.of("AE2_Certus_Quartz_Crystal", 1), null, press).id());
        // middle quantity 9 is required for the bridge recipe
        assertNull(recipes.findRecipe(SimpleStack.of("Ingredient_Bar_Iron", 8), press, null));
        assertNotNull(recipes.findRecipe(SimpleStack.of("Ingredient_Bar_Iron", 9), press, null));

        assertTrue(recipes.isValidOptionalIngredient(press));
        assertFalse(recipes.isValidOptionalIngredient(redstone));
        // slot filter: with a press on top, only matching middles are accepted
        assertTrue(recipes.allowInsert(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("AE2_Certus_Quartz_Crystal", 1),
                press, null, null));
        assertFalse(recipes.allowInsert(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("Rock_Stone", 1), press, null,
                null));
        assertTrue(recipes.allowInsert(InscriberLogic.SLOT_BOTTOM, silicon, calc, null, null));
        assertFalse(recipes.allowInsert(InscriberLogic.SLOT_BOTTOM, press, calc, null, null));
    }

    @Test
    void pressConsumesPlatesAndTakes109InscriberTicksWithoutCards() {
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        inv.set(InscriberLogic.SLOT_TOP, SimpleStack.of("AE2_Printed_Calculation_Processor", 2));
        inv.set(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("Ingredient_Crystal_Red", 2));
        inv.set(InscriberLogic.SLOT_BOTTOM, SimpleStack.of("AE2_Printed_Silicon", 2));
        logic.onInventoryChanged();

        double before = cell.getAECurrentPower();
        int ticks = 0;
        while (inv.get(InscriberLogic.SLOT_OUTPUT) == null && ticks < 500) {
            logic.tickingRequest(node(), 1);
            ticks++;
            if (ticks == 50) {
                assertTrue(logic.isWorking());
            }
        }
        // 101 processing ticks (2 progress each, must exceed 200) + 8 smash ticks
        assertEquals(109, ticks);
        assertEquals(SimpleStack.of("AE2_Calculation_Processor", 1), inv.get(InscriberLogic.SLOT_OUTPUT));
        // press mode consumes one of each
        assertEquals(1, inv.get(InscriberLogic.SLOT_TOP).count());
        assertEquals(1, inv.get(InscriberLogic.SLOT_MIDDLE).count());
        assertEquals(1, inv.get(InscriberLogic.SLOT_BOTTOM).count());
        // 101 ticks * 10 * speedFactor(2) AE
        assertEquals(2020, before - cell.getAECurrentPower(), 1e-6);

        // finish smash animation (16 smash steps in total), then the second item
        int more = 0;
        while (inv.get(InscriberLogic.SLOT_OUTPUT).count() < 2 && more < 500) {
            logic.tickingRequest(node(), 1);
            more++;
        }
        assertEquals(117, more, "full cycle = 101 + 16 ticks");
        assertNull(inv.get(InscriberLogic.SLOT_TOP));
        assertNull(inv.get(InscriberLogic.SLOT_MIDDLE));
        assertNull(inv.get(InscriberLogic.SLOT_BOTTOM));
    }

    @Test
    void inscribeKeepsPressAndSpeedCardsAccelerate() {
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        for (int i = 0; i < 4; i++) {
            assertTrue(logic.getUpgrades().insert(UpgradeCard.SPEED));
        }
        assertFalse(logic.getUpgrades().insert(UpgradeCard.SPEED), "max 4 speed cards");
        inv.set(InscriberLogic.SLOT_TOP, SimpleStack.of("AE2_Calculation_Processor_Press", 1));
        inv.set(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("AE2_Certus_Quartz_Crystal", 3));
        logic.onInventoryChanged();

        double before = cell.getAECurrentPower();
        int ticks = 0;
        while (inv.get(InscriberLogic.SLOT_OUTPUT) == null && ticks < 500) {
            logic.tickingRequest(node(), 1);
            ticks++;
        }
        // speed factor 50: 5 ticks to exceed 200, + 8 smash ticks
        assertEquals(13, ticks);
        assertEquals(5 * 500, before - cell.getAECurrentPower(), 1e-6, "5 ticks * 10 * 50 AE");
        assertEquals(1, inv.get(InscriberLogic.SLOT_TOP).count(), "inscribe keeps the press");
        assertEquals(2, inv.get(InscriberLogic.SLOT_MIDDLE).count());
    }

    @Test
    void middleQuantityIsConsumed() {
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        inv.set(InscriberLogic.SLOT_TOP, SimpleStack.of("AE2_Calculation_Processor_Press", 1));
        inv.set(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("Ingredient_Bar_Iron", 10));
        logic.onInventoryChanged();
        for (int i = 0; i < 120; i++) {
            logic.tickingRequest(node(), 1);
        }
        assertEquals(1, inv.get(InscriberLogic.SLOT_MIDDLE).count());
        assertEquals(SimpleStack.of("AE2_Calculation_Processor_Press", 1), inv.get(InscriberLogic.SLOT_OUTPUT));
    }

    @Test
    void worksThroughWorldTickingAndStopsWhenOutputBlocked() {
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        world.tick(); // inscriber has no work: sleeps
        assertTrue(world.getNode(1, 0, 0).getGrid().getTickManager().isSleeping(node()));

        inv.set(InscriberLogic.SLOT_TOP, SimpleStack.of("AE2_Calculation_Processor_Press", 1));
        inv.set(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("AE2_Certus_Quartz_Crystal", 64));
        logic.onInventoryChanged();
        world.tick(200);
        assertNotNull(inv.get(InscriberLogic.SLOT_OUTPUT));

        // output slot blocked by another item: no progress, no power use
        inv.set(InscriberLogic.SLOT_OUTPUT, SimpleStack.of("Rock_Stone", 64));
        world.tick(20);
        double before = cell.getAECurrentPower();
        int progress = logic.getProcessingTime();
        world.tick(100);
        assertEquals(before, cell.getAECurrentPower(), 1e-9);
        assertEquals(progress, logic.getProcessingTime());
        assertEquals(SimpleStack.of("Rock_Stone", 64), inv.get(InscriberLogic.SLOT_OUTPUT));
    }

    @Test
    void changingInputTypeResetsProgress() {
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        inv.set(InscriberLogic.SLOT_TOP, SimpleStack.of("AE2_Calculation_Processor_Press", 1));
        inv.set(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("AE2_Certus_Quartz_Crystal", 5));
        logic.onInventoryChanged();
        for (int i = 0; i < 20; i++) {
            logic.tickingRequest(node(), 1);
        }
        assertEquals(40, logic.getProcessingTime());
        // only count changed: keep progress
        inv.set(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("AE2_Certus_Quartz_Crystal", 4));
        logic.onInventoryChanged();
        assertEquals(40, logic.getProcessingTime());
        // type changed: reset
        inv.set(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("Ingredient_Bar_Iron", 9));
        logic.onInventoryChanged();
        assertEquals(0, logic.getProcessingTime());
    }

    @Test
    void crankPowersInscriberWithoutGridEnergy() {
        // separate world without energy cell
        var w = new MEWorld();
        var inv = new SimpleSlotAccess(4);
        var logic = new InscriberLogic(inv, recipes);
        var node = w.addNode(BlockPos.asLong(0, 0, 0), NodeType.INSCRIBER, Direction.EAST, null, logic);
        inv.set(InscriberLogic.SLOT_TOP, SimpleStack.of("AE2_Calculation_Processor_Press", 1));
        inv.set(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("AE2_Certus_Quartz_Crystal", 1));
        logic.onInventoryChanged();

        logic.tickingRequest(node, 1);
        assertEquals(0, logic.getProcessingTime(), "no power, no progress");

        var crank = new CrankLogic();
        var target = logic.getCrankable();
        int turns = 0;
        while (inv.get(InscriberLogic.SLOT_OUTPUT) == null && turns < 100) {
            if (crank.power(target)) {
                turns++;
            }
            for (int i = 0; i < CrankLogic.TICKS_PER_ROTATION; i++) {
                crank.tick(target);
                logic.tickingRequest(node, 1);
            }
        }
        assertNotNull(inv.get(InscriberLogic.SLOT_OUTPUT));
        // 2020 AE needed, 160 per turn
        assertTrue(turns >= 13, "needs at least 13 crank turns, was " + turns);
    }

    @Test
    void saveAndLoadState() {
        var inv = new SimpleSlotAccess(4);
        var logic = new InscriberLogic(inv, recipes);
        logic.getUpgrades().insert(UpgradeCard.SPEED);
        logic.getBuffer().setAECurrentPower(320);
        logic.setInputCapacity(4);
        var json = logic.saveState();
        var copy = new InscriberLogic(new SimpleSlotAccess(4), recipes);
        copy.loadState(json);
        assertEquals(1, copy.getUpgrades().getInstalledUpgrades(UpgradeCard.SPEED));
        assertEquals(320, copy.getBuffer().getAECurrentPower());
        assertEquals(4, copy.getSlotLimit(InscriberLogic.SLOT_MIDDLE));
        assertEquals(64, copy.getSlotLimit(InscriberLogic.SLOT_OUTPUT));
        assertFalse(copy.isSeparateSides());
        assertFalse(copy.isAutoExport());

        logic.setSeparateSides(true);
        logic.setAutoExport(true);
        copy.loadState(logic.saveState());
        assertTrue(copy.isSeparateSides());
        assertTrue(copy.isAutoExport());
        assertEquals(4, copy.getInputCapacity());
    }

    @Test
    void automationSeesAllSlotsFromEverySideByDefault() {
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        var press = AEItemKey.of("AE2_Calculation_Processor_Press");
        var crystal = AEItemKey.of("AE2_Certus_Quartz_Crystal");
        for (var side : InscriberLogic.Side.values()) {
            for (int slot = 0; slot < 4; slot++) {
                assertTrue(logic.isExposed(side, slot));
            }
        }
        var view = logic.getExposedInventory(InscriberLogic.Side.TOP);
        assertEquals(4, view.size());
        // only recipe ingredients are accepted, each in a slot it fits
        assertEquals(0, SlotInventories.insert(view, AEItemKey.of("Rock_Stone"), 5, false));
        assertEquals(1, SlotInventories.insert(view, press, 1, false));
        assertEquals(SimpleStack.of("AE2_Calculation_Processor_Press", 1), inv.get(InscriberLogic.SLOT_TOP));
        assertEquals(8, SlotInventories.insert(view, crystal, 8, false));
        assertEquals(8, inv.get(InscriberLogic.SLOT_MIDDLE).count());
        // never into the output slot, not even onto a matching stack
        inv.set(InscriberLogic.SLOT_OUTPUT, SimpleStack.of("AE2_Printed_Calculation_Processor", 3));
        assertEquals(0, SlotInventories.insertIntoSlot(view, InscriberLogic.SLOT_OUTPUT,
                AEItemKey.of("AE2_Printed_Calculation_Processor"), 1, false));
        // combined mode: only the output can be extracted
        assertTrue(logic.canExtract(InscriberLogic.Side.SIDE, InscriberLogic.SLOT_OUTPUT));
        assertFalse(logic.canExtract(InscriberLogic.Side.TOP, InscriberLogic.SLOT_TOP));
        assertFalse(logic.canExtract(InscriberLogic.Side.SIDE, InscriberLogic.SLOT_MIDDLE));
        assertFalse(logic.canExtract(InscriberLogic.Side.BOTTOM, InscriberLogic.SLOT_BOTTOM));
    }

    @Test
    void separateSidesExposePlatesOnlyFromAboveAndBelow() {
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        logic.setSeparateSides(true);
        var top = logic.getExposedInventory(InscriberLogic.Side.TOP);
        var bottom = logic.getExposedInventory(InscriberLogic.Side.BOTTOM);
        var side = logic.getExposedInventory(InscriberLogic.Side.SIDE);
        var calc = AEItemKey.of("AE2_Printed_Calculation_Processor");
        var silicon = AEItemKey.of("AE2_Printed_Silicon");
        var redstone = AEItemKey.of("Ingredient_Crystal_Red");

        // a plate offered at the side is refused, the same plate goes in from above
        assertEquals(0, SlotInventories.insert(side, calc, 1, false));
        assertEquals(1, SlotInventories.insert(top, calc, 1, false));
        assertEquals(1, SlotInventories.insert(bottom, silicon, 1, false));
        assertEquals(SimpleStack.of("AE2_Printed_Calculation_Processor", 1), inv.get(InscriberLogic.SLOT_TOP));
        assertEquals(SimpleStack.of("AE2_Printed_Silicon", 1), inv.get(InscriberLogic.SLOT_BOTTOM));
        // the middle input only from the sides
        assertEquals(0, SlotInventories.insert(top, redstone, 1, false));
        assertEquals(2, SlotInventories.insert(side, redstone, 2, false));
        assertEquals(2, inv.get(InscriberLogic.SLOT_MIDDLE).count());
        // each side only sees its own slots
        assertNull(side.get(InscriberLogic.SLOT_TOP));
        assertNull(top.get(InscriberLogic.SLOT_MIDDLE));
        assertNotNull(top.get(InscriberLogic.SLOT_TOP));
        assertNotNull(side.get(InscriberLogic.SLOT_MIDDLE));
        // plates can be taken out again from their side, the middle input never
        assertTrue(logic.canExtract(InscriberLogic.Side.TOP, InscriberLogic.SLOT_TOP));
        assertTrue(logic.canExtract(InscriberLogic.Side.BOTTOM, InscriberLogic.SLOT_BOTTOM));
        assertFalse(logic.canExtract(InscriberLogic.Side.SIDE, InscriberLogic.SLOT_TOP));
        assertFalse(logic.canExtract(InscriberLogic.Side.TOP, InscriberLogic.SLOT_OUTPUT));
        assertFalse(logic.canExtract(InscriberLogic.Side.SIDE, InscriberLogic.SLOT_MIDDLE));
        assertTrue(logic.canExtract(InscriberLogic.Side.SIDE, InscriberLogic.SLOT_OUTPUT));

        // while the press comes down nothing goes in and the plates stay
        while (!logic.isSmash()) {
            logic.tickingRequest(node(), 1);
        }
        assertEquals(0, SlotInventories.insert(side, redstone, 1, false));
        assertEquals(0, SlotInventories.insert(top, calc, 1, true));
        assertFalse(logic.canExtract(InscriberLogic.Side.TOP, InscriberLogic.SLOT_TOP));
        assertTrue(logic.canExtract(InscriberLogic.Side.SIDE, InscriberLogic.SLOT_OUTPUT));
    }

    @Test
    void inputCapacityLimitsWhatAutomationInserts() {
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        inv.set(InscriberLogic.SLOT_TOP, SimpleStack.of("AE2_Calculation_Processor_Press", 1));
        var view = logic.getExposedInventory(InscriberLogic.Side.SIDE);
        var crystal = AEItemKey.of("AE2_Certus_Quartz_Crystal");
        logic.setInputCapacity(4);
        assertEquals(4, SlotInventories.insert(view, crystal, 10, true));
        assertEquals(4, SlotInventories.insert(view, crystal, 10, false));
        assertEquals(0, SlotInventories.insert(view, crystal, 10, false), "slot is at its capacity");
        logic.setInputCapacity(1);
        assertEquals(0, SlotInventories.insert(view, crystal, 1, false));
        logic.setInputCapacity(64);
        assertEquals(60, SlotInventories.insert(view, crystal, 100, false));
        assertEquals(64, inv.get(InscriberLogic.SLOT_MIDDLE).count());
    }

    @Test
    void autoExportPushesTheOutputIntoAnAdjacentInventory() {
        var inv = new SimpleSlotAccess(4);
        var logic = placeInscriber(inv);
        var chest = new SimpleSlotAccess(1);
        boolean[] askedSidesOnly = {false};
        logic.setExportTargets(sidesOnly -> {
            askedSidesOnly[0] = sidesOnly;
            return java.util.List.of(chest);
        });
        inv.set(InscriberLogic.SLOT_TOP, SimpleStack.of("AE2_Calculation_Processor_Press", 1));
        inv.set(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("AE2_Certus_Quartz_Crystal", 2));
        logic.onInventoryChanged();

        // off: the output stays in the inscriber and it goes to sleep when the inputs are used up
        world.tick(400);
        assertEquals(SimpleStack.of("AE2_Printed_Calculation_Processor", 2), inv.get(InscriberLogic.SLOT_OUTPUT));
        assertNull(chest.get(0));
        assertTrue(node().getGrid().getTickManager().isSleeping(node()));

        // switching it on wakes the inscriber and moves the output
        logic.setAutoExport(true);
        world.tick(40);
        assertNull(inv.get(InscriberLogic.SLOT_OUTPUT));
        assertEquals(SimpleStack.of("AE2_Printed_Calculation_Processor", 2), chest.get(0));
        assertFalse(askedSidesOnly[0]);
        assertTrue(node().getGrid().getTickManager().isSleeping(node()), "nothing left to do");

        // a full target keeps the output where it is; nothing is lost or duplicated
        chest.set(0, SimpleStack.of("Rock_Stone", 64));
        logic.setSeparateSides(true);
        inv.set(InscriberLogic.SLOT_MIDDLE, SimpleStack.of("AE2_Certus_Quartz_Crystal", 1));
        logic.onInventoryChanged();
        world.tick(400);
        assertEquals(SimpleStack.of("AE2_Printed_Calculation_Processor", 1), inv.get(InscriberLogic.SLOT_OUTPUT));
        assertEquals(SimpleStack.of("Rock_Stone", 64), chest.get(0));
        assertTrue(askedSidesOnly[0], "separate sides: not above and below");
        // room again: only what fits moves
        chest.set(0, SimpleStack.of("AE2_Printed_Calculation_Processor", 64));
        world.tick(40);
        assertEquals(1, inv.get(InscriberLogic.SLOT_OUTPUT).count());
        chest.set(0, null);
        world.tick(40);
        assertNull(inv.get(InscriberLogic.SLOT_OUTPUT));
        assertEquals(1, chest.get(0).count());
    }
}
