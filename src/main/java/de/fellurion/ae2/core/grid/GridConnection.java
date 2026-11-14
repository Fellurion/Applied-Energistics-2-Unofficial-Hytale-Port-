/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

import java.util.List;

/**
 * A connection between two adjacent grid nodes. Port of {@code appeng.me.GridConnection}.
 * <p>
 * During channel calculation, side A is always the side closer to a controller.
 */
public final class GridConnection implements IPathItem {
    int usedChannels = 0;
    private int lastUsedChannels = 0;
    private GridNode sideA;
    /** Direction from A to B. */
    private Direction fromAtoB;
    private GridNode sideB;

    GridConnection(GridNode a, GridNode b, Direction fromAtoB) {
        this.sideA = a;
        this.sideB = b;
        this.fromAtoB = fromAtoB;
    }

    public GridNode a() {
        return sideA;
    }

    public GridNode b() {
        return sideB;
    }

    public GridNode getOtherSide(GridNode node) {
        if (node == sideA) {
            return sideB;
        }
        if (node == sideB) {
            return sideA;
        }
        throw new IllegalArgumentException("The given grid node does not participate in this connection.");
    }

    /** Direction from the given side towards the other side. */
    public Direction getDirection(GridNode side) {
        return side == sideA ? fromAtoB : fromAtoB.getOpposite();
    }

    /** Channels carried by this connection after the last channel calculation. */
    public int getUsedChannels() {
        return lastUsedChannels;
    }

    @Override
    public void setAdHocChannels(int channels) {
        this.usedChannels = channels;
    }

    @Override
    public GridNode getControllerRoute() {
        return sideA;
    }

    @Override
    public void setControllerRoute(IPathItem fast) {
        this.usedChannels = 0;
        // If the shortest route to the controller is via side B, flip the sides.
        if (this.sideB == fast) {
            var tmp = sideA;
            sideA = sideB;
            sideB = tmp;
            fromAtoB = fromAtoB.getOpposite();
        }
    }

    @Override
    public int getMaxChannels() {
        var mode = sideB.getGrid().getPathingService().getChannelMode();
        if (mode == de.fellurion.ae2.core.config.Enums.ChannelMode.INFINITE) {
            return Integer.MAX_VALUE;
        }
        return 32 * mode.getCableCapacityFactor();
    }

    @Override
    public Iterable<IPathItem> getPossibleOptions() {
        return List.of(sideA, sideB);
    }

    @Override
    public boolean hasFlag(GridFlag flag) {
        return false;
    }

    int propagateChannelsUpwards() {
        if (sideB.getControllerRoute() == this) {
            this.usedChannels = sideB.usedChannels;
        } else {
            this.usedChannels = 0;
        }
        return this.usedChannels;
    }

    @Override
    public void finalizeChannels() {
        if (lastUsedChannels != usedChannels) {
            lastUsedChannels = usedChannels;
            if (sideA.getGrid() != null) {
                sideA.notifyStatusChange(NodeState.CHANNEL);
            }
            if (sideB.getGrid() != null) {
                sideB.notifyStatusChange(NodeState.CHANNEL);
            }
        }
    }

    @Override
    public String toString() {
        return "GridConnection[" + sideA + " -> " + sideB + "]";
    }
}
