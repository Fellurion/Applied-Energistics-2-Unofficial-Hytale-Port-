/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.machines.SkyStoneTankLogic;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Buses on a Sky Stone Tank: fluids move 125 mB per operation (AE2: {@code AEKeyType.getAmountPerOperation}), fluid
 * cells take fluids only, item cells items only.
 */
class FluidBusTest extends AutomationTestBase {
    private static final AEFluidKey WATER = AEFluidKey.of("Water_Source");
    private static final AEFluidKey LAVA = AEFluidKey.of("Lava_Source");

    /** A drive below the cable with a 4k fluid cell in slot 0 and a 4k item cell in slot 1. */
    private CellHostLogic fluidDrive(int x, int z) {
        var drive = CellHostLogic.drive();
        world.addNode(p(x, -1, z), NodeType.DRIVE, null, null, drive);
        drive.setCell(0, StorageCells.getCellInventory("AE2_Fluid_Storage_Cell_4k", new CellItemData(), null));
        drive.setCell(1, StorageCells.getCellInventory("AE2_Item_Storage_Cell_4k", new CellItemData(), null));
        return drive;
    }

    @Test
    void cellsKeepItemsAndFluidsApart() {
        network(0, 2, 0);
        var drive = fluidDrive(0, 0);
        GridNode node = world.getNode(p(0, 0, 0));
        world.tick();

        assertEquals(5000, insertIntoNetwork(node, WATER, 5000));
        assertEquals(10, insertIntoNetwork(node, STONE, 10));
        var fluidCell = drive.getCell(0).getAvailableStacks();
        var itemCell = drive.getCell(1).getAvailableStacks();
        assertEquals(5000, fluidCell.get(WATER));
        assertEquals(0, fluidCell.get(STONE));
        assertEquals(10, itemCell.get(STONE));
        assertEquals(0, itemCell.get(WATER));

        // without a fluid cell the network takes no fluid, without an item cell no items
        drive.setCell(0, null);
        assertEquals(0, insertIntoNetwork(node, LAVA, 1000));
        drive.setCell(1, null);
        drive.setCell(0, StorageCells.getCellInventory("AE2_Fluid_Storage_Cell_4k", new CellItemData(), null));
        assertEquals(0, insertIntoNetwork(node, STONE, 1));
        assertEquals(1000, insertIntoNetwork(node, LAVA, 1000));
    }

    @Test
    void importBusDrainsTheTank125mBPerOperation() {
        network(0, 2, 0);
        fluidDrive(0, 0);
        var bus = new ImportBusLogic();
        GridNode node = partAbove(1, 0, NodeType.IMPORT_BUS, bus);
        var tank = new SkyStoneTankLogic();
        tank.insert(WATER, 3000, Actionable.MODULATE);
        bus.setTarget(tank.getStorage());
        world.tick();
        assertTrue(node.isActive());

        bus.tickingRequest(node, 1);
        assertEquals(2875, tank.getAmount());
        assertEquals(125, networkAmount(node, WATER));

        // one acceleration card: 8 operations = one bucket per round
        assertTrue(bus.getUpgrades().insert(UpgradeCard.SPEED));
        bus.tickingRequest(node, 1);
        assertEquals(1875, tank.getAmount());
        assertEquals(1125, networkAmount(node, WATER));

        world.tick(400);
        assertTrue(tank.isEmpty());
        assertNull(tank.getFluid());
        assertEquals(3000, networkAmount(node, WATER));
    }

    @Test
    void exportBusFillsTheTankFromItsFilter() {
        network(0, 2, 0);
        fluidDrive(0, 0);
        var bus = new ExportBusLogic();
        GridNode node = partAbove(1, 0, NodeType.EXPORT_BUS, bus);
        var tank = new SkyStoneTankLogic();
        bus.setTarget(tank.getStorage());
        world.tick();
        insertIntoNetwork(node, WATER, 20_000);
        insertIntoNetwork(node, LAVA, 2000);

        // an export bus exports only what is in its filter
        bus.tickingRequest(node, 1);
        assertTrue(tank.isEmpty());

        bus.getConfig().setKey(0, LAVA);
        bus.getConfig().setKey(1, WATER);
        bus.tickingRequest(node, 1);
        assertEquals(LAVA, tank.getFluid());
        assertEquals(125, tank.getAmount());

        world.tick(2000);
        // the tank took the lava first; water does not mix into it
        assertEquals(LAVA, tank.getFluid());
        assertEquals(2000, tank.getAmount());
        assertEquals(20_000, networkAmount(node, WATER));

        // a water-only filter fills a second tank up to its 16 buckets, the rest stays in the network
        var second = new SkyStoneTankLogic();
        bus.getConfig().setKey(0, null);
        bus.setTarget(second.getStorage());
        for (int i = 0; i < 4; i++) {
            bus.getUpgrades().insert(UpgradeCard.SPEED);
        }
        world.tick(2000);
        assertEquals(WATER, second.getFluid());
        assertEquals(16_000, second.getAmount());
        assertEquals(4000, networkAmount(node, WATER));
    }

    @Test
    void storageBusMountsTheTank() {
        network(0, 2, 0);
        var bus = new StorageBusLogic();
        GridNode node = partAbove(1, 0, NodeType.STORAGE_BUS, bus);
        var tank = new SkyStoneTankLogic();
        tank.insert(WATER, 4000, Actionable.MODULATE);
        bus.setTarget(tank.getStorage());
        world.tick();
        assertTrue(node.isActive());

        assertEquals(4000, networkAmount(node, WATER));
        // the network stores into the tank and takes from it; no cell is needed
        assertEquals(12_000, insertIntoNetwork(node, WATER, 50_000));
        assertEquals(16_000, tank.getAmount());
        assertEquals(0, insertIntoNetwork(node, LAVA, 1000));
        assertEquals(0, insertIntoNetwork(node, STONE, 1));
        assertEquals(16_000, extractFromNetwork(node, WATER, 99_000));
        assertTrue(tank.isEmpty());
    }
}
