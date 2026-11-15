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
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.energy.IEnergySource;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Annihilation plane (port of AE2 AnnihilationPlanePart + ItemPickupStrategy) against a fake world: fits / does not
 * fit, energy, tick rates, entities, nothing lost.
 */
class AnnihilationPlaneTest extends AutomationTestBase {

    /** One block position: at most one block with hardness and drops, and the items dropped there. */
    static class FakeTarget implements ItemPickupStrategy.Target {
        boolean present;
        boolean breakable = true;
        float hardness = 1.5f;
        List<GenericStack> drops = List.of();
        /** What breaking really releases; null = the simulated drops. */
        List<GenericStack> released;
        final List<GenericStack> spawned = new ArrayList<>();
        final List<String> blacklist = new ArrayList<>();
        int dropQueries;
        int breaks;

        void block(float hardness, GenericStack... drops) {
            this.present = true;
            this.hardness = hardness;
            this.drops = List.of(drops);
        }

        @Override
        public boolean canHandleBlock() {
            return present && breakable;
        }

        @Override
        public float getDestroySpeed() {
            return hardness;
        }

        @Override
        public List<GenericStack> obtainBlockDrops() {
            dropQueries++;
            return drops;
        }

        @Override
        public List<GenericStack> destroyBlock(List<GenericStack> simulated) {
            if (!present) {
                return null;
            }
            present = false;
            breaks++;
            return released != null ? released : simulated;
        }

        @Override
        public void spawnDrops(List<GenericStack> stacks) {
            spawned.addAll(stacks);
        }

        @Override
        public boolean isItemBlacklisted(AEItemKey item) {
            return blacklist.contains(item.itemId());
        }
    }

    static final class FakeItem implements ItemPickupStrategy.ItemEntity {
        final AEItemKey item;
        int count;
        boolean alive = true;

        FakeItem(AEItemKey item, int count) {
            this.item = item;
            this.count = count;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public AEItemKey getItem() {
            return item;
        }

        @Override
        public int getCount() {
            return count;
        }

        @Override
        public void discard() {
            alive = false;
            count = 0;
        }

        @Override
        public void setCount(int count) {
            this.count = count;
        }
    }

    /** Energy source with a fixed amount; records what was really taken. */
    static final class FakeEnergy implements IEnergySource {
        double stored;
        double extracted;

        FakeEnergy(double stored) {
            this.stored = stored;
        }

        @Override
        public double extractAEPower(double amt, Actionable mode, PowerMultiplier multiplier) {
            double out = Math.min(stored, amt);
            if (mode == Actionable.MODULATE) {
                stored -= out;
                extracted += out;
            }
            return out;
        }
    }

    /** Sink with a capacity per key (default: unlimited). */
    static final class FakeSink implements PickupSink {
        final Map<AEKey, Long> stored = new HashMap<>();
        final Map<AEKey, Long> capacity = new HashMap<>();
        long totalCapacity = Long.MAX_VALUE;
        /** Simulations ignore {@link #totalCapacity}, like two independent simulations of a nearly full storage. */
        boolean optimisticSimulation;

        long total() {
            return stored.values().stream().mapToLong(Long::longValue).sum();
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode) {
            long free = capacity.getOrDefault(what, Long.MAX_VALUE) - stored.getOrDefault(what, 0L);
            if (!(optimisticSimulation && mode == Actionable.SIMULATE)) {
                free = Math.min(free, totalCapacity - total());
            }
            long in = Math.max(0, Math.min(free, amount));
            if (mode == Actionable.MODULATE && in > 0) {
                stored.merge(what, in, Long::sum);
            }
            return in;
        }
    }

    private static GenericStack stack(AEItemKey key, long amount) {
        return new GenericStack(key, amount);
    }

    // ------------------------------------------------------------------------------------------------ strategy

    @Test
    void breaksTheBlockWhenAllDropsFitAndChargesHardnessPlusDrops() {
        var target = new FakeTarget();
        target.block(1.5f, stack(COBBLE, 1));
        var energy = new FakeEnergy(100);
        var sink = new FakeSink();
        var strategy = new ItemPickupStrategy(target);

        assertEquals(PickupStrategy.Result.PICKED_UP, strategy.tryPickup(energy, sink));
        assertFalse(target.present, "block removed");
        assertEquals(1, sink.stored.get(COBBLE));
        assertEquals(1 + 1.5 + 1, energy.extracted, 1e-6, "AE2: 1 + hardness + number of dropped items");
        assertTrue(target.spawned.isEmpty());

        // nothing left: nothing to pick up
        assertEquals(PickupStrategy.Result.CANT_PICKUP, strategy.tryPickup(energy, sink));
        assertEquals(3.5, energy.extracted, 1e-6);
    }

    @Test
    void energyCountsEveryDroppedItem() {
        var target = new FakeTarget();
        target.block(0.5f, stack(STONE, 64), stack(DIRT, 10), stack(STONE, 6));
        var energy = new FakeEnergy(1000);
        var sink = new FakeSink();

        assertEquals(PickupStrategy.Result.PICKED_UP, new ItemPickupStrategy(target).tryPickup(energy, sink));
        assertEquals(1 + 0.5 + 80, energy.extracted, 1e-6);
        assertEquals(70, sink.stored.get(STONE));
        assertEquals(10, sink.stored.get(DIRT));
    }

    @Test
    void blockStaysWhenADropDoesNotFit() {
        var target = new FakeTarget();
        target.block(1.5f, stack(COBBLE, 4));
        var energy = new FakeEnergy(100);
        var sink = new FakeSink();
        sink.capacity.put(COBBLE, 3L);
        var strategy = new ItemPickupStrategy(target);

        assertEquals(PickupStrategy.Result.CANT_STORE, strategy.tryPickup(energy, sink));
        assertTrue(target.present, "block must stay");
        assertEquals(0, target.breaks);
        assertEquals(0, sink.total(), "the check only simulates");
        assertEquals(0, energy.extracted, 1e-9);

        // not accepting until the next reset: the world is not even looked at
        int queries = target.dropQueries;
        assertEquals(PickupStrategy.Result.CANT_PICKUP, strategy.tryPickup(energy, sink));
        assertEquals(queries, target.dropQueries);

        sink.capacity.put(COBBLE, 4L);
        strategy.reset();
        assertEquals(PickupStrategy.Result.PICKED_UP, strategy.tryPickup(energy, sink));
        assertEquals(4, sink.stored.get(COBBLE));
    }

    @Test
    void everyDropHasToFitNotJustOne() {
        // a chest: the chest itself fits, its content does not
        var target = new FakeTarget();
        target.block(2f, stack(GOLD, 1), stack(STONE, 50));
        var sink = new FakeSink();
        sink.capacity.put(STONE, 49L);
        var strategy = new ItemPickupStrategy(target);

        assertEquals(PickupStrategy.Result.CANT_STORE, strategy.tryPickup(new FakeEnergy(1000), sink));
        assertTrue(target.present);
        assertEquals(0, sink.total());
    }

    @Test
    void equalDropsAreCheckedAsOneAmount() {
        // two stacks of the same item: each would fit alone, both together do not
        var target = new FakeTarget();
        target.block(2f, stack(STONE, 30), stack(STONE, 30));
        var sink = new FakeSink();
        sink.capacity.put(STONE, 40L);

        assertEquals(PickupStrategy.Result.CANT_STORE, new ItemPickupStrategy(target).tryPickup(new FakeEnergy(1000),
                sink));
        assertTrue(target.present);
    }

    @Test
    void blockStaysWithoutEnoughEnergy() {
        var target = new FakeTarget();
        target.block(1.5f, stack(COBBLE, 1));
        var energy = new FakeEnergy(3.3); // needs 3.5
        var sink = new FakeSink();
        var strategy = new ItemPickupStrategy(target);

        assertEquals(PickupStrategy.Result.CANT_STORE, strategy.tryPickup(energy, sink));
        assertTrue(target.present);
        assertEquals(0, energy.extracted, 1e-9);

        energy.stored = 3.45; // AE2 tolerates 0.1 AE
        strategy.reset();
        assertEquals(PickupStrategy.Result.PICKED_UP, strategy.tryPickup(energy, sink));
    }

    @Test
    void unbreakableAirAndBlockWithoutDrops() {
        var target = new FakeTarget();
        var strategy = new ItemPickupStrategy(target);
        var energy = new FakeEnergy(100);
        var sink = new FakeSink();
        assertEquals(PickupStrategy.Result.CANT_PICKUP, strategy.tryPickup(energy, sink), "air");

        target.block(1f, stack(COBBLE, 1));
        target.breakable = false;
        assertEquals(PickupStrategy.Result.CANT_PICKUP, strategy.tryPickup(energy, sink), "bedrock");
        assertTrue(target.present);
        assertEquals(0, target.dropQueries);

        // a block that drops nothing is still broken (AE2: "or no drops are reported")
        target.breakable = true;
        target.drops = List.of();
        assertEquals(PickupStrategy.Result.PICKED_UP, strategy.tryPickup(energy, sink));
        assertFalse(target.present);
        assertEquals(2, energy.extracted, 1e-6);
    }

    @Test
    void whatDoesNotFitAfterBreakingIsDroppedNotLost() {
        // both simulations succeed, but together the drops do not fit
        var target = new FakeTarget();
        target.block(1f, stack(STONE, 6), stack(DIRT, 6));
        var sink = new FakeSink();
        sink.totalCapacity = 10;
        sink.optimisticSimulation = true;

        assertEquals(PickupStrategy.Result.PICKED_UP, new ItemPickupStrategy(target).tryPickup(new FakeEnergy(100),
                sink));
        assertFalse(target.present);
        assertEquals(6, sink.stored.get(STONE));
        assertEquals(4, sink.stored.get(DIRT));
        assertEquals(List.of(stack(DIRT, 2)), target.spawned, "the rest lies in the world");
    }

    @Test
    void storesWhatBreakingReallyReleased() {
        var target = new FakeTarget();
        target.block(1f, stack(STONE, 5));
        target.released = List.of(stack(STONE, 5), stack(GOLD, 2));
        var sink = new FakeSink();

        assertEquals(PickupStrategy.Result.PICKED_UP, new ItemPickupStrategy(target).tryPickup(new FakeEnergy(100),
                sink));
        assertEquals(5, sink.stored.get(STONE));
        assertEquals(2, sink.stored.get(GOLD));
    }

    @Test
    void nothingIsStoredWhenTheBlockIsGoneBeforeBreaking() {
        var target = new FakeTarget() {
            @Override
            public List<GenericStack> destroyBlock(List<GenericStack> simulated) {
                return null;
            }
        };
        target.block(1f, stack(STONE, 5));
        var energy = new FakeEnergy(100);
        var sink = new FakeSink();

        assertEquals(PickupStrategy.Result.PICKED_UP, new ItemPickupStrategy(target).tryPickup(energy, sink));
        assertEquals(0, sink.total(), "no duplicate out of thin air");
        assertEquals(0, energy.extracted, 1e-9);
    }

    @Test
    void itemEntitiesAreStoredFullyPartlyOrNotAtAll() {
        var target = new FakeTarget();
        var strategy = new ItemPickupStrategy(target);
        var energy = new FakeEnergy(100);
        var sink = new FakeSink();
        sink.capacity.put(STONE, 12L);

        var first = new FakeItem(STONE, 8);
        assertTrue(strategy.canPickUpEntity(first));
        assertFalse(strategy.canPickUpEntity("a cow"));
        assertTrue(strategy.pickUpEntity(energy, sink, first));
        assertFalse(first.alive, "fully stored: entity removed");
        assertEquals(8, sink.stored.get(STONE));

        var second = new FakeItem(STONE, 10);
        assertTrue(strategy.pickUpEntity(energy, sink, second));
        assertTrue(second.alive);
        assertEquals(6, second.count, "partly stored: the entity keeps the rest");
        assertEquals(12, sink.stored.get(STONE));

        // after an incomplete pickup nothing is accepted until the next reset
        var third = new FakeItem(DIRT, 3);
        assertFalse(strategy.pickUpEntity(energy, sink, third));
        assertEquals(3, third.count);

        strategy.reset();
        assertTrue(strategy.pickUpEntity(energy, sink, second));
        assertEquals(6, second.count, "storage full: untouched");
        assertEquals(12, sink.stored.get(STONE));
        assertEquals(12 + 6, sink.stored.get(STONE) + second.count, "nothing lost, nothing duplicated");
    }

    @Test
    void blacklistedItemsAndDeadEntitiesAreIgnored() {
        var target = new FakeTarget();
        target.blacklist.add(GOLD.itemId());
        var strategy = new ItemPickupStrategy(target);
        var sink = new FakeSink();

        var gold = new FakeItem(GOLD, 2);
        assertFalse(strategy.pickUpEntity(new FakeEnergy(10), sink, gold));
        assertEquals(2, gold.count);

        var dead = new FakeItem(STONE, 2);
        dead.alive = false;
        assertTrue(strategy.pickUpEntity(new FakeEnergy(10), sink, dead));
        assertEquals(0, sink.total());
    }

    // ------------------------------------------------------------------------------------------------ plane

    private AnnihilationPlaneLogic plane;
    private GridNode planeNode;
    private FakeTarget target;
    private CellHostLogic drive;
    private final List<FakeItem> entities = new ArrayList<>();

    private void setUp() {
        network(0, 2, 0);
        drive = drive(0, 0);
        plane = new AnnihilationPlaneLogic();
        target = new FakeTarget();
        plane.setPickupStrategies(List.of(new ItemPickupStrategy(target)));
        plane.setEntitySource(() -> List.copyOf(entities));
        planeNode = partAbove(1, 0, NodeType.ANNIHILATION_PLANE, plane);
        world.tick();
        assertTrue(planeNode.isActive());
    }

    private int rate() {
        return grid(planeNode).getTickManager().getCurrentRate(planeNode);
    }

    private boolean sleeping() {
        return grid(planeNode).getTickManager().isSleeping(planeNode);
    }

    @Test
    void sleepsWithNothingInFrontAndWakesWhenTheBlockChanges() {
        setUp();
        world.tick(130);
        assertTrue(sleeping(), "nothing to pick up: asleep, no ticks");
        int queries = target.dropQueries;
        world.tick(500);
        assertEquals(queries, target.dropQueries);

        target.block(1.5f, stack(COBBLE, 1));
        world.tick(200);
        assertTrue(target.present, "nobody told the plane");

        plane.onNeighborChanged();
        assertFalse(sleeping());
        world.tick();
        assertFalse(target.present, "picked up on the next tick");
        assertEquals(1, networkAmount(planeNode, COBBLE));
        assertEquals(TickRates.AnnihilationPlane.getMin(), rate(), "URGENT after a pickup");

        world.tick(TickRates.AnnihilationPlane.getMin());
        assertTrue(sleeping(), "air again: back to sleep");
    }

    @Test
    void picksUpBlockAfterBlockAtTheFastestRate() {
        setUp();
        target.block(0.5f, stack(DIRT, 1));
        plane.onNeighborChanged();
        world.tick();
        assertEquals(1, target.breaks);
        for (int i = 2; i <= 5; i++) {
            target.block(0.5f, stack(DIRT, 1)); // something fills the gap immediately
            world.tick(TickRates.AnnihilationPlane.getMin());
            assertEquals(i, target.breaks, "one block every 2 ticks");
        }
        assertEquals(5, networkAmount(planeNode, DIRT));
    }

    @Test
    void waitsLongWhenTheNetworkCannotStoreTheDrops() {
        setUp();
        drive.setCell(0, null); // no storage at all
        target.block(1.5f, stack(COBBLE, 1));
        plane.onNeighborChanged();
        world.tick();
        assertTrue(target.present);
        assertFalse(sleeping());
        assertEquals(TickRates.AnnihilationPlane.getMax(), rate(), "IDLE: slowest rate");
        int queries = target.dropQueries;
        world.tick(TickRates.AnnihilationPlane.getMax() - 1);
        assertEquals(queries, target.dropQueries, "no retry before the slow tick");

        drive.setCell(0, StorageCells.getCellInventory("AE2_Item_Storage_Cell_4k", new CellItemData(), null));
        world.tick(2);
        assertFalse(target.present, "retried on its next slow tick");
        assertEquals(1, networkAmount(planeNode, COBBLE));
    }

    @Test
    void insertingCostsOneAePerItemOnTopOfTheBreakingCost() {
        setUp();
        var cells = drive.getCell(0);
        assertNotNull(cells);
        target.block(1.5f, stack(COBBLE, 3));
        plane.onNeighborChanged();
        world.tick();
        // creative energy: only the result can be observed here, the amounts are covered by the strategy tests
        assertEquals(3, networkAmount(planeNode, COBBLE));
        assertTrue(target.spawned.isEmpty());
    }

    @Test
    void collectsReportedEntitiesInItsNextTick() {
        setUp();
        world.tick(130);
        assertTrue(sleeping());
        entities.add(new FakeItem(STONE, 20));
        entities.add(new FakeItem(GOLD, 3));
        world.tick(50);
        assertTrue(entities.get(0).alive, "not reported yet");

        plane.onEntitiesDetected();
        world.tick();
        assertFalse(entities.get(0).alive);
        assertFalse(entities.get(1).alive);
        assertEquals(20, networkAmount(planeNode, STONE));
        assertEquals(3, networkAmount(planeNode, GOLD));
        world.tick(TickRates.AnnihilationPlane.getMax());
        assertTrue(sleeping());
    }

    @Test
    void entityThatDoesNotFitStaysAndTheBlockIsStillTried() {
        setUp();
        drive.setCell(0, null);
        var item = new FakeItem(STONE, 20);
        entities.add(item);
        plane.onEntitiesDetected();
        world.tick();
        assertTrue(item.alive);
        assertEquals(20, item.count, "stays in the world untouched");

        // the failed entity pickup must not keep the plane from looking at the block afterwards
        drive.setCell(0, StorageCells.getCellInventory("AE2_Item_Storage_Cell_4k", new CellItemData(), null));
        entities.clear();
        target.block(1f, stack(COBBLE, 1));
        plane.onNeighborChanged();
        world.tick();
        assertFalse(target.present);
    }

    @Test
    void directCollisionWorksLikeInAe2() {
        setUp();
        var item = new FakeItem(STONE, 5);
        plane.onEntityCollision(item);
        assertFalse(item.alive);
        assertEquals(5, networkAmount(planeNode, STONE));
        plane.onEntityCollision("a cow"); // no strategy takes it
    }

    @Test
    void doesNothingWhileOffline() {
        network(0, 2, 0);
        drive(0, 0);
        plane = new AnnihilationPlaneLogic();
        target = new FakeTarget();
        plane.setPickupStrategies(List.of(new ItemPickupStrategy(target)));
        // not connected to the network: the plane's back (down) has nothing below it
        planeNode = world.addNode(p(1, 5, 0), NodeType.ANNIHILATION_PLANE, de.fellurion.ae2.core.grid.Direction.UP,
                null, plane);
        target.block(1f, stack(COBBLE, 1));
        plane.onNeighborChanged();
        world.tick(200);
        assertTrue(target.present);
        var item = new FakeItem(STONE, 5);
        plane.onEntityCollision(item);
        plane.onEntitiesDetected();
        world.tick(5);
        assertTrue(item.alive);
    }

    @Test
    void continuousGenerationReplacesPickup() {
        setUp();
        plane.setContinuousGeneration(GOLD, 1, 200);
        assertTrue(plane.isContinuouslyGenerating());
        target.block(1f, stack(COBBLE, 1));
        plane.onNeighborChanged();
        world.tick(1000);
        assertTrue(target.present, "a generating plane picks nothing up");
        long gold = networkAmount(planeNode, GOLD);
        assertTrue(gold >= 4 && gold <= 5, "1 per 200 ticks, got " + gold);

        plane.setContinuousGeneration(null, 0, 0);
        plane.onNeighborChanged();
        world.tick(2);
        assertFalse(target.present);
    }
}
