/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.machines.BuddingQuartzLogic.Bud;
import de.fellurion.ae2.core.machines.BuddingQuartzLogic.BudStage;
import de.fellurion.ae2.core.machines.BuddingQuartzLogic.Empty;
import de.fellurion.ae2.core.machines.BuddingQuartzLogic.Grade;
import de.fellurion.ae2.core.machines.BuddingQuartzLogic.Other;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/** Crank, crystal resonance generator, growth accelerator and budding quartz. */
class PowerSourcesTest {
    @BeforeEach
    void setUp() {
        AEConfig.reset();
    }

    @Test
    void crankAdds160AePerTurn() {
        var buffer = new PoweredMachineBuffer(1600);
        var target = buffer.crankable();
        var crank = new CrankLogic();
        assertTrue(crank.power(target));
        assertFalse(crank.power(target), "still rotating (rotation >= 3)");
        for (int i = 0; i < CrankLogic.TICKS_PER_ROTATION - 1; i++) {
            crank.tick(target);
        }
        assertEquals(0, buffer.getAECurrentPower());
        crank.tick(target);
        assertEquals(CrankLogic.POWER_PER_CRANK_TURN, buffer.getAECurrentPower());
        assertFalse(crank.isTurning());

        // fill completely: 10 turns
        for (int turn = 1; turn < 10; turn++) {
            assertTrue(crank.power(target));
            for (int i = 0; i < CrankLogic.TICKS_PER_ROTATION; i++) {
                crank.tick(target);
            }
        }
        assertTrue(buffer.isFull());
        // forcing a full machine more than 10 times breaks the crank
        for (int i = 0; i < 10; i++) {
            assertFalse(crank.power(target));
            assertFalse(crank.shouldBreak());
        }
        crank.power(target);
        assertTrue(crank.shouldBreak());
    }

    @Test
    void onlyStrongestResonanceGeneratorInjects() {
        var world = new MEWorld();
        var cell = EnergyCellLogic.denseEnergyCell();
        world.addNode(BlockPos.asLong(0, 0, 0), NodeType.DENSE_ENERGY_CELL, null, null, cell);
        var weak = new CrystalResonanceGeneratorLogic();
        var strong = new CrystalResonanceGeneratorLogic(() -> 50.0);
        assertEquals(20.0, weak.getRate(), 1e-9);
        // back side must face the cell
        world.addNode(BlockPos.asLong(1, 0, 0), NodeType.CRYSTAL_RESONANCE_GENERATOR, Direction.EAST, null, weak);
        world.addNode(BlockPos.asLong(-1, 0, 0), NodeType.CRYSTAL_RESONANCE_GENERATOR, Direction.WEST, null,
                strong);
        assertEquals(1, world.getGrids().size());
        var energy = world.getNode(0, 0, 0).getGrid().getEnergyService();

        world.tick(10);
        assertTrue(weak.isSuppressed());
        assertFalse(strong.isSuppressed());
        assertEquals(500, energy.getStoredPower(), 1e-6, "10 ticks * 50 AE/t");

        // removing the strong one activates the weak one
        world.removeNode(BlockPos.asLong(-1, 0, 0));
        world.tick(10);
        assertFalse(weak.isSuppressed());
        assertTrue(energy.getStoredPower() >= 500 + 10 * 20 - 50 - 1e-6);
    }

    @Test
    void growthAcceleratorUsesCrankPowerAndTicksNeighbours() {
        var ticked = new EnumMap<Direction, Integer>(Direction.class);
        var logic = new GrowthAcceleratorLogic(d -> ticked.merge(d, 1, Integer::sum));
        var world = new MEWorld();
        world.addNode(BlockPos.asLong(0, 0, 0), NodeType.GROWTH_ACCELERATOR, Direction.UP, null, logic);
        assertFalse(logic.isPowered());
        logic.onTick(10);
        assertTrue(ticked.isEmpty());

        logic.getCrankable().applyTurn(); // 160 AE
        assertTrue(logic.isPowered());
        logic.onTick(10); // -80
        logic.onTick(10); // -80
        assertEquals(2, ticked.get(Direction.UP));
        assertEquals(6, ticked.size());
        assertFalse(logic.isPowered());
        logic.onTick(10);
        assertEquals(2, ticked.get(Direction.NORTH));

        // with a powered grid it works without local power (idle drain is 8 AE/t through the node)
        world.addNode(BlockPos.asLong(0, 1, 0), NodeType.DENSE_ENERGY_CELL, null, null, charged());
        world.tick(30); // ticks every 10 game ticks
        assertTrue(logic.isPowered());
        assertTrue(ticked.get(Direction.NORTH) >= 4, "ticked " + ticked);
        assertEquals(8.0, world.getNode(0, 0, 0).getGrid().getEnergyService().getIdlePowerUsage(), 1e-9);
    }

    private static EnergyCellLogic charged() {
        var c = EnergyCellLogic.denseEnergyCell();
        c.setAECurrentPower(1_000_000);
        return c;
    }

    @Test
    void buddingQuartzDecaysThroughAllGrades() {
        var random = new Random(4711);
        Grade grade = Grade.FLAWED;
        int growths = 0;
        int ticks = 0;
        var seen = new java.util.ArrayList<Grade>();
        seen.add(grade);
        while (grade != null && ticks < 1_000_000) {
            var result = BuddingQuartzLogic.randomTick(grade, random, d -> new Empty(false));
            ticks++;
            if (result != null) {
                growths++;
                assertEquals(BudStage.SMALL, result.newStage());
                if (result.decayed()) {
                    grade = result.newGrade();
                    seen.add(grade);
                }
            }
        }
        assertNull(grade, "damaged budding quartz becomes a plain quartz block");
        assertEquals(java.util.Arrays.asList(Grade.FLAWED, Grade.CHIPPED, Grade.DAMAGED, null), seen);
        // 3 decays with p = 1/12 per growth -> ~36 growths, growth needs p = 1/5 per tick
        assertTrue(growths > 3 && growths < 200, "growths: " + growths);
        assertTrue(ticks > growths * 2, "ticks: " + ticks);
    }

    @Test
    void flawlessNeverDecaysAndGrowthRateIsOneFifth() {
        var random = new Random(1);
        int growths = 0;
        int n = 50_000;
        for (int i = 0; i < n; i++) {
            var r = BuddingQuartzLogic.randomTick(Grade.FLAWLESS, random, d -> new Empty(true));
            if (r != null) {
                growths++;
                assertFalse(r.decayed());
                assertTrue(r.waterlogged());
                assertEquals(Grade.FLAWLESS, r.newGrade());
            }
        }
        assertEquals(n / 5.0, growths, n * 0.01);
    }

    /** Deterministic generator returning queued nextInt(bound) values. */
    static final class Scripted implements RandomGenerator {
        final ArrayDeque<Integer> values = new ArrayDeque<>();

        Scripted(int... v) {
            for (int i : v) {
                values.add(i);
            }
        }

        @Override
        public int nextInt(int bound) {
            return values.poll();
        }

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void budsGrowOnlyWhenFacingAway() {
        int east = Direction.EAST.ordinal();
        // growth roll 0 (success), direction east, decay roll 5 (no decay)
        var r = BuddingQuartzLogic.randomTick(Grade.FLAWED, new Scripted(0, east, 5),
                d -> new Bud(BudStage.SMALL, Direction.EAST));
        assertNotNull(r);
        assertEquals(Direction.EAST, r.direction());
        assertEquals(BudStage.MEDIUM, r.newStage());
        assertFalse(r.decayed());

        // a bud facing another direction does not grow
        assertNull(BuddingQuartzLogic.randomTick(Grade.FLAWED, new Scripted(0, east),
                d -> new Bud(BudStage.SMALL, Direction.UP)));
        // clusters are final, other blocks block growth
        assertNull(BuddingQuartzLogic.randomTick(Grade.FLAWED, new Scripted(0, east),
                d -> new Bud(BudStage.CLUSTER, Direction.EAST)));
        assertNull(BuddingQuartzLogic.randomTick(Grade.FLAWED, new Scripted(0, east), d -> new Other()));
        // failed growth roll
        assertNull(BuddingQuartzLogic.randomTick(Grade.FLAWED, new Scripted(3), d -> new Empty(false)));
        // decay roll 0: flawed -> chipped
        var decayed = BuddingQuartzLogic.randomTick(Grade.FLAWED, new Scripted(0, east, 0),
                d -> new Bud(BudStage.LARGE, Direction.EAST));
        assertEquals(BudStage.CLUSTER, decayed.newStage());
        assertTrue(decayed.decayed());
        assertEquals(Grade.CHIPPED, decayed.newGrade());
    }
}
