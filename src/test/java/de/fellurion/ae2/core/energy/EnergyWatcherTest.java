/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.energy;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Energy watchers of the energy service (ports of AE2 EnergyWatcher / EnergyThreshold). */
class EnergyWatcherTest {
    private MEWorld world;
    private EnergyCellLogic cell;

    /** Node owner without idle drain worth mentioning: counts the notifications. */
    private static final class Host implements IEnergyWatcherNode {
        IEnergyWatcher watcher;
        int updates;
        int passes;
        double seen;

        @Override
        public void updateWatcher(IEnergyWatcher newWatcher) {
            watcher = newWatcher;
            updates++;
        }

        @Override
        public void onThresholdPass(EnergyService energyGrid) {
            passes++;
            seen = energyGrid.getStoredPower();
        }
    }

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
        cell = EnergyCellLogic.energyCell();
        cell.setAECurrentPower(50_000);
        world.addNode(BlockPos.asLong(0, 0, 0), NodeType.ENERGY_CELL, null, null, cell);
    }

    private Host host(int x) {
        var host = new Host();
        // an energy acceptor node: no idle drain, connects on all sides
        world.addNode(BlockPos.asLong(x, 0, 0), NodeType.ENERGY_ACCEPTOR, null, null, host);
        return host;
    }

    @Test
    void notifiedWhenStoredEnergyPassesAThreshold() {
        var host = host(1);
        assertNotNull(host.watcher, "handed over when the node joins a grid");
        assertTrue(host.watcher.add(40_000));
        assertFalse(host.watcher.add(40_000), "already watched");
        world.tick(3);
        int base = host.passes; // the first tick compares against "0 AE"

        world.tick(5);
        assertEquals(base, host.passes, "no change: nothing to report");

        cell.setAECurrentPower(39_000);
        world.tick();
        assertEquals(base + 1, host.passes, "fell below 40k");
        assertTrue(host.seen < 40_000);

        cell.setAECurrentPower(30_000);
        world.tick();
        assertEquals(base + 1, host.passes, "40k is not between 39k and 30k");

        cell.setAECurrentPower(45_000);
        world.tick();
        assertEquals(base + 2, host.passes, "rose above 40k");

        assertTrue(host.watcher.remove(40_000));
        assertFalse(host.watcher.remove(40_000));
        cell.setAECurrentPower(10_000);
        world.tick();
        assertEquals(base + 2, host.passes, "no longer watched");
    }

    @Test
    void severalWatchersAndThresholds() {
        var a = host(1);
        var b = host(-1);
        a.watcher.add(10_000);
        a.watcher.add(20_000);
        b.watcher.add(20_000);
        world.tick(3);
        int baseA = a.passes;
        int baseB = b.passes;

        cell.setAECurrentPower(15_000); // from 50k: passes 20k only
        world.tick();
        assertEquals(baseA + 1, a.passes);
        assertEquals(baseB + 1, b.passes);

        cell.setAECurrentPower(5_000); // passes 10k
        world.tick();
        assertEquals(baseA + 2, a.passes);
        assertEquals(baseB + 1, b.passes);

        a.watcher.reset();
        cell.setAECurrentPower(60_000); // passes both
        world.tick();
        assertEquals(baseA + 2, a.passes, "reset: watches nothing");
        assertEquals(baseB + 2, b.passes);
        assertThrows(IllegalArgumentException.class, () -> a.watcher.add(-1));
    }

    @Test
    void newThresholdIsReportedOnceEvenWithoutAPass() {
        var host = host(1);
        world.tick(3);
        assertEquals(0, host.passes, "nothing watched yet");
        host.watcher.add(40_000);
        world.tick();
        assertEquals(1, host.passes, "told the stored energy once after registering");
        assertTrue(host.seen > 40_000);
        world.tick(5);
        assertEquals(1, host.passes);
        // a second watcher registering later triggers one more report for everyone who watches something
        var other = host(-1);
        other.watcher.add(10);
        world.tick();
        assertEquals(1, other.passes);
        assertEquals(2, host.passes);
        var idle = host(2);
        world.tick(2);
        assertEquals(0, idle.passes, "a watcher without thresholds hears nothing");
    }

    @Test
    void watcherFollowsTheNodeToAnotherGrid() {
        var host = host(2); // not adjacent to the cell: its own grid
        assertEquals(1, host.updates);
        var first = host.watcher;
        first.add(40_000);
        // connect both grids
        world.addNode(BlockPos.asLong(1, 0, 0), NodeType.ENERGY_ACCEPTOR, null, null, null);
        assertTrue(host.updates >= 2, "new grid: new watcher");
        assertNotSame(first, host.watcher);
        host.watcher.add(40_000);
        world.tick(3);
        int base = host.passes;
        cell.setAECurrentPower(1_000);
        world.tick();
        assertEquals(base + 1, host.passes);

        world.removeNode(BlockPos.asLong(2, 0, 0));
        cell.setAECurrentPower(60_000);
        world.tick();
        assertEquals(base + 1, host.passes, "removed nodes hear nothing");
    }
}
