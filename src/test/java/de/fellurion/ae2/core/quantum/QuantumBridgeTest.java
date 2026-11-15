/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.quantum;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.energy.CreativeEnergyCellLogic;
import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.CableType;
import de.fellurion.ae2.core.grid.ControllerState;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridConnection;
import de.fellurion.ae2.core.grid.GridFlag;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.quantum.QuantumBridgeLogic.Formation;
import de.fellurion.ae2.core.quantum.QuantumCluster.LinkState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The quantum network bridge (ports of AE2 {@code QuantumBridgeBlockEntity}, {@code QuantumCluster},
 * {@code QuantumCalculator}): forming the 3x3 ring, which blocks connect to cables, and the link between two bridges
 * with singularities of the same frequency.
 */
class QuantumBridgeTest {
    /** The plane a bridge stands in, by its two axes. */
    enum Plane {
        XY(Direction.EAST, Direction.UP, Direction.SOUTH),
        XZ(Direction.EAST, Direction.SOUTH, Direction.UP),
        YZ(Direction.UP, Direction.SOUTH, Direction.EAST);

        final Direction u;
        final Direction v;
        /** Perpendicular to the plane. */
        final Direction normal;

        Plane(Direction u, Direction v, Direction normal) {
            this.u = u;
            this.v = v;
            this.normal = normal;
        }

        long at(long center, int du, int dv) {
            return BlockPos.offset(center, u.dx * du + v.dx * dv, u.dy * du + v.dy * dv, u.dz * du + v.dz * dv);
        }
    }

    /** A bridge built for a test: the logic of every block by its offset in the plane. */
    private final class Bridge {
        final long center;
        final Plane plane;
        final Map<Long, QuantumBridgeLogic> blocks = new HashMap<>();

        Bridge(long center, Plane plane) {
            this.center = center;
            this.plane = plane;
        }

        long pos(int du, int dv) {
            return plane.at(center, du, dv);
        }

        QuantumBridgeLogic block(int du, int dv) {
            return blocks.get(pos(du, dv));
        }

        QuantumBridgeLogic link() {
            return block(0, 0);
        }

        GridNode linkNode() {
            return link().getNode();
        }

        void place(int du, int dv) {
            long pos = pos(du, dv);
            blocks.put(pos, QuantumBridgeTest.this.place(pos, du == 0 && dv == 0));
        }

        void remove(int du, int dv) {
            world.removeNode(pos(du, dv));
            blocks.remove(pos(du, dv));
        }

        /** Like a chunk unload: all blocks leave the world. */
        void unload() {
            for (long pos : List.copyOf(blocks.keySet())) {
                world.removeNode(pos);
            }
        }

        /** Like a chunk load: new block logics, the link chamber reads its singularity again. */
        void load(long frequency) {
            blocks.clear();
            build(this);
            link().setQEFrequency(frequency);
        }

        boolean formed() {
            return blocks.size() == 9 && blocks.values().stream().allMatch(QuantumBridgeLogic::isFormed);
        }

        QuantumCluster cluster() {
            return link().getCluster();
        }
    }

    private MEWorld world;
    private QuantumBridgeRegistry registry;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
        registry = QuantumBridgeRegistry.create();
    }

    static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private QuantumBridgeLogic place(long pos, boolean link) {
        var logic = new QuantumBridgeLogic(link, registry);
        world.addNode(pos, logic.nodeType(), null, null, logic);
        return logic;
    }

    private static void build(Bridge b) {
        for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
                b.place(du, dv);
            }
        }
    }

    private Bridge bridge(int x, int y, int z, Plane plane) {
        var b = new Bridge(p(x, y, z), plane);
        build(b);
        return b;
    }

    private GridNode cable(long pos, CableType type) {
        return world.addNode(pos, NodeType.cable(type), null, AEColor.TRANSPARENT, null);
    }

    /** Controller with a creative energy cell below it. */
    private GridNode controller(long pos) {
        var controller = world.addNode(pos, NodeType.CONTROLLER, null, null, null);
        world.addNode(BlockPos.offset(pos, Direction.DOWN), NodeType.CREATIVE_ENERGY_CELL, null, null,
                new CreativeEnergyCellLogic());
        return controller;
    }

    /** Enough ticks for forming and linking (two to three), without waiting for power. */
    private void settle() {
        world.tick(6);
    }

    private static GridConnection virtualConnection(Bridge a, Bridge b) {
        return a.linkNode().getVirtualConnection(b.linkNode());
    }

    private static void assertLinked(Bridge a, Bridge b) {
        assertNotNull(virtualConnection(a, b), "connection between the two link chambers");
        assertSame(a.linkNode().getGrid(), b.linkNode().getGrid());
        assertEquals(LinkState.LINKED, a.cluster().getLinkState());
        assertEquals(LinkState.LINKED, b.cluster().getLinkState());
        assertSame(b.cluster(), a.cluster().getOtherCluster());
        assertSame(a.cluster(), b.cluster().getOtherCluster());
        long virtual = a.linkNode().getConnections().stream().filter(c -> !c.isInWorld()).count();
        assertEquals(1, virtual, "exactly one connection");
    }

    private static void assertSeparate(Bridge a, Bridge b) {
        assertNull(virtualConnection(a, b));
        assertNotSame(a.linkNode().getGrid(), b.linkNode().getGrid());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Forming

    @ParameterizedTest
    @EnumSource(Plane.class)
    void formsInEveryPlane(Plane plane) {
        var b = bridge(10, 20, 30, plane);
        assertFalse(b.formed(), "forming happens after the tick");
        settle();

        assertTrue(b.formed());
        var cluster = b.cluster();
        assertNotNull(cluster);
        assertSame(b.link(), cluster.getCenter());
        assertEquals(b.pos(-1, -1), cluster.getBoundsMin());
        assertEquals(b.pos(1, 1), cluster.getBoundsMax());
        for (var block : b.blocks.values()) {
            assertSame(cluster, block.getCluster());
            assertEquals(Formation.FORMED, block.checkFormation());
        }
        // The four diagonal rings are the corners
        for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
                assertEquals(du != 0 && dv != 0, b.block(du, dv).isCorner(), du + "," + dv);
            }
        }
        // All nine blocks are one network
        assertEquals(1, world.getGrids().size());
        assertEquals(9, b.linkNode().getGrid().size());
        assertEquals(LinkState.NO_SINGULARITY, cluster.getLinkState());
    }

    @Test
    void unformedBlocksConnectToNothing() {
        var b = new Bridge(p(0, 0, 0), Plane.XY);
        for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
                if (du != 1 || dv != 1) {
                    b.place(du, dv);
                }
            }
        }
        var cable = cable(b.plane.at(b.center, 2, 0), CableType.SMART_DENSE);
        settle();

        assertEquals(9, world.getGrids().size(), "eight single blocks and the cable");
        for (var block : b.blocks.values()) {
            assertFalse(block.isFormed());
            assertNull(block.getCluster());
            assertTrue(block.getNode().getConnections().isEmpty());
            assertTrue(block.getGridConnectableSides().isEmpty());
            // AE2 grows the area from the block: depending on where it starts, the gap makes the area too small
            // or leaves a hole in it
            assertTrue(Set.of(Formation.WRONG_LAYOUT, Formation.WRONG_SHAPE).contains(block.checkFormation()));
        }
        assertEquals(Formation.WRONG_SHAPE, b.link().checkFormation());
        assertTrue(cable.getConnections().isEmpty());

        // The last ring completes the bridge: the cable at the middle ring of the east side connects
        b.place(1, 1);
        settle();
        assertTrue(b.formed());
        assertTrue(cable.hasConnection(b.block(1, 0).getNode()));
        assertEquals(1, world.getGrids().size());
    }

    @ParameterizedTest
    @EnumSource(Plane.class)
    void cornersAndLinkChamberDoNotConnectToCables(Plane plane) {
        var b = bridge(0, 100, 0, plane);
        var n = plane.normal;
        // Cables against the faces of the bridge, perpendicular to its plane
        var atCorner = cable(BlockPos.offset(b.pos(-1, -1), n), CableType.GLASS);
        var atLink = cable(BlockPos.offset(b.pos(0, 0), n), CableType.GLASS);
        var atEdge = cable(BlockPos.offset(b.pos(0, 1), n), CableType.GLASS);
        // ... and in the plane: outside a corner and outside the middle ring of a side
        var besideCorner = cable(plane.at(b.center, 2, 1), CableType.GLASS);
        var besideEdge = cable(plane.at(b.center, -2, 0), CableType.GLASS);
        settle();

        assertTrue(b.formed());
        assertTrue(atCorner.getConnections().isEmpty(), "corner ring");
        assertFalse(atLink.hasConnection(b.linkNode()), "link chamber");
        assertTrue(besideCorner.getConnections().isEmpty(), "corner ring, in the plane");
        assertTrue(atEdge.hasConnection(b.block(0, 1).getNode()));
        assertTrue(besideEdge.hasConnection(b.block(-1, 0).getNode()));

        assertEquals(EnumSet.allOf(Direction.class), b.block(0, 1).getGridConnectableSides());
        assertEquals(2, b.block(1, 1).getGridConnectableSides().size());
        assertEquals(Set.of(plane.u, plane.u.getOpposite(), plane.v, plane.v.getOpposite()),
                b.link().getGridConnectableSides());
        assertEquals(2, b.block(1, 1).getNode().getConnections().size(), "a corner joins its two neighbours");
        assertEquals(4, b.linkNode().getConnections().size(), "the link chamber joins the four middle rings");
    }

    @Test
    void blocksAreDenseAndDraw22AEEach() {
        var b = bridge(0, 0, 0, Plane.XZ);
        controller(p(-3, 0, 0));
        cable(p(-2, 0, 0), CableType.SMART_DENSE);
        world.tick(60);

        assertTrue(b.formed());
        for (var block : b.blocks.values()) {
            assertTrue(block.getNode().hasFlag(GridFlag.DENSE_CAPACITY));
            assertFalse(block.getNode().hasFlag(GridFlag.REQUIRE_CHANNEL));
            assertEquals(22.0, block.getNode().getIdlePowerUsage());
            assertTrue(block.isPowered());
        }
        var grid = b.linkNode().getGrid();
        assertEquals(12, grid.size(), "bridge, cable, controller and energy cell");
        // 9 x 22 for the bridge, 3 for the controller
        assertEquals(9 * 22.0 + 3.0, grid.getEnergyService().getIdlePowerUsage(), 1e-9);
    }

    @Test
    void wrongLayoutsDoNotForm() {
        // Nine rings without a link chamber
        var noLink = new Bridge(p(0, 0, 0), Plane.XY);
        for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
                noLink.blocks.put(noLink.pos(du, dv), place(noLink.pos(du, dv), false));
            }
        }
        // The link chamber in a corner instead of the center
        var linkInCorner = new Bridge(p(20, 0, 0), Plane.XY);
        for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
                linkInCorner.blocks.put(linkInCorner.pos(du, dv), place(linkInCorner.pos(du, dv), du == 1 && dv == 1));
            }
        }
        // A hole where the area is not grown along: the 3x3 square is found, but a ring is missing
        var holed = new Bridge(p(80, 0, 0), Plane.XY);
        build(holed);
        holed.remove(-1, 1);
        // A row of three and a 2x2 square
        var row = List.of(place(p(40, 0, 0), false), place(p(41, 0, 0), true), place(p(42, 0, 0), false));
        var square = List.of(place(p(60, 0, 0), false), place(p(61, 0, 0), false), place(p(60, 1, 0), false),
                place(p(61, 1, 0), true));
        settle();

        for (var block : noLink.blocks.values()) {
            assertFalse(block.isFormed());
            assertEquals(Formation.WRONG_LAYOUT, block.checkFormation());
        }
        for (var block : linkInCorner.blocks.values()) {
            assertFalse(block.isFormed());
            assertEquals(Formation.WRONG_LAYOUT, block.checkFormation());
        }
        for (var block : holed.blocks.values()) {
            assertFalse(block.isFormed());
        }
        assertEquals(Formation.WRONG_LAYOUT, holed.link().checkFormation());
        for (var block : row) {
            assertFalse(block.isFormed());
            assertEquals(Formation.WRONG_SHAPE, block.checkFormation());
        }
        for (var block : square) {
            assertFalse(block.isFormed());
            assertEquals(Formation.WRONG_SHAPE, block.checkFormation());
        }
        assertEquals(9 + 9 + 8 + 3 + 4, world.getGrids().size(), "nothing connected");
    }

    @Test
    void aTenthBlockAgainstTheRingBreaksTheBridge() {
        var b = bridge(0, 0, 0, Plane.XY);
        settle();
        assertTrue(b.formed());

        // On the face of a middle ring, outside the plane
        long extraPos = BlockPos.offset(b.pos(0, -1), Direction.SOUTH);
        var extra = place(extraPos, false);
        settle();
        assertFalse(extra.isFormed());
        for (var block : b.blocks.values()) {
            assertFalse(block.isFormed(), "bridge broken by the tenth block");
            assertTrue(block.getNode().getConnections().isEmpty());
        }
        assertEquals(Formation.TOUCHED, b.link().checkFormation());

        world.removeNode(extraPos);
        settle();
        assertTrue(b.formed(), "formed again once the extra block is gone");

        // In the plane, next to a corner: the area is no 3x3 square any more
        long besideCorner = b.plane.at(b.center, 2, -1);
        place(besideCorner, false);
        settle();
        assertFalse(b.link().isFormed());
        world.removeNode(besideCorner);
        settle();
        assertTrue(b.formed());

        // Touching only diagonally does not count
        place(b.plane.at(b.center, 2, 2), false);
        settle();
        assertTrue(b.formed());
    }

    @Test
    void twoBridgesNeedAGapBetweenThem() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(4, 0, 0, Plane.XY);
        settle();
        assertTrue(a.formed());
        assertTrue(b.formed());
        assertNotSame(a.cluster(), b.cluster());
        assertEquals(2, world.getGrids().size());

        // Directly side by side they are a 6x3 area: neither forms
        var c = bridge(0, 10, 0, Plane.XY);
        var d = bridge(3, 10, 0, Plane.XY);
        settle();
        assertFalse(c.link().isFormed());
        assertFalse(d.link().isFormed());
    }

    @Test
    void removingAnyBlockDissolvesTheBridgeAndPuttingItBackFormsItAgain() {
        for (int[] offset : new int[][] {{1, 1}, {0, 1}, {0, 0}}) {
            setUp();
            var b = bridge(0, 0, 0, Plane.YZ);
            var cable = cable(b.plane.at(b.center, 0, 2), CableType.SMART_DENSE);
            settle();
            assertTrue(b.formed());
            var first = b.cluster();
            assertTrue(cable.hasConnection(b.block(0, 1).getNode()));

            b.remove(offset[0], offset[1]);
            assertTrue(first.isDestroyed(), "destroyed at once");
            settle();
            for (var block : b.blocks.values()) {
                assertFalse(block.isFormed());
                assertNull(block.getCluster());
                assertTrue(block.getNode().getConnections().isEmpty(), "unformed blocks are not connected");
            }
            assertTrue(cable.getConnections().isEmpty());

            b.place(offset[0], offset[1]);
            settle();
            assertTrue(b.formed());
            assertNotSame(first, b.cluster());
            assertTrue(cable.hasConnection(b.block(0, 1).getNode()));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Link

    @Test
    void sameFrequencyLinksTwoBridges() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(100, 40, -300, Plane.XZ);
        settle();
        assertSeparate(a, b);
        assertEquals(LinkState.NO_SINGULARITY, a.cluster().getLinkState());

        a.link().setQEFrequency(4711);
        settle();
        assertSeparate(a, b);
        assertEquals(LinkState.WAITING, a.cluster().getLinkState());
        assertNull(a.cluster().getOtherCluster());
        assertEquals(4711, a.cluster().getFrequency());

        b.link().setQEFrequency(4711);
        assertSeparate(a, b);
        settle();
        assertLinked(a, b);
        assertEquals(1, world.getGrids().size());
        assertEquals(18, a.linkNode().getGrid().size());
        // The two bridges hold the two places of the frequency
        assertEquals(-4711, a.cluster().getThisSide());
        assertEquals(4711, a.cluster().getOtherSide());
        assertEquals(4711, b.cluster().getThisSide());
        assertSame(a.cluster(), registry.get(-4711));
        assertSame(b.cluster(), registry.get(4711));

        // More status updates do not create more connections
        var connection = virtualConnection(a, b);
        a.cluster().updateStatus(true);
        b.cluster().updateStatus(true);
        world.tick(40);
        assertSame(connection, virtualConnection(a, b));
        assertFalse(connection.isDestroyed());
        assertLinked(a, b);
    }

    @Test
    void theLinkNeedsNoPower() {
        // AE2 (QnbTestPlots.simpleQnbLink): two bare bridges link without any energy source
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(4, 0, 0, Plane.XY);
        a.link().setQEFrequency(1);
        b.link().setQEFrequency(1);
        world.tick(60);
        assertLinked(a, b);
        assertFalse(a.link().isPowered());
    }

    @Test
    void losingPowerDoesNotUnlink() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(50, 0, 0, Plane.XY);
        controller(p(-3, 0, 0));
        cable(p(-2, 0, 0), CableType.SMART_DENSE);
        a.link().setQEFrequency(1);
        b.link().setQEFrequency(1);
        world.tick(60);
        assertLinked(a, b);
        assertTrue(b.link().isPowered());
        var connection = virtualConnection(a, b);

        world.removeNode(p(-3, -1, 0)); // the energy cell
        world.tick(60);
        assertFalse(a.link().isPowered());
        assertFalse(b.link().isPowered());
        assertSame(connection, virtualConnection(a, b));
        assertLinked(a, b);
    }

    @Test
    void differentFrequenciesStaySeparate() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(50, 0, 0, Plane.XY);
        a.link().setQEFrequency(1001);
        b.link().setQEFrequency(1002);
        settle();
        assertSeparate(a, b);
        assertEquals(LinkState.WAITING, a.cluster().getLinkState());
        assertEquals(LinkState.WAITING, b.cluster().getLinkState());

        b.link().setQEFrequency(1001);
        settle();
        assertLinked(a, b);
        assertNull(registry.get(1002));
        assertNull(registry.get(-1002));
    }

    @Test
    void negativeFrequenciesWorkTheSame() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(50, 0, 0, Plane.XY);
        a.link().setQEFrequency(-77);
        b.link().setQEFrequency(-77);
        settle();
        assertLinked(a, b);
    }

    @Test
    void aRingHasNoFrequency() {
        var ring = new QuantumBridgeLogic(false, registry);
        ring.setQEFrequency(5);
        assertEquals(0, ring.getQEFrequency());
    }

    @Test
    void takingOutTheSingularityUnlinks() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(50, 0, 0, Plane.XY);
        a.link().setQEFrequency(9);
        b.link().setQEFrequency(9);
        settle();
        assertLinked(a, b);
        var connection = virtualConnection(a, b);

        a.link().setQEFrequency(0);
        world.tick();
        assertTrue(connection.isDestroyed(), "gone after the tick");
        assertSeparate(a, b);
        assertEquals(LinkState.NO_SINGULARITY, a.cluster().getLinkState());
        assertEquals(LinkState.WAITING, b.cluster().getLinkState());
        assertNull(registry.get(-9));
        assertTrue(a.formed());

        a.link().setQEFrequency(9);
        settle();
        assertLinked(a, b);
    }

    @Test
    void exchangingTheSingularityMovesTheLink() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(50, 0, 0, Plane.XY);
        var c = bridge(100, 0, 0, Plane.XY);
        a.link().setQEFrequency(1);
        b.link().setQEFrequency(1);
        c.link().setQEFrequency(2);
        settle();
        assertLinked(a, b);
        assertSeparate(a, c);

        a.link().setQEFrequency(2);
        settle();
        assertLinked(a, c);
        assertSeparate(a, b);
        assertEquals(LinkState.WAITING, b.cluster().getLinkState());
        assertNull(registry.get(-1), "the place of the old frequency is free again");
    }

    @Test
    void breakingARingUnlinksAndPuttingItBackLinksAgain() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(50, 0, 0, Plane.YZ);
        a.link().setQEFrequency(31);
        b.link().setQEFrequency(31);
        settle();
        assertLinked(a, b);

        b.remove(-1, 1);
        world.tick();
        assertSeparate(a, b);
        assertFalse(b.link().isFormed());
        assertEquals(LinkState.WAITING, a.cluster().getLinkState());
        assertNull(a.cluster().getOtherCluster());
        assertNull(registry.get(31));
        settle();
        assertTrue(a.formed(), "the other bridge stays formed");

        b.place(-1, 1);
        settle();
        assertTrue(b.formed());
        assertLinked(a, b);
    }

    @Test
    void breakingTheLinkChamberUnlinks() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(50, 0, 0, Plane.XY);
        a.link().setQEFrequency(31);
        b.link().setQEFrequency(31);
        settle();
        var connection = virtualConnection(a, b);
        assertNotNull(connection);

        var aNode = a.linkNode();
        a.remove(0, 0);
        assertTrue(connection.isDestroyed(), "a connection ends with its node");
        settle();
        assertEquals(LinkState.WAITING, b.cluster().getLinkState());
        assertFalse(b.cluster().isLinked());
        assertTrue(b.formed());
        assertEquals(9, b.linkNode().getGrid().size());
        assertTrue(aNode.isRemoved());

        // A new link chamber (the singularity dropped out): linked again once it is back in
        a.place(0, 0);
        settle();
        assertTrue(a.formed());
        assertSeparate(a, b);
        a.link().setQEFrequency(31);
        settle();
        assertLinked(a, b);
    }

    @Test
    void unloadingOneSideUnlinksAndLoadingItLinksAgain() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(500, 0, 0, Plane.XY);
        a.link().setQEFrequency(31);
        b.link().setQEFrequency(31);
        settle();
        assertLinked(a, b);
        var aCluster = a.cluster();

        b.unload();
        settle();
        assertTrue(a.formed());
        assertSame(aCluster, a.cluster());
        assertEquals(LinkState.WAITING, a.cluster().getLinkState());
        assertNull(a.cluster().getOtherCluster(), "the other side is not loaded");
        assertEquals(9, a.linkNode().getGrid().size());
        assertEquals(1, world.getGrids().size());

        b.load(31);
        settle();
        assertTrue(b.formed());
        assertLinked(a, b);
    }

    @Test
    void aPartlyUnloadedBridgeIsUnformed() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(50, 0, 0, Plane.XY);
        a.link().setQEFrequency(31);
        b.link().setQEFrequency(31);
        settle();
        assertLinked(a, b);

        // One column of the bridge leaves the world (chunk border)
        for (int dv = -1; dv <= 1; dv++) {
            b.remove(1, dv);
        }
        settle();
        assertSeparate(a, b);
        for (var block : b.blocks.values()) {
            assertFalse(block.isFormed());
        }
        for (int dv = -1; dv <= 1; dv++) {
            b.place(1, dv);
        }
        settle();
        assertTrue(b.formed());
        assertLinked(a, b);
    }

    @Test
    void bothSidesLoadedInAnyOrderAfterARestart() {
        // Everything is loaded with its singularity in place, link chambers first or last
        var a = new Bridge(p(0, 0, 0), Plane.XY);
        var b = new Bridge(p(40, 0, 0), Plane.XZ);
        a.place(0, 0);
        a.link().setQEFrequency(123456789012345L);
        b.place(0, 0);
        b.link().setQEFrequency(123456789012345L);
        world.tick();
        for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
                if (du != 0 || dv != 0) {
                    a.place(du, dv);
                    world.tick();
                    b.place(dv, du);
                }
            }
        }
        settle();
        assertTrue(a.formed());
        assertTrue(b.formed());
        assertLinked(a, b);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Channels

    /** A row of dense cable starting at {@code start} going east, with a terminal on top of each piece. */
    private List<GridNode> terminals(long start, int count) {
        var terminals = new ArrayList<GridNode>();
        for (int i = 0; i < count; i++) {
            long pos = BlockPos.offset(start, i, 0, 0);
            cable(pos, CableType.SMART_DENSE);
            terminals.add(world.addNode(BlockPos.offset(pos, Direction.UP), NodeType.TERMINAL, Direction.UP, null,
                    null));
        }
        return terminals;
    }

    @Test
    void channelsPassOverTheBridge() {
        // Side A: controller and energy, dense cable to the middle ring on the west side of the bridge
        var a = bridge(0, 0, 0, Plane.XY);
        controller(p(-3, 0, 0));
        cable(p(-2, 0, 0), CableType.SMART_DENSE);
        // Side B: 33 terminals behind the bridge, no controller, no energy source
        var b = bridge(200, 0, 0, Plane.XY);
        var terminals = terminals(p(202, 0, 0), 33);
        a.link().setQEFrequency(77);
        world.tick(60);
        for (var t : terminals) {
            assertFalse(t.isActive(), "not linked yet");
        }

        b.link().setQEFrequency(77);
        world.tick(60);
        assertLinked(a, b);
        var grid = a.linkNode().getGrid();
        assertEquals(ControllerState.CONTROLLER_ONLINE, grid.getPathingService().getControllerState());
        assertTrue(b.link().isPowered(), "energy comes over the bridge");
        // The bridge is a dense connection: 32 of the 33 terminals get a channel
        assertEquals(32, terminals.stream().filter(GridNode::isActive).count());
        assertEquals(32, virtualConnection(a, b).getUsedChannels());
        assertEquals(32, a.linkNode().getUsedChannels());
        assertEquals(32, b.linkNode().getUsedChannels());
        assertEquals(32, grid.getPathingService().getUsedChannels());

        // Unlinked: side B is on its own again, without power
        a.link().setQEFrequency(0);
        world.tick(10);
        assertSeparate(a, b);
        for (var t : terminals) {
            assertFalse(t.isActive());
        }
        assertEquals(0, a.linkNode().getGrid().getPathingService().getUsedChannels());
    }

    @Test
    void controllersOnBothSidesConflict() {
        var a = bridge(0, 0, 0, Plane.XY);
        var controllerA = controller(p(-3, 0, 0));
        cable(p(-2, 0, 0), CableType.SMART_DENSE);
        var b = bridge(200, 0, 0, Plane.XY);
        var controllerB = controller(p(203, 0, 0));
        cable(p(202, 0, 0), CableType.SMART_DENSE);
        var terminal = world.addNode(p(202, 1, 0), NodeType.TERMINAL, Direction.UP, null, null);
        world.tick(60);
        assertEquals(ControllerState.CONTROLLER_ONLINE, controllerA.getGrid().getPathingService().getControllerState());
        assertEquals(ControllerState.CONTROLLER_ONLINE, controllerB.getGrid().getPathingService().getControllerState());
        assertTrue(terminal.isActive());

        a.link().setQEFrequency(5);
        b.link().setQEFrequency(5);
        world.tick(60);
        assertLinked(a, b);
        assertSame(controllerA.getGrid(), controllerB.getGrid());
        assertEquals(ControllerState.CONTROLLER_CONFLICT,
                controllerA.getGrid().getPathingService().getControllerState());
        assertFalse(terminal.isActive(), "no channels in a network with a controller conflict");

        b.link().setQEFrequency(0);
        world.tick(60);
        assertSeparate(a, b);
        assertEquals(ControllerState.CONTROLLER_ONLINE, controllerB.getGrid().getPathingService().getControllerState());
        assertTrue(terminal.isActive());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // More than two bridges on one frequency

    @Test
    void aThirdBridgeOnTheFrequencyStaysUnlinked() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(50, 0, 0, Plane.XY);
        var c = bridge(100, 0, 0, Plane.XY);
        a.link().setQEFrequency(8);
        b.link().setQEFrequency(8);
        settle();
        assertLinked(a, b);
        var connection = virtualConnection(a, b);

        c.link().setQEFrequency(8);
        world.tick(40);
        assertLinked(a, b);
        assertSame(connection, virtualConnection(a, b), "the first two keep their connection");
        assertEquals(LinkState.FREQUENCY_IN_USE, c.cluster().getLinkState());
        assertEquals(0, c.cluster().getThisSide());
        assertNull(c.cluster().getOtherCluster());
        assertEquals(9, c.linkNode().getGrid().size());
        assertEquals(4, c.linkNode().getConnections().size(), "only the four rings next to it");
        assertTrue(c.linkNode().getConnections().stream().allMatch(GridConnection::isInWorld));

        // A gives up its place; the third bridge takes it with its next status update
        a.link().setQEFrequency(0);
        settle();
        assertSeparate(a, b);
        assertEquals(LinkState.FREQUENCY_IN_USE, c.cluster().getLinkState(), "nothing told the third bridge yet");
        cable(c.plane.at(c.center, 2, 0), CableType.GLASS); // a change of its grid
        settle();
        assertLinked(c, b);
        assertEquals(-8, c.cluster().getThisSide());

        // Now A is the third one
        a.link().setQEFrequency(8);
        settle();
        assertEquals(LinkState.FREQUENCY_IN_USE, a.cluster().getLinkState());
        assertLinked(c, b);
    }

    @Test
    void aThirdBridgeThatHeldAnotherFrequencyLosesItsOldLink() {
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(50, 0, 0, Plane.XY);
        var c = bridge(100, 0, 0, Plane.XY);
        var d = bridge(150, 0, 0, Plane.XY);
        a.link().setQEFrequency(8);
        b.link().setQEFrequency(8);
        c.link().setQEFrequency(9);
        d.link().setQEFrequency(9);
        settle();
        assertLinked(a, b);
        assertLinked(c, d);

        // The singularity of C is exchanged for a third one of frequency 8
        c.link().setQEFrequency(8);
        settle();
        assertLinked(a, b);
        assertSeparate(c, d);
        assertEquals(LinkState.FREQUENCY_IN_USE, c.cluster().getLinkState());
        assertEquals(LinkState.WAITING, d.cluster().getLinkState());
        assertNull(registry.get(-9));
        assertSame(d.cluster(), registry.get(9));
    }

    @Test
    void registryIsAskedForBothPlacesOfAFrequency() {
        var calls = new ArrayList<String>();
        var backing = QuantumBridgeRegistry.create();
        registry = new QuantumBridgeRegistry() {
            @Override
            public QuantumCluster get(long key) {
                return backing.get(key);
            }

            @Override
            public void register(long key, QuantumCluster cluster) {
                calls.add("register " + key);
                backing.register(key, cluster);
            }

            @Override
            public void unregister(long key, QuantumCluster cluster) {
                calls.add("unregister " + key);
                backing.unregister(key, cluster);
            }
        };
        var a = bridge(0, 0, 0, Plane.XY);
        var b = bridge(50, 0, 0, Plane.XY);
        settle();
        assertTrue(calls.isEmpty(), "bridges without singularity are not registered");

        a.link().setQEFrequency(3);
        b.link().setQEFrequency(3);
        assertEquals(List.of("register -3", "register 3"), calls);
        settle();
        assertLinked(a, b);

        b.remove(1, 0);
        a.link().setQEFrequency(0);
        assertEquals(List.of("register -3", "register 3", "unregister 3", "unregister -3"), calls);
    }
}
