/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.energy.CreativeEnergyCellLogic;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.CableType;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.spatial.SpatialPylonLogic.AxisPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pylons of a world on a real grid: lines kept up to date while pylons come and go, one channel per line, and
 * the region of each network ({@code SpatialPylonBlockEntity} + {@code SpatialPylonService}).
 */
class SpatialPylonsTest {
    private MEWorld world;
    private SpatialPylons pylons;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
        pylons = new SpatialPylons();
    }

    private static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    /** Places a pylon the way the integration layer does: register the position, then create the node. */
    private GridNode pylon(int x, int y, int z) {
        pylons.add(x, y, z);
        return world.addNode(p(x, y, z), NodeType.SPATIAL_PYLON, null, null, new SpatialPylonLogic(pylons, x, y, z));
    }

    private void breakPylon(int x, int y, int z) {
        pylons.remove(x, y, z);
        world.removeNode(p(x, y, z));
    }

    private void pylonLine(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    pylon(x, y, z);
                }
            }
        }
    }

    private void cable(int x, int y, int z) {
        world.addNode(p(x, y, z), NodeType.cable(CableType.GLASS), null, null, null);
    }

    private static SpatialPylonLogic logic(GridNode n) {
        return (SpatialPylonLogic) n.getOwner();
    }

    /**
     * Controller at the origin with a creative energy cell below, and a pylon frame around the 2x2x2 region
     * (1..2 on every axis): one line per axis, the lines start one block away from the corner at the origin.
     */
    private void frameWithController() {
        world.addNode(p(0, 0, 0), NodeType.CONTROLLER, null, null, null);
        world.addNode(p(0, -1, 0), NodeType.CREATIVE_ENERGY_CELL, null, null, new CreativeEnergyCellLogic());
        pylonLine(1, 0, 0, 3, 0, 0);
        pylonLine(0, 1, 0, 0, 3, 0);
        pylonLine(0, 0, 1, 0, 0, 3);
    }

    @Test
    void registryMatchesTheCalculatorAfterRandomChanges() {
        Random rnd = new Random(1234);
        Set<Long> present = new HashSet<>();
        for (int step = 0; step < 6000; step++) {
            int x = rnd.nextInt(5);
            int y = rnd.nextInt(3);
            int z = rnd.nextInt(5);
            long pos = p(x, y, z);
            // more additions than removals at first, then the other way round, so that lines form and break
            boolean add = rnd.nextInt(100) < (step % 400 < 200 ? 65 : 35);
            if (add) {
                present.add(pos);
                pylons.add(x, y, z);
            } else {
                present.remove(pos);
                pylons.remove(x, y, z);
            }
            assertEquals(present.size(), pylons.size());
            for (long q : present) {
                int qx = BlockPos.getX(q);
                int qy = BlockPos.getY(q);
                int qz = BlockPos.getZ(q);
                SpatialPylonCluster expected = SpatialPylonCalculator
                        .calculate((a, b, c) -> present.contains(p(a, b, c)), qx, qy, qz);
                if (!Objects.equals(expected, pylons.clusterAt(qx, qy, qz))) {
                    fail("step " + step + ": pylon " + BlockPos.toString(q) + " expected " + expected + " but was "
                            + pylons.clusterAt(qx, qy, qz));
                }
            }
        }
    }

    @Test
    void lineFormsAndBreaks() {
        pylon(0, 0, 0);
        assertNull(pylons.clusterAt(0, 0, 0));
        pylon(1, 0, 0);
        pylon(2, 0, 0);
        SpatialPylonCluster c = pylons.clusterAt(1, 0, 0);
        assertEquals(new SpatialPylonCluster(0, 0, 0, 2, 0, 0), c);
        assertEquals(c, pylons.clusterAt(0, 0, 0));
        long version = pylons.clusterVersion();

        // a pylon on the side breaks the line, removing it restores the line for all three
        pylon(1, 1, 0);
        assertNull(pylons.clusterAt(0, 0, 0));
        assertNull(pylons.clusterAt(1, 0, 0));
        assertNull(pylons.clusterAt(2, 0, 0));
        assertNotEquals(version, pylons.clusterVersion());
        breakPylon(1, 1, 0);
        assertEquals(c, pylons.clusterAt(0, 0, 0));
        assertEquals(c, pylons.clusterAt(2, 0, 0));

        // breaking the middle leaves two single pylons
        breakPylon(1, 0, 0);
        assertNull(pylons.clusterAt(0, 0, 0));
        assertNull(pylons.clusterAt(2, 0, 0));
        assertFalse(pylons.isPylon(1, 0, 0));
        assertEquals(2, pylons.size());
    }

    @Test
    void aLineSharesOneChannel() {
        // ad-hoc network (no controller): at most 8 channels. Three lines of three pylons need only three.
        world.addNode(p(0, -1, 0), NodeType.CREATIVE_ENERGY_CELL, null, null, new CreativeEnergyCellLogic());
        cable(0, 0, 0);
        pylonLine(1, 0, 0, 3, 0, 0);
        pylonLine(0, 1, 0, 0, 3, 0);
        pylonLine(0, 0, 1, 0, 0, 3);
        world.tick(40);
        for (GridNode n : world.getNodes()) {
            if (n.getOwner() instanceof SpatialPylonLogic) {
                assertTrue(n.meetsChannelRequirements(), n.toString());
                assertTrue(n.isOnline(), n.toString());
            }
        }
        List<GridNode> members = new ArrayList<>();
        logic(world.getNode(2, 0, 0)).getMultiblockNodes().forEachRemaining(members::add);
        assertEquals(3, members.size());
        assertEquals(3, world.getNode(0, 0, 0).getGrid().getPathingService().getUsedChannels());
    }

    @Test
    void regionOfTheNetwork() {
        frameWithController();
        world.tick(40);
        GridNode n = world.getNode(2, 0, 0);
        SpatialPylonService s = pylons.serviceOf(n.getGrid());
        assertTrue(s.isValidRegion());
        assertEquals(2, s.sizeX());
        assertEquals(1, s.innerMinY());
        assertEquals(9, s.pylonCount());
        assertEquals(48614, s.requiredPower());
        // idle power: 0.5 AE/t per pylon as in AE2
        assertEquals(0.5, n.getIdlePowerUsage());

        // the same object is refreshed when the frame changes
        breakPylon(0, 0, 3);
        world.tick(5);
        assertSame(s, pylons.serviceOf(n.getGrid()));
        assertEquals(8, s.pylonCount());
        assertEquals(2, s.getMaxZ());
        assertEquals(1, s.sizeZ());
        assertTrue(s.isValidRegion());
        // one more: the line along Z is a single pylon now and no longer counts, the box becomes flat
        breakPylon(0, 0, 2);
        world.tick(5);
        assertSame(s, pylons.serviceOf(n.getGrid()));
        assertEquals(6, s.pylonCount());
        assertFalse(s.isValidRegion());
    }

    @Test
    void pylonsOfAnotherNetworkDoNotCount() {
        frameWithController();
        // a second, separate network with its own line far away
        world.addNode(p(50, -1, 0), NodeType.CREATIVE_ENERGY_CELL, null, null, new CreativeEnergyCellLogic());
        cable(50, 0, 0);
        pylonLine(51, 0, 0, 53, 0, 0);
        world.tick(40);
        SpatialPylonService a = pylons.serviceOf(world.getNode(2, 0, 0).getGrid());
        SpatialPylonService b = pylons.serviceOf(world.getNode(52, 0, 0).getGrid());
        assertNotSame(a, b);
        assertEquals(9, a.pylonCount());
        assertTrue(a.isValidRegion());
        assertEquals(3, b.pylonCount());
        assertTrue(b.hasRegion());
        assertFalse(b.isValidRegion());
    }

    @Test
    void pylonsWithoutChannelDoNotCount() {
        // ad-hoc network with more than 8 devices: nobody has a channel, so there is no region
        world.addNode(p(0, -1, 0), NodeType.CREATIVE_ENERGY_CELL, null, null, new CreativeEnergyCellLogic());
        cable(0, 0, 0);
        pylonLine(1, 0, 0, 3, 0, 0);
        pylonLine(0, 1, 0, 0, 3, 0);
        pylonLine(0, 0, 1, 0, 0, 3);
        world.tick(40);
        assertTrue(pylons.serviceOf(world.getNode(0, 0, 0).getGrid()).isValidRegion());
        for (int i = 0; i < 6; i++) {
            world.addNode(p(-1 - i, 0, 0), NodeType.INTERFACE, null, null, null);
        }
        world.tick(40);
        assertFalse(world.getNode(2, 0, 0).meetsChannelRequirements());
        assertFalse(pylons.serviceOf(world.getNode(0, 0, 0).getGrid()).hasRegion());
    }

    @Test
    void displayStates() {
        GridNode lonely = pylon(20, 0, 0);
        assertEquals(SpatialPylonLogic.DisplayState.DEFAULT, logic(lonely).displayState());

        frameWithController();
        world.tick(40);
        var start = logic(world.getNode(1, 0, 0)).displayState();
        var middle = logic(world.getNode(2, 0, 0)).displayState();
        var end = logic(world.getNode(0, 0, 3)).displayState();
        assertEquals(AxisPosition.START, start.axisPosition());
        assertEquals(AxisPosition.MIDDLE, middle.axisPosition());
        assertEquals(AxisPosition.END, end.axisPosition());
        assertEquals(SpatialPylonCluster.Axis.X, middle.axis());
        assertEquals(SpatialPylonCluster.Axis.Z, end.axis());
        assertTrue(middle.powered());
        assertTrue(middle.online());

        // an invalid region: powered, but not online
        breakPylon(0, 2, 0);
        world.tick(40);
        var after = logic(world.getNode(2, 0, 0)).displayState();
        assertTrue(after.powered());
        assertFalse(after.online());
        assertEquals(SpatialPylonLogic.DisplayState.DEFAULT, logic(world.getNode(0, 1, 0)).displayState());
    }
}
