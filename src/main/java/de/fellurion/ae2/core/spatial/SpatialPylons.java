/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * All spatial pylons of one world: which pylon belongs to which line, and the spatial region of every network.
 * <p>
 * Replaces the bookkeeping AE2 spreads over {@code SpatialPylonBlockEntity} (each pylon recalculates its multiblock
 * when a neighbour changes, {@code MBCalculator.updateMultiblockAfterNeighborChange}) and the per-grid
 * {@code SpatialPylonService} instances:
 * <ul>
 * <li>{@link #add} / {@link #remove} are called when a pylon block appears or disappears; like in AE2 the pylon and
 * its six neighbours recalculate their line, and a recalculated line is assigned to all of its pylons;</li>
 * <li>{@link #serviceOf(Grid)} returns the region of a network. It is recomputed lazily after a pylon line changed
 * or after any pylon reported a booted network or a channel change ({@link #networkChanged()}), which corresponds to
 * AE2's recomputation on {@code GridBootingStatusChange}.</li>
 * </ul>
 * The cluster of every pylon always equals {@link SpatialPylonCalculator#calculate} for the current set of pylons.
 * Not thread-safe: use it from the world's tick thread.
 */
public final class SpatialPylons {

    /** Pylon position -> its line; a pylon without a line maps to null. */
    private final Map<Long, SpatialPylonCluster> pylons = new HashMap<>();
    private final SpatialPylonCalculator.PylonLookup lookup = this::isPylon;
    private final Map<Grid, CachedService> services = new WeakHashMap<>();
    /** Counts every change of a pylon line. */
    private long clusterVersion;
    /** Counts every reported network change. */
    private long networkVersion;

    private static final class CachedService {
        final SpatialPylonService service = new SpatialPylonService();
        long clusterVersion = -1;
        long networkVersion = -1;
    }

    public boolean isPylon(int x, int y, int z) {
        return pylons.containsKey(BlockPos.asLong(x, y, z));
    }

    /** Number of pylons known in this world. */
    public int size() {
        return pylons.size();
    }

    /** The line the pylon at this position belongs to, or null (no pylon there, or not part of a valid line). */
    public SpatialPylonCluster clusterAt(int x, int y, int z) {
        return pylons.get(BlockPos.asLong(x, y, z));
    }

    public SpatialPylonCluster clusterAt(long pos) {
        return pylons.get(pos);
    }

    /** Changes whenever a pylon line forms, changes or breaks. */
    public long clusterVersion() {
        return clusterVersion;
    }

    /** A pylon block appeared (placed or loaded). */
    public void add(int x, int y, int z) {
        long pos = BlockPos.asLong(x, y, z);
        if (pylons.containsKey(pos)) {
            return;
        }
        pylons.put(pos, null);
        recalculateAround(x, y, z);
    }

    /** A pylon block disappeared (broken or unloaded). */
    public void remove(int x, int y, int z) {
        long pos = BlockPos.asLong(x, y, z);
        if (!pylons.containsKey(pos)) {
            return;
        }
        SpatialPylonCluster old = pylons.remove(pos);
        if (old != null) {
            dissolve(old);
        }
        recalculateAround(x, y, z);
    }

    /** AE2: the changed block and every adjacent pylon recalculate their multiblock. */
    private void recalculateAround(int x, int y, int z) {
        recalculate(x, y, z);
        for (Direction d : Direction.values()) {
            recalculate(x + d.dx, y + d.dy, z + d.dz);
        }
    }

    private void recalculate(int x, int y, int z) {
        long pos = BlockPos.asLong(x, y, z);
        if (!pylons.containsKey(pos)) {
            return;
        }
        SpatialPylonCluster old = pylons.get(pos);
        SpatialPylonCluster now = SpatialPylonCalculator.calculate(lookup, x, y, z);
        if (Objects.equals(old, now)) {
            return;
        }
        if (old != null) {
            dissolve(old);
        }
        if (now != null) {
            // AE2 updateBlockEntities: the new cluster takes all pylons inside its bounds
            for (int px = now.minX(); px <= now.maxX(); px++) {
                for (int py = now.minY(); py <= now.maxY(); py++) {
                    for (int pz = now.minZ(); pz <= now.maxZ(); pz++) {
                        SpatialPylonCluster before = pylons.put(BlockPos.asLong(px, py, pz), now);
                        if (before != null && !before.equals(now)) {
                            dissolve(before);
                        }
                    }
                }
            }
        }
        clusterVersion++;
    }

    /** AE2 {@code SpatialPylonCluster.destroy}: every pylon still assigned to the cluster loses it. */
    private void dissolve(SpatialPylonCluster cluster) {
        for (int px = cluster.minX(); px <= cluster.maxX(); px++) {
            for (int py = cluster.minY(); py <= cluster.maxY(); py++) {
                for (int pz = cluster.minZ(); pz <= cluster.maxZ(); pz++) {
                    long p = BlockPos.asLong(px, py, pz);
                    if (cluster.equals(pylons.get(p))) {
                        pylons.put(p, null);
                    }
                }
            }
        }
        clusterVersion++;
    }

    /** A pylon's network finished booting, its channels or its power changed: the regions have to be looked at again. */
    public void networkChanged() {
        networkVersion++;
    }

    /** Changes whenever what a pylon shows may have changed (lines, channels, power). */
    public long stateVersion() {
        return clusterVersion + networkVersion;
    }

    /**
     * The spatial region of a network (AE2: {@code grid.getSpatialService()}). The returned object is updated in
     * place on later calls.
     */
    public SpatialPylonService serviceOf(Grid grid) {
        CachedService cached = services.computeIfAbsent(grid, g -> new CachedService());
        if (cached.clusterVersion != clusterVersion || cached.networkVersion != networkVersion) {
            cached.clusterVersion = clusterVersion;
            cached.networkVersion = networkVersion;
            List<SpatialPylonCluster> clusters = new ArrayList<>();
            if (!pylons.isEmpty()) {
                for (GridNode node : grid.getNodes()) {
                    if (node.getOwner() instanceof SpatialPylonLogic && node.meetsChannelRequirements()) {
                        SpatialPylonCluster c = pylons.get(node.getPos());
                        if (c != null) {
                            clusters.add(c);
                        }
                    }
                }
            }
            cached.service.reset(clusters);
        }
        return cached.service;
    }
}
