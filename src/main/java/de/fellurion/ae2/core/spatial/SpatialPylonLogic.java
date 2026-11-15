/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IGridMultiblock;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.NodeState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * One spatial pylon: owner of its grid node. Port of {@code appeng.blockentity.spatial.SpatialPylonBlockEntity}.
 * <p>
 * All pylons of a line share one channel ({@link IGridMultiblock}); idle power is 0.5 AE/t per pylon (see
 * {@code NodeType.SPATIAL_PYLON}), so the power of a frame grows with its size as in AE2.
 * <p>
 * Deviations: in AE2 a pylon that is not part of a line exposes no sides and therefore never joins a network. The
 * node types of this port have fixed sides, so such a pylon still connects like any other device (and needs its own
 * channel); it just does not count for the spatial region. The line itself is kept by {@link SpatialPylons}.
 */
public final class SpatialPylonLogic implements IGridMultiblock, IGridNodeListener {

    /** Where a pylon sits in its line. Port of {@code SpatialPylonBlockEntity.AxisPosition}. */
    public enum AxisPosition {
        NONE, START, MIDDLE, END
    }

    /**
     * What a pylon shows. Port of {@code SpatialPylonBlockEntity.ClientState}.
     *
     * @param powered the network is powered and the pylon is part of a line
     * @param online  the line is part of a valid region and the pylon is powered and has its channel
     */
    public record DisplayState(boolean powered, boolean online, AxisPosition axisPosition,
            SpatialPylonCluster.Axis axis) {
        public static final DisplayState DEFAULT = new DisplayState(false, false, AxisPosition.NONE,
                SpatialPylonCluster.Axis.X);
    }

    private final SpatialPylons pylons;
    private final int x;
    private final int y;
    private final int z;
    private GridNode node;

    public SpatialPylonLogic(SpatialPylons pylons, int x, int y, int z) {
        this.pylons = pylons;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** The line this pylon belongs to, or null (AE2: {@code getCluster()}). */
    public SpatialPylonCluster getCluster() {
        return pylons.clusterAt(x, y, z);
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
        pylons.networkChanged();
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
        pylons.networkChanged();
    }

    @Override
    public void onGridChanged(GridNode node) {
        pylons.networkChanged();
    }

    @Override
    public void onStateChanged(GridNode node, NodeState state) {
        if (state != NodeState.CONNECTIONS) {
            pylons.networkChanged();
        }
    }

    /** AE2 {@code getMultiblockNodes}: the nodes of all pylons of the line (nothing without a line). */
    @Override
    public Iterator<GridNode> getMultiblockNodes() {
        SpatialPylonCluster cluster = getCluster();
        GridNode self = this.node;
        if (cluster == null || self == null) {
            return Collections.emptyIterator();
        }
        List<GridNode> nodes = new ArrayList<>(cluster.size());
        for (int px = cluster.minX(); px <= cluster.maxX(); px++) {
            for (int py = cluster.minY(); py <= cluster.maxY(); py++) {
                for (int pz = cluster.minZ(); pz <= cluster.maxZ(); pz++) {
                    GridNode n = self.getWorld().getNode(BlockPos.asLong(px, py, pz));
                    if (n != null && n.getOwner() instanceof SpatialPylonLogic) {
                        nodes.add(n);
                    }
                }
            }
        }
        return nodes.iterator();
    }

    /** AE2 {@code recalculateDisplay}. */
    public DisplayState displayState() {
        SpatialPylonCluster cluster = getCluster();
        GridNode n = this.node;
        if (cluster == null) {
            return DisplayState.DEFAULT;
        }
        AxisPosition pos;
        if (cluster.isStart(x, y, z)) {
            pos = AxisPosition.START;
        } else if (cluster.isEnd(x, y, z)) {
            pos = AxisPosition.END;
        } else {
            pos = AxisPosition.MIDDLE;
        }
        boolean powered = n != null && n.isPowered();
        boolean online = n != null && n.getGrid() != null && n.isOnline()
                && pylons.serviceOf(n.getGrid()).isValid(cluster);
        return new DisplayState(powered, online, pos, cluster.axis());
    }
}
