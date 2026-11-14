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
 * Channel allocation, controller validation and grid merge/split (ports of AE2 PathingCalculation /
 * ControllerValidator behaviour).
 */
class GridChannelTest {
    private MEWorld world;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
    }

    static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private GridNode cable(int x, int y, int z, CableType type, AEColor color) {
        return world.addNode(p(x, y, z), NodeType.cable(type), null, color, null);
    }

    /** A terminal above the given position, connected downwards. */
    private GridNode terminalAbove(int x, int y, int z) {
        return world.addNode(p(x, y + 1, z), NodeType.TERMINAL, Direction.UP, null, null);
    }

    private void power(int x, int y, int z) {
        world.addNode(p(x, y, z), NodeType.CREATIVE_ENERGY_CELL, null, null, new CreativeEnergyCellLogic());
    }

    @Test
    void blockPosRoundTrip() {
        long pos = BlockPos.asLong(-123456, -64, 987654);
        assertEquals(-123456, BlockPos.getX(pos));
        assertEquals(-64, BlockPos.getY(pos));
        assertEquals(987654, BlockPos.getZ(pos));
        assertEquals(BlockPos.asLong(-123455, -64, 987654), BlockPos.offset(pos, Direction.EAST));
        assertEquals(Direction.WEST, Direction.EAST.getOpposite());
        assertEquals(Direction.DOWN, Direction.UP.getOpposite());
        assertEquals(Direction.NORTH, Direction.SOUTH.getOpposite());
    }

    @Test
    void adHocNetworkSupportsEightChannels() {
        power(-1, 0, 0);
        var terminals = new ArrayList<GridNode>();
        for (int x = 0; x < 8; x++) {
            cable(x, 0, 0, CableType.GLASS, AEColor.TRANSPARENT);
            terminals.add(terminalAbove(x, 0, 0));
        }
        world.tick();
        assertEquals(1, world.getGrids().size());
        var grid = terminals.getFirst().getGrid();
        assertEquals(ControllerState.NO_CONTROLLER, grid.getPathingService().getControllerState());
        assertEquals(8, grid.getPathingService().getUsedChannels());
        for (var t : terminals) {
            assertTrue(t.isActive(), "terminal should be active: " + t);
            assertEquals(8, t.getUsedChannels());
        }
        // channel power: channelsByBlocks / 128 = nodes * channels / 128
        assertEquals(grid.size() * 8 / 128.0, grid.getPathingService().getChannelPowerUsage(), 1e-9);

        // A ninth device breaks the ad-hoc network
        cable(8, 0, 0, CableType.GLASS, AEColor.TRANSPARENT);
        var ninth = terminalAbove(8, 0, 0);
        world.tick();
        assertEquals(0, grid.getPathingService().getUsedChannels());
        assertEquals(AdHocNetworkError.TOO_MANY_CHANNELS, grid.getPathingService().getAdHocNetworkError());
        assertFalse(ninth.isActive());
        assertFalse(terminals.getFirst().isActive());
        assertTrue(terminals.getFirst().isPowered());

        // Removing it restores the network
        world.removeNode(ninth.getPos());
        world.tick();
        assertTrue(terminals.getFirst().isActive());
    }

    @Test
    void controllerWithNormalCableGivesEightChannels() {
        world.addNode(p(0, 0, 0), NodeType.CONTROLLER, null, null, null);
        power(0, -1, 0);
        var terminals = new ArrayList<GridNode>();
        for (int x = 1; x <= 10; x++) {
            cable(x, 0, 0, CableType.COVERED, AEColor.TRANSPARENT);
            terminals.add(terminalAbove(x, 0, 0));
        }
        world.tick();
        var grid = terminals.getFirst().getGrid();
        assertEquals(ControllerState.CONTROLLER_ONLINE, grid.getPathingService().getControllerState());
        assertEquals(8, grid.getPathingService().getUsedChannels());
        long active = terminals.stream().filter(GridNode::isActive).count();
        assertEquals(8, active);
        // BFS assigns channels to the devices closest to the controller first
        for (int i = 0; i < 8; i++) {
            assertTrue(terminals.get(i).isActive());
        }
        assertFalse(terminals.get(8).isActive());
        assertFalse(terminals.get(9).isActive());
        // first cable carries all 8 channels
        var firstCable = world.getNode(p(1, 0, 0));
        assertEquals(8, firstCable.getUsedChannels());
        assertEquals(8, firstCable.getChannelsPerSide().get(Direction.WEST));
    }

    @Test
    void denseCableCarries32Channels() {
        world.addNode(p(0, 0, 0), NodeType.CONTROLLER, null, null, null);
        power(0, -1, 0);
        // dense trunk along x, each dense cable feeds a normal cable going +z with terminals
        int devices = 0;
        var all = new ArrayList<GridNode>();
        for (int x = 1; x <= 5; x++) {
            cable(x, 0, 0, CableType.SMART_DENSE, AEColor.TRANSPARENT);
            for (int z = 1; z <= 8; z++) {
                cable(x, 0, z, CableType.SMART, AEColor.TRANSPARENT);
                all.add(terminalAbove(x, 0, z));
                devices++;
            }
        }
        assertEquals(40, devices);
        world.tick();
        var grid = all.getFirst().getGrid();
        // dense trunk from one controller face supports 32 channels
        assertEquals(32, grid.getPathingService().getUsedChannels());
        assertEquals(32, world.getNode(p(1, 0, 0)).getUsedChannels());
        assertEquals(32, all.stream().filter(GridNode::isActive).count());
    }

    @Test
    void colorsSeparateNetworks() {
        cable(0, 0, 0, CableType.GLASS, AEColor.RED);
        cable(1, 0, 0, CableType.GLASS, AEColor.BLUE);
        assertEquals(2, world.getGrids().size());
        assertEquals(0, world.getNode(p(0, 0, 0)).getConnectionMask());
        // fluix connects to everything
        cable(0, 0, 1, CableType.GLASS, AEColor.TRANSPARENT);
        cable(1, 0, 1, CableType.GLASS, AEColor.TRANSPARENT);
        assertEquals(1, world.getGrids().size());
        var red = world.getNode(p(0, 0, 0));
        assertEquals(Direction.SOUTH.bit(), red.getConnectionMask());
        // recolor blue cable to red: direct connection
        world.updateNode(p(1, 0, 0), null, AEColor.RED);
        assertEquals(Direction.SOUTH.bit() | Direction.EAST.bit(), red.getConnectionMask());
    }

    @Test
    void partsConnectOnlyOnTheirBack() {
        cable(0, 0, 0, CableType.GLASS, AEColor.TRANSPARENT);
        // terminal above the cable facing DOWN (front towards the cable): back points up -> no connection
        var t = world.addNode(p(0, 1, 0), NodeType.TERMINAL, Direction.DOWN, null, null);
        assertEquals(0, t.getConnectionMask());
        world.updateNode(p(0, 1, 0), Direction.UP, null);
        assertEquals(Direction.DOWN.bit(), t.getConnectionMask());
        assertSame(t.getGrid(), world.getNode(p(0, 0, 0)).getGrid());
    }

    @Test
    void removingNodeSplitsGrid() {
        for (int x = 0; x < 5; x++) {
            cable(x, 0, 0, CableType.GLASS, AEColor.TRANSPARENT);
        }
        assertEquals(1, world.getGrids().size());
        world.removeNode(p(2, 0, 0));
        assertEquals(2, world.getGrids().size());
        assertNotSame(world.getNode(p(0, 0, 0)).getGrid(), world.getNode(p(4, 0, 0)).getGrid());
        assertEquals(2, world.getNode(p(0, 0, 0)).getGrid().size());
        // reconnect
        cable(2, 0, 0, CableType.GLASS, AEColor.TRANSPARENT);
        assertEquals(1, world.getGrids().size());
        assertEquals(5, world.getNode(p(0, 0, 0)).getGrid().size());
    }

    @Test
    void controllerConflictWhenTooLarge() {
        power(0, -1, 0);
        for (int x = 0; x < 7; x++) {
            world.addNode(p(x, 0, 0), NodeType.CONTROLLER, null, null, null);
        }
        var t = world.addNode(p(0, 1, 0), NodeType.TERMINAL, Direction.UP, null, null);
        world.tick();
        var grid = t.getGrid();
        assertEquals(ControllerState.CONTROLLER_ONLINE, grid.getPathingService().getControllerState());
        assertTrue(t.isActive());
        // 8 in a row exceeds 7x7x7
        world.addNode(p(7, 0, 0), NodeType.CONTROLLER, null, null, null);
        world.tick();
        assertEquals(ControllerState.CONTROLLER_CONFLICT, grid.getPathingService().getControllerState());
        assertFalse(t.isActive());
        world.removeNode(p(7, 0, 0));
        world.tick();
        assertEquals(ControllerState.CONTROLLER_ONLINE, t.getGrid().getPathingService().getControllerState());
    }

    @Test
    void controllerCrossIsConflict() {
        power(0, -1, 0);
        // plus shape in the XZ plane: center has neighbours on two axes
        for (long pos : List.of(p(1, 0, 1), p(0, 0, 1), p(2, 0, 1), p(1, 0, 0), p(1, 0, 2))) {
            world.addNode(pos, NodeType.CONTROLLER, null, null, null);
        }
        var t = world.addNode(p(0, 1, 1), NodeType.TERMINAL, Direction.UP, null, null);
        world.tick();
        assertEquals(ControllerState.CONTROLLER_CONFLICT, t.getGrid().getPathingService().getControllerState());
        // an L shape is fine
        world.removeNode(p(1, 0, 2));
        world.removeNode(p(1, 0, 0));
        world.addNode(p(2, 0, 2), NodeType.CONTROLLER, null, null, null);
        world.tick();
        assertEquals(ControllerState.CONTROLLER_ONLINE, t.getGrid().getPathingService().getControllerState());
    }

    @Test
    void disconnectedControllersConflict() {
        power(0, -1, 0);
        world.addNode(p(0, 0, 0), NodeType.CONTROLLER, null, null, null);
        cable(1, 0, 0, CableType.GLASS, AEColor.TRANSPARENT);
        world.addNode(p(2, 0, 0), NodeType.CONTROLLER, null, null, null);
        world.tick();
        var grid = world.getNode(p(0, 0, 0)).getGrid();
        assertSame(grid, world.getNode(p(2, 0, 0)).getGrid());
        assertEquals(ControllerState.CONTROLLER_CONFLICT, grid.getPathingService().getControllerState());
    }

    @Test
    void networkWithoutPowerIsInactive() {
        cable(0, 0, 0, CableType.GLASS, AEColor.TRANSPARENT);
        var t = terminalAbove(0, 0, 0);
        world.tick(5);
        assertFalse(t.isPowered());
        assertFalse(t.isActive());
        assertEquals(1, t.getUsedChannels()); // ad-hoc channel assigned even without power
    }
}
