/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Assigns channels starting from the controllers. Port of {@code appeng.me.pathfinding.PathingCalculation}.
 * <p>
 * First, a BFS is performed starting from the controllers. This establishes a tree that connects all path items to a
 * controller. As nodes that require channels are visited, they are assigned a channel if possible, by checking the
 * channel count of a few key nodes (the "highest similar ancestors") along the path. Dense cables are visited first,
 * then normal cables, then other devices. Second, a DFS is performed to propagate the channel counts upwards.
 */
final class PathingCalculation {
    private final Grid grid;
    private final Set<GridNode> multiblocksWithChannel = new HashSet<>();
    @SuppressWarnings("unchecked")
    private final Queue<IPathItem>[] queues = new Queue[] {
            new ArrayDeque<>(), // 0: dense cable queue
            new ArrayDeque<>(), // 1: normal cable queue
            new ArrayDeque<>() // 2: non-cable queue
    };
    private final Set<IPathItem> visited = new HashSet<>();
    private final Map<GridNode, Integer> channelBottlenecks = new HashMap<>();
    private final Set<GridNode> channelNodes = new HashSet<>();
    private int channelsInUse = 0;
    private int channelsByBlocks = 0;

    PathingCalculation(Grid grid) {
        this.grid = grid;
        // Add every outgoing connection of the controllers (that doesn't point to another controller) to the list.
        for (var node : grid.getControllers()) {
            visited.add(node);
            for (var gc : node.getConnections()) {
                if (!gc.getOtherSide(node).isController()) {
                    enqueue(gc, 0);
                    gc.setControllerRoute(node);
                }
            }
        }
    }

    private void enqueue(IPathItem pathItem, int queueIndex) {
        visited.add(pathItem);
        int possibleIndex;
        if (pathItem instanceof GridConnection) {
            possibleIndex = 0; // Grid connection does not have flags, allow any queue.
        } else if (pathItem.hasFlag(GridFlag.DENSE_CAPACITY)) {
            possibleIndex = 0;
        } else if (pathItem.hasFlag(GridFlag.PREFERRED)) {
            possibleIndex = 1;
        } else {
            possibleIndex = 2;
        }
        queues[Math.max(possibleIndex, queueIndex)].add(pathItem);
    }

    void compute() {
        for (int i = 0; i < 3; ++i) {
            processQueue(queues[i], i);
        }
        propagateAssignments();
    }

    private void processQueue(Queue<IPathItem> oldOpen, int queueIndex) {
        while (!oldOpen.isEmpty()) {
            IPathItem i = oldOpen.poll();
            for (IPathItem pi : i.getPossibleOptions()) {
                if (!this.visited.contains(pi)) {
                    pi.setControllerRoute(i);
                    if (pi.hasFlag(GridFlag.REQUIRE_CHANNEL) && !this.multiblocksWithChannel.contains(pi)) {
                        boolean worked = tryUseChannel((GridNode) pi);
                        if (worked && pi.hasFlag(GridFlag.MULTIBLOCK)) {
                            var multiblock = ((GridNode) pi).getService(IGridMultiblock.class);
                            if (multiblock != null) {
                                var it = multiblock.getMultiblockNodes();
                                while (it.hasNext()) {
                                    var other = it.next();
                                    if (other != null && other != pi) {
                                        this.multiblocksWithChannel.add(other);
                                    }
                                }
                            }
                        }
                    }
                    enqueue(pi, queueIndex);
                }
            }
        }
    }

    /** Try to allocate a channel along the path from {@code start} to the controller. */
    private boolean tryUseChannel(GridNode start) {
        if (start.hasFlag(GridFlag.COMPRESSED_CHANNEL) && !start.getSubtreeAllowsCompressedChannels()) {
            return false;
        }
        GridNode pi = start;
        while (pi != null) {
            if (channelBottlenecks.getOrDefault(pi, 0) >= pi.getMaxChannels()) {
                return false;
            }
            pi = pi.getHighestSimilarAncestor();
        }
        pi = start;
        while (pi != null) {
            channelBottlenecks.merge(pi, 1, Integer::sum);
            pi = pi.getHighestSimilarAncestor();
        }
        channelNodes.add(start);
        return true;
    }

    private static final Object SUBTREE_END = new Object();

    /** Propagates assignment to all nodes by performing an iterative DFS. */
    private void propagateAssignments() {
        List<Object> stack = new ArrayList<>();
        Set<IPathItem> controllerNodes = new HashSet<>();

        for (var node : grid.getControllers()) {
            controllerNodes.add(node);
            for (var gc : node.getConnections()) {
                if (!gc.getOtherSide(node).isController()) {
                    stack.add(gc);
                }
            }
        }

        while (!stack.isEmpty()) {
            Object current = stack.getLast();
            if (current == SUBTREE_END) {
                stack.removeLast();
                IPathItem item = (IPathItem) stack.removeLast();
                if (item instanceof GridNode node) {
                    boolean hasChannel = channelNodes.contains(item);
                    channelsByBlocks += node.propagateChannelsUpwards(hasChannel);
                    if (hasChannel) {
                        channelsInUse++;
                    }
                } else {
                    channelsByBlocks += ((GridConnection) item).propagateChannelsUpwards();
                }
            } else {
                stack.add(SUBTREE_END);
                for (var pi : ((IPathItem) current).getPossibleOptions()) {
                    if (!controllerNodes.contains(pi) && pi.getControllerRoute() == current) {
                        stack.add(pi);
                    }
                }
            }
        }

        // Give a channel to all nodes that are a part of a multiblock that was given a channel before.
        for (var multiblockNode : multiblocksWithChannel) {
            multiblockNode.incrementChannelCount(1);
        }
    }

    int getChannelsInUse() {
        return channelsInUse;
    }

    int getChannelsByBlocks() {
        return channelsByBlocks;
    }
}
