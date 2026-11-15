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
import java.util.TreeSet;

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
 * <li>Grids joined by quartz fibers ({@link IEnergyOverlayGridConnection}) form an overlay energy grid: extracting,
 * injecting and the energy demand go through the storages of all member grids, largest storage first, each grid
 * once. The grids stay separate otherwise - {@link #getStoredPower()}, {@link #getMaxStoredPower()}, idle drain and
 * the powered state are per grid, and one passive generator is active per overlay grid.</li>
 * <li>Energy watchers ({@link IEnergyWatcherNode}, energy level emitters) are notified at the end of a tick in which
 * the stored energy passed one of their thresholds, and once at the end of the tick in which a threshold was
 * registered (not in AE2: there a watcher that joined a half-merged grid could keep a wrong amount).</li>
 * </ul>
 * Deviations: stored power is summed directly instead of being cached for 90 ticks; AE2's provider/requester removal
 * optimisation (re-adding via events) is replaced by simply iterating all storages.
 */
public final class EnergyService implements IGridService, IEnergySource {
    private static final double AVERAGE_LENGTH = 40.0;

    private final Grid grid;
    private final GridEnergyStorage localStorage = new GridEnergyStorage();
    /** All public storages in insertion order. */
    private final List<IAEPowerStorage> storages = new ArrayList<>();
    private final Map<GridNode, IAEPowerStorage> nodeStorages = new IdentityHashMap<>();
    private final Set<IPassiveEnergyGenerator> passiveGenerators = new LinkedHashSet<>();
    /** Connections to other grids' energy (quartz fibers); one entry per providing node. */
    private final List<IEnergyOverlayGridConnection> overlayGridConnections = new ArrayList<>();
    /** The overlay grid this service is a member of; null until it is (re)built on demand. */
    EnergyOverlayGrid overlayGrid;
    private IPassiveEnergyGenerator currentPassiveGenerator;
    /** Watched amounts of stored energy, ordered by amount (AE2: {@code interests}). */
    private final TreeSet<EnergyThreshold> interests = new TreeSet<>();
    private final Map<GridNode, EnergyWatcher> watchers = new IdentityHashMap<>();
    private double lastStoredPower;
    /** A threshold was registered since the last end of tick: every watcher is told the stored energy once. */
    private boolean newInterests;

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
        if (passiveGenerators.isEmpty()) {
            return;
        }
        // Only one passive generator may be active per overlay grid.
        var overlay = getOverlayGrid();
        for (var gen : passiveGenerators) {
            var current = overlay.getCurrentPassiveGenerator();
            if (current == gen) {
                gen.setSuppressed(false);
            } else if (current == null || gen.getRate() > current.getRate()) {
                if (current != null) {
                    current.setSuppressed(true);
                }
                overlay.setCurrentPassiveGenerator(gen);
                gen.setSuppressed(false);
            } else {
                gen.setSuppressed(true);
            }
        }
    }

    @Override
    public void onServerEndTick() {
        // Inject the passive energy once per overlay grid, at the energy service that contains the generator.
        if (!passiveGenerators.isEmpty()) {
            var currentPassiveGenerator = getOverlayGrid().getCurrentPassiveGenerator();
            if (currentPassiveGenerator != null && passiveGenerators.contains(currentPassiveGenerator)) {
                injectPower(currentPassiveGenerator.getRate(), Actionable.MODULATE);
            }
        }

        if (!interests.isEmpty()) {
            double oldPower = lastStoredPower;
            lastStoredPower = getStoredPower();
            if (newInterests) {
                // A watcher that registered while its grid was still being put together (merge, chunk load) may
                // have read an incomplete amount, and lastStoredPower is stale while nobody watches.
                newInterests = false;
                for (var watcher : List.copyOf(watchers.values())) {
                    if (!watcher.myInterests.isEmpty()) {
                        watcher.host.onThresholdPass(this);
                    }
                }
            } else {
                var low = new EnergyThreshold(Math.min(oldPower, lastStoredPower), null, Integer.MIN_VALUE);
                var high = new EnergyThreshold(Math.max(oldPower, lastStoredPower), null, Integer.MAX_VALUE);
                var passed = interests.subSet(low, true, high, true);
                if (!passed.isEmpty()) {
                    // copy: hosts may re-register their thresholds while being notified
                    for (var th : List.copyOf(passed)) {
                        th.watcher.host.onThresholdPass(this);
                    }
                }
            }
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
        double extracted = 0;
        for (var service : getConnectedServices()) {
            extracted += service.extractProviderPower(toExtract - extracted, mode);
            if (extracted >= toExtract) {
                break;
            }
        }
        return pm.divide(extracted);
    }

    /** Extracts from this grid's own storages only (one member of the overlay grid). */
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
     * Injects power into the storages of this grid and of the grids joined to it by quartz fibers.
     *
     * @return the amount that could not be stored (overflow)
     */
    public double injectPower(double amt, Actionable mode) {
        if (amt < 0) {
            throw new IllegalArgumentException("amt must be >= 0");
        }
        double leftover = amt;
        for (var service : getConnectedServices()) {
            leftover = service.injectProviderPower(leftover, mode);
            if (leftover <= 0) {
                break;
            }
        }
        return Math.max(0.0, leftover);
    }

    /** Injects into this grid's own storages only (one member of the overlay grid). */
    private double injectProviderPower(double amt, Actionable mode) {
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

    /**
     * How much energy (up to maxRequired) the storages of this grid and of the grids joined to it by quartz fibers
     * could still accept.
     */
    public double getEnergyDemand(double maxRequired) {
        double required = 0;
        for (var service : getConnectedServices()) {
            required += service.getProviderEnergyDemand(maxRequired - required);
            if (required >= maxRequired) {
                break;
            }
        }
        return required;
    }

    private double getProviderEnergyDemand(double maxRequired) {
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

    /** Sum of energy stored in all providers of this grid (AE); other grids of the overlay grid are not counted. */
    public double getStoredPower() {
        double sum = 0;
        for (var p : storages) {
            if (p.getPowerFlow().isAllowExtraction()) {
                sum += p.getAECurrentPower();
            }
        }
        return Math.max(0.0, sum);
    }

    public double getMaxStoredPower() {
        double sum = 0;
        for (var p : storages) {
            if (p.getPowerFlow().isAllowExtraction()) {
                sum += p.getAEMaxPower();
            }
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

        var overlayConnection = node.getService(IEnergyOverlayGridConnection.class);
        if (overlayConnection != null) {
            overlayGridConnections.add(overlayConnection);
            invalidateOverlayEnergyGrid();
        }

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

        var watcherHost = node.getService(IEnergyWatcherNode.class);
        if (watcherHost != null) {
            var watcher = new EnergyWatcher(watcherHost);
            watchers.put(node, watcher);
            watcherHost.updateWatcher(watcher);
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

        var overlayConnection = node.getService(IEnergyOverlayGridConnection.class);
        if (overlayConnection != null) {
            overlayGridConnections.remove(overlayConnection);
            invalidateOverlayEnergyGrid();
        }

        var gen = node.getService(IPassiveEnergyGenerator.class);
        if (gen != null) {
            passiveGenerators.remove(gen);
            if (overlayGrid != null && overlayGrid.getCurrentPassiveGenerator() == gen) {
                overlayGrid.setCurrentPassiveGenerator(null);
            }
        }

        var ps = nodeStorages.remove(node);
        if (ps != null) {
            storages.remove(ps);
        }

        var watcher = watchers.remove(node);
        if (watcher != null) {
            watcher.reset();
        }
        return share;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Overlay energy grid (quartz fibers)

    List<IEnergyOverlayGridConnection> getOverlayGridConnections() {
        return overlayGridConnections;
    }

    /**
     * Forgets which grids share their energy with this one; it is worked out again on the next energy operation.
     * Called automatically when a node providing {@link IEnergyOverlayGridConnection} joins or leaves this grid.
     */
    public void invalidateOverlayEnergyGrid() {
        if (overlayGrid != null) {
            overlayGrid.invalidate();
        }
    }

    /**
     * The energy services whose storages this grid uses: itself plus every grid reachable through quartz fibers,
     * largest storage first.
     */
    public List<EnergyService> getConnectedServices() {
        return getOverlayGrid().energyServices;
    }

    private EnergyOverlayGrid getOverlayGrid() {
        if (overlayGrid == null) {
            EnergyOverlayGrid.buildCache(this);
        }
        return overlayGrid;
    }

    // Energy watchers (port of appeng.me.energy.EnergyWatcher / EnergyThreshold)

    /**
     * A watched amount. Ordered by amount, then by the watcher's id, so that all thresholds between two amounts are a
     * sub set; {@code watcher == null} marks the bounds of such a query.
     */
    private record EnergyThreshold(double threshold, EnergyWatcher watcher, int order)
            implements Comparable<EnergyThreshold> {
        @Override
        public int compareTo(EnergyThreshold o) {
            int a = Double.compare(threshold, o.threshold);
            return a != 0 ? a : Integer.compare(order, o.order);
        }
    }

    private int nextWatcherId;

    private final class EnergyWatcher implements IEnergyWatcher {
        final IEnergyWatcherNode host;
        /** Unique per grid and never one of the query bounds. */
        final int id;
        final Set<EnergyThreshold> myInterests = new LinkedHashSet<>();

        EnergyWatcher(IEnergyWatcherNode host) {
            this.host = host;
            if (nextWatcherId == Integer.MAX_VALUE) {
                nextWatcherId = Integer.MIN_VALUE + 1; // never hand out one of the query bounds
            }
            this.id = nextWatcherId++;
        }

        @Override
        public boolean add(double amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("amount must be >= 0");
            }
            var th = new EnergyThreshold(amount, this, id);
            if (myInterests.contains(th)) {
                return false;
            }
            if (interests.add(th) && myInterests.add(th)) {
                newInterests = true;
                return true;
            }
            return false;
        }

        @Override
        public boolean remove(double amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("amount must be >= 0");
            }
            var th = new EnergyThreshold(amount, this, id);
            return myInterests.remove(th) && interests.remove(th);
        }

        @Override
        public void reset() {
            for (var th : myInterests) {
                interests.remove(th);
            }
            myInterests.clear();
        }
    }

    private void checkNotInOperation() {
        if (ongoingOperation) {
            throw new IllegalStateException("Cannot modify the energy grid while energy is being extracted/injected.");
        }
    }
}
