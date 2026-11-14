/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.energy;

import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.StorageHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Energy drain, power-up buffering, provider priorities and powered storage costs (ports of AE2 EnergyService /
 * StorageHelper behaviour).
 */
class EnergyServiceTest {
    private MEWorld world;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
    }

    static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    /** controller(0,0,0) + energy cell below + drive (1k cell) east + terminal on top. */
    private EnergyCellLogic controllerSetup(double initialEnergy) {
        world.addNode(p(0, 0, 0), NodeType.CONTROLLER, null, null, null);
        var cell = EnergyCellLogic.energyCell();
        cell.setAECurrentPower(initialEnergy);
        world.addNode(p(0, -1, 0), NodeType.ENERGY_CELL, null, null, cell);
        var drive = CellHostLogic.drive();
        world.addNode(p(1, 0, 0), NodeType.DRIVE, Direction.NORTH, null, drive);
        drive.setCell(0, StorageCells.getCellInventory("AE2_Item_Storage_Cell_1k", new CellItemData(), null));
        world.addNode(p(0, 1, 0), NodeType.TERMINAL, Direction.UP, null, null);
        return cell;
    }

    @Test
    void idleDrainMatchesAE2Values() {
        var cell = controllerSetup(10_000);
        world.tick();
        var terminal = world.getNode(p(0, 1, 0));
        var energy = terminal.getGrid().getEnergyService();
        assertTrue(terminal.isActive());
        // controller 3 + drive 2 + 1k cell 0.5 + terminal 0.5 + energy cell 0
        // channels: 2 devices, channelsByBlocks = 4 (device nodes + their connections) → 4/128 AE/t
        assertEquals(6.0 + 4 / 128.0, energy.getIdlePowerUsage(), 1e-9);
        double before = cell.getAECurrentPower();
        world.tick(10);
        assertEquals(10 * energy.getIdlePowerUsage(), before - cell.getAECurrentPower(), 1e-6);
        assertEquals(EnergyCellLogic.getStorageLevelFromFillFactor(10_000 / 200_000.0), cell.getFillLevel());
    }

    @Test
    void cellFillLevels() {
        assertEquals(0, EnergyCellLogic.getStorageLevelFromFillFactor(0));
        assertEquals(0, EnergyCellLogic.getStorageLevelFromFillFactor(0.2));
        assertEquals(1, EnergyCellLogic.getStorageLevelFromFillFactor(0.25));
        assertEquals(3, EnergyCellLogic.getStorageLevelFromFillFactor(0.98));
        assertEquals(4, EnergyCellLogic.getStorageLevelFromFillFactor(0.99)); // AE2 adds 0.01 before flooring
        assertEquals(4, EnergyCellLogic.getStorageLevelFromFillFactor(1.0));
        assertEquals(200_000, EnergyCellLogic.energyCell().getAEMaxPower());
        assertEquals(1_600_000, EnergyCellLogic.denseEnergyCell().getAEMaxPower());
    }

    @Test
    void powerUpIsBufferedFor30TicksButPowerLossIsImmediate() {
        var cell = new EnergyCellLogic(100_000, 800, 200);
        world.addNode(p(0, 0, 0), NodeType.ENERGY_CELL, null, null, cell);
        GridNode terminal = world.addNode(p(0, 1, 0), NodeType.TERMINAL, Direction.UP, null, null);
        world.tick(3);
        assertFalse(terminal.isPowered());

        cell.setAECurrentPower(50_000);
        int ticks = 0;
        while (!terminal.isPowered() && ticks < 100) {
            world.tick();
            ticks++;
        }
        // first tick with power resets the counter, then it must stay powered for more than 30 ticks
        assertEquals(32, ticks);
        assertTrue(terminal.isActive());

        cell.setAECurrentPower(0);
        world.tick();
        assertFalse(terminal.isPowered());
    }

    @Test
    void newGridWithEnergyIsPoweredOnFirstTick() {
        var cell = EnergyCellLogic.energyCell();
        cell.setAECurrentPower(1000);
        world.addNode(p(0, 0, 0), NodeType.ENERGY_CELL, null, null, cell);
        var terminal = world.addNode(p(0, 1, 0), NodeType.TERMINAL, Direction.UP, null, null);
        world.tick();
        assertTrue(terminal.isPowered());
    }

    @Test
    void providersAreDrainedByPriorityAndFilledInReverse() {
        var normal = EnergyCellLogic.energyCell();
        var dense = EnergyCellLogic.denseEnergyCell();
        normal.setAECurrentPower(1000);
        dense.setAECurrentPower(1000);
        world.addNode(p(0, 0, 0), NodeType.ENERGY_CELL, null, null, normal);
        world.addNode(p(1, 0, 0), NodeType.DENSE_ENERGY_CELL, null, null, dense);
        var energy = world.getNode(p(0, 0, 0)).getGrid().getEnergyService();
        // dense cell (priority 1600) is drained before the normal cell (200)
        assertEquals(500, energy.extractAEPower(500, Actionable.MODULATE, PowerMultiplier.ONE), 1e-9);
        assertEquals(500, dense.getAECurrentPower(), 1e-9);
        assertEquals(1000, normal.getAECurrentPower(), 1e-9);
        // injection fills the grid buffer (25 AE per node) first, then the lowest priority cell
        assertEquals(0, energy.injectPower(100, Actionable.MODULATE), 1e-9);
        assertEquals(50, energy.getLocalStorage().getAECurrentPower(), 1e-9);
        assertEquals(1050, normal.getAECurrentPower(), 1e-9);
        assertEquals(500, dense.getAECurrentPower(), 1e-9);
        assertEquals(1050 + 500 + 50, energy.getStoredPower(), 1e-9);
        assertEquals(200_000 + 1_600_000 + 50, energy.getMaxStoredPower(), 1e-9);
    }

    @Test
    void poweredStorageOperationsCostEnergy() {
        var cell = controllerSetup(10_000);
        world.tick();
        var grid = world.getNode(p(0, 0, 0)).getGrid();
        var inv = grid.getStorageService().getInventory();
        var energy = grid.getEnergyService();
        var src = IActionSource.empty();

        double before = cell.getAECurrentPower();
        assertEquals(100, StorageHelper.poweredInsert(energy, inv, AEItemKey.of("Rock_Stone"), 100, src));
        assertEquals(100, before - cell.getAECurrentPower(), 1e-9, "1 AE per item");

        before = cell.getAECurrentPower();
        assertEquals(40, StorageHelper.poweredExtraction(energy, inv, AEItemKey.of("Rock_Stone"), 40, src));
        assertEquals(40, before - cell.getAECurrentPower(), 1e-9);

        // fluids: 1 AE per 125 mB, but the item cell does not accept fluids → nothing inserted, nothing paid
        before = cell.getAECurrentPower();
        assertEquals(0, StorageHelper.poweredInsert(energy, inv, AEFluidKey.of("Water"), 1000, src));
        assertEquals(before, cell.getAECurrentPower(), 1e-9);
    }

    @Test
    void poweredInsertIsLimitedByAvailableEnergy() {
        var cell = new EnergyCellLogic(1_000_000, 800, 200);
        world.addNode(p(0, 0, 0), NodeType.ENERGY_CELL, null, null, cell);
        var drive = CellHostLogic.drive();
        world.addNode(p(1, 0, 0), NodeType.DRIVE, Direction.NORTH, null, drive);
        drive.setCell(0, StorageCells.getCellInventory("AE2_Item_Storage_Cell_1k", new CellItemData(), null));
        cell.setAECurrentPower(1000);
        world.tick();
        var grid = world.getNode(p(0, 0, 0)).getGrid();
        var energy = grid.getEnergyService();
        // drain everything but 10 AE (the grid's own 25 AE/node buffer is empty)
        energy.extractAEPower(energy.getStoredPower() - 10, Actionable.MODULATE, PowerMultiplier.ONE);
        long inserted = StorageHelper.poweredInsert(energy, grid.getStorageService().getInventory(),
                AEItemKey.of("Rock_Stone"), 100, IActionSource.empty());
        assertEquals(10, inserted);
    }
}
