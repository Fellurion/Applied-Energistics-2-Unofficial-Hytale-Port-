/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.energy.CreativeEnergyCellLogic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Secondary nodes and virtual connections of {@link MEWorld} (AE2: outer nodes of parts and
 * {@code GridConnection.create(a, b, null)}).
 */
class VirtualConnectionTest {
    private MEWorld world;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
    }

    static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private GridNode cable(int x, int y, int z, CableType type) {
        return world.addNode(p(x, y, z), NodeType.cable(type), null, AEColor.TRANSPARENT, null);
    }

    private GridNode terminalAbove(int x, int y, int z) {
        return world.addNode(p(x, y + 1, z), NodeType.TERMINAL, Direction.UP, null, null);
    }

    @Test
    void secondaryNodeConnectsOnItsOwnSideOnly() {
        var back = cable(0, 0, 0, CableType.GLASS);
        var front = cable(2, 0, 0, CableType.GLASS);
        // A part at x=1 facing east: main node on the back (west), outer node on the front (east)
        var main = world.addNode(p(1, 0, 0), NodeType.QUARTZ_FIBER_MAIN, Direction.EAST, null, null);
        var outer = world.addSecondaryNode(p(1, 0, 0), NodeType.QUARTZ_FIBER_OUTER, Direction.EAST, null, "owner",
                null);

        assertTrue(outer.isSecondary());
        assertFalse(main.isSecondary());
        assertSame(main, world.getNode(p(1, 0, 0)));
        assertSame(outer, world.getSecondaryNode(p(1, 0, 0)));
        assertEquals("owner", outer.getOwner());
        assertNull(outer.getService(String.class), "a secondary node takes no services from its owner");

        assertTrue(main.hasConnection(back));
        assertFalse(main.hasConnection(front));
        assertTrue(outer.hasConnection(front));
        assertFalse(outer.hasConnection(back));
        assertFalse(main.hasConnection(outer));
        assertNotSame(main.getGrid(), outer.getGrid());
        assertSame(back.getGrid(), main.getGrid());
        assertSame(front.getGrid(), outer.getGrid());
        assertEquals(2, world.getGrids().size(), "the grid behind and the grid in front");
    }

    @Test
    void neighboursPlacedLaterFindTheSecondaryNode() {
        var main = world.addNode(p(1, 0, 0), NodeType.QUARTZ_FIBER_MAIN, Direction.EAST, null, null);
        var outer = world.addSecondaryNode(p(1, 0, 0), NodeType.QUARTZ_FIBER_OUTER, Direction.EAST, null, null, null);
        var front = cable(2, 0, 0, CableType.GLASS);
        var back = cable(0, 0, 0, CableType.GLASS);
        var above = cable(1, 1, 0, CableType.GLASS);
        assertTrue(outer.hasConnection(front));
        assertTrue(main.hasConnection(back));
        assertTrue(above.getConnections().isEmpty(), "neither node exposes the top");
        assertEquals(Direction.EAST.bit(), outer.getConnectionMask());
        assertEquals(Direction.WEST.bit(), front.getConnectionMask());

        world.refreshConnections(front);
        world.refreshConnections(outer);
        assertEquals(1, outer.getConnections().size());
        assertEquals(1, front.getConnections().size());
    }

    @Test
    void removingTheMainNodeRemovesTheSecondaryNode() {
        var back = cable(0, 0, 0, CableType.GLASS);
        var front = cable(2, 0, 0, CableType.GLASS);
        world.addNode(p(1, 0, 0), NodeType.QUARTZ_FIBER_MAIN, Direction.EAST, null, null);
        var outer = world.addSecondaryNode(p(1, 0, 0), NodeType.QUARTZ_FIBER_OUTER, Direction.EAST, null, null, null);
        assertThrows(IllegalStateException.class, () -> world.addSecondaryNode(p(1, 0, 0),
                NodeType.QUARTZ_FIBER_OUTER, Direction.EAST, null, null, null));

        world.removeNode(p(1, 0, 0));
        assertTrue(outer.isRemoved());
        assertNull(world.getSecondaryNode(p(1, 0, 0)));
        assertTrue(front.getConnections().isEmpty());
        assertTrue(back.getConnections().isEmpty());
        assertEquals(2, world.getGrids().size());
        assertTrue(world.getSecondaryNodes().isEmpty());
    }

    @Test
    void rotatingMovesBothNodes() {
        var west = cable(0, 0, 0, CableType.GLASS);
        var east = cable(2, 0, 0, CableType.GLASS);
        var main = world.addNode(p(1, 0, 0), NodeType.QUARTZ_FIBER_MAIN, Direction.EAST, null, null);
        var outer = world.addSecondaryNode(p(1, 0, 0), NodeType.QUARTZ_FIBER_OUTER, Direction.EAST, null, null, null);
        assertTrue(main.hasConnection(west));
        assertTrue(outer.hasConnection(east));

        world.updateNode(p(1, 0, 0), Direction.WEST, null);
        assertTrue(main.hasConnection(east));
        assertTrue(outer.hasConnection(west));
        assertEquals(1, main.getConnections().size());
        assertEquals(1, outer.getConnections().size());
        assertSame(east.getGrid(), main.getGrid());
        assertSame(west.getGrid(), outer.getGrid());
        assertNotSame(main.getGrid(), outer.getGrid());
    }

    @Test
    void virtualConnectionMergesAndSplitsGrids() {
        var a = cable(0, 0, 0, CableType.SMART_DENSE);
        var a2 = cable(1, 0, 0, CableType.SMART_DENSE);
        var b = cable(100, 50, -100, CableType.SMART_DENSE);
        var b2 = cable(101, 50, -100, CableType.SMART_DENSE);
        assertEquals(2, world.getGrids().size());

        var connection = world.addVirtualConnection(a, b);
        assertFalse(connection.isInWorld());
        assertNull(connection.getDirection(a));
        assertSame(b, connection.getOtherSide(a));
        assertEquals(1, world.getGrids().size());
        assertSame(a2.getGrid(), b2.getGrid());
        assertTrue(a.hasConnection(b));
        assertSame(connection, a.getVirtualConnection(b));
        assertSame(connection, b.getVirtualConnection(a));
        assertNull(a.getVirtualConnection(a2));

        // The connection has no side: masks, sides and per-side channels only know the cables
        assertEquals(Direction.EAST.bit(), a.getConnectionMask());
        assertEquals(java.util.Set.of(Direction.EAST), a.getConnectedSides());
        assertEquals(java.util.Set.of(Direction.EAST), a.getChannelsPerSide().keySet());
        assertNull(a.getConnection(Direction.UP));

        // It survives re-evaluating the in-world connections
        world.refreshConnections(a);
        world.refreshConnections(b);
        world.updateNode(a.getPos(), null, AEColor.TRANSPARENT);
        assertFalse(connection.isDestroyed());
        assertEquals(1, world.getGrids().size());

        assertThrows(IllegalStateException.class, () -> world.addVirtualConnection(b, a));
        assertThrows(IllegalStateException.class, () -> world.addVirtualConnection(a, a2), "already joined by a cable");
        assertThrows(IllegalArgumentException.class, () -> world.addVirtualConnection(a, a));
        assertThrows(IllegalArgumentException.class, () -> world.removeVirtualConnection(a.getConnection(Direction.EAST)));

        world.removeVirtualConnection(connection);
        assertTrue(connection.isDestroyed());
        assertEquals(2, world.getGrids().size());
        assertNotSame(a2.getGrid(), b2.getGrid());
        assertFalse(a.hasConnection(b));
        world.removeVirtualConnection(connection); // no-op
        world.tick();
    }

    @Test
    void virtualConnectionEndsWithItsNode() {
        var a = cable(0, 0, 0, CableType.GLASS);
        var b = cable(10, 0, 0, CableType.GLASS);
        var b2 = cable(11, 0, 0, CableType.GLASS);
        var connection = world.addVirtualConnection(a, b);
        assertEquals(1, world.getGrids().size());

        world.removeNode(a.getPos());
        assertTrue(connection.isDestroyed());
        assertEquals(1, world.getGrids().size());
        assertEquals(1, b.getConnections().size());
        assertSame(b.getGrid(), b2.getGrid());
        assertThrows(IllegalArgumentException.class, () -> world.addVirtualConnection(a, b));
    }

    @Test
    void virtualConnectionInsideOneGridDoesNotSplitWhenRemoved() {
        var a = cable(0, 0, 0, CableType.GLASS);
        cable(1, 0, 0, CableType.GLASS);
        var c = cable(2, 0, 0, CableType.GLASS);
        var connection = world.addVirtualConnection(a, c);
        assertEquals(1, world.getGrids().size());
        world.removeVirtualConnection(connection);
        assertEquals(1, world.getGrids().size());
        assertSame(a.getGrid(), c.getGrid());
    }

    @Test
    void channelsFlowThroughADenseVirtualConnection() {
        // Controller side
        world.addNode(p(0, 0, 0), NodeType.CONTROLLER, null, null, null);
        world.addNode(p(0, -1, 0), NodeType.CREATIVE_ENERGY_CELL, null, null, new CreativeEnergyCellLogic());
        var near = cable(1, 0, 0, CableType.SMART_DENSE);
        // Far side: 12 terminals on dense cable, nowhere near the controller
        var far = cable(50, 0, 50, CableType.SMART_DENSE);
        List<GridNode> terminals = new ArrayList<>();
        terminals.add(terminalAbove(50, 0, 50));
        for (int x = 51; x < 62; x++) {
            cable(x, 0, 50, CableType.SMART_DENSE);
            terminals.add(terminalAbove(x, 0, 50));
        }
        world.tick();
        for (var t : terminals) {
            assertFalse(t.isActive());
        }

        var connection = world.addVirtualConnection(near, far);
        world.tick();
        var grid = near.getGrid();
        assertEquals(ControllerState.CONTROLLER_ONLINE, grid.getPathingService().getControllerState());
        assertEquals(12, grid.getPathingService().getUsedChannels());
        for (var t : terminals) {
            assertTrue(t.isActive(), "terminal should be active: " + t);
        }
        assertEquals(12, connection.getUsedChannels());
        assertEquals(12, near.getUsedChannels());

        // 33 devices: the connection (like every connection) carries at most 32 channels
        for (int x = 62; x < 83; x++) {
            cable(x, 0, 50, CableType.SMART_DENSE);
            terminals.add(terminalAbove(x, 0, 50));
        }
        world.tick();
        assertEquals(33, terminals.size());
        assertEquals(32, terminals.stream().filter(GridNode::isActive).count());
        assertEquals(32, connection.getUsedChannels());

        world.removeVirtualConnection(connection);
        world.tick();
        assertNotSame(near.getGrid(), far.getGrid());
        assertEquals(0, near.getGrid().getPathingService().getUsedChannels());
    }

    @Test
    void endOfTickTasksRunOncePerTick() {
        var order = new ArrayList<String>();
        world.runAtEndOfTick(() -> {
            order.add("first");
            world.runAtEndOfTick(() -> order.add("queued while running"));
        });
        world.tick();
        assertEquals(List.of("first"), order);
        world.tick();
        assertEquals(List.of("first", "queued while running"), order);
        world.tick();
        assertEquals(2, order.size());
    }
}
