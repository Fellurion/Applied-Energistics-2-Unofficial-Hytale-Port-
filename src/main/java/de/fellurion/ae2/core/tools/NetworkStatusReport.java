/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import de.fellurion.ae2.core.energy.IPassiveEnergyGenerator;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.machines.VibrationChamberLogic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * Statistics about an ME network and the machines that form it, as shown by the Network Tool. Port of
 * {@code appeng.menu.me.networktool.NetworkStatus}, {@code MachineGroup} and {@code MachineGroupKey}.
 * <p>
 * Machines are grouped by their representation and by whether they lack a channel. AE2 takes the representation from
 * {@code IGridNode.getVisualRepresentation()} (an item); the core does not know items, so the caller supplies it
 * (the item id of the block at the node's position). Nodes without a representation are left out, as in AE2.
 *
 * @param averagePowerInjection AE/t fed into the network (rolling average)
 * @param averagePowerUsage     AE/t drained from the network (rolling average)
 * @param storedPower           AE currently stored
 * @param maxStoredPower        AE the network can store
 * @param channelPower          AE/t used for channels
 * @param channelsUsed          number of devices that received a channel
 * @param groupedMachines       machines grouped by representation, see {@link MachineGroup#COMPARATOR}
 */
public record NetworkStatusReport(double averagePowerInjection, double averagePowerUsage, double storedPower,
        double maxStoredPower, double channelPower, int channelsUsed, List<MachineGroup> groupedMachines) {

    /**
     * Machines grouped by their representation. Port of {@code MachineGroup}.
     *
     * @param display                 the representation the machines were grouped by
     * @param missingChannel          true for the group of machines of this kind that need a channel and have none
     * @param count                   number of machines in the group
     * @param idlePowerUsage          summed idle power usage in AE/t
     * @param powerGenerationCapacity the power this group can generate in AE/t
     */
    public record MachineGroup(String display, boolean missingChannel, int count, double idlePowerUsage,
            double powerGenerationCapacity) {

        /**
         * AE2's order: groups missing a channel first, then by count, largest first. The representation breaks ties
         * so the order is stable between refreshes.
         */
        public static final Comparator<MachineGroup> COMPARATOR = Comparator.comparing(MachineGroup::missingChannel)
                .thenComparingInt(MachineGroup::count).reversed().thenComparing(MachineGroup::display);
    }

    private static final class Sum {
        int count;
        double idle;
        double generation;
    }

    private record Key(String display, boolean missingChannel) {
    }

    /**
     * Port of {@code NetworkStatus.fromGrid}.
     *
     * @param representation the representation of a node, or null to leave the node out
     */
    public static NetworkStatusReport fromGrid(Grid grid, Function<GridNode, String> representation) {
        var energy = grid.getEnergyService();
        // This is essentially a groupBy machineRepresentation + count, sum(idlePowerUsage)
        var sums = new LinkedHashMap<Key, Sum>();
        for (GridNode machine : grid.getNodes()) {
            String display = representation.apply(machine);
            if (display == null) {
                continue;
            }
            var sum = sums.computeIfAbsent(new Key(display, !machine.meetsChannelRequirements()), k -> new Sum());
            sum.count++;
            sum.idle += machine.getIdlePowerUsage();
            var passive = machine.getService(IPassiveEnergyGenerator.class);
            if (passive != null && !passive.isSuppressed()) {
                sum.generation += passive.getRate();
            }
            if (machine.getOwner() instanceof VibrationChamberLogic chamber) {
                sum.generation += chamber.getMaxEnergyRate();
            }
        }
        var groups = new ArrayList<MachineGroup>(sums.size());
        for (var e : sums.entrySet()) {
            groups.add(new MachineGroup(e.getKey().display(), e.getKey().missingChannel(), e.getValue().count,
                    e.getValue().idle, e.getValue().generation));
        }
        groups.sort(MachineGroup.COMPARATOR);
        return new NetworkStatusReport(energy.getAvgPowerInjection(), energy.getAvgPowerUsage(),
                energy.getStoredPower(), energy.getMaxStoredPower(), energy.getChannelPowerUsage(),
                grid.getPathingService().getUsedChannels(), List.copyOf(groups));
    }
}
