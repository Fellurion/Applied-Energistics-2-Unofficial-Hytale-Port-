/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.energy;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.AccessRestriction;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IGridService;
import de.fellurion.ae2.core.grid.NodeState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The energy network of a grid. Port of {@code appeng.me.service.EnergyService}.
 * <ul>
 * <li>Each tick (end of tick) the idle drain of all nodes plus the channel power ({@code channelsByBlocks/128}) is
 * extracted. The grid counts as powered if the full idle drain could be extracted (or, with ~0 drain, at least 0.1 AE
 * is available).</li>
 * <li>Power-up is buffered: the public "powered" state only switches on after the grid had power for more than 30
 * consecutive ticks (power-down is immediate), except for the very first tick of a grid.</li>
 * <li>Providers (energy cells, ...) are drained highest priority first; requesters are filled lowest priority first.
 * The grid's own 25 AE/node buffer has the lowest priority.</li>
 * <li>Only the strongest passive generator (crystal resonance generator) injects power, the others are suppressed.</li>
 * </ul>
 * Deviations: no energy overlay grid (quartz fibers), no energy watchers/thresholds; stored power is summed directly
 * instead of being cached for 90 ticks; AE2's provider/requester removal optimisation (re-adding via events) is
 * replaced by simply iterating all storages.
 */
public final class EnergyService implements IGridService, IEnergySource {
    private static final double AVERAGE_LENGTH = 40.0;

    private final Grid grid;
    private final GridEnergyStorage localStorage = new GridEnergyStorage();
    /** All public storages in insertion order. */
    private final List<IAEPowerStorage> storages = new ArrayList<>();
    private final Map<GridNode, IAEPowerStorage> nodeStorages = new IdentityHashMap<>();
    private final Set<IPassiveEnergyGenerator> passiveGenerators = new LinkedHashSet<>();
    private IPassiveEnergyGenerator currentPassiveGenerator;

    private double drainPerTick = 0;
    private double avgDrainPerTick = 0;
    private double avgInjectionPerTick = 0;
    private double tickDrainPerTick = 0;
    private double tickInjectionPerTick = 0;

    private boolean publicHasPower = false;
    private boolean hasPower = true;
    private long ticksSinceHasPowerChange = 900;
    private boolean ongoingOperation;

    public EnergyService(Grid grid) {
        this.grid = grid;
        storages.add(localStorage);
    }

    private List<IAEPowerStorage> providers() {
        var list = new ArrayList<IAEPowerStorage>();
        for (var s : storages) {
            if (s.getPowerFlow().isAllowExtraction()) {
                list.add(s);
            }
        }
        list.sort(Comparator.comparingInt(IAEPowerStorage::getPriority).reversed());
        return list;
    }

    private List<IAEPowerStorage> requesters() {
        var list = new ArrayList<IAEPowerStorage>();
        for (var s : storages) {
            if (s.getPowerFlow().isAllowInsertion()) {
                list.add(s);
            }
        }
        list.sort(Comparator.comparingInt(IAEPowerStorage::getPriority));
        return list;
    }

    @Override
    public void onServerStartTick() {
        // Only one passive generator may be active per grid.
        for (var gen : passiveGenerators) {
            if (currentPassiveGenerator == gen) {
                gen.setSuppressed(false);
            } else if (currentPassiveGenerator == null || gen.getRate() > currentPassiveGenerator.getRate()) {
                if (currentPassiveGenerator != null) {
                    currentPassiveGenerator.setSuppressed(true);
                }
                currentPassiveGenerator = gen;
                gen.setSuppressed(false);
            } else {
                gen.setSuppressed(true);
            }
        }
    }

    @Override
    public void onServerEndTick() {
        if (currentPassiveGenerator != null && passiveGenerators.contains(currentPassiveGenerator)) {
            injectPower(currentPassiveGenerator.getRate(), Actionable.MODULATE);
        }

        avgDrainPerTick *= (AVERAGE_LENGTH - 1) / AVERAGE_LENGTH;
        avgInjectionPerTick *= (AVERAGE_LENGTH - 1) / AVERAGE_LENGTH;
        avgDrainPerTick += tickDrainPerTick / AVERAGE_LENGTH;
        avgInjectionPerTick += tickInjectionPerTick / AVERAGE_LENGTH;
        tickDrainPerTick = 0;
        tickInjectionPerTick = 0;

        boolean currentlyHasPower;
        if (drainPerTick > 0.0001) {
            double drained = extractAEPower(getIdlePowerUsage(), Actionable.MODULATE, PowerMultiplier.CONFIG);
            currentlyHasPower = drained >= drainPerTick - 0.001;
        } else {
            currentlyHasPower = extractAEPower(0.1, Actionable.SIMULATE, PowerMultiplier.CONFIG) > 0;
        }

        if (currentlyHasPower == hasPower) {
            ticksSinceHasPowerChange++;
        } else {
            ticksSinceHasPowerChange = 0;
        }
        hasPower = currentlyHasPower;

        // update public status, this buffers power ups for 30 ticks.
        if (hasPower && ticksSinceHasPowerChange > 30) {
            publicPowerState(true);
        } else if (!hasPower) {
            publicPowerState(false);
        }
    }

    private void publicPowerState(boolean newState) {
        if (publicHasPower == newState) {
            return;
        }
        publicHasPower = newState;
        grid.notifyAllNodes(NodeState.POWER);
    }

    @Override
    public double extractAEPower(double amt, Actionable mode, PowerMultiplier pm) {
        double toExtract = pm.multiply(amt);
        double extracted = extractProviderPower(toExtract, mode);
        return pm.divide(extracted);
    }

    private double extractProviderPower(double amt, Actionable mode) {
        if (amt < 0) {
            throw new IllegalArgumentException("amt must be >= 0");
        }
        double extractedPower = 0;
        ongoingOperation = true;
        try {
            for (var node : providers()) {
                if (extractedPower >= amt) {
                    break;
                }
                double req = amt - extractedPower;
                extractedPower += node.extractAEPower(req, mode, PowerMultiplier.ONE);
            }
        } finally {
            ongoingOperation = false;
        }
        double result = Math.min(extractedPower, amt);
        if (mode == Actionable.MODULATE) {
            if (extractedPower > amt) {
                localStorage.injectAEPower(extractedPower - amt, Actionable.MODULATE);
            }
            tickDrainPerTick += result;
        }
        return result;
    }

    /**
     * Injects power into the grid's storages.
     *
     * @return the amount that could not be stored (overflow)
     */
    public double injectPower(double amt, Actionable mode) {
        if (amt < 0) {
            throw new IllegalArgumentException("amt must be >= 0");
        }
        double original = amt;
        ongoingOperation = true;
        try {
            for (var node : requesters()) {
                if (amt <= 0) {
                    break;
                }
                amt = node.injectAEPower(amt, mode);
            }
        } finally {
            ongoingOperation = false;
        }
        double overflow = Math.max(0.0, amt);
        if (mode == Actionable.MODULATE) {
            tickInjectionPerTick += original - overflow;
        }
        return overflow;
    }

    /** How much energy (up to maxRequired) the grid's storages could still accept. */
    public double getEnergyDemand(double maxRequired) {
        double required = 0;
        for (var node : requesters()) {
            if (required >= maxRequired) {
                break;
            }
            if (node.getPowerFlow() != AccessRestriction.READ) {
                required += Math.max(0.0, node.getAEMaxPower() - node.getAECurrentPower());
            }
        }
        return required;
    }

    /** Idle drain of all nodes + channel power (AE/t). */
    public double getIdlePowerUsage() {
        return drainPerTick + grid.getPathingService().getChannelPowerUsage();
    }

    public double getChannelPowerUsage() {
        return grid.getPathingService().getChannelPowerUsage();
    }

    public double getAvgPowerUsage() {
        return avgDrainPerTick;
    }

    public double getAvgPowerInjection() {
        return avgInjectionPerTick;
    }

    public boolean isNetworkPowered() {
        return publicHasPower;
    }

    /** Sum of energy stored in all providers (AE). */
    public double getStoredPower() {
        double sum = 0;
        for (var p : providers()) {
            sum += p.getAECurrentPower();
        }
        return Math.max(0.0, sum);
    }

    public double getMaxStoredPower() {
        double sum = 0;
        for (var p : providers()) {
            sum += p.getAEMaxPower();
        }
        return sum;
    }

    public GridEnergyStorage getLocalStorage() {
        return localStorage;
    }

    public void nodeIdlePowerChanged(GridNode node) {
        double newDraw = node.getIdlePowerUsage();
        double diff = newDraw - previousDraw(node);
        setPreviousDraw(node, newDraw);
        drainPerTick += diff;
    }

    private final Map<GridNode, Double> previousDraws = new IdentityHashMap<>();

    private double previousDraw(GridNode node) {
        return previousDraws.getOrDefault(node, 0.0);
    }

    private void setPreviousDraw(GridNode node, double draw) {
        previousDraws.put(node, draw);
    }

    @Override
    public void addNode(GridNode node) {
        addNode(node, 0);
    }

    /** Adds a node and injects energy that it carries over from a previous grid into the local buffer. */
    public void addNode(GridNode node, double bufferedEnergy) {
        checkNotInOperation();
        localStorage.addNode();
        setPreviousDraw(node, node.getIdlePowerUsage());
        drainPerTick += node.getIdlePowerUsage();

        var gen = node.getService(IPassiveEnergyGenerator.class);
        if (gen != null) {
            passiveGenerators.add(gen);
        }

        var ps = node.getService(IAEPowerStorage.class);
        if (ps != null && ps.isAEPublicPowerStorage()) {
            storages.add(ps);
            nodeStorages.put(node, ps);
        }

        if (bufferedEnergy > 0) {
            localStorage.injectAEPower(bufferedEnergy, Actionable.MODULATE);
        }
    }

    @Override
    public void removeNode(GridNode node) {
        removeNodeReturningShare(node);
    }

    /** Removes the node and returns its share of the grid's local energy buffer. */
    public double removeNodeReturningShare(GridNode node) {
        checkNotInOperation();
        double share = localStorage.removeNode();
        drainPerTick -= previousDraw(node);
        previousDraws.remove(node);

        var gen = node.getService(IPassiveEnergyGenerator.class);
        if (gen != null) {
            passiveGenerators.remove(gen);
            if (currentPassiveGenerator == gen) {
                currentPassiveGenerator = null;
            }
        }

        var ps = nodeStorages.remove(node);
        if (ps != null) {
            storages.remove(ps);
        }
        return share;
    }

    private void checkNotInOperation() {
        if (ongoingOperation) {
            throw new IllegalStateException("Cannot modify the energy grid while energy is being extracted/injected.");
        }
    }
}
