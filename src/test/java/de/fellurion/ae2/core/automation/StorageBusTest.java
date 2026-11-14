/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Enums.AccessRestriction;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.inv.SimpleSlotAccess;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.inv.SlotInventories;
import de.fellurion.ae2.core.stacks.GenericStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Storage bus behaviour (port of AE2 StorageBusPart + MEInventoryHandler + NetworkStorage ordering). */
class StorageBusTest extends AutomationTestBase {
    private StorageBusLogic bus;
    private GridNode busNode;
    private CellHostLogic drive;
    private SimpleSlotAccess chest;

    private void setUp() {
        network(0, 2, 0);
        drive = drive(0, 0);
        bus = new StorageBusLogic();
        busNode = partAbove(1, 0, NodeType.STORAGE_BUS, bus);
        chest = chest(27);
        bus.setTarget(ExternalStorageFacade.of(chest));
        world.tick();
        assertTrue(busNode.isOnline());
    }

    @Test
    void higherPriorityIsFilledFirstAndLowestPriorityIsEmptiedFirst() {
        setUp();
        bus.setPriority(10);
        assertEquals(10, insertIntoNetwork(busNode, STONE, 10));
        assertEquals(10, SlotInventories.count(chest, STONE), "storage bus has the higher priority");

        bus.setPriority(-10);
        insertIntoNetwork(busNode, DIRT, 10);
        assertEquals(0, SlotInventories.count(chest, DIRT), "drive (priority 0) now wins");
        assertEquals(10, drive.getCell(0).getAvailableStacks().get(DIRT));

        // Priority groups are visited from high to low: the drive (0) accepts stone before the chest (-10)
        insertIntoNetwork(busNode, STONE, 5);
        assertEquals(5, drive.getCell(0).getAvailableStacks().get(STONE));
        assertEquals(10, SlotInventories.count(chest, STONE));

        // Extraction starts with the lowest priority: the chest
        assertEquals(3, extractFromNetwork(busNode, STONE, 3));
        assertEquals(7, SlotInventories.count(chest, STONE));
        assertEquals(5, drive.getCell(0).getAvailableStacks().get(STONE));
        assertEquals(15 - 3, networkAmount(busNode, STONE));
    }

    @Test
    void containingStorageIsPreferredWithinSamePriority() {
        setUp();
        // same priority 0: chest already contains gold -> preferred for gold
        chest.set(0, SimpleStack.of("Ingredient_Bar_Gold", 1));
        insertIntoNetwork(busNode, GOLD, 10);
        assertEquals(11, SlotInventories.count(chest, GOLD));
        assertEquals(0, drive.getCell(0).getAvailableStacks().get(GOLD));
    }

    @Test
    void partitionWhitelistAndInverterBlacklist() {
        setUp();
        bus.setPriority(10);
        bus.getConfig().setKey(0, STONE);
        insertIntoNetwork(busNode, DIRT, 4);
        insertIntoNetwork(busNode, STONE, 4);
        assertEquals(0, SlotInventories.count(chest, DIRT));
        assertEquals(4, SlotInventories.count(chest, STONE));
        assertEquals(4, drive.getCell(0).getAvailableStacks().get(DIRT));

        assertTrue(bus.getUpgrades().insert(UpgradeCard.INVERTER));
        insertIntoNetwork(busNode, STONE, 4);
        insertIntoNetwork(busNode, COBBLE, 4);
        assertEquals(4, SlotInventories.count(chest, STONE), "stone is blacklisted now");
        assertEquals(4, SlotInventories.count(chest, COBBLE));
        // filter on extract: blacklisted stone in the chest is no longer visible/extractable
        assertEquals(4, networkAmount(busNode, STONE), "only the drive's stone is visible");
    }

    @Test
    void readOnlyAccess() {
        setUp();
        chest.set(0, SimpleStack.of("Ingredient_Bar_Gold", 5));
        bus.setPriority(10);
        bus.setAccess(AccessRestriction.READ);
        insertIntoNetwork(busNode, STONE, 3);
        assertEquals(0, SlotInventories.count(chest, STONE));
        assertEquals(3, drive.getCell(0).getAvailableStacks().get(STONE));
        assertEquals(5, networkAmount(busNode, GOLD));
        assertEquals(2, extractFromNetwork(busNode, GOLD, 2));
        assertEquals(3, SlotInventories.count(chest, GOLD));

        bus.setAccess(AccessRestriction.WRITE);
        assertEquals(0, networkAmount(busNode, GOLD), "write-only hides contents");
        assertEquals(0, extractFromNetwork(busNode, GOLD, 1));
    }

    @Test
    void voidCardDestroysOverflow() {
        setUp();
        var tiny = new SimpleSlotAccess(1);
        bus.setTarget(ExternalStorageFacade.of(tiny));
        bus.setPriority(10);
        bus.getConfig().setKey(0, STONE);
        bus.getUpgrades().insert(UpgradeCard.VOID);
        assertEquals(100, insertIntoNetwork(busNode, STONE, 100));
        assertEquals(64, tiny.get(0).count());
        assertEquals(0, drive.getCell(0).getAvailableStacks().get(STONE), "overflow was voided, not stored");
    }

    @Test
    void targetChangeAndRemoval() {
        setUp();
        chest.set(0, SimpleStack.of("Ingredient_Bar_Gold", 5));
        world.tick();
        assertEquals(5, busNode.getGrid().getStorageService().getCachedInventory().get(GOLD));
        bus.clearTarget();
        world.tick();
        assertEquals(0, busNode.getGrid().getStorageService().getCachedInventory().get(GOLD));
        bus.setTarget(ExternalStorageFacade.of(chest));
        world.tick();
        assertEquals(5, busNode.getGrid().getStorageService().getCachedInventory().get(GOLD));
    }

    @Test
    void storageBusOnInterfaceAccessesSubnetwork() {
        // Network A (z = 0): drive with iron + interface without config
        network(0, 2, 0);
        var driveA = drive(0, 0);
        var iface = new InterfaceLogic(InterfaceLogic.BLOCK_ITEM_ID);
        var ifaceNode = partAbove(2, 0, NodeType.INTERFACE, iface);
        // Network B (z = 10): own drive + storage bus pointing at the interface
        network(0, 2, 10);
        var driveB = drive(0, 10);
        var sb = new StorageBusLogic();
        var sbNode = partAbove(1, 10, NodeType.STORAGE_BUS, sb);
        world.tick();
        assertNotSame(ifaceNode.getGrid(), sbNode.getGrid());

        insertIntoNetwork(ifaceNode, IRON, 50);
        sb.setTarget(iface.getExposedStorage());
        sb.setPriority(5);

        assertEquals(50, networkAmount(sbNode, IRON), "B sees A's whole network storage");
        assertEquals(20, extractFromNetwork(sbNode, IRON, 20));
        assertEquals(30, driveA.getCell(0).getAvailableStacks().get(IRON));
        insertIntoNetwork(sbNode, GOLD, 7);
        assertEquals(7, driveA.getCell(0).getAvailableStacks().get(GOLD), "inserted into A via the interface");
        assertEquals(0, driveB.getCell(0).getAvailableStacks().get(GOLD));

        // With a config the interface only exposes its local slots
        iface.getConfig().setStack(0, new GenericStack(IRON, 10));
        world.tick(20);
        assertEquals(10, iface.getStorage().getAmount(0));
        assertEquals(10, networkAmount(sbNode, IRON));
        assertEquals(0, networkAmount(sbNode, GOLD));
    }

    @Test
    void storageBusStateRoundTripsThroughJson() {
        var sb = new StorageBusLogic();
        sb.getConfig().setKey(3, GOLD);
        sb.getUpgrades().insert(UpgradeCard.INVERTER);
        sb.setPriority(-3);
        sb.setAccess(AccessRestriction.READ);
        var copy = new StorageBusLogic();
        copy.fromJson(sb.toJson());
        assertEquals(GOLD, copy.getConfig().getKey(3));
        assertTrue(copy.getUpgrades().isInstalled(UpgradeCard.INVERTER));
        assertEquals(-3, copy.getPriority());
        assertEquals(AccessRestriction.READ, copy.getAccess());
    }
}
