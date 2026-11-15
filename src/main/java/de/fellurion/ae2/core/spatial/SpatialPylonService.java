/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

import de.fellurion.ae2.core.config.AEConfig;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The spatial region of one ME network. Port of {@code appeng.me.service.SpatialPylonService}
 * ({@code appeng.api.networking.spatial.ISpatialService}).
 * <p>
 * The region is spanned by all formed pylon lines of the network whose pylons have a channel:
 * <ul>
 * <li>{@link #getMinX()}.. / {@link #getMaxX()}.. are the corners of the bounding box of those lines. The blocks that
 * are transferred are the ones strictly inside ({@code min + 1 .. max - 1}), see {@link #innerMinX()} and
 * {@link #sizeX()};</li>
 * <li>the region is valid if the box is more than one block thick on every axis and every pylon line lies on an edge
 * of the box (AE2's edge conditions per axis, ported literally);</li>
 * <li>efficiency = pylons / max(6, (X*Z + X*Y + Y*Z) * 3 / 8), capped at 100 %, with X, Y, Z the box extents;</li>
 * <li>required power = (X*Y*Z * spatialPowerMultiplier) ^ (1 + (spatialPowerExponent - 1) * (1 - efficiency)).</li>
 * </ul>
 * Deviations: AE2 registers this as a grid service that recomputes on every {@code GridBootingStatusChange}; here the
 * integration layer calls {@link #reset(Collection)} with the clusters of the network whenever the network finished
 * (re)booting or its pylons changed. AE2 keeps the last efficiency when the region disappears; here it is 0 then.
 * All pylons of a network are in the same world, so AE2's level check is not needed.
 */
public final class SpatialPylonService {
    private long powerRequired = 0;
    private double efficiency = 0.0;
    private boolean hasRegion = false;
    private boolean isValid = false;
    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;
    private int pylonBlocks;
    private Set<SpatialPylonCluster> clusters = Set.of();

    /**
     * Recomputes the region.
     *
     * @param formedClusters the clusters of all pylons of the network that meet their channel requirement (a cluster
     *                       may be passed several times, once per pylon)
     */
    public void reset(Collection<SpatialPylonCluster> formedClusters) {
        this.clusters = new LinkedHashSet<>(formedClusters);
        this.clusters.remove(null);

        this.isValid = true;
        this.hasRegion = false;
        int pylons = 0;

        for (SpatialPylonCluster cl : this.clusters) {
            // Expand the bounding box
            if (!this.hasRegion) {
                this.hasRegion = true;
                this.minX = cl.minX();
                this.minY = cl.minY();
                this.minZ = cl.minZ();
                this.maxX = cl.maxX();
                this.maxY = cl.maxY();
                this.maxZ = cl.maxZ();
            } else {
                this.maxX = Math.max(this.maxX, cl.maxX());
                this.maxY = Math.max(this.maxY, cl.maxY());
                this.maxZ = Math.max(this.maxZ, cl.maxZ());
                this.minX = Math.min(this.minX, cl.minX());
                this.minY = Math.min(this.minY, cl.minY());
                this.minZ = Math.min(this.minZ, cl.minZ());
            }
            pylons += cl.size();
        }
        this.pylonBlocks = pylons;

        double minPower = 0;
        if (this.hasRegion) {
            this.isValid = this.maxX - this.minX > 1 && this.maxY - this.minY > 1 && this.maxZ - this.minZ > 1;

            for (SpatialPylonCluster cl : this.clusters) {
                switch (cl.axis()) {
                    case X -> this.isValid = this.isValid
                            && (this.maxY == cl.minY() || this.minY == cl.maxY()
                                    || this.maxZ == cl.minZ() || this.minZ == cl.maxZ())
                            && (this.maxY == cl.maxY() || this.minY == cl.minY()
                                    || this.maxZ == cl.maxZ() || this.minZ == cl.minZ());
                    case Y -> this.isValid = this.isValid
                            && (this.maxX == cl.minX() || this.minX == cl.maxX()
                                    || this.maxZ == cl.minZ() || this.minZ == cl.maxZ())
                            && (this.maxX == cl.maxX() || this.minX == cl.minX()
                                    || this.maxZ == cl.maxZ() || this.minZ == cl.minZ());
                    case Z -> this.isValid = this.isValid
                            && (this.maxY == cl.minY() || this.minY == cl.maxY()
                                    || this.maxX == cl.minX() || this.minX == cl.maxX())
                            && (this.maxY == cl.maxY() || this.minY == cl.minY()
                                    || this.maxX == cl.maxX() || this.minX == cl.minX());
                    case UNFORMED -> this.isValid = false;
                }
            }

            final int reqX = this.maxX - this.minX;
            final int reqY = this.maxY - this.minY;
            final int reqZ = this.maxZ - this.minZ;
            final int requirePylonBlocks = Math.max(6, (reqX * reqZ + reqX * reqY + reqY * reqZ) * 3 / 8);

            this.efficiency = (double) pylons / (double) requirePylonBlocks;
            if (this.efficiency > 1.0) {
                this.efficiency = 1.0;
            }
            if (this.efficiency < 0.0) {
                this.efficiency = 0.0;
            }

            minPower = (double) reqX * (double) reqY * reqZ * AEConfig.get().spatialPowerMultiplier();
        } else {
            this.efficiency = 0.0;
        }

        this.powerRequired = (long) Math.pow(minPower,
                1 + (AEConfig.get().spatialPowerExponent() - 1) * (1 - this.efficiency));
    }

    /** True if the network has at least one formed pylon line with a channel. */
    public boolean hasRegion() {
        return this.hasRegion;
    }

    /** True if the region obeys all rules. */
    public boolean isValidRegion() {
        return this.hasRegion && this.isValid;
    }

    /** True if the cluster belongs to this network's region and the region is valid (AE2: {@code cluster.isValid()}). */
    public boolean isValid(SpatialPylonCluster cluster) {
        return isValidRegion() && cluster != null && this.clusters.contains(cluster);
    }

    /** Minimum corner of the pylon bounding box (AE2: {@code getMin()}). Only meaningful with a region. */
    public int getMinX() {
        return this.minX;
    }

    public int getMinY() {
        return this.minY;
    }

    public int getMinZ() {
        return this.minZ;
    }

    /** Maximum corner of the pylon bounding box (AE2: {@code getMax()}). Only meaningful with a region. */
    public int getMaxX() {
        return this.maxX;
    }

    public int getMaxY() {
        return this.maxY;
    }

    public int getMaxZ() {
        return this.maxZ;
    }

    /** First transferred block (AE2 {@code SpatialStorageCellItem.doSpatialTransition}: {@code min + 1}). */
    public int innerMinX() {
        return this.minX + 1;
    }

    public int innerMinY() {
        return this.minY + 1;
    }

    public int innerMinZ() {
        return this.minZ + 1;
    }

    /** Extent of the transferred region in blocks (AE2: {@code max - min - 1}); 0 or less without a usable region. */
    public int sizeX() {
        return this.hasRegion ? this.maxX - this.minX - 1 : 0;
    }

    public int sizeY() {
        return this.hasRegion ? this.maxY - this.minY - 1 : 0;
    }

    public int sizeZ() {
        return this.hasRegion ? this.maxZ - this.minZ - 1 : 0;
    }

    /** Number of pylons in the formed lines of this network. */
    public int pylonCount() {
        return this.pylonBlocks;
    }

    /** Number of formed pylon lines of this network. */
    public int clusterCount() {
        return this.clusters.size();
    }

    /** AE units needed for one transfer. */
    public long requiredPower() {
        return this.powerRequired;
    }

    /** Efficiency in percent, 0..100. */
    public float currentEfficiency() {
        return (float) this.efficiency * 100;
    }
}
