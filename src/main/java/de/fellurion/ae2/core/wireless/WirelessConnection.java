/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.wireless;

import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.MEWorld;

/**
 * Which access point of a grid a wireless terminal at a given place talks to. Port of
 * {@code WirelessTerminalMenuHost.updateConnectedAccessPoint} / {@code getAccessPointSignal}
 * ({@code appeng.helpers.WirelessTerminalMenuHost}).
 * <p>
 * An access point is usable when it is active, in the same world and the squared distance between its block position
 * and the holder is smaller than its squared range. The closest usable access point wins (it gives the lowest energy
 * drain); the remaining range is the largest one among all usable access points.
 *
 * @param accessPoint    the closest usable access point, or null if none is in range
 * @param distance       distance to it in blocks
 * @param remainingRange how far the holder is from losing the signal, in blocks
 */
public record WirelessConnection(IWirelessAccessPoint accessPoint, double distance, double remainingRange) {

    /** Signal of one access point; squared values as in AE2. */
    public record Signal(double distanceSquared, double remainingRangeSquared) {
        /** The access point cannot be used. */
        public static final Signal NONE = new Signal(Double.MAX_VALUE, Double.MIN_VALUE);

        public boolean usable() {
            return distanceSquared < Double.MAX_VALUE;
        }
    }

    /** True if an access point is in range. */
    public boolean connected() {
        return accessPoint != null;
    }

    /**
     * @return the signal of the access point for a holder at x/y/z in {@code world}, or {@link Signal#NONE} if it
     *         cannot be used (inactive, other world, out of range)
     */
    public static Signal signal(IWirelessAccessPoint wap, MEWorld world, double x, double y, double z) {
        double rangeLimit = wap.getRange();
        rangeLimit *= rangeLimit;

        if (world != null && wap.getWorld() == world) {
            long pos = wap.getPos();
            double offX = BlockPos.getX(pos) - x;
            double offY = BlockPos.getY(pos) - y;
            double offZ = BlockPos.getZ(pos) - z;

            double r = offX * offX + offY * offY + offZ * offZ;
            if (r < rangeLimit && wap.isActive()) {
                return new Signal(r, rangeLimit - r);
            }
        }
        return Signal.NONE;
    }

    /** Finds the connection among all access points of {@code grid}; a null grid gives no connection. */
    public static WirelessConnection find(Grid grid, MEWorld world, double x, double y, double z) {
        return find(grid == null ? null : grid.getMachines(IWirelessAccessPoint.class), world, x, y, z);
    }

    /** Finds the connection among the given access points. */
    public static WirelessConnection find(Iterable<? extends IWirelessAccessPoint> accessPoints, MEWorld world,
            double x, double y, double z) {
        IWirelessAccessPoint bestWap = null;
        double bestSqDistance = Double.MAX_VALUE;
        double bestSqRemainingRange = Double.MIN_VALUE;

        if (accessPoints != null) {
            for (var wap : accessPoints) {
                var signal = signal(wap, world, x, y, z);

                // an unusable access point reports MAX_VALUE and fails this check
                if (signal.distanceSquared() < bestSqDistance) {
                    bestSqDistance = signal.distanceSquared();
                    bestWap = wap;
                }
                // there may be access points with larger range that are farther away,
                // but those would have larger energy consumption
                if (signal.remainingRangeSquared() > bestSqRemainingRange) {
                    bestSqRemainingRange = signal.remainingRangeSquared();
                }
            }
        }
        return new WirelessConnection(bestWap, Math.sqrt(bestSqDistance), Math.sqrt(bestSqRemainingRange));
    }
}
