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
import de.fellurion.ae2.core.parts.ToggleBusLogic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Toggle bus and inverted toggle bus: a switchable connection (ports of AE2 {@code ToggleBusPart} /
 * {@code InvertedToggleBusPart}).
 * <p>
 * Controller network at x &lt;= 1, the bus at x=2 (facing east), a terminal on a cable at x=3.
 */
class ToggleBusTest {
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

    private ToggleBusLogic bus(int x, int y, int z, boolean inverted) {
        var logic = new ToggleBusLogic(inverted);
        var main = world.addNode(p(x, y, z), logic.mainNodeType(), Direction.EAST, null, logic);
        logic.createOuterNode(main);
        return logic;
    }

    private GridNode[] network() {
        var controller = world.addNode(p(0, 0, 0), NodeType.CONTROLLER, null, null, null);
        world.addNode(p(0, -1, 0), NodeType.CREATIVE_ENERGY_CELL, null, null, new CreativeEnergyCellLogic());
        cable(1, 0, 0);
        cable(3, 0, 0);
        var terminal = world.addNode(p(3, 1, 0), NodeType.TERMINAL, Direction.UP, null, null);
        return new GridNode[] {controller, terminal};
    }

    @Test
    void toggleBusConnectsWhileItHasASignal() {
        var nodes = network();
        var controller = nodes[0];
        var terminal = nodes[1];
        var bus = bus(2, 0, 0, false);
        assertFalse(bus.isInverted());
        assertTrue(bus.getMainNode().hasFlag(GridFlag.PREFERRED));
        assertTrue(bus.getOuterNode().hasFlag(GridFlag.PREFERRED));
        assertEquals(0.0, bus.getMainNode().getIdlePowerUsage());
        world.tick(40);

        // Off: two networks
        assertFalse(bus.isEnabled());
        assertNotSame(controller.getGrid(), terminal.getGrid());
        assertSame(controller.getGrid(), bus.getMainNode().getGrid());
        assertSame(terminal.getGrid(), bus.getOuterNode().getGrid());
        assertFalse(terminal.isActive());
        assertEquals(2, world.getGrids().size());

        // Signal: one network, the terminal gets its channel through the bus
        bus.setSignal(true);
        assertTrue(bus.isEnabled());
        assertTrue(bus.hasSignal());
        assertEquals(1, world.getGrids().size());
        world.tick(2);
        assertSame(controller.getGrid(), terminal.getGrid());
        assertTrue(terminal.isActive());
        assertEquals(1, bus.getMainNode().getUsedChannels());
        assertEquals(1, bus.getOuterNode().getUsedChannels());
        bus.setSignal(true); // no change
        assertEquals(1, world.getGrids().size());

        // Signal gone: split again
        bus.setSignal(false);
        assertEquals(2, world.getGrids().size());
        world.tick(2);
        assertNotSame(controller.getGrid(), terminal.getGrid());
        assertFalse(terminal.isActive());

        // Breaking an enabled bus splits the networks too
        bus.setSignal(true);
        world.tick(2);
        assertTrue(terminal.isActive());
        world.removeNode(p(2, 0, 0));
        world.tick(2);
        assertFalse(terminal.isActive());
        assertEquals(2, world.getGrids().size());
    }

    @Test
    void invertedToggleBusConnectsWhileItHasNoSignal() {
        var nodes = network();
        var controller = nodes[0];
        var terminal = nodes[1];
        var bus = bus(2, 0, 0, true);
        assertTrue(bus.isInverted());
        assertFalse(bus.getMainNode().hasFlag(GridFlag.PREFERRED));
        world.tick(40);
        assertTrue(bus.isEnabled());
        assertSame(controller.getGrid(), terminal.getGrid());
        assertTrue(terminal.isActive());

        bus.setSignal(true);
        assertFalse(bus.isEnabled());
        world.tick(2);
        assertNotSame(controller.getGrid(), terminal.getGrid());
        assertFalse(terminal.isActive());

        bus.setSignal(false);
        world.tick(2);
        assertTrue(terminal.isActive());
    }

    @Test
    void busDoesNotConnectSideways() {
        network();
        var bus = bus(2, 0, 0, false);
        var above = cable(2, 1, 0);
        assertTrue(above.getConnections().stream().noneMatch(c -> c.getOtherSide(above) == bus.getMainNode()
                || c.getOtherSide(above) == bus.getOuterNode()));
        assertEquals(1, bus.getMainNode().getConnections().size());
        assertEquals(1, bus.getOuterNode().getConnections().size());
    }
}
