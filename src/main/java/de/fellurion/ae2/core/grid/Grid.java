/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

import de.fellurion.ae2.core.crafting.CraftingService;
import de.fellurion.ae2.core.energy.EnergyService;
import de.fellurion.ae2.core.grid.ticking.TickManager;
import de.fellurion.ae2.core.storage.StorageService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A connected ME network. Port of {@code appeng.me.Grid}.
 * <p>
 * Grids are created, merged and split automatically by {@link MEWorld} when nodes are added or removed. Services (in
 * AE2 registration order): {@link TickManager}, {@link PathingService}, {@link EnergyService},
 * {@link StorageService}, {@link CraftingService}, plus any {@link IGridService} registered via
 * {@link MEWorld#addServiceFactory}.
 */
public final class Grid {
    private final MEWorld world;
    private final Set<GridNode> nodes = new LinkedHashSet<>();
    private final TickManager tickManager = new TickManager();
    private final PathingService pathingService;
    private final EnergyService energyService;
    private final StorageService storageService;
    private final CraftingService craftingService;
    private final List<IGridService> extraServices = new ArrayList<>();
    private final List<IGridService> services = new ArrayList<>();

    Grid(MEWorld world) {
        this.world = world;
        this.pathingService = new PathingService(this);
        this.energyService = new EnergyService(this);
        this.storageService = new StorageService();
        this.craftingService = new CraftingService(this);
        services.add(pathingService);
        services.add(energyService);
        services.add(storageService);
        services.add(craftingService);
        for (var f : world.serviceFactories()) {
            var s = f.apply(this);
            extraServices.add(s);
            services.add(s);
        }
    }

    public MEWorld getWorld() {
        return world;
    }

    public Set<GridNode> getNodes() {
        return Collections.unmodifiableSet(nodes);
    }

    public int size() {
        return nodes.size();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** All controller nodes of this grid. */
    public List<GridNode> getControllers() {
        var list = new ArrayList<GridNode>();
        for (var n : nodes) {
            if (n.isController()) {
                list.add(n);
            }
        }
        return list;
    }

    /** All nodes whose owner is an instance of the given class (AE2 {@code getMachines}). */
    public <T> List<T> getMachines(Class<T> ownerClass) {
        var list = new ArrayList<T>();
        for (var n : nodes) {
            if (ownerClass.isInstance(n.getOwner())) {
                list.add(ownerClass.cast(n.getOwner()));
            }
        }
        return list;
    }

    public TickManager getTickManager() {
        return tickManager;
    }

    public PathingService getPathingService() {
        return pathingService;
    }

    public EnergyService getEnergyService() {
        return energyService;
    }

    public StorageService getStorageService() {
        return storageService;
    }

    public CraftingService getCraftingService() {
        return craftingService;
    }

    /** Returns an extra service registered through {@link MEWorld#addServiceFactory}. */
    public <T extends IGridService> T getService(Class<T> type) {
        for (var s : extraServices) {
            if (type.isInstance(s)) {
                return type.cast(s);
            }
        }
        return null;
    }

    /** "Priority" used when merging grids (AE2: powered grids win, then size). */
    int mergePriority() {
        return energyService.isNetworkPowered() ? 1 : 0;
    }

    void add(GridNode node, double bufferedEnergy) {
        nodes.add(node);
        node.grid = this;
        tickManager.addNode(node);
        pathingService.addNode(node);
        energyService.addNode(node, bufferedEnergy);
        storageService.addNode(node);
        craftingService.addNode(node);
        for (var s : extraServices) {
            s.addNode(node);
        }
    }

    /** @return the node's share of the local energy buffer */
    double remove(GridNode node) {
        for (var s : extraServices) {
            s.removeNode(node);
        }
        craftingService.removeNode(node);
        storageService.removeNode(node);
        double share = energyService.removeNodeReturningShare(node);
        pathingService.removeNode(node);
        tickManager.removeNode(node);
        nodes.remove(node);
        node.grid = null;
        return share;
    }

    void onServerStartTick() {
        tickManager.onServerStartTick();
        for (var s : services) {
            s.onServerStartTick();
        }
    }

    void tickMachines() {
        tickManager.tickQueue();
    }

    void onServerEndTick() {
        for (var s : services) {
            s.onServerEndTick();
        }
    }

    void onNodeStateChanged(GridNode node, NodeState state) {
        for (var s : services) {
            s.onNodeStateChanged(node, state);
        }
    }

    public void notifyAllNodes(NodeState state) {
        for (var n : List.copyOf(nodes)) {
            if (n.grid == this) {
                n.notifyStatusChange(state);
            }
        }
    }

    @Override
    public String toString() {
        return "Grid[" + nodes.size() + " nodes]";
    }
}
