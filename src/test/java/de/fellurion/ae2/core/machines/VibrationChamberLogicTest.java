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
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.inv.SimpleSlotAccess;
import de.fellurion.ae2.core.inv.SimpleStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VibrationChamberLogicTest {
    /** Minecraft furnace semantics: coal burns 1600 fuel ticks. */
    private static int burnTime(SimpleStack s) {
        return s.is("Ingredient_Coal") ? 1600 : 0;
    }

    private MEWorld world;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
    }

    private VibrationChamberLogic place(SimpleSlotAccess inv, boolean withCell) {
        if (withCell) {
            world.addNode(BlockPos.asLong(0, 0, 0), NodeType.DENSE_ENERGY_CELL, null, null,
                    EnergyCellLogic.denseEnergyCell());
        }
        var logic = new VibrationChamberLogic(inv, VibrationChamberLogicTest::burnTime, null);
        world.addNode(BlockPos.asLong(1, 0, 0), NodeType.VIBRATION_CHAMBER, null, null, logic);
        return logic;
    }

    @Test
    void initialRatesFromConfig() {
        var logic = new VibrationChamberLogic(new SimpleSlotAccess(1), VibrationChamberLogicTest::burnTime, null);
        assertEquals(5.0, logic.getEnergyPerFuelTick(), 1e-9);
        assertEquals(1.0, logic.getCurrentFuelTicksPerTick(), 1e-9, "starts at 5 AE/t");
        assertEquals(0.8, logic.getMinFuelTicksPerTick(), 1e-9, "min 4 AE/t");
        assertEquals(8.0, logic.getMaxFuelTicksPerTick(), 1e-9, "max 40 AE/t");
        logic.getUpgrades().insert(UpgradeCard.ENERGY);
        logic.getUpgrades().insert(UpgradeCard.SPEED);
        assertEquals(7.5, logic.getEnergyPerFuelTick(), 1e-9);
        assertEquals(60.0, logic.getMaxEnergyRate(), 1e-9);
        assertFalse(logic.isItemValid(SimpleStack.of("Rock_Stone", 1)));
        assertTrue(logic.isItemValid(SimpleStack.of("Ingredient_Coal", 1)));
    }

    @Test
    void burnsOneCoalInto8000AeAndSpeedsUp() {
        var inv = new SimpleSlotAccess(1);
        inv.set(0, SimpleStack.of("Ingredient_Coal", 2));
        var logic = place(inv, true);
        var energy = world.getNode(1, 0, 0).getGrid().getEnergyService();
        assertEquals(1, inv.get(0).count(), "first coal eaten when joining the grid");
        assertTrue(logic.isOn());

        double maxRateSeen = 0;
        int ticks = 0;
        while (logic.getRemainingFuelTicks() > 0 && ticks < 10_000) {
            world.tick();
            ticks++;
            maxRateSeen = Math.max(maxRateSeen, logic.getCurrentEnergyRate());
        }
        // all 1600 fuel ticks * 5 AE went into the network (nothing overflowed)
        assertEquals(8000, energy.getStoredPower(), 1e-6);
        assertEquals(40.0, maxRateSeen, 1e-9, "accelerates to the max of 40 AE/t");
        // burning at up to 40 AE/t is much faster than 1600 ticks
        assertTrue(ticks < 600, "took " + ticks + " ticks");

        // second coal is eaten on the next chamber tick
        world.tick(50);
        assertNull(inv.get(0));
        assertTrue(logic.getRemainingFuelTicks() > 0);
    }

    @Test
    void energyCardIncreasesYield() {
        var inv = new SimpleSlotAccess(1);
        var logic = new VibrationChamberLogic(inv, VibrationChamberLogicTest::burnTime, null);
        logic.getUpgrades().insert(UpgradeCard.ENERGY);
        world.addNode(BlockPos.asLong(0, 0, 0), NodeType.DENSE_ENERGY_CELL, null, null,
                EnergyCellLogic.denseEnergyCell());
        world.addNode(BlockPos.asLong(1, 0, 0), NodeType.VIBRATION_CHAMBER, null, null, logic);
        inv.set(0, SimpleStack.of("Ingredient_Coal", 1));
        logic.onInventoryChanged();
        world.tick(2000);
        var energy = world.getNode(1, 0, 0).getGrid().getEnergyService();
        assertEquals(1600 * 7.5, energy.getStoredPower(), 1e-6);
        assertFalse(logic.isOn());
    }

    @Test
    void slowsDownWhenNetworkIsFull() {
        var inv = new SimpleSlotAccess(1);
        inv.set(0, SimpleStack.of("Ingredient_Coal", 1));
        // no energy cell: only the 25 AE grid buffer, fills immediately
        var logic = place(inv, false);
        world.tick(500);
        assertEquals(4.0, logic.getCurrentEnergyRate(), 1e-9, "throttled to the minimum of 4 AE/t");
        assertTrue(logic.isOn());

        var copy = new VibrationChamberLogic(new SimpleSlotAccess(1), VibrationChamberLogicTest::burnTime, null);
        copy.loadState(logic.saveState());
        assertEquals(logic.getRemainingFuelTicks(), copy.getRemainingFuelTicks(), 1e-9);
        assertEquals(logic.getCurrentFuelTicksPerTick(), copy.getCurrentFuelTicksPerTick(), 0.1);
    }
}
