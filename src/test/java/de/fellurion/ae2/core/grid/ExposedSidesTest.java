/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

import de.fellurion.ae2.core.config.AEConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@link MEWorld#setExposedSides}: connectable sides set at runtime (AE2: {@code setExposedOnSides}). */
class ExposedSidesTest {
    private MEWorld world;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
    }

    static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private GridNode cable(int x, int y, int z) {
        return world.addNode(p(x, y, z), NodeType.cable(CableType.GLASS), null, AEColor.TRANSPARENT, null);
    }

    @Test
    void sidesOpenAndCloseAtRuntime() {
        var west = cable(-1, 0, 0);
        var east = cable(1, 0, 0);
        var above = cable(0, 1, 0);
        var block = world.addNode(p(0, 0, 0), NodeType.QUANTUM_RING.withSideRule(NodeType.SideRule.NONE), null, null,
                null);
        assertTrue(block.getConnections().isEmpty());
        assertEquals(4, world.getGrids().size());

        world.setExposedSides(block, EnumSet.of(Direction.WEST, Direction.UP));
        assertTrue(block.hasConnection(west));
        assertTrue(block.hasConnection(above));
        assertFalse(block.hasConnection(east));
        assertEquals(Set.of(Direction.WEST, Direction.UP), block.getExposedSides());
        assertEquals(2, world.getGrids().size());
        assertEquals(Direction.EAST.bit(), west.getConnectionMask());

        // A neighbour placed later connects on an open side only
        var below = cable(0, -1, 0);
        assertTrue(below.getConnections().isEmpty());

        world.setExposedSides(block, EnumSet.allOf(Direction.class));
        assertEquals(4, block.getConnections().size());
        assertEquals(1, world.getGrids().size());

        world.setExposedSides(block, EnumSet.of(Direction.EAST));
        assertEquals(1, block.getConnections().size());
        assertTrue(block.hasConnection(east));
        assertEquals(4, world.getGrids().size());
        assertSame(block.getGrid(), east.getGrid());
        assertEquals(0, west.getConnectionMask());

        // The override survives a re-orientation; null gives the sides of the type back
        world.updateNode(block.getPos(), Direction.UP, null);
        assertEquals(Set.of(Direction.EAST), block.getExposedSides());
        world.setExposedSides(block, null);
        assertTrue(block.getExposedSides().isEmpty());
        assertTrue(block.getConnections().isEmpty());
        assertEquals(5, world.getGrids().size());
    }

    @Test
    void virtualConnectionsAreKept() {
        var a = world.addNode(p(0, 0, 0), NodeType.QUANTUM_LINK, null, null, null);
        var b = world.addNode(p(10, 0, 0), NodeType.QUANTUM_LINK, null, null, null);
        var connection = world.addVirtualConnection(a, b);
        world.setExposedSides(a, EnumSet.noneOf(Direction.class));
        assertFalse(connection.isDestroyed());
        assertSame(a.getGrid(), b.getGrid());

        world.removeNode(a.getPos());
        world.setExposedSides(a, EnumSet.allOf(Direction.class)); // no-op for a removed node
        assertTrue(a.getConnections().isEmpty());
    }
}
