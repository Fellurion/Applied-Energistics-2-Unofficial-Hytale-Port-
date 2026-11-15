/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

/**
 * Finds the pylon line a spatial pylon belongs to. Port of
 * {@code appeng.me.cluster.implementations.SpatialPylonCalculator} together with the generic search of
 * {@code appeng.me.cluster.MBCalculator#calculateMultiblock}.
 * <p>
 * Rules, exactly as in AE2:
 * <ul>
 * <li>starting at the pylon, the bounds grow over adjacent pylons along -X, -Y, -Z, then +X, +Y, +Z;</li>
 * <li>the result must be a line: it extends along exactly one axis ({@code checkMultiblockScale}), so a single pylon
 * forms nothing;</li>
 * <li>no other pylon may touch any of the six faces of the line ({@code verifyUnownedRegion}) - pylon lines that meet
 * in a corner or run side by side do not form.</li>
 * </ul>
 * Deviation: AE2 creates and destroys clusters incrementally on neighbour updates; here the cluster of a pylon is a
 * pure function of the pylon positions and is evaluated by the integration layer whenever those change.
 */
public final class SpatialPylonCalculator {

    /** The world as the calculator sees it: where are spatial pylons (in loaded chunks)? */
    @FunctionalInterface
    public interface PylonLookup {
        boolean isPylon(int x, int y, int z);
    }

    private SpatialPylonCalculator() {
    }

    /**
     * @return the pylon line that contains the pylon at {@code x,y,z}, or null if that pylon is not part of a valid
     *         line
     */
    public static SpatialPylonCluster calculate(PylonLookup level, int x, int y, int z) {
        int minX = x;
        int minY = y;
        int minZ = z;
        int maxX = x;
        int maxY = y;
        int maxZ = z;

        // find size of MB structure...
        while (level.isPylon(minX - 1, minY, minZ)) {
            minX--;
        }
        while (level.isPylon(minX, minY - 1, minZ)) {
            minY--;
        }
        while (level.isPylon(minX, minY, minZ - 1)) {
            minZ--;
        }
        while (level.isPylon(maxX + 1, maxY, maxZ)) {
            maxX++;
        }
        while (level.isPylon(maxX, maxY + 1, maxZ)) {
            maxY++;
        }
        while (level.isPylon(maxX, maxY, maxZ + 1)) {
            maxZ++;
        }

        if (checkMultiblockScale(minX, minY, minZ, maxX, maxY, maxZ)
                && verifyUnownedRegion(level, minX, minY, minZ, maxX, maxY, maxZ)
                && verifyInternalStructure(level, minX, minY, minZ, maxX, maxY, maxZ)) {
            return new SpatialPylonCluster(minX, minY, minZ, maxX, maxY, maxZ);
        }
        return null;
    }

    /** AE2: the structure is a line along exactly one axis. */
    static boolean checkMultiblockScale(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return minX == maxX && minY == maxY && minZ != maxZ
                || minX == maxX && minY != maxY && minZ == maxZ
                || minX != maxX && minY == maxY && minZ == maxZ;
    }

    /** AE2: every block inside the bounds is a pylon. */
    private static boolean verifyInternalStructure(PylonLookup level, int minX, int minY, int minZ, int maxX,
            int maxY, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!level.isPylon(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** AE2 {@code MBCalculator.verifyUnownedRegion}: no pylon on any of the six faces around the bounds. */
    private static boolean verifyUnownedRegion(PylonLookup level, int minX, int minY, int minZ, int maxX, int maxY,
            int maxZ) {
        return !anyPylon(level, minX - 1, minY, minZ, minX - 1, maxY, maxZ)
                && !anyPylon(level, maxX + 1, minY, minZ, maxX + 1, maxY, maxZ)
                && !anyPylon(level, minX, minY - 1, minZ, maxX, minY - 1, maxZ)
                && !anyPylon(level, minX, maxY + 1, minZ, maxX, maxY + 1, maxZ)
                && !anyPylon(level, minX, minY, minZ - 1, maxX, maxY, minZ - 1)
                && !anyPylon(level, minX, minY, maxZ + 1, maxX, maxY, maxZ + 1);
    }

    private static boolean anyPylon(PylonLookup level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (level.isPylon(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
