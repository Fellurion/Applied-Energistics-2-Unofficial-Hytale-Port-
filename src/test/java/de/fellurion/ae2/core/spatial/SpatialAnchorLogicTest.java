/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.CableType;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The Spatial Anchor (port of {@code SpatialAnchorBlockEntity}) on a real grid with a recording chunk loader. */
class SpatialAnchorLogicTest {
    private MEWorld world;
    private final Set<Long> forced = new HashSet<>();
    private GridNode anchorNode;
    private SpatialAnchorLogic anchor;
    private EnergyCellLogic cell;

    /** 32 block chunks, packed like the integration layer does. */
    private static long chunk(int x, int z) {
        return ((long) (x >> 5) << 32) | ((z >> 5) & 0xFFFFFFFFL);
    }

    private static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
        forced.clear();
        anchorNode = null;
        anchor = new SpatialAnchorLogic(new SpatialAnchorLogic.ChunkLoader() {
            @Override
            public boolean forceChunk(long chunk) {
                return forced.add(chunk);
            }

            @Override
            public boolean releaseChunk(long chunk) {
                return forced.remove(chunk);
            }
        }, new SpatialAnchorLogic.Network() {
            @Override
            public boolean hasGridBooted() {
                return anchorNode != null && anchorNode.hasGridBooted();
            }

            @Override
            public boolean isOnline() {
                return anchorNode != null && anchorNode.isOnline();
            }

            @Override
            public Set<Long> chunks() {
                if (anchorNode == null || anchorNode.getGrid() == null) {
                    return null;
                }
                Set<Long> set = new HashSet<>();
                for (GridNode n : anchorNode.getGrid().getNodes()) {
                    set.add(chunk(BlockPos.getX(n.getPos()), BlockPos.getZ(n.getPos())));
                }
                return set;
            }

            @Override
            public void setIdlePowerUsage(double usage) {
                if (anchorNode != null) {
                    anchorNode.setIdlePowerUsage(usage);
                }
            }
        });
    }

    /** Controller with an energy cell, the anchor next to it and a cable line along +X up to {@code lastX}. */
    private void build(int lastX) {
        world.addNode(p(0, 0, 0), NodeType.CONTROLLER, null, null, null);
        cell = EnergyCellLogic.denseEnergyCell();
        cell.setAECurrentPower(1_000_000);
        world.addNode(p(0, -1, 0), NodeType.DENSE_ENERGY_CELL, null, null, cell);
        anchorNode = world.addNode(p(0, 0, 1), NodeType.SPATIAL_ANCHOR, null, null, anchor);
        for (int x = 1; x <= lastX; x++) {
            world.addNode(p(x, 0, 0), NodeType.cable(CableType.GLASS), null, null, null);
        }
    }

    @Test
    void powerFormula() {
        assertEquals(80, SpatialAnchorLogic.powerFor(0));
        assertEquals(81, SpatialAnchorLogic.powerFor(1));
        assertEquals(83, SpatialAnchorLogic.powerFor(2));
        assertEquals(135, SpatialAnchorLogic.powerFor(10));
    }

    @Test
    void forcesTheChunksOfTheNetwork() {
        build(70); // x 0..70: chunks 0, 1 and 2
        world.tick(100);
        assertTrue(anchorNode.isOnline());
        assertEquals(Set.of(chunk(0, 0), chunk(32, 0), chunk(64, 0)), forced);
        assertEquals(forced, anchor.getLoadedChunks());
        assertEquals(3, anchor.countLoadedChunks());
        // 80 + 3 * 4 / 2
        assertEquals(86.0, anchorNode.getIdlePowerUsage());
        assertEquals(86, anchor.currentPower());
    }

    @Test
    void followsTheNetworkWhenItChanges() {
        build(40);
        world.tick(100);
        assertEquals(2, forced.size());

        // the network grows into a third chunk
        for (int x = 41; x <= 64; x++) {
            world.addNode(p(x, 0, 0), NodeType.cable(CableType.GLASS), null, null, null);
        }
        world.tick(3);
        assertEquals(Set.of(chunk(0, 0), chunk(32, 0), chunk(64, 0)), forced);
        assertEquals(86.0, anchorNode.getIdlePowerUsage());

        // cut the line inside the first chunk: the other chunks are no longer part of the network
        world.removeNode(p(5, 0, 0));
        world.tick(3);
        assertEquals(Set.of(chunk(0, 0)), forced);
        assertEquals(81.0, anchorNode.getIdlePowerUsage());
    }

    @Test
    void releasesEverythingAfterPowerLoss() {
        build(40);
        world.tick(100);
        assertEquals(2, forced.size());

        world.removeNode(p(0, -1, 0)); // the energy cell
        world.tick(100);
        assertFalse(anchorNode.isOnline());
        // lenient: the chunks stay for more than 200 ticks
        assertEquals(2, forced.size());
        world.tick(200);
        assertTrue(forced.isEmpty());
        assertEquals(0, anchor.countLoadedChunks());

        // power back: the state change wakes the anchor and the chunks are forced again
        cell = EnergyCellLogic.denseEnergyCell();
        cell.setAECurrentPower(1_000_000);
        world.addNode(p(0, -1, 0), NodeType.DENSE_ENERGY_CELL, null, null, cell);
        world.tick(100);
        assertTrue(anchorNode.isOnline());
        assertEquals(2, forced.size());
    }

    @Test
    void removingTheAnchorReleasesAll() {
        build(40);
        world.tick(100);
        assertEquals(2, forced.size());
        world.removeNode(p(0, 0, 1));
        anchor.onRemoved();
        assertTrue(forced.isEmpty());
        // and it never forces again
        anchor.tickingRequest(anchorNode, 20);
        assertTrue(forced.isEmpty());
    }

    @Test
    void registeredChunksCountForPower() {
        build(10);
        anchor.registerChunk(chunk(0, 0));
        anchor.registerChunk(chunk(320, 0));
        assertEquals(83.0, anchorNode.getIdlePowerUsage());
        world.tick(100);
        // the clean-up drops the chunk the network is not in
        assertEquals(Set.of(chunk(0, 0)), anchor.getLoadedChunks());
        assertEquals(81.0, anchorNode.getIdlePowerUsage());
    }
}
