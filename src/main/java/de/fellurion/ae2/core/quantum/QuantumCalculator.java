/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.quantum;

import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.MEWorld;

/**
 * Finds and forms the quantum bridge a block belongs to. Port of
 * {@code appeng.me.cluster.implementations.QuantumCalculator} and of its base class
 * {@code appeng.me.cluster.MBCalculator}.
 * <p>
 * The rules are AE2's: starting at a block, the area is grown along the bridge blocks next to it (first towards the
 * lower, then towards the higher coordinates, axis by axis). It forms a bridge if
 * <ol>
 * <li>it is 3x3x1 blocks in one of the three planes,</li>
 * <li>no other bridge block touches one of its six faces,</li>
 * <li>every position holds a bridge block, the fifth of the nine (the center) a link chamber and all others quantum
 * rings.</li>
 * </ol>
 * The first, third, seventh and ninth block are the corners of the ring; they are flagged as such
 * ({@link QuantumBridgeLogic#isCorner()}, AE2: the {@code corner} bit of the block entity's {@code constructed}
 * flags).
 * <p>
 * The world is read through {@link MEWorld#getNode(long)}: a bridge block is a node whose owner is a
 * {@link QuantumBridgeLogic}, so only loaded blocks count.
 * <p>
 * Deviation: AE2 guards against recursive rebuilds with {@code MBCalculator.modificationInProgress}, because forming
 * a cluster sends block updates that reach the calculator again. Here neighbours are told after the tick (see
 * {@link QuantumBridgeLogic}), so there is no recursion to guard against.
 */
final class QuantumCalculator {
    private final QuantumBridgeLogic target;

    QuantumCalculator(QuantumBridgeLogic target) {
        this.target = target;
    }

    /** The result of looking at the blocks around a position. */
    private static final class Scan {
        final int[] min = new int[3];
        final int[] max = new int[3];
        QuantumBridgeLogic.Formation formation;
    }

    /** Whether the blocks around {@code loc} make up a bridge, and if not, which rule fails. */
    static QuantumBridgeLogic.Formation check(MEWorld world, long loc) {
        return scan(world, loc).formation;
    }

    private static Scan scan(MEWorld world, long loc) {
        var s = new Scan();
        final int[] min = s.min;
        final int[] max = s.max;
        min[0] = max[0] = BlockPos.getX(loc);
        min[1] = max[1] = BlockPos.getY(loc);
        min[2] = max[2] = BlockPos.getZ(loc);

        // find size of MB structure...
        while (isValidBlockEntityAt(world, min[0] - 1, min[1], min[2])) {
            min[0]--;
        }
        while (isValidBlockEntityAt(world, min[0], min[1] - 1, min[2])) {
            min[1]--;
        }
        while (isValidBlockEntityAt(world, min[0], min[1], min[2] - 1)) {
            min[2]--;
        }
        while (isValidBlockEntityAt(world, max[0] + 1, max[1], max[2])) {
            max[0]++;
        }
        while (isValidBlockEntityAt(world, max[0], max[1] + 1, max[2])) {
            max[1]++;
        }
        while (isValidBlockEntityAt(world, max[0], max[1], max[2] + 1)) {
            max[2]++;
        }

        if (!checkMultiblockScale(min, max)) {
            s.formation = QuantumBridgeLogic.Formation.WRONG_SHAPE;
        } else if (!verifyUnownedRegion(world, min, max)) {
            s.formation = QuantumBridgeLogic.Formation.TOUCHED;
        } else if (!verifyInternalStructure(world, min, max)) {
            s.formation = QuantumBridgeLogic.Formation.WRONG_LAYOUT;
        } else {
            s.formation = QuantumBridgeLogic.Formation.FORMED;
        }
        return s;
    }

    /**
     * Forms, keeps or dissolves the bridge around the target block at {@code loc}. Port of
     * {@code MBCalculator.calculateMultiblock}.
     */
    void calculateMultiblock(MEWorld world, long loc) {
        var currentCluster = target.getCluster();
        if (currentCluster != null && currentCluster.isDestroyed()) {
            return; // If we're still part of a cluster that is in the process of being destroyed, don't recalc.
        }

        var s = scan(world, loc);
        if (s.formation == QuantumBridgeLogic.Formation.FORMED) {
            long min = BlockPos.asLong(s.min[0], s.min[1], s.min[2]);
            long max = BlockPos.asLong(s.max[0], s.max[1], s.max[2]);

            boolean updateGrid = false;
            var cluster = target.getCluster();
            if (cluster == null || cluster.getBoundsMin() != min || cluster.getBoundsMax() != max) {
                cluster = new QuantumCluster(min, max, target.getRegistry());
                updateBlockEntities(cluster, world, s.min, s.max);
                updateGrid = true;
            }

            cluster.updateStatus(updateGrid);
            return;
        }

        target.disconnect();
    }

    /** True for a 3x3x1 area in any of the three planes. */
    static boolean checkMultiblockScale(int[] min, int[] max) {
        if ((max[0] - min[0] + 1) * (max[1] - min[1] + 1) * (max[2] - min[2] + 1) == 9) {
            final int ones = (max[0] - min[0] == 0 ? 1 : 0) + (max[1] - min[1] == 0 ? 1 : 0)
                    + (max[2] - min[2] == 0 ? 1 : 0);

            final int threes = (max[0] - min[0] == 2 ? 1 : 0) + (max[1] - min[1] == 2 ? 1 : 0)
                    + (max[2] - min[2] == 2 ? 1 : 0);

            return ones == 1 && threes == 2;
        }
        return false;
    }

    /** True if no bridge block lies against one of the six faces of the area. */
    private static boolean verifyUnownedRegion(MEWorld world, int[] min, int[] max) {
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                int[] lo = min.clone();
                int[] hi = max.clone();
                lo[axis] = hi[axis] = side == 0 ? min[axis] - 1 : max[axis] + 1;
                for (int z = lo[2]; z <= hi[2]; z++) {
                    for (int y = lo[1]; y <= hi[1]; y++) {
                        for (int x = lo[0]; x <= hi[0]; x++) {
                            if (isValidBlockEntityAt(world, x, y, z)) {
                                return false;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    private static boolean verifyInternalStructure(MEWorld world, int[] min, int[] max) {
        byte num = 0;

        // Order of Minecraft's BlockPos.betweenClosed: x first, then y, then z
        for (int z = min[2]; z <= max[2]; z++) {
            for (int y = min[1]; y <= max[1]; y++) {
                for (int x = min[0]; x <= max[0]; x++) {
                    var te = QuantumBridgeLogic.at(world, BlockPos.asLong(x, y, z));

                    if (te == null || !te.isValid()) {
                        return false;
                    }

                    num++;
                    if (num == 5) {
                        if (!te.isCenter()) {
                            return false;
                        }
                    } else if (te.isCenter()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void updateBlockEntities(QuantumCluster c, MEWorld world, int[] min, int[] max) {
        // A block that still belongs to another bridge leaves it first
        for (int z = min[2]; z <= max[2]; z++) {
            for (int y = min[1]; y <= max[1]; y++) {
                for (int x = min[0]; x <= max[0]; x++) {
                    var old = QuantumBridgeLogic.at(world, BlockPos.asLong(x, y, z)).getCluster();
                    if (old != null) {
                        old.destroy();
                    }
                }
            }
        }

        byte num = 0;
        byte ringNum = 0;

        for (int z = min[2]; z <= max[2]; z++) {
            for (int y = min[1]; y <= max[1]; y++) {
                for (int x = min[0]; x <= max[0]; x++) {
                    final QuantumBridgeLogic te = QuantumBridgeLogic.at(world, BlockPos.asLong(x, y, z));

                    num++;
                    final byte flags;
                    if (num == 5) {
                        flags = num;
                        c.setCenter(te);
                    } else {
                        if (num == 1 || num == 3 || num == 7 || num == 9) {
                            flags = (byte) (QuantumBridgeLogic.CORNER | num);
                        } else {
                            flags = num;
                        }
                        c.getRing()[ringNum] = te;
                        ringNum++;
                    }

                    te.updateStatus(c, flags);
                }
            }
        }
    }

    private static boolean isValidBlockEntityAt(MEWorld world, int x, int y, int z) {
        return QuantumBridgeLogic.at(world, BlockPos.asLong(x, y, z)) != null;
    }
}
