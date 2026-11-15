/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.CableType;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.machines.CrystalResonanceGeneratorLogic;
import de.fellurion.ae2.core.tools.NetworkStatusReport.MachineGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkStatusReportTest {
    private MEWorld world;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
    }

    private static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    private static String kind(GridNode node) {
        return node.getType().isCable() ? null : node.getType().kind();
    }

    private static MachineGroup group(NetworkStatusReport report, String display, boolean missingChannel) {
        for (var g : report.groupedMachines()) {
            if (g.display().equals(display) && g.missingChannel() == missingChannel) {
                return g;
            }
        }
        throw new AssertionError("no group " + display + " missingChannel=" + missingChannel + " in "
                + report.groupedMachines());
    }

    @Test
    void groupsMachinesAndReadsEnergyAndChannels() {
        var cell = EnergyCellLogic.energyCell();
        cell.setAECurrentPower(1000);
        world.addNode(p(-1, 0, 0), NodeType.ENERGY_CELL, null, null, cell);
        for (int x = 0; x < 3; x++) {
            world.addNode(p(x, 0, 0), NodeType.cable(CableType.GLASS), null, AEColor.TRANSPARENT, null);
            world.addNode(p(x, 1, 0), NodeType.TERMINAL, Direction.UP, null, null);
        }
        world.addNode(p(3, 0, 0), NodeType.DRIVE, Direction.EAST, null, null);
        world.tick(40);

        var grid = world.getNode(0, 0, 0).getGrid();
        var report = NetworkStatusReport.fromGrid(grid, NetworkStatusReportTest::kind);
        assertEquals(3, report.groupedMachines().size(), "cables have no representation here and are left out");
        var terminals = group(report, "terminal", false);
        assertEquals(3, terminals.count());
        assertEquals(1.5, terminals.idlePowerUsage(), 1e-9, "3 x 0.5 AE/t");
        assertEquals(0, terminals.powerGenerationCapacity(), 1e-9);
        assertEquals(2.0, group(report, "drive", false).idlePowerUsage(), 1e-9);
        assertEquals(1, group(report, "energy_cell", false).count());
        assertEquals("terminal", report.groupedMachines().getFirst().display(), "largest group first");

        assertEquals(4, report.channelsUsed(), "three terminals and the drive");
        assertEquals(grid.getEnergyService().getChannelPowerUsage(), report.channelPower(), 1e-9);
        assertEquals(grid.getEnergyService().getMaxStoredPower(), report.maxStoredPower(), 1e-9);
        assertTrue(report.maxStoredPower() >= cell.getAEMaxPower(), "the cell plus the nodes' own buffers");
        assertEquals(grid.getEnergyService().getStoredPower(), report.storedPower(), 1e-9);
        assertTrue(report.storedPower() < 1000, "the network has been draining the cell");
        assertEquals(grid.getEnergyService().getAvgPowerUsage(), report.averagePowerUsage(), 1e-9);
        assertTrue(report.averagePowerUsage() > 0);
        assertEquals(0, report.averagePowerInjection(), 1e-9);
    }

    @Test
    void machinesWithoutChannelFormTheirOwnGroupAndComeFirst() {
        // ad-hoc network with nine channel users: nobody gets a channel
        world.addNode(p(-1, 0, 0), NodeType.ENERGY_CELL, null, null, EnergyCellLogic.energyCell());
        List<GridNode> terminals = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            world.addNode(p(x, 0, 0), NodeType.cable(CableType.GLASS), null, AEColor.TRANSPARENT, null);
            terminals.add(world.addNode(p(x, 1, 0), NodeType.TERMINAL, Direction.UP, null, null));
        }
        world.tick(40);
        var report = NetworkStatusReport.fromGrid(terminals.getFirst().getGrid(), NetworkStatusReportTest::kind);
        assertEquals(0, report.channelsUsed());
        var first = report.groupedMachines().getFirst();
        assertEquals("terminal", first.display());
        assertTrue(first.missingChannel());
        assertEquals(9, first.count());
        assertEquals(1, group(report, "energy_cell", false).count(), "no channel needed");
    }

    @Test
    void passiveGeneratorsReportTheirRate() {
        var generator = new CrystalResonanceGeneratorLogic();
        world.addNode(p(0, 0, 0), NodeType.cable(CableType.GLASS), null, AEColor.TRANSPARENT, null);
        world.addNode(p(0, 1, 0), NodeType.CRYSTAL_RESONANCE_GENERATOR, Direction.UP, null, generator);
        world.tick(5);
        var report = NetworkStatusReport.fromGrid(world.getNode(0, 0, 0).getGrid(), n -> n.getType().kind());
        assertEquals(generator.getRate(), group(report, "crystal_resonance_generator", false).powerGenerationCapacity(),
                1e-9);
        assertEquals(1, group(report, "cable", false).count(), "with a representation, cables are listed like in AE2");
    }
}
