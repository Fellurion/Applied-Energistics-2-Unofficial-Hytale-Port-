/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IGridService;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.IStackWatcher;
import de.fellurion.ae2.core.storage.IStorageMounts;
import de.fellurion.ae2.core.storage.IStorageProvider;
import de.fellurion.ae2.core.storage.MEStorage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Autocrafting service of a grid. Port of {@code appeng.me.service.CraftingService} (+
 * {@code CraftingServiceStorage}).
 * <ul>
 * <li>Collects the patterns of all {@link ICraftingProvider} nodes (pattern providers) of the grid.</li>
 * <li>Forms crafting CPUs ({@link CraftingCpuCluster}) from the {@link CraftingUnitLogic} nodes of the grid and ticks
 * them at the end of every tick.</li>
 * <li>Mounts a storage with priority {@link Integer#MAX_VALUE} that intercepts items awaited by CPUs (crafting
 * results returned by pattern providers into the network).</li>
 * </ul>
 * Typical use from a terminal: {@code plan = service.calculate(src, key, amount, strategy)}; show
 * {@code plan.missingItems()/bytes()}; then {@code service.submitJob(plan, null, null, true, src)}.
 * <p>
 * Deviations: calculations run synchronously; crafting watchers ({@link ICraftingWatcherNode}) are only told about
 * request changes, not about craftable changes; CPU clusters are formed from adjacent crafting nodes of the grid (filled cuboid, each extent ≤ 17 blocks,
 * at least one storage block) when crafting nodes are added/removed, evaluated at the end of the tick.
 */
public final class CraftingService implements IGridService {
    /** Co-processors descending, then storage ascending. */
    private static final Comparator<CraftingCpuCluster> FAST_FIRST_COMPARATOR = Comparator
            .comparingInt(CraftingCpuCluster::getCoProcessors).reversed()
            .thenComparingLong(CraftingCpuCluster::getAvailableStorage);
    /** Co-processors ascending, then storage ascending. */
    private static final Comparator<CraftingCpuCluster> FAST_LAST_COMPARATOR = Comparator
            .comparingInt(CraftingCpuCluster::getCoProcessors)
            .thenComparingLong(CraftingCpuCluster::getAvailableStorage);

    private final Grid grid;
    private final NetworkCraftingProviders craftingProviders = new NetworkCraftingProviders();
    private final List<CraftingCpuCluster> craftingCPUClusters = new ArrayList<>();
    private final Map<UUID, CraftingLink.Nexus> craftingLinks = new HashMap<>();
    private final Set<AEKey> currentlyCrafting = new HashSet<>();
    private final Map<GridNode, CraftingWatcher> craftingWatchers = new IdentityHashMap<>();
    private boolean updateList = false;

    public CraftingService(Grid grid) {
        this.grid = grid;
        grid.getStorageService().addGlobalStorageProvider(new CraftingServiceStorage());
    }

    public Grid getGrid() {
        return grid;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Node management

    @Override
    public void addNode(GridNode node) {
        craftingProviders.removeProvider(node);
        craftingProviders.addProvider(node);
        var requester = node.getService(ICraftingRequester.class);
        if (requester != null) {
            for (var link : requester.getRequestedJobs()) {
                if (link instanceof CraftingLink cl) {
                    addLink(cl);
                }
            }
        }
        if (node.getOwner() instanceof CraftingUnitLogic) {
            updateList = true;
        }
        var watchingNode = node.getService(ICraftingWatcherNode.class);
        if (watchingNode != null) {
            var watcher = new CraftingWatcher(watchingNode);
            craftingWatchers.put(node, watcher);
            watchingNode.updateCraftingWatcher(watcher);
        }
    }

    @Override
    public void removeNode(GridNode node) {
        var craftingWatcher = craftingWatchers.remove(node);
        if (craftingWatcher != null) {
            craftingWatcher.reset();
        }
        var requester = node.getService(ICraftingRequester.class);
        if (requester != null) {
            for (var nexus : craftingLinks.values()) {
                if (nexus.isRequester(requester)) {
                    nexus.removeNode();
                }
            }
        }
        craftingProviders.removeProvider(node);
        if (node.getOwner() instanceof CraftingUnitLogic) {
            updateList = true;
        }
    }

    /** Marks the CPU list for recalculation at the end of the tick. */
    public void clustersChanged() {
        updateList = true;
    }

    @Override
    public void onServerEndTick() {
        if (updateList) {
            updateList = false;
            updateCPUClusters();
        }
        craftingLinks.values().removeIf(nexus -> nexus.isDead(this));
        for (var cpu : List.copyOf(craftingCPUClusters)) {
            if (!cpu.isDestroyed()) {
                cpu.craftingLogic.tickCraftingLogic(grid.getEnergyService(), this);
            }
        }
        boolean watched = !craftingWatchers.isEmpty();
        Set<AEKey> previouslyCrafting = !watched || currentlyCrafting.isEmpty() ? Set.of()
                : new HashSet<>(currentlyCrafting);
        currentlyCrafting.clear();
        for (var cpu : craftingCPUClusters) {
            cpu.craftingLogic.getAllWaitingFor(currentlyCrafting);
        }

        // Notify watchers about keys whose crafting started or ended, but only if there can be changes and there
        // are watchers
        if (watched && !(previouslyCrafting.isEmpty() && currentlyCrafting.isEmpty())
                && !previouslyCrafting.equals(currentlyCrafting)) {
            var changed = new HashSet<AEKey>();
            for (var what : previouslyCrafting) {
                if (!currentlyCrafting.contains(what)) {
                    changed.add(what);
                }
            }
            for (var what : currentlyCrafting) {
                if (!previouslyCrafting.contains(what)) {
                    changed.add(what);
                }
            }
            for (var watcher : List.copyOf(craftingWatchers.values())) {
                for (var what : changed) {
                    if (watcher.watchAll || watcher.keys.contains(what)) {
                        watcher.host.onRequestChange(what);
                    }
                }
            }
        }
    }

    /** Port of {@code appeng.me.helpers.StackWatcher} for {@link ICraftingWatcherNode}s. */
    private final class CraftingWatcher implements IStackWatcher {
        final ICraftingWatcherNode host;
        final Set<AEKey> keys = new HashSet<>();
        boolean watchAll;

        CraftingWatcher(ICraftingWatcherNode host) {
            this.host = host;
        }

        @Override
        public void setWatchAll(boolean watchAll) {
            this.watchAll = watchAll;
        }

        @Override
        public boolean add(AEKey key) {
            return keys.add(key);
        }

        @Override
        public boolean remove(AEKey key) {
            return keys.remove(key);
        }

        @Override
        public void reset() {
            keys.clear();
            watchAll = false;
        }

        @Override
        public void destroy() {
            reset();
            craftingWatchers.values().remove(this);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // CPU clusters

    private void updateCPUClusters() {
        var units = new ArrayList<CraftingUnitLogic>();
        for (var n : grid.getNodes()) {
            if (n.getOwner() instanceof CraftingUnitLogic u && u.getNode() == n) {
                units.add(u);
            }
        }
        var visited = new HashSet<CraftingUnitLogic>();
        var newClusters = new ArrayList<CraftingCpuCluster>();
        boolean changed = false;
        for (var start : units) {
            if (!visited.add(start)) {
                continue;
            }
            // Component of adjacent (connected) crafting blocks
            var comp = new ArrayList<CraftingUnitLogic>();
            var queue = new ArrayDeque<CraftingUnitLogic>();
            queue.add(start);
            while (!queue.isEmpty()) {
                var u = queue.poll();
                comp.add(u);
                for (var c : u.getNode().getConnections()) {
                    var other = c.getOtherSide(u.getNode());
                    if (other.getOwner() instanceof CraftingUnitLogic ou && ou.getNode() == other && visited.add(ou)) {
                        queue.add(ou);
                    }
                }
            }
            long[] bounds = validCuboid(comp);
            if (bounds == null) {
                for (var u : comp) {
                    if (u.cluster != null && !u.cluster.isDestroyed()) {
                        u.cluster.breakCluster();
                        changed = true;
                    }
                }
                continue;
            }
            var existing = comp.getFirst().cluster;
            if (existing != null && !existing.isDestroyed() && Set.copyOf(existing.getUnits()).equals(Set.copyOf(comp))) {
                newClusters.add(existing);
                continue;
            }
            // Structure changed: break old clusters of these blocks, form a new one
            for (var u : comp) {
                if (u.cluster != null && !u.cluster.isDestroyed()) {
                    u.cluster.breakCluster();
                }
            }
            // Core block: the first block with saved state, else the first block
            String state = null;
            CraftingUnitLogic core = comp.getFirst();
            for (var u : comp) {
                var s = u.takeSavedClusterState();
                if (s != null && state == null) {
                    state = s;
                    core = u;
                }
            }
            comp.remove(core);
            comp.addFirst(core);
            var cluster = new CraftingCpuCluster(comp, bounds[0], bounds[1]);
            for (var u : comp) {
                u.cluster = cluster;
            }
            if (state != null) {
                try {
                    cluster.loadFromJson(state);
                } catch (RuntimeException e) {
                    // corrupt state: start empty
                }
            }
            for (var u : comp) {
                u.changed();
            }
            newClusters.add(cluster);
            changed = true;
        }

        for (var old : craftingCPUClusters) {
            if (!newClusters.contains(old) && !old.isDestroyed()) {
                boolean movedAway = true;
                for (var u : old.getUnits()) {
                    var n = u.getNode();
                    if (n == null || n.isRemoved() || n.getGrid() == grid || u.cluster != old) {
                        movedAway = false;
                        break;
                    }
                }
                if (!movedAway) {
                    old.breakCluster();
                }
                changed = true;
            }
        }
        craftingCPUClusters.clear();
        craftingCPUClusters.addAll(newClusters);
        for (var cpu : craftingCPUClusters) {
            var link = cpu.craftingLogic.getLastLink();
            if (link instanceof CraftingLink cl) {
                addLink(cl);
            }
        }
        if (changed) {
            // multiblock channel sharing depends on the clusters
            grid.getPathingService().repath();
        }
    }

    /** @return {min, max} packed positions if the blocks fill a valid cuboid with storage, else null */
    private static long[] validCuboid(List<CraftingUnitLogic> comp) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean storage = false;
        for (var u : comp) {
            long p = u.getNode().getPos();
            minX = Math.min(minX, BlockPos.getX(p));
            minY = Math.min(minY, BlockPos.getY(p));
            minZ = Math.min(minZ, BlockPos.getZ(p));
            maxX = Math.max(maxX, BlockPos.getX(p));
            maxY = Math.max(maxY, BlockPos.getY(p));
            maxZ = Math.max(maxZ, BlockPos.getZ(p));
            storage |= u.getStorageBytes() > 0;
        }
        // CraftingCPUCalculator.checkMultiblockScale: max - min <= 16 on each axis
        if (maxX - minX > 16 || maxY - minY > 16 || maxZ - minZ > 16) {
            return null;
        }
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume != comp.size() || !storage) {
            return null;
        }
        return new long[] { BlockPos.asLong(minX, minY, minZ), BlockPos.asLong(maxX, maxY, maxZ) };
    }

    /** Active, formed CPUs of this grid. */
    public List<CraftingCpuCluster> getCpus() {
        var list = new ArrayList<CraftingCpuCluster>();
        for (var cpu : craftingCPUClusters) {
            if (cpu.isActive() && !cpu.isDestroyed()) {
                list.add(cpu);
            }
        }
        return list;
    }

    /** All formed CPUs, including inactive ones. */
    public List<CraftingCpuCluster> getAllCpus() {
        return List.copyOf(craftingCPUClusters);
    }

    public boolean hasCpu(CraftingCpuCluster cpu) {
        return craftingCPUClusters.contains(cpu);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Links

    void addLink(CraftingLink link) {
        if (link.isStandalone()) {
            return;
        }
        var nexus = craftingLinks.computeIfAbsent(link.getCraftingID(), CraftingLink.Nexus::new);
        link.setNexus(nexus);
    }

    long insertIntoCpus(AEKey what, long amount, Actionable type) {
        long inserted = 0;
        for (var cpu : craftingCPUClusters) {
            if (!cpu.isDestroyed()) {
                inserted += cpu.craftingLogic.insert(what, amount - inserted, type);
            }
        }
        return inserted;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Patterns

    public Collection<IPatternDetails> getCraftingFor(AEKey whatToCraft) {
        return craftingProviders.getCraftingFor(whatToCraft);
    }

    public AEKey getFuzzyCraftable(AEKey whatToCraft, Predicate<AEKey> filter) {
        return craftingProviders.getFuzzyCraftable(whatToCraft, filter);
    }

    /** All craftable (and emitable) keys matching the filter, e.g. for "craftable" entries in terminals. */
    public Set<AEKey> getCraftables(Predicate<AEKey> filter) {
        return craftingProviders.getCraftables(filter);
    }

    public boolean isCraftable(AEKey what) {
        return !getCraftingFor(what).isEmpty() || canEmitFor(what);
    }

    public boolean canEmitFor(AEKey someItem) {
        return craftingProviders.canEmitFor(someItem);
    }

    /** Providers (round robin) that can execute the pattern. */
    public Iterable<ICraftingProvider> getProviders(IPatternDetails key) {
        return craftingProviders.getMediums(key);
    }

    public void refreshNodeCraftingProvider(GridNode node) {
        craftingProviders.removeProvider(node);
        craftingProviders.addProvider(node);
    }

    public void addGlobalCraftingProvider(ICraftingProvider cc) {
        craftingProviders.addProvider(cc);
    }

    public void removeGlobalCraftingProvider(ICraftingProvider cc) {
        craftingProviders.removeProvider(cc);
    }

    public void refreshGlobalCraftingProvider(ICraftingProvider cc) {
        craftingProviders.removeProvider(cc);
        craftingProviders.addProvider(cc);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Requests

    /**
     * Runs a crafting calculation (synchronously). AE2: {@code beginCraftingCalculation}, which returns a future.
     */
    public CraftingPlan calculate(IActionSource src, AEKey what, long amount, CalculationStrategy strategy) {
        if (what == null || amount <= 0) {
            throw new IllegalArgumentException("Invalid Crafting Job Request");
        }
        return new CraftingCalculation(grid, src, new GenericStack(what, amount), strategy).run();
    }

    /**
     * Submits a plan to a CPU.
     *
     * @param requester       requester that receives the final output (null: output goes into the network)
     * @param target          a specific CPU, or null to auto-select
     * @param prioritizePower auto-select the fastest CPU (else the slowest suitable one)
     */
    public CraftingSubmitResult submitJob(CraftingPlan job, ICraftingRequester requester, CraftingCpuCluster target,
            boolean prioritizePower, IActionSource src) {
        if (job.simulation()) {
            return CraftingSubmitResult.INCOMPLETE_PLAN;
        }
        CraftingCpuCluster cpuCluster;
        if (target != null) {
            cpuCluster = target;
        } else {
            var unsuitable = new UnsuitableCpus[1];
            cpuCluster = findSuitableCraftingCPU(job, prioritizePower, src, unsuitable);
            if (cpuCluster == null) {
                return unsuitable[0] == null ? CraftingSubmitResult.NO_CPU_FOUND
                        : CraftingSubmitResult.noSuitableCpu(unsuitable[0]);
            }
        }
        return cpuCluster.submitJob(grid, job, src, requester);
    }

    private CraftingCpuCluster findSuitableCraftingCPU(CraftingPlan job, boolean prioritizePower, IActionSource src,
            UnsuitableCpus[] unsuitableCpus) {
        var valid = new ArrayList<CraftingCpuCluster>();
        int offline = 0, busy = 0, tooSmall = 0, excluded = 0;
        for (var cpu : craftingCPUClusters) {
            if (cpu.isDestroyed() || !cpu.isActive()) {
                offline++;
                continue;
            }
            if (cpu.isBusy()) {
                busy++;
                continue;
            }
            if (cpu.getAvailableStorage() < job.bytes()) {
                tooSmall++;
                continue;
            }
            if (!cpu.canBeAutoSelectedFor(src)) {
                excluded++;
                continue;
            }
            valid.add(cpu);
        }
        if (valid.isEmpty()) {
            if (offline > 0 || busy > 0 || tooSmall > 0 || excluded > 0) {
                unsuitableCpus[0] = new UnsuitableCpus(offline, busy, tooSmall, excluded);
            }
            return null;
        }
        valid.sort((a, b) -> {
            var firstPreferred = a.isPreferredFor(src);
            var secondPreferred = b.isPreferredFor(src);
            if (firstPreferred != secondPreferred) {
                return Boolean.compare(secondPreferred, firstPreferred);
            }
            return prioritizePower ? FAST_FIRST_COMPARATOR.compare(a, b) : FAST_LAST_COMPARATOR.compare(a, b);
        });
        return valid.getFirst();
    }

    /** True if any CPU waits for this key. */
    public boolean isRequesting(AEKey what) {
        return currentlyCrafting.contains(what);
    }

    public boolean isRequestingAny() {
        return !currentlyCrafting.isEmpty();
    }

    public long getRequestedAmount(AEKey what) {
        long requested = 0;
        for (var cluster : craftingCPUClusters) {
            requested += cluster.craftingLogic.getWaitingFor(what);
        }
        return requested;
    }

    /** Intercepts awaited items inserted into the network and forwards them to the CPUs. */
    private final class CraftingServiceStorage implements IStorageProvider {
        private final MEStorage inventory = new MEStorage() {
            @Override
            public boolean isPreferredStorageFor(AEKey key, IActionSource source) {
                return true;
            }

            @Override
            public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
                return insertIntoCpus(what, amount, mode);
            }

            @Override
            public String getDescription() {
                return "ae2.autocrafting";
            }
        };

        @Override
        public void mountInventories(IStorageMounts mounts) {
            mounts.mount(inventory, Integer.MAX_VALUE);
        }
    }
}
