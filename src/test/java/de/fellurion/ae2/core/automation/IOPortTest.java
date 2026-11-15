/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.CellState;
import de.fellurion.ae2.core.cells.StorageCell;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.config.FullnessMode;
import de.fellurion.ae2.core.config.OperationMode;
import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.grid.CableType;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IActionSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ME IO Port behaviour (port of AE2 IOPortBlockEntity). */
class IOPortTest extends AutomationTestBase {

    private static final String CELL_1K = "AE2_Item_Storage_Cell_1k";
    private static final String CELL_4K = "AE2_Item_Storage_Cell_4k";
    /** A 1k cell holds (1024 − 8) · 8 items of a single type. */
    private static final long CELL_1K_ONE_TYPE = 8128;

    /**
     * Stand-in for the block's item slots: an item is its id plus the cell data it carries. The cell inventories are
     * created with a save provider, so the data only changes when the port asks for it to be written
     * ({@link IOPortLogic.CellSlots#cellChanged}) - exactly what the integration layer has to do.
     */
    static final class Slots implements IOPortLogic.CellSlots {
        record Item(String itemId, CellItemData data) {
        }

        final Item[] input = new Item[IOPortLogic.NUMBER_OF_CELL_SLOTS];
        final Item[] output = new Item[IOPortLogic.NUMBER_OF_CELL_SLOTS];
        final StorageCell[] cells = new StorageCell[IOPortLogic.NUMBER_OF_CELL_SLOTS];
        Runnable changed = () -> {
        };

        /** Puts an item into an input slot; {@code data} null = not a storage cell. */
        void put(int slot, String itemId, CellItemData data) {
            input[slot] = new Item(itemId, data);
            cells[slot] = data == null ? null : StorageCells.getCellInventory(itemId, data, () -> {
            });
            changed.run();
        }

        void take(int slot) {
            input[slot] = null;
            cells[slot] = null;
            changed.run();
        }

        @Override
        public boolean isEmpty(int slot) {
            return input[slot] == null;
        }

        @Override
        public StorageCell getCell(int slot) {
            return cells[slot];
        }

        @Override
        public void cellChanged(int slot) {
            cells[slot].persist();
        }

        @Override
        public boolean moveToOutput(int slot) {
            for (int o = 0; o < output.length; o++) {
                if (output[o] == null) {
                    if (cells[slot] != null) {
                        cells[slot].persist();
                    }
                    output[o] = input[slot];
                    take(slot);
                    return true;
                }
            }
            return false;
        }

        int outputCount() {
            int n = 0;
            for (var i : output) {
                if (i != null) {
                    n++;
                }
            }
            return n;
        }

        /** Everything the items in the slots carry, read from their data (not from the live inventories). */
        Map<AEKey, Long> stored() {
            var out = new LinkedHashMap<AEKey, Long>();
            for (var items : new Item[][]{input, output}) {
                for (var i : items) {
                    if (i != null && i.data() != null) {
                        i.data().getContents().forEach((k, v) -> out.merge(k, v, Long::sum));
                    }
                }
            }
            return out;
        }
    }

    private Slots slots;
    private IOPortLogic port;
    private GridNode node;
    private CellHostLogic drive;

    /** Energy cell, three cables, a drive with an empty 4k cell and the IO port above the middle cable. */
    private void setUp() {
        network(0, 2, 0);
        drive = drive(0, 0);
        slots = new Slots();
        port = new IOPortLogic(slots);
        slots.changed = port::onInventoryChanged;
        node = world.addNode(p(1, 1, 0), NodeType.IO_PORT, null, null, port);
        world.tick(); // power up + channels
        assertTrue(node.isActive());
    }

    private static CellItemData cellWith(String cellItemId, Object... keysAndAmounts) {
        var data = new CellItemData();
        var cell = StorageCells.getCellInventory(cellItemId, data, null);
        for (int i = 0; i < keysAndAmounts.length; i += 2) {
            long amount = ((Number) keysAndAmounts[i + 1]).longValue();
            assertEquals(amount, cell.insert((AEKey) keysAndAmounts[i], amount, Actionable.MODULATE,
                    IActionSource.empty()));
        }
        return data;
    }

    private static long amount(CellItemData data, AEKey key) {
        return data.getContents().getOrDefault(key, 0L);
    }

    private TickRateModulation tick() {
        return port.tickingRequest(node, 1);
    }

    /** Network content plus what the cell items in the port carry. */
    private long total(AEKey key) {
        return networkAmount(node, key) + slots.stored().getOrDefault(key, 0L);
    }

    // ------------------------------------------------------------------------------------------------------------
    // EMPTY: cell -> network

    @Test
    void emptyMoves256PerTickAndThenTheCellToTheOutput() {
        setUp();
        assertEquals(OperationMode.EMPTY, port.getOperationMode());
        assertEquals(FullnessMode.EMPTY, port.getFullnessMode());
        var data = cellWith(CELL_1K, STONE, 300, DIRT, 50);
        slots.put(0, CELL_1K, data);

        // 256 operations: the budget is used up -> tick again as soon as possible, the cell stays
        assertEquals(TickRateModulation.URGENT, tick());
        assertEquals(256, networkAmount(node, STONE));
        assertEquals(0, networkAmount(node, DIRT));
        assertEquals(44, amount(data, STONE), "the item data follows every tick");
        assertNotNull(slots.input[0]);

        // rest: 44 + 50, budget left -> the cell is empty and moves
        assertEquals(TickRateModulation.URGENT, tick());
        assertEquals(300, networkAmount(node, STONE));
        assertEquals(50, networkAmount(node, DIRT));
        assertNull(slots.input[0]);
        assertSame(data, slots.output[0].data());
        assertFalse(data.hasContents());
        assertEquals("{}", data.toJson());

        // nothing left to do
        assertEquals(TickRateModulation.SLEEP, tick());
    }

    @Test
    void emptyWithFullnessModes() {
        // EMPTY and FULL keep a cell the network cannot take completely, HALF ("work is done") releases it
        for (var mode : FullnessMode.values()) {
            setUpWorld();
            setUp();
            // the network: one 1k cell that has room for 100 more stone and no byte left for a second type
            drive.setCell(0, StorageCells.getCellInventory(CELL_1K, cellWith(CELL_1K, STONE, CELL_1K_ONE_TYPE - 100),
                    null));
            world.tick();
            port.setFullnessMode(mode);
            var data = cellWith(CELL_1K, STONE, 150, DIRT, 20);
            slots.put(0, CELL_1K, data);

            var result = tick();
            assertEquals(CELL_1K_ONE_TYPE, networkAmount(node, STONE), mode + ": only 100 fit");
            assertEquals(0, networkAmount(node, DIRT), mode.toString());
            assertEquals(50, amount(data, STONE), mode.toString());
            assertEquals(20, amount(data, DIRT), mode.toString());
            if (mode == FullnessMode.HALF) {
                assertEquals(TickRateModulation.URGENT, result);
                assertNull(slots.input[0]);
                assertSame(data, slots.output[0].data());
            } else {
                assertEquals(TickRateModulation.IDLE, result, mode + ": the cell waits");
                assertNotNull(slots.input[0]);
                assertEquals(0, slots.outputCount());
                // still waiting later on, nothing is lost meanwhile
                world.tick(40);
                assertNotNull(slots.input[0]);
                assertEquals(50, amount(data, STONE));
            }
        }
    }

    @Test
    void emptyHandlesAllSixSlotsWithOneBudget() {
        setUp();
        for (int i = 0; i < 6; i++) {
            slots.put(i, CELL_1K, cellWith(CELL_1K, STONE, 100));
        }
        // 256 operations: cells 0 and 1 completely, 56 out of cell 2
        assertEquals(TickRateModulation.URGENT, tick());
        assertEquals(256, networkAmount(node, STONE));
        assertEquals(2, slots.outputCount());
        assertNull(slots.input[0]);
        assertNull(slots.input[1]);
        assertEquals(44, amount(slots.input[2].data(), STONE));
        assertEquals(100, amount(slots.input[5].data(), STONE));

        tick();
        tick();
        assertEquals(600, networkAmount(node, STONE));
        assertEquals(6, slots.outputCount());
        assertEquals(TickRateModulation.SLEEP, tick());
    }

    // ------------------------------------------------------------------------------------------------------------
    // FILL: network -> cell

    @Test
    void fillMovesFromTheNetworkIntoTheCell() {
        setUp();
        port.setOperationMode(OperationMode.FILL);
        port.setFullnessMode(FullnessMode.FULL);
        insertIntoNetwork(node, STONE, 10_000);
        var data = new CellItemData();
        slots.put(0, CELL_1K, data);

        assertEquals(TickRateModulation.URGENT, tick());
        assertEquals(256, amount(data, STONE), "the item data follows every tick");
        assertEquals(10_000 - 256, networkAmount(node, STONE));

        // until the cell is full: it then moves to the output
        int ticks = 1;
        while (slots.input[0] != null && ticks < 100) {
            tick();
            ticks++;
            assertEquals(10_000, total(STONE));
        }
        assertNull(slots.input[0]);
        assertSame(data, slots.output[0].data());
        assertEquals(CellState.FULL, StorageCells.getCellInventory(CELL_1K, data, null).getStatus());
        assertEquals(CELL_1K_ONE_TYPE, amount(data, STONE));
        assertEquals(10_000 - CELL_1K_ONE_TYPE, networkAmount(node, STONE));
        assertEquals((CELL_1K_ONE_TYPE + 255) / 256, ticks, "256 per tick");
    }

    @Test
    void fillWithFullnessModes() {
        // the network holds less than the cell: FULL never releases it, HALF does, EMPTY only an untouched cell
        for (var mode : FullnessMode.values()) {
            setUpWorld();
            setUp();
            port.setOperationMode(OperationMode.FILL);
            port.setFullnessMode(mode);
            insertIntoNetwork(node, STONE, 100);
            var data = new CellItemData();
            slots.put(0, CELL_1K, data);

            var result = tick();
            assertEquals(100, amount(data, STONE), mode.toString());
            assertEquals(0, networkAmount(node, STONE), mode.toString());
            if (mode == FullnessMode.HALF) {
                assertEquals(TickRateModulation.URGENT, result);
                assertSame(data, slots.output[0].data());
            } else {
                assertEquals(TickRateModulation.IDLE, result);
                world.tick(40);
                assertNotNull(slots.input[0], mode + ": neither empty nor full");
            }
        }

        // FILL + EMPTY with nothing in the network: the empty cell passes straight through (as in AE2)
        setUpWorld();
        setUp();
        port.setOperationMode(OperationMode.FILL);
        slots.put(0, CELL_1K, new CellItemData());
        assertEquals(TickRateModulation.URGENT, tick());
        assertNotNull(slots.output[0]);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rate, energy, state

    @Test
    void accelerationCardsDoubleTheRate() {
        long[] expected = {256, 512, 1024, 2048};
        for (int cards = 0; cards <= 3; cards++) {
            setUpWorld();
            setUp();
            for (int i = 0; i < cards; i++) {
                assertTrue(port.getUpgrades().insert(UpgradeCard.SPEED));
            }
            assertEquals(expected[cards], port.getOperationsPerTick());
            var data = cellWith(CELL_1K, STONE, 5000);
            slots.put(0, CELL_1K, data);
            tick();
            assertEquals(expected[cards], networkAmount(node, STONE), cards + " cards");
            assertEquals(5000 - expected[cards], amount(data, STONE));
        }
        // three slots: no fourth card of any kind, and no card the IO port does not support
        assertFalse(port.getUpgrades().canInsert(UpgradeCard.SPEED));
        assertFalse(port.getUpgrades().canInsert(UpgradeCard.REDSTONE));
        assertEquals(0, port.getUpgrades().getMaxInstalled(UpgradeCard.CAPACITY));
        assertEquals(1, port.getUpgrades().getMaxInstalled(UpgradeCard.REDSTONE));
        assertEquals(3, port.getUpgrades().getMaxInstalled(UpgradeCard.SPEED));
    }

    @Test
    void everyItemCostsOneAE() {
        // a real energy cell instead of the creative one
        var energyCell = EnergyCellLogic.energyCell();
        energyCell.setAECurrentPower(10_000);
        world.addNode(p(-1, 0, 0), NodeType.ENERGY_CELL, null, null, energyCell);
        for (int x = 0; x <= 2; x++) {
            world.addNode(p(x, 0, 0), NodeType.cable(CableType.GLASS), null, AEColor.TRANSPARENT, null);
        }
        drive(0, 0);
        slots = new Slots();
        port = new IOPortLogic(slots);
        node = world.addNode(p(1, 1, 0), NodeType.IO_PORT, null, null, port);
        world.tick();
        assertTrue(node.isActive());
        assertEquals(1.0, node.getIdlePowerUsage(), "idle drain of the block");

        slots.put(0, CELL_1K, cellWith(CELL_1K, STONE, 200));
        double before = node.getGrid().getEnergyService().getStoredPower();
        tick();
        assertEquals(200, networkAmount(node, STONE));
        assertEquals(200.0, before - node.getGrid().getEnergyService().getStoredPower(), 1e-6);
    }

    @Test
    void energyRunningOutStopsTheTransferWithoutLoss() {
        var energyCell = EnergyCellLogic.energyCell();
        energyCell.setAECurrentPower(120);
        world.addNode(p(-1, 0, 0), NodeType.ENERGY_CELL, null, null, energyCell);
        world.addNode(p(0, 0, 0), NodeType.cable(CableType.GLASS), null, AEColor.TRANSPARENT, null);
        drive = drive(0, 0);
        slots = new Slots();
        port = new IOPortLogic(slots);
        slots.changed = port::onInventoryChanged;
        node = world.addNode(p(0, 1, 0), NodeType.IO_PORT, null, null, port);
        world.tick();
        assertTrue(node.isActive());

        var data = cellWith(CELL_1K, STONE, 500);
        slots.put(0, CELL_1K, data);
        for (int i = 0; i < 50; i++) {
            world.tick();
            assertEquals(500, inDrive(STONE) + amount(data, STONE));
        }
        assertFalse(node.isActive(), "the network ran dry");
        long moved = inDrive(STONE);
        assertTrue(moved > 0 && moved < 500, "only what could be paid for: " + moved);
        assertEquals(500 - moved, amount(data, STONE));
        assertNotNull(slots.input[0]);
    }

    /** Content of the drive's cell itself (an unpowered drive is not part of the network inventory). */
    private long inDrive(AEKey key) {
        return drive.getCell(0).getAvailableStacks().get(key);
    }

    @Test
    void nothingMovesWithoutPower() {
        world.addNode(p(0, 0, 0), NodeType.cable(CableType.GLASS), null, AEColor.TRANSPARENT, null);
        drive(0, 0);
        slots = new Slots();
        port = new IOPortLogic(slots);
        node = world.addNode(p(0, 1, 0), NodeType.IO_PORT, null, null, port);
        var data = cellWith(CELL_1K, STONE, 500);
        slots.put(0, CELL_1K, data);
        port.onInventoryChanged();
        world.tick(100);
        assertFalse(node.isActive());
        assertEquals(TickRateModulation.IDLE, tick());
        assertEquals(500, amount(data, STONE));
        assertNotNull(slots.input[0]);
    }

    @Test
    void nothingMovesWithoutChannel() {
        // ad-hoc network with 9 devices that need a channel: nobody gets one
        network(0, 8, 0);
        drive(0, 0);
        for (int x = 1; x <= 8; x++) {
            partAbove(x, 0, NodeType.IMPORT_BUS, new ImportBusLogic());
        }
        slots = new Slots();
        port = new IOPortLogic(slots);
        node = world.addNode(p(0, 1, 0), NodeType.IO_PORT, null, null, port);
        var data = cellWith(CELL_1K, STONE, 500);
        slots.put(0, CELL_1K, data);
        port.onInventoryChanged();
        world.tick(100);
        assertTrue(node.isPowered());
        assertFalse(node.meetsChannelRequirements());
        assertEquals(500, amount(data, STONE));
        assertNotNull(slots.input[0]);
    }

    @Test
    void cellStaysInTheInputWhileTheOutputIsFull() {
        setUp();
        for (int i = 0; i < 6; i++) {
            slots.output[i] = new Slots.Item(CELL_1K, new CellItemData());
        }
        var data = cellWith(CELL_1K, STONE, 10);
        slots.put(0, CELL_1K, data);
        assertEquals(TickRateModulation.IDLE, tick());
        assertEquals(10, networkAmount(node, STONE));
        assertNotNull(slots.input[0], "emptied, but no free output slot");

        slots.output[3] = null;
        assertEquals(TickRateModulation.URGENT, tick());
        assertNull(slots.input[0]);
        assertSame(data, slots.output[3].data());
    }

    @Test
    void anItemThatIsNoCellGoesStraightToTheOutput() {
        setUp();
        slots.put(2, "Rock_Stone", null);
        tick();
        assertNull(slots.input[2]);
        assertEquals("Rock_Stone", slots.output[0].itemId());
    }

    @Test
    void redstoneCard() {
        setUp();
        var data = cellWith(CELL_1K, STONE, 5000);
        slots.put(0, CELL_1K, data);
        var ticks = node.getGrid().getTickManager();

        // without the card the mode and the signal do not matter
        port.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
        assertTrue(port.isEnabled());
        assertFalse(ticks.isSleeping(node));

        assertTrue(port.getUpgrades().insert(UpgradeCard.REDSTONE));
        assertFalse(port.isEnabled());
        assertTrue(ticks.isSleeping(node), "switched off: asleep");
        assertEquals(TickRateModulation.SLEEP, tick());
        world.tick(20);
        assertEquals(5000, amount(data, STONE));

        port.setRedstonePowered(true);
        assertFalse(ticks.isSleeping(node), "signal: awake");
        world.tick(2);
        assertTrue(amount(data, STONE) < 5000);

        port.setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        assertFalse(port.isEnabled());
        assertTrue(ticks.isSleeping(node));
        port.setRedstonePowered(false);
        assertTrue(port.isEnabled());

        port.setRedstoneMode(RedstoneMode.IGNORE);
        port.setRedstonePowered(true);
        assertTrue(port.isEnabled(), "always active");

        // taking the card out switches the port back on
        port.setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        assertTrue(ticks.isSleeping(node));
        for (int i = 0; i < port.getUpgrades().size(); i++) {
            port.getUpgrades().remove(i);
        }
        assertFalse(ticks.isSleeping(node));
    }

    @Test
    void sleepsWhenIdleAndWakesOnInventoryChange() {
        setUp();
        var ticks = node.getGrid().getTickManager();
        assertTrue(ticks.isSleeping(node), "no cells: asleep from the start");
        assertEquals(TickRates.IOPort.getMin(), port.getTickingRequest(node).minTickRate());
        assertEquals(TickRates.IOPort.getMax(), port.getTickingRequest(node).maxTickRate());
        assertTrue(port.getTickingRequest(node).isSleeping());

        slots.put(0, CELL_1K, cellWith(CELL_1K, STONE, 1000));
        assertFalse(ticks.isSleeping(node));
        assertFalse(port.getTickingRequest(node).isSleeping());
        // 1000 items at 256 per tick, ticked every tick while the budget is used up
        world.tick(TickRates.IOPort.getMax() + 4);
        assertEquals(1000, networkAmount(node, STONE));
        assertNotNull(slots.output[0]);
        world.tick(TickRates.IOPort.getMax());
        assertTrue(ticks.isSleeping(node), "done: asleep again");

        // a waiting cell is polled at the slowest rate, taking it out puts the port to sleep
        port.setFullnessMode(FullnessMode.FULL);
        slots.put(0, CELL_1K, new CellItemData());
        world.tick(3);
        assertFalse(ticks.isSleeping(node));
        assertEquals(TickRates.IOPort.getMax(), ticks.getCurrentRate(node));
        slots.take(0);
        assertTrue(ticks.isSleeping(node));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Conservation and persistence

    @Test
    void nothingIsLostOrDuplicated() {
        setUp();
        AEKey[] keys = {STONE, DIRT, GOLD, IRON, COBBLE};
        long[] initial = {3000, 700, 64, 1, 2500};
        // spread over three cells of two sizes, plus something that is already in the network
        slots.put(0, CELL_1K, cellWith(CELL_1K, STONE, 1000, DIRT, 700, GOLD, 60));
        slots.put(3, CELL_4K, cellWith(CELL_4K, STONE, 1500, COBBLE, 2500));
        slots.put(5, CELL_1K, cellWith(CELL_1K, GOLD, 4, IRON, 1));
        insertIntoNetwork(node, STONE, 500);
        port.getUpgrades().insert(UpgradeCard.SPEED);

        for (int i = 0; i < 60; i++) {
            world.tick();
            for (int k = 0; k < keys.length; k++) {
                assertEquals(initial[k], total(keys[k]), keys[k] + " after tick " + i);
            }
        }
        assertEquals(3, slots.outputCount());
        assertTrue(slots.stored().isEmpty(), "everything is in the network");
        for (int k = 0; k < keys.length; k++) {
            assertEquals(initial[k], networkAmount(node, keys[k]));
        }

        // and back again into fresh cells
        port.setOperationMode(OperationMode.FILL);
        port.setFullnessMode(FullnessMode.HALF);
        slots.put(0, CELL_1K, new CellItemData());
        slots.put(1, CELL_4K, new CellItemData());
        for (int i = 0; i < 60; i++) {
            world.tick();
            for (int k = 0; k < keys.length; k++) {
                assertEquals(initial[k], total(keys[k]), keys[k] + " after fill tick " + i);
            }
        }
        assertEquals(5, slots.outputCount());
        assertFalse(slots.stored().isEmpty());
        long inCells = slots.stored().values().stream().mapToLong(Long::longValue).sum();
        long inNetwork = 0;
        for (var key : keys) {
            inNetwork += networkAmount(node, key);
        }
        assertEquals(3000 + 700 + 64 + 1 + 2500, inCells + inNetwork);
    }

    @Test
    void emptyingNeverLosesItemsTheCellWouldNotTakeBack() {
        // a cell partitioned to dirt after it was filled with stone refuses to take stone back; the network accepts
        // less than it promised because two views of the same storage both count its free space
        setUp();
        var shared = StorageCells.getCellInventory(CELL_1K, cellWith(CELL_1K, STONE, CELL_1K_ONE_TYPE - 100), null);
        drive.setCell(0, shared);
        drive.setCell(1, shared);
        world.tick();
        var data = cellWith(CELL_1K, STONE, 300);
        data.setConfig(List.of(DIRT));
        slots.put(0, CELL_1K, data);
        assertEquals(200, node.getGrid().getStorageService().getInventory().insert(STONE, 300, Actionable.SIMULATE,
                IActionSource.empty()), "the network promises twice the free space");

        world.tick(20);
        assertEquals(CELL_1K_ONE_TYPE, shared.getAvailableStacks().get(STONE));
        assertEquals(200, amount(data, STONE), "100 fitted, the rest never left the cell");
        assertNotNull(slots.input[0]);
    }

    @Test
    void saveAndLoadMidTransfer() {
        setUp();
        port.setFullnessMode(FullnessMode.HALF);
        port.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
        port.getUpgrades().insert(UpgradeCard.SPEED);
        port.getUpgrades().insert(UpgradeCard.REDSTONE);
        port.setRedstonePowered(true);
        var data = cellWith(CELL_4K, STONE, 3000, DIRT, 200);
        slots.put(0, CELL_4K, data);
        tick();
        assertEquals(512, networkAmount(node, STONE));

        // what the block entity and the cell item would store
        String state = port.saveState();
        String cellJson = data.toJson();
        String driveJson = persistDriveCell();

        // a new world built from the saved state
        setUpWorld();
        network(0, 2, 0);
        drive = CellHostLogic.drive();
        world.addNode(p(0, -1, 0), NodeType.DRIVE, null, null, drive);
        drive.setCell(0, StorageCells.getCellInventory(CELL_4K, CellItemData.fromJson(driveJson), null));
        slots = new Slots();
        port = new IOPortLogic(slots);
        port.loadState(state);
        slots.changed = port::onInventoryChanged;
        node = world.addNode(p(1, 1, 0), NodeType.IO_PORT, null, null, port);
        var restored = CellItemData.fromJson(cellJson);
        slots.put(0, CELL_4K, restored);

        assertEquals(OperationMode.EMPTY, port.getOperationMode());
        assertEquals(FullnessMode.HALF, port.getFullnessMode());
        assertEquals(RedstoneMode.HIGH_SIGNAL, port.getRedstoneMode());
        assertEquals(1, port.getUpgrades().getInstalledUpgrades(UpgradeCard.SPEED));
        assertTrue(port.getUpgrades().isInstalled(UpgradeCard.REDSTONE));
        assertTrue(port.isRedstonePowered());
        assertEquals(2488, amount(restored, STONE));

        world.tick(30);
        assertEquals(3000, networkAmount(node, STONE));
        assertEquals(200, networkAmount(node, DIRT));
        assertFalse(restored.hasContents());
        assertSame(restored, slots.output[0].data());

        // defaults survive an empty state
        var fresh = new IOPortLogic(new Slots());
        fresh.loadState(null);
        fresh.loadState("{}");
        assertEquals(OperationMode.EMPTY, fresh.getOperationMode());
        assertEquals(FullnessMode.EMPTY, fresh.getFullnessMode());
        assertEquals(RedstoneMode.IGNORE, fresh.getRedstoneMode());
    }

    private String persistDriveCell() {
        var data = new CellItemData();
        var copy = StorageCells.getCellInventory(CELL_4K, data, null);
        for (var e : drive.getCell(0).getAvailableStacks()) {
            copy.insert(e.getKey(), e.getLongValue(), Actionable.MODULATE, IActionSource.empty());
        }
        return data.toJson();
    }

    @Test
    void settingsNotifyTheSaveListener() {
        var logic = new IOPortLogic(new Slots());
        int[] saves = {0};
        logic.setSaveListener(() -> saves[0]++);
        logic.setOperationMode(OperationMode.FILL);
        logic.setFullnessMode(FullnessMode.FULL);
        logic.setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        logic.getUpgrades().insert(UpgradeCard.SPEED);
        assertEquals(4, saves[0]);
        logic.loadState(logic.saveState());
        assertEquals(4, saves[0], "loading is not a change");
        logic.setRedstonePowered(true);
        assertEquals(4, saves[0], "the signal is not a setting");
    }
}
