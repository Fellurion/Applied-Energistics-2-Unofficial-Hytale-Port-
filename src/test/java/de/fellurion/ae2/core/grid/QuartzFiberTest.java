/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.machines.CrystalResonanceGeneratorLogic;
import de.fellurion.ae2.core.parts.QuartzFiberLogic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quartz fiber: two networks share their energy without being merged (ports of AE2 {@code QuartzFiberPart} and the
 * energy overlay grid of {@code EnergyService}).
 * <p>
 * Network A lies at x &lt;= 1, the fiber at x=2 (facing east), network B at x &gt;= 3.
 */
class QuartzFiberTest {
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

    private GridNode terminalAbove(int x, int y, int z) {
        return world.addNode(p(x, y + 1, z), NodeType.TERMINAL, Direction.UP, null, null);
    }

    private EnergyCellLogic energyCell(int x, int y, int z, double stored) {
        var cell = EnergyCellLogic.energyCell();
        cell.setAECurrentPower(stored);
        world.addNode(p(x, y, z), NodeType.ENERGY_CELL, null, null, cell);
        return cell;
    }

    private QuartzFiberLogic fiber(int x, int y, int z, Direction facing) {
        var logic = new QuartzFiberLogic();
        var main = world.addNode(p(x, y, z), NodeType.QUARTZ_FIBER_MAIN, facing, null, logic);
        logic.createOuterNode(main);
        return logic;
    }

    @Test
    void fiberPowersTheOtherNetworkWithoutMergingIt() {
        // A: energy cell + controller + terminal; B: two terminals, no energy of its own
        var cell = energyCell(0, 0, 0, 50_000);
        var cableA = cable(1, 0, 0);
        world.addNode(p(1, -1, 0), NodeType.CONTROLLER, null, null, null);
        var terminalA = terminalAbove(1, 0, 0);
        var cableB = cable(3, 0, 0);
        cable(4, 0, 0);
        var terminalB1 = terminalAbove(3, 0, 0);
        var terminalB2 = terminalAbove(4, 0, 0);
        world.tick(40);
        assertTrue(terminalA.isActive());
        assertFalse(terminalB1.isPowered());

        var fiber = fiber(2, 0, 0, Direction.EAST);
        assertTrue(fiber.isConnected());
        assertEquals(0.0, fiber.getMainNode().getIdlePowerUsage());
        assertEquals(0.0, fiber.getOuterNode().getIdlePowerUsage());
        assertTrue(fiber.getMainNode().hasFlag(GridFlag.CANNOT_CARRY));
        assertTrue(fiber.getOuterNode().hasFlag(GridFlag.CANNOT_CARRY));
        world.tick(40);

        var gridA = cableA.getGrid();
        var gridB = cableB.getGrid();
        assertNotSame(gridA, gridB, "the fiber does not merge the networks");
        assertSame(gridA, fiber.getMainNode().getGrid());
        assertSame(gridB, fiber.getOuterNode().getGrid());
        assertEquals(2, world.getGrids().size());
        assertTrue(terminalB1.isActive(), "B runs on A's energy");
        assertTrue(terminalB2.isActive());

        // Still two networks: A has a controller and one device, B is ad-hoc with two devices
        assertEquals(ControllerState.CONTROLLER_ONLINE, gridA.getPathingService().getControllerState());
        assertEquals(ControllerState.NO_CONTROLLER, gridB.getPathingService().getControllerState());
        assertEquals(1, gridA.getPathingService().getUsedChannels());
        assertEquals(2, gridB.getPathingService().getUsedChannels());
        assertEquals(1, gridA.getControllers().size());
        assertTrue(gridB.getControllers().isEmpty());

        // Each network reports only its own storage: the cell is counted once
        assertEquals(200_000 + 25.0 * gridA.size(), gridA.getEnergyService().getMaxStoredPower(), 1e-6);
        assertEquals(25.0 * gridB.size(), gridB.getEnergyService().getMaxStoredPower(), 1e-6);
        assertEquals(2, gridA.getEnergyService().getConnectedServices().size());
        assertEquals(gridA.getEnergyService().getConnectedServices(), gridB.getEnergyService().getConnectedServices());
        assertSame(gridA.getEnergyService(), gridB.getEnergyService().getConnectedServices().getFirst(),
                "largest storage first");

        // B's idle drain is paid from A's cell
        double idleA = gridA.getEnergyService().getIdlePowerUsage();
        double idleB = gridB.getEnergyService().getIdlePowerUsage();
        assertTrue(idleB > 0.9);
        double total = cell.getAECurrentPower() + gridA.getEnergyService().getLocalStorage().getAECurrentPower()
                + gridB.getEnergyService().getLocalStorage().getAECurrentPower();
        world.tick(20);
        double totalAfter = cell.getAECurrentPower() + gridA.getEnergyService().getLocalStorage().getAECurrentPower()
                + gridB.getEnergyService().getLocalStorage().getAECurrentPower();
        assertEquals(20 * (idleA + idleB), total - totalAfter, 1e-6);

        // Extracting on B takes it from A's cell, exactly once
        double before = cell.getAECurrentPower();
        double got = gridB.getEnergyService().extractAEPower(1000, Actionable.MODULATE, PowerMultiplier.ONE);
        assertEquals(1000, got, 1e-9);
        assertEquals(1000, before - cell.getAECurrentPower(), 1e-9);
        assertEquals(1000, gridB.getEnergyService().extractAEPower(1000, Actionable.SIMULATE, PowerMultiplier.ONE), 1e-9);
        assertEquals(before - 1000, cell.getAECurrentPower(), 1e-9, "simulation takes nothing");

        // Removing the fiber: B loses power at once, A is unaffected
        world.removeNode(p(2, 0, 0));
        world.tick(2);
        assertFalse(terminalB1.isPowered());
        assertTrue(terminalA.isActive());
        assertEquals(1, gridB.getEnergyService().getConnectedServices().size());
        assertEquals(1, gridA.getEnergyService().getConnectedServices().size());
    }

    @Test
    void energyFlowsBothWays() {
        // The cell is on the outer side this time, the consumer on the main side
        var cableA = cable(1, 0, 0);
        var terminalA = terminalAbove(1, 0, 0);
        var cableB = cable(3, 0, 0);
        var cell = energyCell(4, 0, 0, 10_000);
        fiber(2, 0, 0, Direction.EAST);
        world.tick(40);
        assertNotSame(cableA.getGrid(), cableB.getGrid());
        assertTrue(terminalA.isActive(), "A runs on B's energy");

        // Injecting on A charges B's storages (its 25 AE/node buffer first, then the cell); A's demand includes them
        var energyA = cableA.getGrid().getEnergyService();
        var energyB = cableB.getGrid().getEnergyService();
        assertTrue(energyA.getEnergyDemand(1_000_000) > 150_000);
        double cellBefore = cell.getAECurrentPower();
        double before = cellBefore + energyA.getLocalStorage().getAECurrentPower()
                + energyB.getLocalStorage().getAECurrentPower();
        assertEquals(0, energyA.injectPower(5000, Actionable.MODULATE), 1e-9);
        double after = cell.getAECurrentPower() + energyA.getLocalStorage().getAECurrentPower()
                + energyB.getLocalStorage().getAECurrentPower();
        assertEquals(5000, after - before, 1e-9);
        assertTrue(cell.getAECurrentPower() - cellBefore > 4800, "nearly all of it lands in B's cell");
        assertEquals(0, energyA.injectPower(100, Actionable.SIMULATE), 1e-9);
        assertEquals(after, cell.getAECurrentPower() + energyA.getLocalStorage().getAECurrentPower()
                + energyB.getLocalStorage().getAECurrentPower(), 1e-9);
    }

    @Test
    void parallelFibersAndChainsCountEachNetworkOnce() {
        // A - fiber - B - fiber - C, plus a second fiber between A and B
        var cell = energyCell(0, 0, 0, 100_000);
        var cableA = cable(1, 0, 0);
        cable(1, 0, 1);
        var cableB = cable(3, 0, 0);
        cable(3, 0, 1);
        var terminalB = terminalAbove(3, 0, 0);
        var cableC = cable(5, 0, 0);
        var terminalC = terminalAbove(5, 0, 0);
        fiber(2, 0, 0, Direction.EAST);
        fiber(2, 0, 1, Direction.WEST); // the other way round: main node on B, outer node on A
        fiber(4, 0, 0, Direction.EAST);
        world.tick(40);

        assertEquals(3, world.getGrids().size());
        var a = cableA.getGrid().getEnergyService();
        var b = cableB.getGrid().getEnergyService();
        var c = cableC.getGrid().getEnergyService();
        assertEquals(3, a.getConnectedServices().size());
        assertEquals(a.getConnectedServices(), b.getConnectedServices());
        assertEquals(a.getConnectedServices(), c.getConnectedServices());
        assertTrue(terminalB.isActive());
        assertTrue(terminalC.isActive(), "C is powered through B");

        double before = cell.getAECurrentPower();
        assertEquals(700, c.extractAEPower(700, Actionable.MODULATE, PowerMultiplier.ONE), 1e-9);
        assertEquals(700, before - cell.getAECurrentPower(), 1e-9);
        // More than there is: every storage is emptied once, nothing is counted twice
        double available = cell.getAECurrentPower() + a.getLocalStorage().getAECurrentPower()
                + b.getLocalStorage().getAECurrentPower() + c.getLocalStorage().getAECurrentPower();
        assertEquals(available, c.extractAEPower(1_000_000, Actionable.SIMULATE, PowerMultiplier.ONE), 1e-6);

        // Breaking the middle fiber cuts C off, A and B stay joined
        world.removeNode(p(4, 0, 0));
        world.tick(2);
        assertFalse(terminalC.isPowered());
        assertTrue(terminalB.isActive());
        assertEquals(1, c.getConnectedServices().size());
        assertEquals(2, a.getConnectedServices().size());
    }

    @Test
    void fiberCarriesNoChannels() {
        // A has a controller; B has nine devices and would need one too
        energyCell(0, 0, 0, 100_000);
        cable(1, 0, 0);
        world.addNode(p(1, -1, 0), NodeType.CONTROLLER, null, null, null);
        fiber(2, 0, 0, Direction.EAST);
        GridNode last = null;
        for (int x = 3; x < 12; x++) {
            cable(x, 0, 0);
            last = terminalAbove(x, 0, 0);
        }
        world.tick(40);
        assertTrue(last.isPowered());
        assertFalse(last.isActive());
        assertEquals(AdHocNetworkError.TOO_MANY_CHANNELS, last.getGrid().getPathingService().getAdHocNetworkError());
    }

    @Test
    void mergingNetworksKeepsTheEnergyLink() {
        var cell = energyCell(0, 0, 0, 100_000);
        cable(1, 0, 0);
        fiber(2, 0, 0, Direction.EAST);
        var cableB = cable(3, 0, 0);
        var terminalB = terminalAbove(3, 0, 0);
        // A second, separate network that later joins B
        var cableD = cable(5, 0, 0);
        var terminalD = terminalAbove(5, 0, 0);
        world.tick(40);
        assertTrue(terminalB.isActive());
        assertFalse(terminalD.isPowered());

        cable(4, 0, 0);
        world.tick(40);
        assertSame(cableB.getGrid(), cableD.getGrid());
        assertTrue(terminalD.isActive());
        assertTrue(terminalB.isActive());
        assertEquals(2, cableD.getGrid().getEnergyService().getConnectedServices().size());

        // Splitting it off again: D is on its own
        world.removeNode(p(4, 0, 0));
        world.tick(2);
        assertFalse(terminalD.isPowered());
        assertTrue(terminalB.isActive());
        assertTrue(cell.getAECurrentPower() < 100_000);
    }

    @Test
    void onePassiveGeneratorPerOverlayGrid() {
        var genA = new CrystalResonanceGeneratorLogic();
        var genB = new CrystalResonanceGeneratorLogic();
        cable(1, 0, 0);
        world.addNode(p(1, 1, 0), NodeType.CRYSTAL_RESONANCE_GENERATOR, Direction.UP, null, genA);
        cable(3, 0, 0);
        world.addNode(p(3, 1, 0), NodeType.CRYSTAL_RESONANCE_GENERATOR, Direction.UP, null, genB);
        world.tick(5);
        assertFalse(genA.isSuppressed());
        assertFalse(genB.isSuppressed(), "separate networks: both generate");

        fiber(2, 0, 0, Direction.EAST);
        world.tick(5);
        assertTrue(genA.isSuppressed() != genB.isSuppressed(), "joined by a fiber: only one generates");

        world.removeNode(p(2, 0, 0));
        world.tick(5);
        assertFalse(genA.isSuppressed());
        assertFalse(genB.isSuppressed());
    }
}
