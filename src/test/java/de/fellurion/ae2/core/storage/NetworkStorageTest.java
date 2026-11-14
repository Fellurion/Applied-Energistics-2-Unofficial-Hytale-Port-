/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.storage;

import de.fellurion.ae2.core.cells.CellDefinition;
import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.CellState;
import de.fellurion.ae2.core.cells.StorageCellTier;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.energy.CreativeEnergyCellLogic;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Priority/preferred-storage semantics of {@link NetworkStorage} and the {@link StorageService} lifecycle.
 */
class NetworkStorageTest {
    static final AEItemKey COBBLE = AEItemKey.of("Rock_Stone_Cobble");
    static final AEItemKey DIRT = AEItemKey.of("Soil_Dirt");
    static final IActionSource SRC = IActionSource.empty();

    private MEWorld world;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
    }

    /** Places an ad-hoc network: creative energy at x=0, drives at x=1..n. */
    private List<CellHostLogic> drives(int n) {
        world.addNode(BlockPos.asLong(0, 0, 0), NodeType.CREATIVE_ENERGY_CELL, null, null,
                new CreativeEnergyCellLogic());
        var list = new ArrayList<CellHostLogic>();
        for (int i = 1; i <= n; i++) {
            var drive = CellHostLogic.drive();
            // front faces north, so east/west connect
            world.addNode(BlockPos.asLong(i, 0, 0), NodeType.DRIVE, Direction.NORTH, null, drive);
            drive.setCell(0, StorageCells.getCellInventory("AE2_Item_Storage_Cell_1k", new CellItemData(), null));
            list.add(drive);
        }
        world.tick();
        return list;
    }

    private Grid grid() {
        return world.getNode(BlockPos.asLong(0, 0, 0)).getGrid();
    }

    @Test
    void drivesMountWhenOnline() {
        var d = drives(2);
        var inv = grid().getStorageService().getInventory();
        // 2 drive cells + the crafting service's global storage (CPU result insertion, as in AE2)
        assertEquals(3, ((NetworkStorage) inv).getMountCount());
        assertEquals(2.5, world.getNode(BlockPos.asLong(1, 0, 0)).getIdlePowerUsage(), 1e-9);
        // 2 drives with 1k cells: 2 * 8128 cobble
        assertEquals(2 * 8128, inv.insert(COBBLE, 100_000, Actionable.MODULATE, SRC));
        assertEquals(CellState.FULL, d.get(0).getCellStatus(0));
        assertEquals(CellState.ABSENT, d.get(0).getCellStatus(1));

        // Breaking the energy source takes the network offline → cells unmounted
        world.removeNode(BlockPos.asLong(0, 0, 0));
        world.tick();
        var g = world.getNode(BlockPos.asLong(1, 0, 0)).getGrid();
        assertFalse(world.getNode(BlockPos.asLong(1, 0, 0)).isPowered());
        assertEquals(1, ((NetworkStorage) g.getStorageService().getInventory()).getMountCount());
        assertEquals(0, g.getStorageService().getInventory().insert(COBBLE, 1, Actionable.SIMULATE, SRC));
    }

    @Test
    void insertsIntoHighestPriorityFirstAndExtractsFromLowestFirst() {
        var d = drives(2);
        d.get(0).setPriority(5);
        d.get(1).setPriority(10);
        var inv = grid().getStorageService().getInventory();
        assertEquals(100, inv.insert(COBBLE, 100, Actionable.MODULATE, SRC));
        assertEquals(100, d.get(1).getCell(0).getAvailableStacks().get(COBBLE), "priority 10 drive gets it");
        assertEquals(0, d.get(0).getCell(0).getAvailableStacks().get(COBBLE));

        // Fill the high priority drive, the overflow goes to the low priority one
        inv.insert(COBBLE, 8128, Actionable.MODULATE, SRC);
        assertEquals(8128, d.get(1).getCell(0).getAvailableStacks().get(COBBLE));
        assertEquals(100, d.get(0).getCell(0).getAvailableStacks().get(COBBLE));

        // Extraction drains the lowest priority first
        assertEquals(150, inv.extract(COBBLE, 150, Actionable.MODULATE, SRC));
        assertEquals(0, d.get(0).getCell(0).getAvailableStacks().get(COBBLE));
        assertEquals(8128 - 50, d.get(1).getCell(0).getAvailableStacks().get(COBBLE));
    }

    @Test
    void storageThatAlreadyContainsTheKeyIsPreferredWithinSamePriority() {
        var d = drives(2);
        // put dirt directly into the second drive's cell
        d.get(1).getCell(0).insert(DIRT, 1, Actionable.MODULATE, SRC);
        var inv = grid().getStorageService().getInventory();
        inv.insert(DIRT, 50, Actionable.MODULATE, SRC);
        assertEquals(51, d.get(1).getCell(0).getAvailableStacks().get(DIRT));
        assertEquals(0, d.get(0).getCell(0).getAvailableStacks().get(DIRT));
        // without preference: first mounted (first drive) gets it
        inv.insert(COBBLE, 10, Actionable.MODULATE, SRC);
        assertEquals(10, d.get(0).getCell(0).getAvailableStacks().get(COBBLE));
    }

    @Test
    void partitionedCellsReceiveOnlyTheirItems() {
        var d = drives(2);
        var partitioned = new CellItemData().setConfig(List.of(DIRT));
        d.get(1).setCell(0, StorageCells.getCellInventory("AE2_Item_Storage_Cell_1k", partitioned, null));
        var inv = grid().getStorageService().getInventory();
        inv.insert(COBBLE, 10, Actionable.MODULATE, SRC);
        inv.insert(DIRT, 10, Actionable.MODULATE, SRC);
        assertEquals(0, d.get(1).getCell(0).getAvailableStacks().get(COBBLE));
        // the partitioned cell does not count as "preferred" (as in AE2: drives wrap cells without a partition list),
        // so the first drive gets the dirt
        assertEquals(10, d.get(0).getCell(0).getAvailableStacks().get(DIRT));
    }

    @Test
    void watchersReceiveChangesAtEndOfTick() {
        drives(1);
        var storage = grid().getStorageService();
        Map<AEKey, Long> seen = new LinkedHashMap<>();
        var watcher = storage.createWatcher(new IStorageWatcherNode() {
            @Override
            public void updateWatcher(IStackWatcher w) {
                w.setWatchAll(true);
            }

            @Override
            public void onStackChange(AEKey what, long amount) {
                seen.put(what, amount);
            }
        });
        storage.getInventory().insert(COBBLE, 64, Actionable.MODULATE, SRC);
        assertTrue(seen.isEmpty());
        world.tick();
        assertEquals(Map.of(COBBLE, 64L), seen);
        seen.clear();
        storage.getInventory().extract(COBBLE, 64, Actionable.MODULATE, SRC);
        world.tick();
        assertEquals(Map.of(COBBLE, 0L), seen);
        assertEquals(0, storage.getCachedInventory().get(COBBLE));
        watcher.destroy();
        storage.getInventory().insert(COBBLE, 1, Actionable.MODULATE, SRC);
        seen.clear();
        world.tick();
        assertTrue(seen.isEmpty());
        // cache is rebuilt lazily without watchers
        assertEquals(1, storage.getCachedInventory().get(COBBLE));
    }

    @Test
    void cellDataIsPersistedWhenDriveIsRemoved() {
        var data = new CellItemData();
        var drive = CellHostLogic.drive();
        world.addNode(BlockPos.asLong(0, 0, 0), NodeType.CREATIVE_ENERGY_CELL, null, null,
                new CreativeEnergyCellLogic());
        world.addNode(BlockPos.asLong(1, 0, 0), NodeType.DRIVE, Direction.NORTH, null, drive);
        var saves = new int[1];
        drive.setCell(0, StorageCells.getCellInventory(CellDefinition.itemCell(StorageCellTier.SIZE_4K).itemId(), data,
                () -> saves[0]++));
        world.tick();
        grid().getStorageService().getInventory().insert(COBBLE, 1000, Actionable.MODULATE, SRC);
        assertTrue(saves[0] > 0);
        world.removeNode(BlockPos.asLong(1, 0, 0));
        assertEquals(1000L, data.getContents().get(COBBLE));
    }
}
