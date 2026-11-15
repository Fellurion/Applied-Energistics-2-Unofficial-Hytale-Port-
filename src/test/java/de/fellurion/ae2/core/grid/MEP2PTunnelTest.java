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
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.p2p.MEP2PTunnelLogic;
import de.fellurion.ae2.core.p2p.P2PFrequency;
import de.fellurion.ae2.core.p2p.P2PTunnelLogic;
import de.fellurion.ae2.core.p2p.P2PTunnelType;
import de.fellurion.ae2.core.stacks.AEKeyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ME P2P tunnels: frequencies, linking and channels through the tunnel (ports of AE2 {@code MEP2PTunnelPart},
 * {@code P2PTunnelPart}, {@code P2PService} behaviour).
 * <p>
 * Layout of most tests, seen from the side (tunnels face up: back = down, front = up):
 *
 * <pre>
 *  y=3              terminals of the remote segments
 *  y=2   controller - dense cable        dense cable (remote 1)      dense cable (remote 2)
 *  y=1                  input tunnel         output tunnel 1             output tunnel 2
 *  y=0   energy -   glass cable ------------ glass cable --------------- glass cable      (carrying network)
 * </pre>
 */
class MEP2PTunnelTest {
    /** Longest sleep of an ME tunnel plus the power-up delay: enough ticks for any change to settle. */
    private static final int SETTLE = 80;

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

    private void power(int x, int y, int z) {
        world.addNode(p(x, y, z), NodeType.CREATIVE_ENERGY_CELL, null, null, new CreativeEnergyCellLogic());
    }

    /** An ME tunnel facing up: main node connects down, outer node up. */
    private MEP2PTunnelLogic tunnel(int x, int y, int z) {
        var logic = (MEP2PTunnelLogic) P2PTunnelLogic.create(P2PTunnelType.ME);
        var main = world.addNode(p(x, y, z), P2PTunnelType.ME.mainNodeType(), Direction.UP, null, logic);
        assertSame(main, logic.getMainNode());
        logic.createOuterNode();
        return logic;
    }

    private static void link(P2PTunnelLogic input, P2PTunnelLogic... outputs) {
        input.saveToMemoryCard();
        for (var o : outputs) {
            assertTrue(o.loadFromMemoryCard(input.getFrequency()));
        }
    }

    /** Main network: controller, energy, dense cable ending above x=2 at y=2. */
    private GridNode mainNetwork() {
        var controller = world.addNode(p(0, 2, 0), NodeType.CONTROLLER, null, null, null);
        power(0, 2, 1);
        cable(1, 2, 0, CableType.SMART_DENSE);
        cable(2, 2, 0, CableType.SMART_DENSE);
        return controller;
    }

    /** Carrying network: glass cable at y=0 from x=2 to {@code toX}, with its own energy. */
    private void carryingNetwork(int toX) {
        power(2, 0, 1);
        for (int x = 2; x <= toX; x++) {
            cable(x, 0, 0, CableType.GLASS);
        }
    }

    @Test
    void tunnelNodesAndPower() {
        mainNetwork();
        carryingNetwork(5);
        var t = tunnel(2, 1, 0);
        var main = t.getMainNode();
        var outer = t.getOuterNode();
        assertTrue(main.hasFlag(GridFlag.REQUIRE_CHANNEL));
        assertTrue(main.hasFlag(GridFlag.COMPRESSED_CHANNEL));
        assertTrue(outer.hasFlag(GridFlag.DENSE_CAPACITY));
        assertTrue(outer.hasFlag(GridFlag.CANNOT_CARRY_COMPRESSED));
        assertEquals(2.0, main.getIdlePowerUsage());
        assertEquals(32, outer.getMaxChannels());
        // main node below on the carrying network, outer node above on the main network
        assertSame(world.getNode(p(2, 0, 0)).getGrid(), main.getGrid());
        assertSame(world.getNode(p(2, 2, 0)).getGrid(), outer.getGrid());
        assertNotSame(main.getGrid(), outer.getGrid());

        assertEquals(2.0, P2PTunnelType.ITEM.idlePower());
        assertEquals(2.0, P2PTunnelType.FLUID.idlePower());
        assertEquals(2.0, P2PTunnelType.FE.idlePower());
        assertEquals(0.5, P2PTunnelType.LIGHT.idlePower());
        assertEquals(0.5, P2PTunnelType.REDSTONE.idlePower());
        assertEquals(0.5, P2PTunnelType.REDSTONE.mainNodeType().idlePower());
        assertFalse(P2PTunnelType.ITEM.mainNodeType().hasFlag(GridFlag.COMPRESSED_CHANNEL));
        assertTrue(P2PTunnelType.ITEM.mainNodeType().hasFlag(GridFlag.REQUIRE_CHANNEL));
        assertSame(P2PTunnelType.LIGHT, P2PTunnelType.fromId("light"));
        assertNull(P2PTunnelType.fromId("steam"));

        world.tick(SETTLE);
        // One tunnel on the ad-hoc carrying network: one channel, tunnel online
        assertEquals(1, main.getGrid().getPathingService().getUsedChannels());
        assertTrue(main.isOnline());
        assertEquals(2.0 + main.getGrid().getPathingService().getChannelPowerUsage(),
                main.getGrid().getEnergyService().getIdlePowerUsage(), 1e-9);
    }

    @Test
    void channelsReachSeveralOutputs() {
        var controller = mainNetwork();
        carryingNetwork(12);
        var input = tunnel(2, 1, 0);
        var out1 = tunnel(6, 1, 0);
        var out2 = tunnel(12, 1, 0);
        // remote 1: three devices, remote 2: two devices
        var terminals = new ArrayList<GridNode>();
        for (int x = 6; x <= 8; x++) {
            cable(x, 2, 0, CableType.SMART_DENSE);
            terminals.add(terminalAbove(x, 2, 0));
        }
        for (int x = 12; x <= 13; x++) {
            cable(x, 2, 0, CableType.SMART_DENSE);
            terminals.add(terminalAbove(x, 2, 0));
        }
        world.tick(SETTLE);
        assertEquals(4, world.getGrids().size(), "main, carrying, two remote segments");
        for (var t : terminals) {
            assertFalse(t.isActive(), "not linked yet: " + t);
        }
        assertEquals(0, input.getFrequency());
        assertNull(input.getInput());
        assertTrue(input.getOutputs().isEmpty());

        assertTrue(input.saveToMemoryCard(), "a new frequency is generated");
        short freq = input.getFrequency();
        assertNotEquals(0, freq);
        assertFalse(input.isOutput());
        assertFalse(input.saveToMemoryCard(), "an input keeps its frequency");
        assertEquals(freq, input.getFrequency());
        assertTrue(out1.loadFromMemoryCard(freq));
        assertTrue(out2.loadFromMemoryCard(freq));
        assertFalse(out2.loadFromMemoryCard(freq), "already on that frequency");
        assertFalse(input.loadFromMemoryCard(freq), "an input is not turned into an output by its own card");
        assertTrue(out1.isOutput());
        assertSame(input, out1.getInput());
        assertEquals(List.of(out1, out2), input.getOutputs());
        assertSame(input, input.getMainNode().getGrid().getP2PService().getInput(freq));

        world.tick(SETTLE);
        var grid = controller.getGrid();
        assertEquals(2, world.getGrids().size(), "main network incl. both remote segments, carrying network");
        assertEquals(2, input.getConnectionCount());
        for (var t : terminals) {
            assertSame(grid, t.getGrid());
            assertTrue(t.isActive(), "terminal should be active: " + t);
        }
        assertEquals(5, grid.getPathingService().getUsedChannels());
        assertEquals(ControllerState.CONTROLLER_ONLINE, grid.getPathingService().getControllerState());
        // All five channels pass the input's outer node and the dense cable to the controller
        assertEquals(5, input.getOuterNode().getUsedChannels());
        assertEquals(3, out1.getOuterNode().getUsedChannels());
        assertEquals(2, out2.getOuterNode().getUsedChannels());
        assertEquals(5, world.getNode(p(1, 2, 0)).getUsedChannels());

        // The carrying network is separate: three tunnels = three channels, nothing else
        var carrying = input.getMainNode().getGrid();
        assertNotSame(grid, carrying);
        assertEquals(3, carrying.getPathingService().getUsedChannels());
        assertEquals(ControllerState.NO_CONTROLLER, carrying.getPathingService().getControllerState());
        assertTrue(out1.getMainNode().isOnline());
        // idle: three tunnels at 2 AE/t on the carrying network; their outer nodes cost the main network 1 AE/t each
        assertEquals(6.0 + carrying.getPathingService().getChannelPowerUsage(),
                carrying.getEnergyService().getIdlePowerUsage(), 1e-9);
        assertEquals(3.0 + 3.0 + 5 * 0.5 + grid.getPathingService().getChannelPowerUsage(),
                grid.getEnergyService().getIdlePowerUsage(), 1e-9);
    }

    @Test
    void tunnelCarriesThirtyTwoChannels() {
        var controller = mainNetwork();
        carryingNetwork(6);
        var input = tunnel(2, 1, 0);
        var output = tunnel(6, 1, 0);
        var terminals = new ArrayList<GridNode>();
        for (int x = 6; x < 6 + 34; x++) {
            cable(x, 2, 0, CableType.SMART_DENSE);
            terminals.add(terminalAbove(x, 2, 0));
        }
        link(input, output);
        world.tick(SETTLE);
        assertSame(controller.getGrid(), terminals.getFirst().getGrid());
        assertEquals(32, terminals.stream().filter(GridNode::isActive).count());
        assertEquals(32, output.getOuterNode().getUsedChannels());
        assertEquals(32, input.getOuterNode().getUsedChannels());
    }

    @Test
    void breakingAnEndpointSeparatesTheNetworks() {
        var controller = mainNetwork();
        carryingNetwork(12);
        var input = tunnel(2, 1, 0);
        var out1 = tunnel(6, 1, 0);
        var out2 = tunnel(12, 1, 0);
        cable(6, 2, 0, CableType.SMART_DENSE);
        var t1 = terminalAbove(6, 2, 0);
        cable(12, 2, 0, CableType.SMART_DENSE);
        var t2 = terminalAbove(12, 2, 0);
        link(input, out1, out2);
        world.tick(SETTLE);
        assertTrue(t1.isActive());
        assertTrue(t2.isActive());

        // Break output 1: its segment is cut off at once, output 2 keeps working
        world.removeNode(p(6, 1, 0));
        assertTrue(out1.getOuterNode().isRemoved());
        assertNotSame(controller.getGrid(), t1.getGrid());
        world.tick(SETTLE);
        assertFalse(t1.isActive());
        assertTrue(t2.isActive());
        assertSame(controller.getGrid(), t2.getGrid());
        assertEquals(1, input.getConnectionCount());
        assertEquals(List.of(out2), input.getOutputs());

        // Placing the tunnel again with its saved state restores the link
        var again = (MEP2PTunnelLogic) P2PTunnelLogic.create(P2PTunnelType.ME);
        again.restore(out1.getFrequency(), true);
        world.addNode(p(6, 1, 0), P2PTunnelType.ME.mainNodeType(), Direction.UP, null, again);
        again.createOuterNode();
        world.tick(SETTLE);
        assertTrue(t1.isActive());
        assertSame(controller.getGrid(), t1.getGrid());
        assertEquals(2, input.getConnectionCount());

        // Break the input: both segments are on their own
        world.removeNode(p(2, 1, 0));
        world.tick(SETTLE);
        assertFalse(t1.isActive());
        assertFalse(t2.isActive());
        assertNotSame(controller.getGrid(), t1.getGrid());
        assertNotSame(t1.getGrid(), t2.getGrid());
        assertNull(again.getInput());
    }

    @Test
    void tunnelNeedsPowerAndChannelOnTheCarryingNetwork() {
        var controller = mainNetwork();
        // carrying network without energy
        for (int x = 2; x <= 6; x++) {
            cable(x, 0, 0, CableType.GLASS);
        }
        var input = tunnel(2, 1, 0);
        var output = tunnel(6, 1, 0);
        cable(6, 2, 0, CableType.SMART_DENSE);
        var terminal = terminalAbove(6, 2, 0);
        link(input, output);
        world.tick(SETTLE);
        assertFalse(input.getMainNode().isOnline());
        assertTrue(input.getOutputs().isEmpty(), "an offline input has no outputs");
        assertFalse(terminal.isActive());
        assertNotSame(controller.getGrid(), terminal.getGrid());

        // Energy arrives: the link comes up by itself
        power(2, 0, 1);
        world.tick(SETTLE);
        assertTrue(input.getMainNode().isOnline());
        assertTrue(terminal.isActive());
        assertSame(controller.getGrid(), terminal.getGrid());

        // Energy is gone again: the link drops
        world.removeNode(p(2, 0, 1));
        world.tick(SETTLE);
        assertFalse(terminal.isActive());
        assertNotSame(controller.getGrid(), terminal.getGrid());
        assertEquals(0, input.getConnectionCount());
    }

    @Test
    void tunnelThroughTunnelIsRefused() {
        var controller = mainNetwork();
        carryingNetwork(6);
        var input = tunnel(2, 1, 0);
        var output = tunnel(6, 1, 0);
        // remote segment: a terminal and the main node of a second tunnel pair
        cable(6, 2, 0, CableType.SMART_DENSE);
        cable(7, 2, 0, CableType.SMART_DENSE);
        cable(8, 2, 0, CableType.SMART_DENSE);
        var terminal = terminalAbove(6, 2, 0);
        var nestedIn = tunnel(7, 3, 0);
        var nestedOut = tunnel(8, 3, 0);
        cable(8, 4, 0, CableType.GLASS);
        var behindNested = terminalAbove(8, 4, 0);
        link(input, output);
        link(nestedIn, nestedOut);
        world.tick(SETTLE);

        var grid = controller.getGrid();
        assertSame(grid, terminal.getGrid());
        assertSame(grid, nestedIn.getMainNode().getGrid());
        assertTrue(terminal.isActive());
        // The nested tunnels would need a channel through the outer tunnel: they get none and stay offline
        assertFalse(nestedIn.getMainNode().meetsChannelRequirements());
        assertFalse(nestedOut.getMainNode().meetsChannelRequirements());
        assertFalse(nestedIn.getMainNode().isOnline());
        assertEquals(0, nestedIn.getConnectionCount());
        assertFalse(behindNested.isActive());
        assertEquals(1, grid.getPathingService().getUsedChannels());

        // A tunnel pair that sits directly on the controller's network (not behind another tunnel) does work
        var okIn = tunnel(1, 3, 0);
        var okOut = tunnel(2, 3, 0);
        link(okIn, okOut);
        world.tick(SETTLE);
        assertTrue(okIn.getMainNode().isOnline());
        assertTrue(okOut.getMainNode().isOnline());
        assertEquals(1, okIn.getConnectionCount());
    }

    @Test
    void adHocNetworkBehindATunnelRejectsNestedTunnels() {
        // No controller anywhere: the tunnelled network is ad-hoc and contains the outer nodes of a tunnel pair
        carryingNetwork(6);
        power(2, 2, 1);
        cable(2, 2, 0, CableType.GLASS);
        var input = tunnel(2, 1, 0);
        var output = tunnel(6, 1, 0);
        cable(6, 2, 0, CableType.GLASS);
        var terminal = terminalAbove(6, 2, 0);
        link(input, output);
        world.tick(SETTLE);
        assertTrue(terminal.isActive());
        assertSame(world.getNode(p(2, 2, 0)).getGrid(), terminal.getGrid());
        assertNull(terminal.getGrid().getPathingService().getAdHocNetworkError());

        // The main node of another ME tunnel on that network: AE2 refuses the whole ad-hoc network
        var nested = tunnel(6, 3, 1);
        cable(6, 2, 1, CableType.GLASS);
        world.tick(SETTLE);
        assertSame(terminal.getGrid(), nested.getMainNode().getGrid());
        assertEquals(AdHocNetworkError.NESTED_P2P_TUNNEL, terminal.getGrid().getPathingService().getAdHocNetworkError());
        assertFalse(terminal.isActive());
    }

    @Test
    void relinkingMovesAnOutputToAnotherInput() {
        var controller = mainNetwork();
        carryingNetwork(12);
        var inputA = tunnel(2, 1, 0);
        var output = tunnel(6, 1, 0);
        cable(6, 2, 0, CableType.SMART_DENSE);
        var terminal = terminalAbove(6, 2, 0);
        // second, unpowered network with its own input
        cable(12, 2, 0, CableType.SMART_DENSE);
        var inputB = tunnel(12, 1, 0);
        link(inputA, output);
        world.tick(SETTLE);
        assertTrue(terminal.isActive());

        inputB.saveToMemoryCard();
        assertNotEquals(inputA.getFrequency(), inputB.getFrequency());
        long changes = inputA.getChangeCount();
        assertTrue(output.loadFromMemoryCard(inputB.getFrequency()));
        assertTrue(inputA.getChangeCount() > changes, "the old input is told that its output left");
        assertSame(inputB, output.getInput());
        assertTrue(inputA.getOutputs().isEmpty());
        world.tick(SETTLE);
        assertFalse(terminal.isActive(), "now tunnelled to the network without controller power");
        assertNotSame(controller.getGrid(), terminal.getGrid());
        assertSame(world.getNode(p(12, 2, 0)).getGrid(), terminal.getGrid());
        assertEquals(0, inputA.getConnectionCount());
        assertEquals(1, inputB.getConnectionCount());

        // Saving on the output makes it the input of a new frequency: the link is gone
        assertTrue(output.saveToMemoryCard());
        assertFalse(output.isOutput());
        assertNotEquals(inputB.getFrequency(), output.getFrequency());
        world.tick(SETTLE);
        assertEquals(0, inputB.getConnectionCount());
        assertNotSame(world.getNode(p(12, 2, 0)).getGrid(), terminal.getGrid());
    }

    @Test
    void tunnelsOfDifferentTypesDoNotLink() {
        carryingNetwork(6);
        var me = tunnel(2, 1, 0);
        var item = P2PTunnelLogic.create(P2PTunnelType.ITEM);
        var itemNode = world.addNode(p(6, 1, 0), P2PTunnelType.ITEM.mainNodeType(), Direction.UP, null, item);
        assertSame(itemNode, item.getMainNode());
        assertNull(world.getSecondaryNode(p(6, 1, 0)));
        world.tick(SETTLE);
        me.saveToMemoryCard();
        item.loadFromMemoryCard(me.getFrequency());
        world.tick(SETTLE);
        assertTrue(item.isOutput());
        assertEquals(me.getFrequency(), item.getFrequency());
        assertNull(item.getInput(), "the input of that frequency is an ME tunnel");
        assertTrue(me.getOutputs().isEmpty());
        assertEquals(0, me.getConnectionCount());

        // An item input with item outputs
        var itemIn = P2PTunnelLogic.create(P2PTunnelType.ITEM);
        world.addNode(p(4, 1, 0), P2PTunnelType.ITEM.mainNodeType(), Direction.UP, null, itemIn);
        world.tick(SETTLE);
        itemIn.saveToMemoryCard();
        item.loadFromMemoryCard(itemIn.getFrequency());
        assertSame(itemIn, item.getInput());
        assertEquals(List.of(item), itemIn.getOutputs());
        assertTrue(itemIn.isActive());
    }

    @Test
    void transportIsTaxedOnTheTunnelsNetwork() {
        var cell = EnergyCellLogic.energyCell();
        cell.setAECurrentPower(10_000);
        world.addNode(p(2, 0, 1), NodeType.ENERGY_CELL, null, null, cell);
        cable(2, 0, 0, CableType.GLASS);
        var item = P2PTunnelLogic.create(P2PTunnelType.ITEM);
        world.addNode(p(2, 1, 0), P2PTunnelType.ITEM.mainNodeType(), Direction.UP, null, item);
        world.tick(SETTLE);
        assertTrue(item.isActive());
        var energy = item.getMainNode().getGrid().getEnergyService();
        double before = energy.getStoredPower();
        // 0.025 AE per item, 2.5 % of transported energy
        item.deductTransportCost(64, AEKeyType.ITEMS);
        assertEquals(64 * 0.025, before - energy.getStoredPower(), 1e-9);
        item.deductTransportCost(1000, AEKeyType.FLUIDS);
        assertEquals(64 * 0.025 + 1000 / 125.0 * 0.025, before - energy.getStoredPower(), 1e-9);
        item.deductEnergyCost(400);
        assertEquals(64 * 0.025 + 0.2 + 10, before - energy.getStoredPower(), 1e-9);
        item.deductTransportCost(0, AEKeyType.ITEMS);
        assertEquals(64 * 0.025 + 0.2 + 10, before - energy.getStoredPower(), 1e-9);
    }

    @Test
    void frequencyDisplay() {
        assertEquals("0A3F", P2PFrequency.toHexString((short) 0x0A3F));
        assertEquals("FFFF", P2PFrequency.toHexString((short) -1));
        assertEquals((short) 0x0A3F, P2PFrequency.fromHexString("0A3F"));
        assertEquals((short) -1, P2PFrequency.fromHexString("ffff"));
        assertEquals(0, P2PFrequency.fromHexString("nope"));
        assertEquals(0, P2PFrequency.fromHexString(null));
        var colors = P2PFrequency.toColors((short) 0x0A3F);
        assertArrayEquals(new AEColor[] {AEColor.WHITE, AEColor.MAGENTA, AEColor.BLACK, AEColor.GREEN}, colors);
        assertEquals((short) 0x0A3F, P2PFrequency.fromColors(colors));
    }
}
