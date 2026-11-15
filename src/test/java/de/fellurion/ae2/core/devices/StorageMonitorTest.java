/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.devices;

import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.energy.CreativeEnergyCellLogic;
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.CableType;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IActionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Storage monitor and conversion monitor (ports of AE2 AbstractMonitorPart / ConversionMonitorPart). */
class StorageMonitorTest {
    private static final AEItemKey STONE = AEItemKey.of("Rock_Stone");
    private static final AEItemKey DIRT = AEItemKey.of("Soil_Dirt");
    private static final IActionSource PLAYER = IActionSource.ofPlayer("tester");

    private MEWorld world;
    private StorageMonitorLogic monitor;
    private GridNode node;
    private int changes;
    private int saves;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
        changes = 0;
        saves = 0;
    }

    private static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    /** Energy source at (-1,0,0), cable at (0,0,0), drive with a 4k cell below it, monitor above it. */
    private void network(Object energy, NodeType energyType, NodeType monitorType) {
        world.addNode(p(-1, 0, 0), energyType, null, null, energy);
        world.addNode(p(0, 0, 0), NodeType.cable(CableType.GLASS), null, null, null);
        var drive = CellHostLogic.drive();
        world.addNode(p(0, -1, 0), NodeType.DRIVE, null, null, drive);
        drive.setCell(0, StorageCells.getCellInventory("AE2_Item_Storage_Cell_4k", new CellItemData(), null));
        monitor = new StorageMonitorLogic();
        monitor.setChangeListener(() -> changes++);
        monitor.setSaveListener(() -> saves++);
        node = world.addNode(p(0, 1, 0), monitorType, Direction.UP, null, monitor);
        world.tick();
        assertTrue(node.isActive());
    }

    private void network(NodeType monitorType) {
        network(new CreativeEnergyCellLogic(), NodeType.CREATIVE_ENERGY_CELL, monitorType);
    }

    private long put(AEKey key, long amount) {
        return node.getGrid().getStorageService().getInventory().insert(key, amount, Actionable.MODULATE,
                IActionSource.empty());
    }

    private long take(AEKey key, long amount) {
        return node.getGrid().getStorageService().getInventory().extract(key, amount, Actionable.MODULATE,
                IActionSource.empty());
    }

    @Test
    void nodeTypesMatchAE2() {
        assertFalse(NodeType.STORAGE_MONITOR.requiresChannel(), "storage monitor: no channel");
        assertEquals(1.0 / 16.0, NodeType.STORAGE_MONITOR.idlePower());
        assertTrue(NodeType.CONVERSION_MONITOR.requiresChannel(), "conversion monitor: channel");
        assertEquals(0.5, NodeType.CONVERSION_MONITOR.idlePower());
    }

    @Test
    void showsTheAmountOfItsItemAndFollowsTheNetwork() {
        network(NodeType.STORAGE_MONITOR);
        put(STONE, 150);
        put(DIRT, 7);
        world.tick();
        assertNull(monitor.getConfiguredItem());
        assertEquals(0, monitor.getAmount());

        assertTrue(monitor.configure(STONE));
        assertEquals(STONE, monitor.getConfiguredItem());
        assertEquals(150, monitor.getAmount(), "read when the item is set");
        int before = changes;

        put(STONE, 50);
        world.tick();
        assertEquals(200, monitor.getAmount());
        assertEquals(before + 1, changes);

        put(DIRT, 50);
        world.tick();
        assertEquals(before + 1, changes, "other items are not watched");

        take(STONE, 200);
        world.tick();
        assertEquals(0, monitor.getAmount(), "gone");

        assertTrue(monitor.configure(DIRT));
        assertEquals(57, monitor.getAmount());
        assertTrue(monitor.configure(null), "empty hand clears");
        assertNull(monitor.getConfiguredItem());
        assertEquals(0, monitor.getAmount());
        put(DIRT, 1);
        world.tick();
        assertEquals(0, monitor.getAmount());
    }

    @Test
    void lockedMonitorKeepsItsItem() {
        network(NodeType.STORAGE_MONITOR);
        assertTrue(monitor.configure(STONE));
        assertTrue(monitor.toggleLocked());
        assertTrue(monitor.isLocked());
        assertFalse(monitor.configure(DIRT), "locked");
        assertFalse(monitor.configure(null), "locked");
        assertEquals(STONE, monitor.getConfiguredItem());
        // the amount still follows
        put(STONE, 12);
        world.tick();
        assertEquals(12, monitor.getAmount());
        assertTrue(monitor.toggleLocked());
        assertFalse(monitor.isLocked());
        assertTrue(monitor.configure(DIRT));
    }

    @Test
    void inactiveMonitorIgnoresThePlayer() {
        // no energy at all: the node never becomes active
        world.addNode(p(0, 0, 0), NodeType.cable(CableType.GLASS), null, null, null);
        monitor = new StorageMonitorLogic();
        node = world.addNode(p(0, 1, 0), NodeType.STORAGE_MONITOR, Direction.UP, null, monitor);
        world.tick(40);
        assertFalse(monitor.isActive());
        assertFalse(monitor.configure(STONE));
        assertFalse(monitor.toggleLocked());
        assertNull(monitor.getConfiguredItem());
        assertFalse(monitor.isLocked());
        assertEquals(0, monitor.insert(STONE, 10, PLAYER));
        assertEquals(0, monitor.extract(10, PLAYER));
    }

    @Test
    void conversionMonitorMovesItemsWithEnergyCost() {
        var cell = EnergyCellLogic.energyCell();
        cell.setAECurrentPower(1_000);
        network(cell, NodeType.ENERGY_CELL, NodeType.CONVERSION_MONITOR);
        assertTrue(monitor.configure(STONE));

        double before = node.getGrid().getEnergyService().getStoredPower();
        assertEquals(64, monitor.insert(STONE, 64, PLAYER));
        double after = node.getGrid().getEnergyService().getStoredPower();
        assertEquals(64, before - after, 1e-6, "1 AE per item");
        world.tick();
        assertEquals(64, monitor.getAmount());

        assertEquals(10, monitor.extract(10, PLAYER));
        assertEquals(54, monitor.extract(100, PLAYER), "only what is there");
        assertEquals(0, monitor.extract(1, PLAYER));
        world.tick();
        assertEquals(0, monitor.getAmount());

        // any item can be inserted (AE2: a locked monitor takes whatever is held)
        assertEquals(5, monitor.insert(DIRT, 5, PLAYER));
        assertEquals(0, monitor.insert(STONE, 0, PLAYER));
        assertEquals(0, monitor.insert(null, 5, PLAYER));
        monitor.setConfiguredItem(null);
        assertEquals(0, monitor.extract(5, PLAYER), "nothing configured: nothing to take");
    }

    @Test
    void conversionMonitorWithoutEnergyMovesNothing() {
        var cell = EnergyCellLogic.energyCell();
        cell.setAECurrentPower(1_000);
        network(cell, NodeType.ENERGY_CELL, NodeType.CONVERSION_MONITOR);
        monitor.configure(STONE);
        put(STONE, 500);
        world.tick();
        cell.setAECurrentPower(0);
        for (int i = 0; i < 200 && node.isPowered(); i++) {
            world.tick();
        }
        assertFalse(monitor.isActive());
        assertEquals(0, monitor.extract(64, PLAYER));
        assertEquals(0, monitor.insert(STONE, 64, PLAYER));

        cell.setAECurrentPower(1_000);
        for (int i = 0; i < 100 && !node.isActive(); i++) {
            world.tick();
        }
        world.tick();
        assertEquals(500, monitor.getAmount(), "nothing was moved while the network was down");
    }

    @Test
    void saveAndLoad() {
        network(NodeType.STORAGE_MONITOR);
        var tool = AEItemKey.of("Tool_Pickaxe", "{\"d\":12.0}");
        monitor.configure(tool);
        monitor.toggleLocked();
        assertEquals(2, saves, "item and lock are persisted");
        var json = monitor.saveState();

        var copy = new StorageMonitorLogic();
        copy.loadState(json);
        assertEquals(tool, copy.getConfiguredItem());
        assertTrue(copy.isLocked());
        assertEquals(json, copy.saveState());

        var empty = new StorageMonitorLogic();
        empty.loadState(new StorageMonitorLogic().saveState());
        assertNull(empty.getConfiguredItem());
        assertFalse(empty.isLocked());
        empty.loadState(null);
        empty.loadState("");
    }

    @Test
    void loadedMonitorReadsTheAmountWhenItJoinsTheGrid() {
        network(NodeType.STORAGE_MONITOR);
        put(STONE, 33);
        world.tick();
        var saved = new StorageMonitorLogic();
        saved.setConfiguredItem(STONE);
        var loaded = new StorageMonitorLogic();
        loaded.loadState(saved.saveState());
        // second monitor at the side of the cable
        var n2 = world.addNode(p(0, 0, 1), NodeType.STORAGE_MONITOR, Direction.SOUTH, null, loaded);
        assertEquals(33, loaded.getAmount());
        world.tick();
        assertTrue(n2.isActive());
        put(STONE, 1);
        world.tick();
        assertEquals(34, loaded.getAmount());
        world.removeNode(p(0, 0, 1));
        put(STONE, 1);
        world.tick();
        assertEquals(34, loaded.getAmount(), "removed: no longer watching");
    }
}
