/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.FENCE;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.FENCES;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.INGOT;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.ORE;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.PLANK;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.SMELT;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.STICK;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.STICKS;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.p;
import static de.fellurion.ae2.core.crafting.CraftingUnitLogic.UnitType.ACCELERATOR;
import static de.fellurion.ae2.core.crafting.CraftingUnitLogic.UnitType.MONITOR;
import static de.fellurion.ae2.core.crafting.CraftingUnitLogic.UnitType.STORAGE_1K;
import static de.fellurion.ae2.core.crafting.CraftingUnitLogic.UnitType.STORAGE_4K;
import static de.fellurion.ae2.core.crafting.CraftingUnitLogic.UnitType.UNIT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end autocrafting: CPU formation, job submission, pattern pushing through a pattern provider into a molecular
 * assembler (crafting pattern) and a test machine (processing pattern), results returned via the provider.
 */
class CraftingExecutionTest {
    @BeforeEach
    void setUp() {
        AEConfig.reset();
    }

    @Test
    void cpuFormationStorageAndCoprocessors() {
        var net = new CraftingTestNetwork(List.of(SMELT), STORAGE_4K, ACCELERATOR, ACCELERATOR, MONITOR);
        var cpus = net.crafting().getCpus();
        assertEquals(1, cpus.size());
        var cpu = cpus.getFirst();
        assertEquals(4096, cpu.getAvailableStorage());
        assertEquals(2, cpu.getCoProcessors());
        assertTrue(net.units.stream().allMatch(CraftingUnitLogic::isFormed));
        // the multiblock shares one channel: all CPU blocks are active
        for (var u : net.units) {
            assertTrue(u.getNode().isActive(), "unit should be active: " + u.getType());
        }
    }

    @Test
    void cpuWithoutStorageDoesNotForm() {
        var net = new CraftingTestNetwork(List.of(SMELT), UNIT, ACCELERATOR);
        assertTrue(net.crafting().getAllCpus().isEmpty());
        assertFalse(net.units.getFirst().isFormed());
    }

    @Test
    void nonCuboidCpuDoesNotForm() {
        var net = new CraftingTestNetwork(List.of(SMELT), STORAGE_1K, UNIT);
        assertEquals(1, net.crafting().getAllCpus().size());
        // add a block above the second unit -> L shape (3 blocks, bbox volume 4)
        var extra = new CraftingUnitLogic(UNIT);
        net.world.addNode(p(6, 2, 0), NodeType.CRAFTING_UNIT, null, null, extra);
        net.world.tick();
        assertTrue(net.crafting().getAllCpus().isEmpty());
        // complete the 2x2 square -> valid again
        var extra2 = new CraftingUnitLogic(UNIT);
        net.world.addNode(p(5, 2, 0), NodeType.CRAFTING_UNIT, null, null, extra2);
        net.world.tick();
        assertEquals(1, net.crafting().getAllCpus().size());
        assertEquals(1024, net.crafting().getAllCpus().getFirst().getAvailableStorage());
    }

    @Test
    void endToEndTwoLevelJob() {
        var net = new CraftingTestNetwork(List.of(STICKS, FENCES), STORAGE_1K, ACCELERATOR, MONITOR);
        net.insert(PLANK, 10);
        net.world.tick();

        var cs = net.crafting();
        var plan = cs.calculate(net.src, FENCE, 4, CalculationStrategy.REPORT_MISSING_ITEMS);
        var result = cs.submitJob(plan, null, null, true, net.src);
        assertTrue(result.successful(), () -> "submit failed: " + result);
        var cpu = cs.getCpus().getFirst();
        assertTrue(cpu.isBusy());
        // ingredients were moved into the CPU
        assertEquals(3, net.stored(PLANK));
        assertEquals(7, cpu.craftingLogic.getStored(PLANK));
        // monitor shows the job
        var monitor = net.units.stream().filter(u -> u.getType() == MONITOR).findFirst().orElseThrow();
        assertEquals(new GenericStack(FENCE, 4), monitor.getDisplayedJob());
        assertNotNull(cpu.getJobStatus());

        for (int i = 0; i < 400 && cpu.isBusy(); i++) {
            net.world.tick();
        }
        assertFalse(cpu.isBusy(), "job should be finished");
        assertEquals(4, net.stored(FENCE));
        assertEquals(3, net.stored(PLANK));
        assertEquals(0, net.stored(STICK));
        assertTrue(cpu.craftingLogic.getInventory().list.isEmpty());
        assertNull(monitor.getDisplayedJob());
        // the assembler crafted 3 times, the machine processed 4 fences
        assertEquals(4, net.machine.pushTicks.size());
    }

    @Test
    void submitFailsWhenCpuTooSmall() {
        var net = new CraftingTestNetwork(List.of(SMELT), STORAGE_1K);
        net.insert(ORE, 2000);
        net.world.tick();
        var plan = net.crafting().calculate(net.src, INGOT, 1000, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertFalse(plan.simulation());
        assertTrue(plan.bytes() > 1024);
        var result = net.crafting().submitJob(plan, null, null, true, net.src);
        assertEquals(CraftingSubmitErrorCode.NO_SUITABLE_CPU_FOUND, result.errorCode());
        assertEquals(new UnsuitableCpus(0, 0, 1, 0), result.errorDetail());
        assertEquals(2000, net.stored(ORE));
    }

    @Test
    void submitFailsWithoutCpu() {
        var net = new CraftingTestNetwork(List.of(SMELT));
        net.insert(ORE, 5);
        net.world.tick();
        var plan = net.crafting().calculate(net.src, INGOT, 5, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertEquals(CraftingSubmitErrorCode.NO_CPU_FOUND, net.crafting().submitJob(plan, null, null, true, net.src)
                .errorCode());
    }

    private List<Long> runSmeltJob(CraftingUnitLogic.UnitType... cpu) {
        var net = new CraftingTestNetwork(List.of(SMELT), cpu);
        net.insert(ORE, 8);
        net.world.tick();
        var cs = net.crafting();
        var plan = cs.calculate(net.src, INGOT, 8, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(cs.submitJob(plan, null, null, true, net.src).successful());
        var cpuCluster = cs.getCpus().getFirst();
        for (int i = 0; i < 100 && cpuCluster.isBusy(); i++) {
            net.world.tick();
        }
        assertFalse(cpuCluster.isBusy());
        assertEquals(8, net.stored(INGOT));
        assertEquals(0, net.stored(ORE));
        return net.machine.pushTicks;
    }

    @Test
    void cpuOperationsPerTickWithoutCoprocessor() {
        var ticks = runSmeltJob(STORAGE_1K);
        assertEquals(8, ticks.size());
        // coProcessors + 1 = 1 pattern within any window of 4 ticks
        for (int i = 1; i < ticks.size(); i++) {
            assertEquals(4, ticks.get(i) - ticks.get(i - 1), "pushes at " + ticks);
        }
    }

    @Test
    void cpuOperationsPerTickWithAccelerators() {
        var ticks = runSmeltJob(STORAGE_1K, ACCELERATOR, ACCELERATOR, ACCELERATOR);
        assertEquals(8, ticks.size());
        // 3 co-processors: bursts of 4 patterns in one tick, then 3 idle ticks
        assertEquals(ticks.get(0), ticks.get(3));
        assertEquals(ticks.get(4), ticks.get(7));
        assertEquals(4, ticks.get(4) - ticks.get(0));
    }

    @Test
    void autoSelectionPrefersFastCpu() {
        var net = new CraftingTestNetwork(List.of(SMELT), STORAGE_1K);
        // second CPU with an accelerator, separate cuboid (not adjacent to the first)
        var u1 = new CraftingUnitLogic(STORAGE_1K);
        var u2 = new CraftingUnitLogic(ACCELERATOR);
        net.world.addNode(p(0, 0, 1), NodeType.cable(de.fellurion.ae2.core.grid.CableType.GLASS), null, null, null);
        net.world.addNode(p(0, 0, 2), NodeType.CRAFTING_STORAGE, null, null, u1);
        net.world.addNode(p(0, 0, 3), NodeType.CRAFTING_ACCELERATOR, null, null, u2);
        net.world.tick(2);
        assertEquals(2, net.crafting().getCpus().size());
        net.insert(ORE, 3);
        net.world.tick();
        var plan = net.crafting().calculate(net.src, INGOT, 1, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(net.crafting().submitJob(plan, null, null, true, net.src).successful());
        assertTrue(u1.getCluster().isBusy(), "prioritizePower picks the CPU with co-processors");
        var plan2 = net.crafting().calculate(net.src, INGOT, 1, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(net.crafting().submitJob(plan2, null, null, false, net.src).successful());
        assertTrue(net.units.getFirst().getCluster().isBusy());
        var plan3 = net.crafting().calculate(net.src, INGOT, 1, CalculationStrategy.REPORT_MISSING_ITEMS);
        var res3 = net.crafting().submitJob(plan3, null, null, true, net.src);
        assertEquals(CraftingSubmitErrorCode.NO_SUITABLE_CPU_FOUND, res3.errorCode());
        assertEquals(2, ((UnsuitableCpus) res3.errorDetail()).busy());
    }

    @Test
    void breakingCpuCancelsJobAndReturnsItems() {
        var net = new CraftingTestNetwork(List.of(SMELT), STORAGE_1K, UNIT);
        net.machine.process = false; // machine swallows inputs, results never come back
        net.insert(ORE, 6);
        net.world.tick();
        var cs = net.crafting();
        var plan = cs.calculate(net.src, INGOT, 6, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(cs.submitJob(plan, null, null, true, net.src).successful());
        net.world.tick(5); // two patterns pushed (ticks 1 and 5)
        assertEquals(0, net.stored(ORE));
        var pushed = net.machine.received.get(ORE);
        assertTrue(pushed >= 1 && pushed < 6, "pushed " + pushed);

        var drops = new ArrayList<GenericStack>();
        CraftingCpuCluster.setDropHandler((c, s) -> drops.addAll(s));
        try {
            net.units.get(1).onBlockBroken();
            net.world.removeNode(p(6, 1, 0));
            // remaining ore back in the network, nothing dropped
            assertEquals(6 - pushed, net.stored(ORE));
            assertTrue(drops.isEmpty());
            net.world.tick();
            // the remaining storage block forms a new 1-block CPU
            assertEquals(1, cs.getCpus().size());
            assertFalse(cs.getCpus().getFirst().isBusy());
        } finally {
            CraftingCpuCluster.setDropHandler(null);
        }
    }

    @Test
    void requesterReceivesFinalOutput() {
        var net = new CraftingTestNetwork(List.of(SMELT), STORAGE_1K);
        net.insert(ORE, 3);
        net.world.tick();
        var requester = new TestRequester();
        // the requester must be a node of the grid (otherwise the link dies, AE2 CraftingLinkNexus.isDead)
        net.world.addNode(p(4, 1, 0), NodeType.INTERFACE, null, null, requester);
        net.world.tick();
        var plan = net.crafting().calculate(net.src, INGOT, 3, CalculationStrategy.REPORT_MISSING_ITEMS);
        var result = net.crafting().submitJob(plan, requester, null, true, net.src);
        assertTrue(result.successful());
        assertNotNull(result.link());
        requester.links.add(result.link());
        for (int i = 0; i < 60 && !result.link().isDone(); i++) {
            net.world.tick();
        }
        assertTrue(result.link().isDone());
        assertEquals(3, requester.received);
        assertEquals(0, net.stored(INGOT)); // all went to the requester
        assertTrue(requester.stateChanges > 0);
    }

    @Test
    void cpuStatePersistsAcrossReload() {
        var net = new CraftingTestNetwork(List.of(SMELT), STORAGE_1K);
        net.machine.process = false;
        net.insert(ORE, 5);
        net.world.tick();
        var plan = net.crafting().calculate(net.src, INGOT, 5, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(net.crafting().submitJob(plan, null, null, true, net.src).successful());
        net.world.tick(); // one pattern pushed
        var core = net.units.getFirst();
        var json = core.getClusterStateToSave();
        assertNotNull(json);

        // "reload": a new world with the saved state on the core block
        var net2 = new CraftingTestNetwork(List.of(SMELT));
        net2.machine.process = false;
        var restored = new CraftingUnitLogic(STORAGE_1K);
        restored.setSavedClusterState(json);
        net2.world.addNode(p(5, 1, 0), NodeType.CRAFTING_STORAGE, null, null, restored);
        net2.world.tick(2);
        var cpu = restored.getCluster();
        assertNotNull(cpu);
        assertTrue(cpu.isBusy());
        // restored: 4 ore, 4 tasks, 1 awaited ingot; the CPU continued right away with one more push
        assertEquals(1, net2.machine.received.get(ORE));
        assertEquals(3, cpu.craftingLogic.getStored(ORE));
        assertEquals(3, cpu.craftingLogic.getRemainingTaskCount());
        assertEquals(2, cpu.craftingLogic.getWaitingFor(INGOT));
        assertEquals(new GenericStack(INGOT, 5), cpu.craftingLogic.getFinalJobOutput());
        // finish: the ingot that was in the machine before the reload arrives as well
        net2.machine.process = true;
        net2.insert(INGOT, 1);
        for (int i = 0; i < 60 && cpu.isBusy(); i++) {
            net2.world.tick();
        }
        assertFalse(cpu.isBusy());
        assertEquals(5, net2.stored(INGOT));
        assertEquals(0, net2.stored(ORE));
    }

    @Test
    void molecularAssemblerSpeed() {
        var net = new CraftingTestNetwork(List.of(STICKS));
        net.world.tick(50); // let the assembler fall asleep for a while
        assertEquals(10, craftOnce(net));
        net.world.tick(10); // provider returns the sticks into the network
        assertEquals(4, net.stored(STICK));
        for (int i = 0; i < 5; i++) {
            assertTrue(net.assembler.getUpgrades().insert(de.fellurion.ae2.core.cells.UpgradeCard.SPEED));
        }
        assertFalse(net.assembler.getUpgrades().insert(de.fellurion.ae2.core.cells.UpgradeCard.SPEED));
        // 5 acceleration cards: 50 progress per tick
        assertEquals(2, craftOnce(net));
        net.world.tick(10);
        assertEquals(8, net.stored(STICK));
    }

    private static int craftOnce(CraftingTestNetwork net) {
        var inputs = new de.fellurion.ae2.core.stacks.KeyCounter[] { new de.fellurion.ae2.core.stacks.KeyCounter() };
        inputs[0].add(PLANK, 1);
        assertTrue(net.assembler.acceptsPlans());
        assertTrue(net.assembler.pushPattern(STICKS, inputs, net.provider));
        assertFalse(net.assembler.acceptsPlans());
        int ticks = 0;
        while (net.assembler.isWorking() && ticks < 100) {
            net.world.tick();
            ticks++;
        }
        return ticks;
    }

    @Test
    void blockingModeWaitsUntilMachineIsEmpty() {
        var net = new CraftingTestNetwork(List.of(SMELT), STORAGE_1K, ACCELERATOR, ACCELERATOR, ACCELERATOR);
        var blockingTarget = new BlockingTarget();
        net.provider.setTargetSupplier(() -> List.of(blockingTarget));
        net.provider.setBlocking(true);
        net.insert(ORE, 3);
        net.world.tick();
        var plan = net.crafting().calculate(net.src, INGOT, 3, CalculationStrategy.REPORT_MISSING_ITEMS);
        assertTrue(net.crafting().submitJob(plan, null, null, true, net.src).successful());
        net.world.tick(8);
        // although the CPU could push 4 per tick, blocking mode allows only one until the machine is emptied
        assertEquals(1, blockingTarget.content.size());
        blockingTarget.content.clear();
        net.world.tick(8);
        assertEquals(1, blockingTarget.content.size());
    }

    /** Machine that keeps its inputs (for blocking mode). */
    private static final class BlockingTarget implements PatternProviderTarget {
        final List<AEKey> content = new ArrayList<>();

        @Override
        public long insert(AEKey what, long amount, Actionable type) {
            if (type == Actionable.MODULATE) {
                content.add(what);
            }
            return amount;
        }

        @Override
        public boolean containsPatternInput(Set<AEKey> patternInputs) {
            return content.stream().anyMatch(patternInputs::contains);
        }
    }

    private static final class TestRequester implements ICraftingRequester,
            de.fellurion.ae2.core.grid.IGridNodeListener {
        final Set<ICraftingLink> links = new HashSet<>();
        long received;
        int stateChanges;
        de.fellurion.ae2.core.grid.GridNode node;

        @Override
        public void onNodeAttached(de.fellurion.ae2.core.grid.GridNode node) {
            this.node = node;
        }

        @Override
        public Set<ICraftingLink> getRequestedJobs() {
            return links;
        }

        @Override
        public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
            if (mode == Actionable.MODULATE) {
                received += amount;
            }
            return amount;
        }

        @Override
        public void jobStateChange(ICraftingLink link) {
            stateChanges++;
        }

        @Override
        public de.fellurion.ae2.core.grid.GridNode getActionableNode() {
            return node;
        }
    }
}
