/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.storage;

import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.energy.CreativeEnergyCellLogic;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When {@link StorageService} rebuilds its snapshot for watchers: at once after a change through the network,
 * otherwise only now and then.
 */
class StorageWatcherRefreshTest {
    private static final AEItemKey COBBLE = AEItemKey.of("Rock_Stone_Cobble");
    private static final AEItemKey DIRT = AEItemKey.of("Soil_Dirt");
    private static final IActionSource SRC = IActionSource.empty();

    private MEWorld world;
    private CellHostLogic drive;
    private StorageService storage;
    private final Map<AEKey, Long> seen = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
        world.addNode(BlockPos.asLong(0, 0, 0), NodeType.CREATIVE_ENERGY_CELL, null, null,
                new CreativeEnergyCellLogic());
        drive = CellHostLogic.drive();
        world.addNode(BlockPos.asLong(1, 0, 0), NodeType.DRIVE, Direction.NORTH, null, drive);
        drive.setCell(0, StorageCells.getCellInventory("AE2_Item_Storage_Cell_1k", new CellItemData(), null));
        world.tick();
        storage = world.getNode(BlockPos.asLong(0, 0, 0)).getGrid().getStorageService();
        storage.createWatcher(new IStorageWatcherNode() {
            @Override
            public void updateWatcher(IStackWatcher w) {
                w.setWatchAll(true);
            }

            @Override
            public void onStackChange(AEKey what, long amount) {
                seen.put(what, amount);
            }
        });
        world.tick(2);
        seen.clear();
    }

    @Test
    void idleNetworkIsNotRescannedEveryTick() {
        long before = storage.getCacheUpdateCount();
        world.tick(StorageService.WATCHER_REFRESH_TICKS * 5);
        long rebuilds = storage.getCacheUpdateCount() - before;
        assertTrue(rebuilds >= 4 && rebuilds <= 6, "about one rebuild per refresh interval, was " + rebuilds);
        assertTrue(seen.isEmpty());
    }

    @Test
    void changesThroughTheNetworkAreReportedInTheSameTick() {
        long before = storage.getCacheUpdateCount();
        storage.getInventory().insert(COBBLE, 10, Actionable.MODULATE, SRC);
        world.tick();
        assertEquals(Map.of(COBBLE, 10L), seen);
        assertEquals(before + 1, storage.getCacheUpdateCount());

        seen.clear();
        storage.getInventory().extract(COBBLE, 4, Actionable.MODULATE, SRC);
        world.tick();
        assertEquals(Map.of(COBBLE, 6L), seen);

        // simulated operations change nothing and trigger nothing
        seen.clear();
        long idle = storage.getCacheUpdateCount();
        storage.getInventory().insert(DIRT, 10, Actionable.SIMULATE, SRC);
        storage.getInventory().extract(COBBLE, 1, Actionable.SIMULATE, SRC);
        world.tick();
        assertTrue(seen.isEmpty());
        assertEquals(idle, storage.getCacheUpdateCount());
    }

    @Test
    void changesBehindTheNetworkAreReportedWithTheNextRegularRebuild() {
        // straight into the cell, not through the network storage
        drive.getCell(0).insert(DIRT, 7, Actionable.MODULATE, SRC);
        int ticks = 0;
        while (seen.isEmpty() && ticks < 100) {
            world.tick();
            ticks++;
        }
        assertEquals(Map.of(DIRT, 7L), seen);
        assertTrue(ticks <= StorageService.WATCHER_REFRESH_TICKS, "within one refresh interval, took " + ticks);
    }

    @Test
    void readingTheSnapshotAlwaysGivesTheCurrentContent() {
        world.tick(); // nothing changed: the snapshot was not rebuilt
        drive.getCell(0).insert(DIRT, 3, Actionable.MODULATE, SRC);
        world.tick();
        assertEquals(3, storage.getCachedInventory().get(DIRT), "a reader gets a fresh snapshot");
        assertEquals(Map.of(DIRT, 3L), seen, "and the watchers hear about it with that rebuild");
    }

    @Test
    void invalidatingForcesTheRebuildAtTheEndOfTheTick() {
        drive.getCell(0).insert(DIRT, 5, Actionable.MODULATE, SRC);
        storage.invalidateCache();
        world.tick();
        assertEquals(Map.of(DIRT, 5L), seen);
    }

    @Test
    void swappingACellIsReportedInTheSameTick() {
        storage.getInventory().insert(COBBLE, 10, Actionable.MODULATE, SRC);
        world.tick();
        seen.clear();
        drive.setCell(0, null);
        world.tick();
        assertEquals(Map.of(COBBLE, 0L), seen, "the cell with the cobble is gone");
    }
}
