/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.automation.InterfaceLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.GenericStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.INGOT;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.ORE;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.SMELT;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.p;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Crafting card of an interface wired to the crafting service through {@link MultiCraftingTracker}.
 */
class CraftingCardTest {
    @BeforeEach
    void setUp() {
        AEConfig.reset();
    }

    @Test
    void interfaceWithCraftingCardRequestsMissingItems() {
        var net = new CraftingTestNetwork(List.of(SMELT), CraftingUnitLogic.UnitType.STORAGE_1K);
        net.insert(ORE, 10);

        var iface = new InterfaceLogic(InterfaceLogic.BLOCK_ITEM_ID);
        var tracker = new MultiCraftingTracker(iface, 9,
                (slot, what, amount, mode) -> iface.getStorage().insert(slot, what, amount, mode));
        iface.setCraftingHook(tracker);
        assertTrue(iface.getUpgrades().insert(UpgradeCard.CRAFTING));
        net.world.addNode(p(4, 1, 0), NodeType.INTERFACE, null, null, iface);
        net.world.tick();

        // stock 4 ingots: none in the network → the interface requests a crafting job
        iface.getConfig().setStack(0, new GenericStack(INGOT, 4));
        for (int i = 0; i < 200 && iface.getStorage().getAmount(0) < 4; i++) {
            net.world.tick();
        }
        assertEquals(INGOT, iface.getStorage().getKey(0));
        assertEquals(4, iface.getStorage().getAmount(0));
        assertEquals(6, net.stored(ORE));
        assertEquals(0, net.stored(INGOT), "the crafted ingots go directly into the interface");
        assertFalse(tracker.isBusy(0));
        assertTrue(tracker.getRequestedJobs().isEmpty());
    }

    @Test
    void noRequestWithoutPatternOrCard() {
        var net = new CraftingTestNetwork(List.of(), CraftingUnitLogic.UnitType.STORAGE_1K);
        var iface = new InterfaceLogic(InterfaceLogic.BLOCK_ITEM_ID);
        var tracker = new MultiCraftingTracker(iface, 9,
                (slot, what, amount, mode) -> iface.getStorage().insert(slot, what, amount, mode));
        iface.setCraftingHook(tracker);
        iface.getUpgrades().insert(UpgradeCard.CRAFTING);
        net.world.addNode(p(4, 1, 0), NodeType.INTERFACE, null, null, iface);
        iface.getConfig().setStack(0, new GenericStack(INGOT, 4));
        net.world.tick(20);
        assertFalse(tracker.isBusy(0));
        assertNull(iface.getStorage().getKey(0));
        assertFalse(tracker.requestCrafting(0, INGOT, 4), "not craftable");
    }
}
