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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * All ME grid nodes of one Hytale world and the grids they form. This is the main entry point of the integration
 * layer:
 * <ol>
 * <li>{@link #addNode} when a networked block is placed / loaded,</li>
 * <li>{@link #removeNode} when it is broken / unloaded,</li>
 * <li>{@link #updateNode} when its facing or color changes,</li>
 * <li>{@link #tick()} once per world tick.</li>
 * </ol>
 * Nodes connect to adjacent nodes when both expose the touching sides ({@link NodeType.SideRule}) and their colors
 * are compatible ({@link AEColor#isCompatibleWith}). Connected nodes always share one {@link Grid}; grids are merged
 * when a connection joins two grids and split when a removal disconnects them.
 * <p>
 * Replaces AE2's {@code GridHelper}, {@code InWorldGridNode.findInWorldConnections}, {@code GridConnection.create},
 * {@code GridPropagator}, {@code GridSplitDetector} and {@code TickHandler}'s grid ticking. Deviation: AE2 keeps the
 * grid of the "pivot" side on a split; here the component containing a controller (else the largest) keeps it.
 * <p>
 * Threading: not thread-safe; call everything from the world's tick thread.
 */
public final class MEWorld {
    private final Map<Long, GridNode> nodes = new HashMap<>();
    private final Set<Grid> grids = new LinkedHashSet<>();
    private final List<IGridNodeListener> listeners = new ArrayList<>();
    private final List<Function<Grid, IGridService>> serviceFactories = new ArrayList<>();
    private long currentTick;

    /**
     * Registers a factory for an additional per-grid service (applies to grids created afterwards, so register at
     * startup).
     */
    public void addServiceFactory(Function<Grid, IGridService> factory) {
        serviceFactories.add(factory);
    }

    List<Function<Grid, IGridService>> serviceFactories() {
        return serviceFactories;
    }

    /** Adds a world-wide listener for node status changes (e.g. to update block states / cable shapes). */
    public void addListener(IGridNodeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(IGridNodeListener listener) {
        listeners.remove(listener);
    }

    void fireStateChanged(GridNode node, NodeState state) {
        for (var l : listeners) {
            l.onStateChanged(node, state);
        }
    }

    public long getCurrentTick() {
        return currentTick;
    }

    public GridNode getNode(long pos) {
        return nodes.get(pos);
    }

    public GridNode getNode(int x, int y, int z) {
        return nodes.get(BlockPos.asLong(x, y, z));
    }

    public Collection<GridNode> getNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Collection<Grid> getGrids() {
        return Collections.unmodifiableSet(grids);
    }

    public GridNode addNode(long pos, NodeType type, Direction facing, AEColor color, Object owner) {
        return addNode(pos, type, facing, color, owner, null);
    }

    /**
     * Creates a node at {@code pos} and connects it to its neighbours.
     *
     * @param pos           packed position ({@link BlockPos#asLong})
     * @param type          node type, see {@link NodeType#forBlock}, {@link NodeType#forPart}, {@link NodeType#cable}
     * @param facing        the block's front (null for non-rotatable blocks: connects on all exposed sides)
     * @param color         cable/block color (null = transparent/fluix)
     * @param owner         logic object providing services (may be null for plain cables/controllers)
     * @param extraServices additional services by interface (may be null)
     * @return the new node
     * @throws IllegalStateException if a node already exists at that position
     */
    public GridNode addNode(long pos, NodeType type, Direction facing, AEColor color, Object owner,
            Map<Class<?>, Object> extraServices) {
        if (nodes.containsKey(pos)) {
            throw new IllegalStateException("A grid node already exists at " + BlockPos.toString(pos));
        }
        var node = new GridNode(this, pos, type, facing, color, owner, extraServices);
        nodes.put(pos, node);

        var grid = new Grid(this);
        grids.add(grid);
        grid.add(node, 0);

        var changed = new LinkedHashSet<GridNode>();
        changed.add(node);
        for (var dir : node.getExposedSides()) {
            var other = nodes.get(BlockPos.offset(pos, dir));
            if (other != null && shouldConnect(node, dir, other)) {
                connect(node, other, dir);
                changed.add(other);
            }
        }

        var listener = node.getService(IGridNodeListener.class);
        if (listener != null) {
            listener.onNodeAttached(node);
        }
        for (var l : listeners) {
            l.onNodeAttached(node);
        }
        for (var n : changed) {
            n.notifyStatusChange(NodeState.CONNECTIONS);
        }
        return node;
    }

    /** Removes the node at {@code pos} (block broken or chunk unloaded). No-op if there is none. */
    public GridNode removeNode(long pos) {
        var node = nodes.remove(pos);
        if (node == null) {
            return null;
        }
        var neighbours = new ArrayList<GridNode>();
        for (var c : List.copyOf(node.connections)) {
            var other = c.getOtherSide(node);
            other.connections.remove(c);
            neighbours.add(other);
        }
        node.connections.clear();

        var grid = node.grid;
        grid.remove(node);
        node.removed = true;
        if (grid.isEmpty()) {
            grids.remove(grid);
        } else {
            grid.getPathingService().repath();
            checkSplit(grid, neighbours);
        }

        var listener = node.getService(IGridNodeListener.class);
        if (listener != null) {
            listener.onNodeRemoved(node);
        }
        for (var l : listeners) {
            l.onNodeRemoved(node);
        }
        for (var n : neighbours) {
            n.notifyStatusChange(NodeState.CONNECTIONS);
        }
        return node;
    }

    public GridNode removeNode(int x, int y, int z) {
        return removeNode(BlockPos.asLong(x, y, z));
    }

    /**
     * Updates the facing and/or color of a node and re-evaluates its connections (e.g. after rotating a part or
     * coloring a cable).
     */
    public void updateNode(long pos, Direction facing, AEColor color) {
        var node = nodes.get(pos);
        if (node == null) {
            return;
        }
        node.setOrientation(facing, color);
        refreshConnections(node);
    }

    /** Re-evaluates the connections of the node at {@code pos}. */
    public void refreshConnections(GridNode node) {
        var changed = new LinkedHashSet<GridNode>();
        var lost = new ArrayList<GridNode>();
        for (var c : List.copyOf(node.connections)) {
            var dir = c.getDirection(node);
            var other = c.getOtherSide(node);
            if (!shouldConnect(node, dir, other)) {
                node.connections.remove(c);
                other.connections.remove(c);
                lost.add(other);
                changed.add(other);
                changed.add(node);
            }
        }
        for (var dir : Direction.values()) {
            var other = nodes.get(BlockPos.offset(node.getPos(), dir));
            if (other != null && !node.hasConnection(other) && shouldConnect(node, dir, other)) {
                connect(node, other, dir);
                changed.add(node);
                changed.add(other);
            }
        }
        if (!lost.isEmpty()) {
            var grid = node.grid;
            grid.getPathingService().controllersChanged();
            lost.add(node);
            checkSplit(grid, lost);
        }
        for (var n : changed) {
            n.notifyStatusChange(NodeState.CONNECTIONS);
        }
    }

    private static boolean shouldConnect(GridNode a, Direction dirFromA, GridNode b) {
        return !a.removed && !b.removed
                && a.isExposedOnSide(dirFromA)
                && b.isExposedOnSide(dirFromA.getOpposite())
                && a.getGridColor().isCompatibleWith(b.getGridColor());
    }

    private void connect(GridNode a, GridNode b, Direction fromAtoB) {
        var connection = new GridConnection(a, b, fromAtoB);
        if (a.grid != b.grid) {
            merge(a.grid, b.grid);
        }
        a.connections.add(connection);
        b.connections.add(connection);
        a.grid.getPathingService().controllersChanged();
    }

    private void merge(Grid gridA, Grid gridB) {
        Grid winner;
        Grid loser;
        if (gridA.mergePriority() != gridB.mergePriority()) {
            winner = gridA.mergePriority() > gridB.mergePriority() ? gridA : gridB;
        } else {
            winner = gridA.size() >= gridB.size() ? gridA : gridB;
        }
        loser = winner == gridA ? gridB : gridA;
        moveNodes(List.copyOf(loser.getNodes()), loser, winner);
        grids.remove(loser);
    }

    private void moveNodes(List<GridNode> toMove, Grid from, Grid to) {
        var wasPowered = new HashMap<GridNode, Boolean>();
        for (var n : toMove) {
            wasPowered.put(n, n.isPowered());
            double share = from.remove(n);
            to.add(n, share);
        }
        for (var n : toMove) {
            var listener = n.getService(IGridNodeListener.class);
            if (listener != null) {
                listener.onGridChanged(n);
            }
            for (var l : listeners) {
                l.onGridChanged(n);
            }
            if (wasPowered.get(n) != n.isPowered()) {
                n.notifyStatusChange(NodeState.POWER);
            }
        }
    }

    /** Splits {@code grid} if the given seed nodes are no longer connected to each other. */
    private void checkSplit(Grid grid, List<GridNode> seeds) {
        var visited = new HashSet<GridNode>();
        var components = new ArrayList<List<GridNode>>();
        for (var seed : seeds) {
            if (seed.removed || seed.grid != grid || visited.contains(seed)) {
                continue;
            }
            var component = new ArrayList<GridNode>();
            var queue = new ArrayDeque<GridNode>();
            queue.add(seed);
            visited.add(seed);
            while (!queue.isEmpty()) {
                var n = queue.poll();
                component.add(n);
                for (var c : n.connections) {
                    var o = c.getOtherSide(n);
                    if (visited.add(o)) {
                        queue.add(o);
                    }
                }
            }
            components.add(component);
        }
        if (components.size() <= 1) {
            return;
        }
        // Keep the grid for the component with a controller, otherwise the largest
        List<GridNode> keep = null;
        for (var comp : components) {
            boolean hasController = comp.stream().anyMatch(GridNode::isController);
            if (keep == null) {
                keep = comp;
            } else {
                boolean keepHasController = keep.stream().anyMatch(GridNode::isController);
                if (hasController && !keepHasController
                        || hasController == keepHasController && comp.size() > keep.size()) {
                    keep = comp;
                }
            }
        }
        for (var comp : components) {
            if (comp == keep) {
                continue;
            }
            var newGrid = new Grid(this);
            grids.add(newGrid);
            moveNodes(comp, grid, newGrid);
        }
        grid.getPathingService().repath();
    }

    /**
     * Ticks all grids. Order per tick (as AE2's {@code TickHandler}): start-of-tick for all grids (tick counter,
     * passive generators), then the adaptive machine ticks of all grids, then end-of-tick services (channels, energy
     * drain, storage watchers, crafting CPUs).
     */
    public void tick() {
        currentTick++;
        var snapshot = List.copyOf(grids);
        for (var g : snapshot) {
            if (grids.contains(g)) {
                g.onServerStartTick();
            }
        }
        for (var g : snapshot) {
            if (grids.contains(g)) {
                g.tickMachines();
            }
        }
        for (var g : snapshot) {
            if (grids.contains(g)) {
                g.onServerEndTick();
            }
        }
    }

    /** Convenience: ticks {@code n} times. */
    public void tick(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }
}
