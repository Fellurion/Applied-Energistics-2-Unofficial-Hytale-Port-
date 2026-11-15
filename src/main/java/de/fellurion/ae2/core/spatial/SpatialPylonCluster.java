/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

/**
 * A formed line of spatial pylons. Port of {@code appeng.me.cluster.implementations.SpatialPylonCluster}.
 * <p>
 * A cluster is a straight line of at least two pylons (see {@link SpatialPylonCalculator}). Its bounds are the first
 * and last pylon of the line, its {@linkplain #axis() axis} the direction the line runs in.
 * <p>
 * Deviations: AE2's cluster is a mutable object holding its block entities and a "valid" flag set by the
 * {@link SpatialPylonService}; here it is an immutable value (two clusters with the same bounds are equal) and the
 * validity is asked from the service of the grid. The number of pylons ({@link #size()}) follows from the bounds.
 */
public final class SpatialPylonCluster {

    /** Direction of a pylon line. Port of {@code SpatialPylonCluster.Axis}. */
    public enum Axis {
        X, Y, Z, UNFORMED
    }

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final Axis axis;

    public SpatialPylonCluster(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        if (minX != maxX) {
            this.axis = Axis.X;
        } else if (minY != maxY) {
            this.axis = Axis.Y;
        } else if (minZ != maxZ) {
            this.axis = Axis.Z;
        } else {
            this.axis = Axis.UNFORMED;
        }
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    /** AE2: {@code getCurrentAxis()}. */
    public Axis axis() {
        return axis;
    }

    /** Number of pylons in the line (AE2: {@code getLine().size()}). */
    public int size() {
        return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** True for the pylon at the lower end of the line. */
    public boolean isStart(int x, int y, int z) {
        return x == minX && y == minY && z == minZ;
    }

    /** True for the pylon at the upper end of the line. */
    public boolean isEnd(int x, int y, int z) {
        return x == maxX && y == maxY && z == maxZ;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SpatialPylonCluster c && c.minX == minX && c.minY == minY && c.minZ == minZ
                && c.maxX == maxX && c.maxY == maxY && c.maxZ == maxZ;
    }

    @Override
    public int hashCode() {
        int h = minX;
        h = 31 * h + minY;
        h = 31 * h + minZ;
        h = 31 * h + maxX;
        h = 31 * h + maxY;
        h = 31 * h + maxZ;
        return h;
    }

    @Override
    public String toString() {
        return "SpatialPylonCluster[" + minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ + "]";
    }
}
