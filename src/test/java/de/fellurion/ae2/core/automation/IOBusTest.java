/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.config.Enums.SchedulingMode;
import de.fellurion.ae2.core.grid.CableType;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.inv.SimpleSlotAccess;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.inv.SlotInventories;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Import / export bus behaviour (ports of AE2 ImportBusPart, ExportBusPart, IOBusPart). */
class IOBusTest extends AutomationTestBase {

    private ImportBusLogic importBus;
    private GridNode importNode;
    private SimpleSlotAccess chest;

    private void setUpImport() {
        network(0, 2, 0);
        drive(0, 0);
        importBus = new ImportBusLogic();
        importNode = partAbove(1, 0, NodeType.IMPORT_BUS, importBus);
        chest = chest(9);
        importBus.setTarget(ExternalStorageFacade.of(chest));
        world.tick(); // power up + channels
        assertTrue(importNode.isActive());
    }

    @Test
    void importBusMovesOneItemPerOperationAndEightWithAccelerationCard() {
        setUpImport();
        chest.set(0, SimpleStack.of("Rock_Stone", 64));
        chest.set(1, SimpleStack.of("Soil_Dirt", 10));

        assertEquals(TickRateModulation.FASTER, importBus.tickingRequest(importNode, 1));
        assertEquals(63, chest.get(0).count());
        assertEquals(1, networkAmount(importNode, STONE));

        assertTrue(importBus.getUpgrades().insert(UpgradeCard.SPEED));
        importBus.tickingRequest(importNode, 1);
        assertEquals(55, chest.get(0).count());
        assertEquals(9, networkAmount(importNode, STONE));

        // 4 cards: 96 per operation round, spans multiple slots
        for (int i = 0; i < 3; i++) {
            assertTrue(importBus.getUpgrades().insert(UpgradeCard.SPEED));
        }
        assertFalse(importBus.getUpgrades().canInsert(UpgradeCard.SPEED), "max 4 acceleration cards");
        importBus.tickingRequest(importNode, 1);
        assertNull(chest.get(0));
        assertNull(chest.get(1));
        assertEquals(64, networkAmount(importNode, STONE));
        assertEquals(10, networkAmount(importNode, DIRT));
        // nothing left: no work done -> slower
        assertEquals(TickRateModulation.SLOWER, importBus.tickingRequest(importNode, 1));
    }

    @Test
    void importBusEmptiesChestOverTime() {
        setUpImport();
        chest.set(0, SimpleStack.of("Rock_Stone", 20));
        world.tick(400);
        assertNull(chest.get(0));
        assertEquals(20, networkAmount(importNode, STONE));
    }

    @Test
    void importBusFilterAndInverterCard() {
        setUpImport();
        chest.set(0, SimpleStack.of("Rock_Stone", 5));
        chest.set(1, SimpleStack.of("Soil_Dirt", 5));
        importBus.getConfig().setKey(0, DIRT);
        importBus.tickingRequest(importNode, 1);
        assertEquals(5, chest.get(0).count(), "stone is not in the whitelist");
        assertEquals(4, chest.get(1).count());

        assertTrue(importBus.getUpgrades().insert(UpgradeCard.INVERTER));
        importBus.tickingRequest(importNode, 1);
        assertEquals(4, chest.get(0).count(), "inverted: everything but dirt");
        assertEquals(4, chest.get(1).count());
    }

    @Test
    void importBusRespectsRedstoneCard() {
        setUpImport();
        chest.set(0, SimpleStack.of("Rock_Stone", 10));
        importBus.getUpgrades().insert(UpgradeCard.REDSTONE);
        importBus.setRedstoneMode(RedstoneMode.HIGH_SIGNAL);
        assertEquals(TickRateModulation.SLEEP, importBus.tickingRequest(importNode, 1));
        assertEquals(10, chest.get(0).count());
        importBus.setRedstonePowered(true);
        importBus.tickingRequest(importNode, 1);
        assertEquals(9, chest.get(0).count());

        // Pulse mode: exactly one round per rising edge
        importBus.setRedstoneMode(RedstoneMode.SIGNAL_PULSE);
        importBus.tickingRequest(importNode, 1);
        assertEquals(9, chest.get(0).count(), "no edge yet");
        importBus.setRedstonePowered(false);
        importBus.setRedstonePowered(true);
        importBus.tickingRequest(importNode, 1);
        assertEquals(8, chest.get(0).count());
        importBus.tickingRequest(importNode, 1);
        assertEquals(8, chest.get(0).count(), "pulse consumed");
    }

    @Test
    void importBusDoesNothingWithoutPower() {
        // no energy cell: network never powered
        world.addNode(p(0, 0, 0), NodeType.cable(CableType.GLASS), null, null, null);
        var bus = new ImportBusLogic();
        var node = partAbove(0, 0, NodeType.IMPORT_BUS, bus);
        var c = chest(1);
        c.set(0, SimpleStack.of("Rock_Stone", 3));
        bus.setTarget(ExternalStorageFacade.of(c));
        world.tick(100);
        assertFalse(node.isActive());
        assertEquals(3, c.get(0).count());
    }

    private ExportBusLogic exportBus;
    private GridNode exportNode;

    private void setUpExport() {
        network(0, 2, 0);
        drive(0, 0);
        exportBus = new ExportBusLogic();
        exportNode = partAbove(1, 0, NodeType.EXPORT_BUS, exportBus);
        chest = chest(9);
        world.tick();
        insertIntoNetwork(exportNode, STONE, 100);
        insertIntoNetwork(exportNode, DIRT, 100);
        exportBus.setTarget(ExternalStorageFacade.of(chest));
        exportBus.getConfig().setKey(0, STONE);
        exportBus.getConfig().setKey(1, DIRT);
    }

    @Test
    void exportBusDefaultSchedulingAlwaysStartsWithFirstSlot() {
        setUpExport();
        for (int i = 0; i < 4; i++) {
            exportBus.tickingRequest(exportNode, 1);
        }
        assertEquals(4, SlotInventories.count(chest, STONE));
        assertEquals(0, SlotInventories.count(chest, DIRT));
        assertEquals(96, networkAmount(exportNode, STONE));
    }

    @Test
    void exportBusRoundRobinAlternatesSlots() {
        setUpExport();
        exportBus.setSchedulingMode(SchedulingMode.ROUNDROBIN);
        exportBus.tickingRequest(exportNode, 1);
        assertEquals(1, SlotInventories.count(chest, STONE));
        assertEquals(1, exportBus.getNextSlot());
        exportBus.tickingRequest(exportNode, 1);
        assertEquals(1, SlotInventories.count(chest, DIRT));
        exportBus.tickingRequest(exportNode, 1);
        exportBus.tickingRequest(exportNode, 1);
        assertEquals(2, SlotInventories.count(chest, STONE));
        assertEquals(2, SlotInventories.count(chest, DIRT));
    }

    @Test
    void exportBusWithAccelerationCardsFillsChest() {
        setUpExport();
        exportBus.getConfig().setKey(1, null);
        exportBus.getUpgrades().insert(UpgradeCard.SPEED);
        exportBus.getUpgrades().insert(UpgradeCard.SPEED); // 32 per round
        exportBus.tickingRequest(exportNode, 1);
        assertEquals(32, SlotInventories.count(chest, STONE));
        assertEquals(68, networkAmount(exportNode, STONE));
        // Chest full (9 x 64 = 576 > 100): over time all 100 stone end up in the chest
        world.tick(200);
        assertEquals(100, SlotInventories.count(chest, STONE));
        assertEquals(0, networkAmount(exportNode, STONE));
    }

    @Test
    void exportBusDoesNotVoidWhenTargetFull() {
        setUpExport();
        var tiny = new SimpleSlotAccess(1);
        tiny.set(0, SimpleStack.of("Rock_Stone", 63));
        exportBus.setTarget(ExternalStorageFacade.of(tiny));
        exportBus.getUpgrades().insert(UpgradeCard.SPEED);
        exportBus.tickingRequest(exportNode, 1);
        assertEquals(64, tiny.get(0).count());
        assertEquals(99, networkAmount(exportNode, STONE));
        assertEquals(100, networkAmount(exportNode, DIRT));
    }

    @Test
    void busStateRoundTripsThroughJson() {
        var bus = new ExportBusLogic();
        bus.getConfig().setKey(0, STONE);
        bus.getConfig().setKey(5, DIRT);
        bus.getUpgrades().insert(UpgradeCard.SPEED);
        bus.getUpgrades().insert(UpgradeCard.CAPACITY);
        bus.setSchedulingMode(SchedulingMode.ROUNDROBIN);
        bus.setRedstoneMode(RedstoneMode.LOW_SIGNAL);
        var json = bus.toJson();

        var copy = new ExportBusLogic();
        copy.fromJson(json);
        assertEquals(STONE, copy.getConfig().getKey(0));
        assertEquals(DIRT, copy.getConfig().getKey(5));
        assertNull(copy.getConfig().getKey(1));
        assertEquals(1, copy.getUpgrades().getInstalledUpgrades(UpgradeCard.SPEED));
        assertEquals(1, copy.getUpgrades().getInstalledUpgrades(UpgradeCard.CAPACITY));
        assertEquals(SchedulingMode.ROUNDROBIN, copy.getSchedulingMode());
        assertEquals(RedstoneMode.LOW_SIGNAL, copy.getRedstoneMode());
        assertEquals(json, copy.toJson());
    }
}
