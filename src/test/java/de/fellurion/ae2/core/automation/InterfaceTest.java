/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Interface stocking (port of AE2 helpers/InterfaceLogic). */
class InterfaceTest extends AutomationTestBase {
    private InterfaceLogic iface;
    private GridNode node;

    private void setUp() {
        network(0, 2, 0);
        drive(0, 0);
        iface = new InterfaceLogic(InterfaceLogic.BLOCK_ITEM_ID, 9, id -> id.equals("Tool_Pickaxe") ? 1 : 64);
        node = partAbove(1, 0, NodeType.INTERFACE, iface);
        world.tick();
        insertIntoNetwork(node, STONE, 100);
        insertIntoNetwork(node, DIRT, 100);
    }

    @Test
    void stocksConfiguredAmountAndReturnsItWhenUnconfigured() {
        setUp();
        assertFalse(iface.hasWorkToDo());
        iface.getConfig().setStack(0, new GenericStack(STONE, 32));
        assertTrue(iface.hasWorkToDo());
        world.tick(5);
        assertEquals(new GenericStack(STONE, 32), iface.getStorage().getStack(0));
        assertEquals(68, networkAmount(node, STONE));
        assertFalse(iface.hasWorkToDo());

        // Lower the amount: surplus goes back
        iface.getConfig().setStack(0, new GenericStack(STONE, 10));
        world.tick(5);
        assertEquals(10, iface.getStorage().getAmount(0));
        assertEquals(90, networkAmount(node, STONE));

        // Remove config: everything is pushed back
        iface.getConfig().setStack(0, null);
        world.tick(5);
        assertNull(iface.getStorage().getStack(0));
        assertEquals(100, networkAmount(node, STONE));
    }

    @Test
    void wrongItemIsReplaced() {
        setUp();
        // Someone puts dirt into slot 2 through the Hytale container
        iface.getStorageSlotAccess().set(2, SimpleStack.of("Soil_Dirt", 5));
        assertEquals(5, iface.getStorage().getAmount(2));
        world.tick(5);
        assertNull(iface.getStorage().getStack(2), "unconfigured content is returned");
        assertEquals(105, networkAmount(node, DIRT), "100 + the 5 returned");

        iface.getConfig().setStack(2, new GenericStack(STONE, 4));
        assertFalse(iface.getStorageSlotAccess().isItemValid(2, SimpleStack.of("Soil_Dirt", 1)),
                "configured slot only accepts the configured item");
        world.tick(5);
        assertEquals(4, iface.getStorageSlotAccess().get(2).count());
    }

    @Test
    void configIsCappedAtOneStack() {
        setUp();
        iface.getConfig().setStack(0, new GenericStack(STONE, 500));
        assertEquals(64, iface.getConfig().getAmount(0), "items: min(max stack, 99)");
        iface.getConfig().setStack(1, new GenericStack(AEItemKey.of("Tool_Pickaxe"), 5));
        assertEquals(1, iface.getConfig().getAmount(1));
        iface.getConfig().setStack(2, new GenericStack(AEFluidKey.of("Water"), 10_000));
        assertEquals(4000, iface.getConfig().getAmount(2), "fluids: 4 buckets");
    }

    @Test
    void missingItemsAreStockedWhenTheyArrive() {
        setUp();
        iface.getConfig().setStack(0, new GenericStack(GOLD, 8));
        world.tick(10);
        assertNull(iface.getStorage().getStack(0));
        assertTrue(iface.hasWorkToDo());
        insertIntoNetwork(node, GOLD, 3);
        world.tick(130); // interface slowed down to at most 120 ticks between attempts
        assertEquals(3, iface.getStorage().getAmount(0));
        insertIntoNetwork(node, GOLD, 10);
        world.tick(130);
        assertEquals(8, iface.getStorage().getAmount(0));
        assertEquals(5, networkAmount(node, GOLD));
    }

    @Test
    void fuzzyCardAcceptsVariants() {
        setUp();
        var namedStone = AEItemKey.of("Rock_Stone", "{\"name\":\"x\"}");
        extractFromNetwork(node, STONE, 100);
        insertIntoNetwork(node, namedStone, 20);
        iface.getConfig().setStack(0, new GenericStack(STONE, 16));
        world.tick(5);
        assertNull(iface.getStorage().getStack(0), "exact match required without fuzzy card");
        assertTrue(iface.getUpgrades().insert(UpgradeCard.FUZZY));
        world.tick(130);
        assertEquals(new GenericStack(namedStone, 16), iface.getStorage().getStack(0));
    }

    @Test
    void interfaceStateRoundTripsThroughJson() {
        setUp();
        iface.getConfig().setStack(0, new GenericStack(STONE, 12));
        world.tick(5);
        iface.setPriority(4);
        var json = iface.toJson();
        var copy = new InterfaceLogic(InterfaceLogic.BLOCK_ITEM_ID);
        copy.fromJson(json);
        assertEquals(12, copy.getConfig().getAmount(0));
        assertEquals(12, copy.getStorage().getAmount(0));
        assertEquals(4, copy.getPriority());
        assertFalse(copy.hasWorkToDo());
    }
}
