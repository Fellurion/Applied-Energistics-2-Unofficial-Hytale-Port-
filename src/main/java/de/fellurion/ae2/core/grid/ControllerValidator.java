/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates the controller multiblock. Port of {@code appeng.me.pathfinding.ControllerValidator}.
 * <p>
 * Conditions for a valid controller structure: (1) its bounding box must not exceed {@link #MAX_SIZE} (7) blocks on
 * any axis, (2) all controllers of the grid must be directly connected to each other (reachable from the first
 * controller via controller-to-controller connections), (3) no controller may have controller neighbours on two or
 * three axes ("cross").
 */
public final class ControllerValidator {
    /** Maximum size of controller structure on each axis. */
    public static final int MAX_SIZE = 7;

    private ControllerValidator() {
    }

    public static ControllerState calculateState(Collection<GridNode> controllers) {
        if (controllers.isEmpty()) {
            return ControllerState.NO_CONTROLLER;
        }

        var start = controllers.iterator().next();
        int minX = BlockPos.getX(start.getPos()), maxX = minX;
        int minY = BlockPos.getY(start.getPos()), maxY = minY;
        int minZ = BlockPos.getZ(start.getPos()), maxZ = minZ;
        int found = 0;
        boolean valid = true;

        // Visit only controller nodes connected to the starting controller (AE2: visitNode returns true only for
        // controllers so the visitor never leaves the controller structure).
        var visited = new HashSet<GridNode>();
        var queue = new ArrayDeque<GridNode>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty() && valid) {
            var node = queue.poll();
            long p = node.getPos();
            minX = Math.min(BlockPos.getX(p), minX);
            maxX = Math.max(BlockPos.getX(p), maxX);
            minY = Math.min(BlockPos.getY(p), minY);
            maxY = Math.max(BlockPos.getY(p), maxY);
            minZ = Math.min(BlockPos.getZ(p), minZ);
            maxZ = Math.max(BlockPos.getZ(p), maxZ);
            if (maxX - minX < MAX_SIZE && maxY - minY < MAX_SIZE && maxZ - minZ < MAX_SIZE) {
                found++;
                for (var c : node.connections) {
                    var other = c.getOtherSide(node);
                    if (other.isController() && visited.add(other)) {
                        queue.add(other);
                    }
                }
            } else {
                valid = false;
            }
        }

        if (!valid) {
            return ControllerState.CONTROLLER_CONFLICT;
        }
        if (found != controllers.size()) {
            return ControllerState.CONTROLLER_CONFLICT;
        }
        if (hasControllerCross(controllers)) {
            return ControllerState.CONTROLLER_CONFLICT;
        }
        return ControllerState.CONTROLLER_ONLINE;
    }

    /** Return true if controllers have a cross pattern, i.e. two neighbors on two or three axes. */
    static boolean hasControllerCross(Collection<GridNode> controllers) {
        Set<Long> posSet = new HashSet<>(controllers.size());
        for (var c : controllers) {
            posSet.add(c.getPos());
        }
        for (long pos : posSet) {
            boolean northSouth = posSet.contains(BlockPos.offset(pos, Direction.NORTH))
                    && posSet.contains(BlockPos.offset(pos, Direction.SOUTH));
            boolean eastWest = posSet.contains(BlockPos.offset(pos, Direction.EAST))
                    && posSet.contains(BlockPos.offset(pos, Direction.WEST));
            boolean upDown = posSet.contains(BlockPos.offset(pos, Direction.UP))
                    && posSet.contains(BlockPos.offset(pos, Direction.DOWN));
            if ((northSouth ? 1 : 0) + (eastWest ? 1 : 0) + (upDown ? 1 : 0) > 1) {
                return true;
            }
        }
        return false;
    }
}
