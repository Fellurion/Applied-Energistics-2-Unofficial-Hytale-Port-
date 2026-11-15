/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.automation.LevelEmitterLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IStackWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.*;
import static de.fellurion.ae2.core.crafting.CraftingUnitLogic.UnitType.STORAGE_4K;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Level emitter with a crafting card (AE2 StorageLevelEmitterPart: "emit while crafting" / "emit to craft") and the
 * crafting watchers of the crafting service.
 */
class LevelEmitterCraftingTest {
    private final List<Boolean> outputs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AEConfig.reset();
    }

    /** Emitter on the free cable at x=4, with a crafting card. */
    private LevelEmitterLogic craftingEmitter(CraftingTestNetwork net, AEKey key) {
        var emitter = new LevelEmitterLogic();
        emitter.setOutputListener(outputs::add);
        emitter.getConfig().setKey(0, key);
        emitter.setReportingValue(1_000_000); // ignored with a crafting card
        assertTrue(emitter.getUpgrades().insert(UpgradeCard.CRAFTING));
        GridNode node = net.world.addNode(p(4, 1, 0), NodeType.LEVEL_EMITTER, Direction.UP, null, emitter);
        net.world.tick(2);
        assertTrue(node.isActive());
        return emitter;
    }

    @Test
    void emitsWhileTheItemIsBeingCrafted() {
        var net = new CraftingTestNetwork(List.of(SMELT), STORAGE_4K);
        var emitter = craftingEmitter(net, INGOT);
        assertFalse(emitter.isEmitting(), "nothing is being crafted");
        assertFalse(net.crafting().canEmitFor(INGOT), "\"emit while crafting\" offers nothing to the calculation");

        net.machine.process = false; // the machine keeps the ore: the job stays open
        net.insert(ORE, 3);
        net.world.tick();
        var plan = net.crafting().calculate(net.src, INGOT, 3, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(net.crafting().submitJob(plan, null, null, true, net.src).successful());
        net.world.tick(5);
        assertTrue(net.crafting().isRequesting(INGOT));
        assertTrue(emitter.isEmitting(), "a CPU waits for ingots");

        net.machine.process = true;
        net.machine.processAll();
        var cpu = net.crafting().getCpus().getFirst();
        for (int i = 0; i < 100 && cpu.isBusy(); i++) {
            net.world.tick();
        }
        net.world.tick();
        assertFalse(cpu.isBusy());
        assertFalse(emitter.isEmitting(), "the job is done");
        // The CPU only waits for the results of patterns it has already pushed, so the output may drop in between.
        assertTrue(outputs.size() >= 2 && outputs.size() % 2 == 0, outputs::toString);
        assertTrue(outputs.getFirst());
        assertFalse(outputs.getLast());
    }

    @Test
    void emitterWatchingAnotherItemStaysOff() {
        var net = new CraftingTestNetwork(List.of(SMELT), STORAGE_4K);
        var emitter = craftingEmitter(net, PLANK);
        net.machine.process = false;
        net.insert(ORE, 1);
        net.world.tick();
        var plan = net.crafting().calculate(net.src, INGOT, 1, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(net.crafting().submitJob(plan, null, null, true, net.src).successful());
        net.world.tick(5);
        assertFalse(emitter.isEmitting());

        // without a key it reacts to any request
        emitter.getConfig().setKey(0, null);
        assertTrue(emitter.isEmitting());
        // removing the card returns to the threshold (1M of everything is not there)
        emitter.getUpgrades().remove(0);
        assertFalse(emitter.isEmitting());
    }

    @Test
    void emitToCraftOffersTheItemAndWaitsForDelivery() {
        var net = new CraftingTestNetwork(List.of(), STORAGE_4K);
        var emitter = craftingEmitter(net, INGOT);
        assertFalse(net.crafting().isCraftable(INGOT));

        emitter.setCraftViaRedstone(true);
        assertTrue(net.crafting().canEmitFor(INGOT));
        assertTrue(net.crafting().isCraftable(INGOT), "the emitter makes the item requestable");

        var plan = net.crafting().calculate(net.src, INGOT, 5, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertFalse(plan.simulation());
        assertEquals(5, plan.emittedItems().get(INGOT));
        assertTrue(net.crafting().submitJob(plan, null, null, true, net.src).successful());
        net.world.tick(2);
        assertTrue(emitter.isEmitting(), "the job waits for the emitted item");

        // whatever the signal switches on delivers the items into the network
        net.insert(INGOT, 3);
        net.world.tick(2);
        assertTrue(emitter.isEmitting(), "2 are still missing");
        net.insert(INGOT, 2);
        var cpu = net.crafting().getCpus().getFirst();
        for (int i = 0; i < 50 && cpu.isBusy(); i++) {
            net.world.tick();
        }
        net.world.tick();
        assertFalse(emitter.isEmitting());
        assertEquals(5, net.stored(INGOT), "the job handed its result to the network");

        emitter.setCraftViaRedstone(false);
        assertFalse(net.crafting().canEmitFor(INGOT));
        emitter.setCraftViaRedstone(true);
        emitter.getUpgrades().remove(0);
        assertFalse(net.crafting().canEmitFor(INGOT), "no card: nothing is offered");
    }

    @Test
    void craftingWatcherReportsStartAndEndOfRequests() {
        var net = new CraftingTestNetwork(List.of(SMELT), STORAGE_4K);
        var seen = new ArrayList<String>();
        var host = new WatcherHost(seen);
        host.service = net.crafting();
        net.world.addNode(p(4, 1, 0), NodeType.LEVEL_EMITTER, Direction.UP, null, host);
        assertNotNull(host.watcher, "the node got its watcher when it joined the grid");
        host.watcher.add(INGOT);
        net.world.tick(2);

        net.machine.process = false;
        net.insert(ORE, 2);
        net.world.tick();
        var plan = net.crafting().calculate(net.src, INGOT, 2, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(net.crafting().submitJob(plan, null, null, true, net.src).successful());
        net.world.tick(3);
        assertEquals(List.of("Ingot_Iron:true"), seen);

        net.machine.process = true;
        net.machine.processAll();
        net.world.tick(20);
        // every start and every end of waiting is reported, alternating
        assertTrue(seen.size() >= 2 && seen.size() % 2 == 0, seen::toString);
        for (int i = 0; i < seen.size(); i++) {
            assertEquals("Ingot_Iron:" + (i % 2 == 0), seen.get(i));
        }
        int reported = seen.size();

        // a watcher that was reset hears nothing
        host.watcher.reset();
        net.insert(ORE, 1);
        net.world.tick();
        var plan2 = net.crafting().calculate(net.src, INGOT, 1, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(net.crafting().submitJob(plan2, null, null, true, net.src).successful());
        net.world.tick(20);
        assertEquals(reported, seen.size());
    }

    private static final class WatcherHost implements ICraftingWatcherNode {
        final List<String> seen;
        IStackWatcher watcher;
        CraftingService service;

        WatcherHost(List<String> seen) {
            this.seen = seen;
        }

        @Override
        public void updateCraftingWatcher(IStackWatcher newWatcher) {
            watcher = newWatcher;
        }

        @Override
        public void onRequestChange(AEKey what) {
            seen.add(what.getId() + ":" + service.isRequesting(what));
        }
    }
}
