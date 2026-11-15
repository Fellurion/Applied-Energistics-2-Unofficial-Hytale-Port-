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
 * Two additions serve parts that are more than one node between neighbours:
 * <ul>
 * <li>{@link #addSecondaryNode}: a block may own a second node (AE2: the "outer" node of ME P2P tunnels, quartz fibers
 * and toggle buses) that exposes other sides than the main node and belongs to a grid of its own.</li>
 * <li>{@link #addVirtualConnection}: a connection between any two nodes, adjacent or not (AE2:
 * {@code GridHelper.createConnection(a, b)}). It carries up to 32 channels, survives {@link #refreshConnections},
 * merges the two grids and splits them again when it is removed.</li>
 * </ul>
 * <p>
 * Replaces AE2's {@code GridHelper}, {@code InWorldGridNode.findInWorldConnections}, {@code GridConnection.create},
 * {@code GridPropagator}, {@code GridSplitDetector} and {@code TickHandler}'s grid ticking. Deviation: AE2 keeps the
 * grid of the "pivot" side on a split; here the component containing a controller (else the largest) keeps it.
 * <p>
 * Threading: not thread-safe; call everything from the world's tick thread.
 */
public final class MEWorld {
    private final Map<Long, GridNode> nodes = new HashMap<>();
    /** Second node of a block (same position as its main node), see {@link #addSecondaryNode}. */
    private final Map<Long, GridNode> secondaryNodes = new HashMap<>();
    private final ArrayDeque<Runnable> endOfTickTasks = new ArrayDeque<>();
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
        attach(node);
        return node;
    }

    /**
     * Creates the second node of the block at {@code pos} (AE2: the "outer" node of {@code MEP2PTunnelPart},
     * {@code QuartzFiberPart} and {@code ToggleBusPart}). It connects to neighbours like any node, on the sides its
     * type exposes for {@code facing} - use a type that exposes other sides than the block's main node, e.g.
     * {@link NodeType.SideRule#FRONT_ONLY} next to a main node with {@code BACK_ONLY}. Where both would expose the
     * same side, neighbours connect to the main node.
     * <p>
     * The two nodes of a block are not connected to each other and normally end up in different grids; join them
     * with {@link #addVirtualConnection} if they should form one network (toggle bus).
     * <p>
     * Unlike a main node, a secondary node does not take services from its owner: it has exactly the services in
     * {@code services}. {@code owner} only identifies it ({@link GridNode#getOwner()}).
     * <p>
     * The node is removed by {@link #removeSecondaryNode} and, at the latest, together with the main node by
     * {@link #removeNode(long)}. {@link #updateNode} re-orients it together with the main node.
     *
     * @param services services by interface (may be null)
     * @throws IllegalStateException if the position already has a secondary node
     */
    public GridNode addSecondaryNode(long pos, NodeType type, Direction facing, AEColor color, Object owner,
            Map<Class<?>, Object> services) {
        if (secondaryNodes.containsKey(pos)) {
            throw new IllegalStateException("A secondary grid node already exists at " + BlockPos.toString(pos));
        }
        var node = new GridNode(this, pos, type, facing, color, owner, services, true);
        secondaryNodes.put(pos, node);
        attach(node);
        return node;
    }

    /** The secondary node of the block at {@code pos}, or null. */
    public GridNode getSecondaryNode(long pos) {
        return secondaryNodes.get(pos);
    }

    public Collection<GridNode> getSecondaryNodes() {
        return Collections.unmodifiableCollection(secondaryNodes.values());
    }

    /** Removes the secondary node at {@code pos}. No-op if there is none. */
    public GridNode removeSecondaryNode(long pos) {
        var node = secondaryNodes.remove(pos);
        if (node != null) {
            detach(node);
        }
        return node;
    }

    /** Gives a new node its own grid and connects it to its neighbours. */
    private void attach(GridNode node) {
        long pos = node.getPos();
        var grid = new Grid(this);
        grids.add(grid);
        grid.add(node, 0);

        var changed = new LinkedHashSet<GridNode>();
        changed.add(node);
        for (var dir : node.getExposedSides()) {
            if (exposedNode(pos, dir) != node) {
                continue; // the block's main node owns this side
            }
            var other = exposedNode(BlockPos.offset(pos, dir), dir.getOpposite());
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
    }

    /**
     * The node of the block at {@code pos} that is connectable on {@code side}: the main node if it exposes that
     * side, else the secondary node if it does (AE2: {@code GridHelper.getExposedNode}).
     */
    private GridNode exposedNode(long pos, Direction side) {
        var main = nodes.get(pos);
        if (main != null && main.isExposedOnSide(side)) {
            return main;
        }
        var secondary = secondaryNodes.get(pos);
        if (secondary != null && secondary.isExposedOnSide(side)) {
            return secondary;
        }
        return null;
    }

    /**
     * Removes the node at {@code pos} (block broken or chunk unloaded), and with it the block's secondary node. No-op
     * if there is none.
     */
    public GridNode removeNode(long pos) {
        removeSecondaryNode(pos);
        var node = nodes.remove(pos);
        if (node == null) {
            return null;
        }
        detach(node);
        return node;
    }

    /** Takes a node that was already unregistered out of its grid, dropping all its connections. */
    private void detach(GridNode node) {
        var neighbours = new ArrayList<GridNode>();
        for (var c : List.copyOf(node.connections)) {
            var other = c.getOtherSide(node);
            other.connections.remove(c);
            c.destroyed = true;
            if (!neighbours.contains(other)) {
                neighbours.add(other);
            }
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
            if (!n.removed) {
                n.notifyStatusChange(NodeState.CONNECTIONS);
            }
        }
    }

    public GridNode removeNode(int x, int y, int z) {
        return removeNode(BlockPos.asLong(x, y, z));
    }

    /**
     * Updates the facing and/or color of a node and re-evaluates its connections (e.g. after rotating a part or
     * coloring a cable). A secondary node of the same block is re-oriented with it.
     */
    public void updateNode(long pos, Direction facing, AEColor color) {
        var node = nodes.get(pos);
        if (node == null) {
            return;
        }
        var secondary = secondaryNodes.get(pos);
        node.setOrientation(facing, color);
        if (secondary != null) {
            // Both first: the main node must not grab the side the secondary node is about to give up
            secondary.setOrientation(facing, color);
            refreshConnections(secondary);
        }
        refreshConnections(node);
    }

    /**
     * Re-evaluates the in-world connections of {@code node} (main or secondary). Virtual connections are kept.
     */
    public void refreshConnections(GridNode node) {
        if (node.removed) {
            return;
        }
        var changed = new LinkedHashSet<GridNode>();
        var lost = new ArrayList<GridNode>();
        for (var c : List.copyOf(node.connections)) {
            if (!c.isInWorld()) {
                continue;
            }
            var dir = c.getDirection(node);
            var other = c.getOtherSide(node);
            if (!shouldConnect(node, dir, other) || exposedNode(node.getPos(), dir) != node
                    || exposedNode(other.getPos(), dir.getOpposite()) != other) {
                node.connections.remove(c);
                other.connections.remove(c);
                c.destroyed = true;
                lost.add(other);
                changed.add(other);
                changed.add(node);
            }
        }
        for (var dir : Direction.values()) {
            if (exposedNode(node.getPos(), dir) != node) {
                continue;
            }
            var other = exposedNode(BlockPos.offset(node.getPos(), dir), dir.getOpposite());
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

    /**
     * Replaces the sides {@code node} is connectable on, whatever its node type says, and re-evaluates its in-world
     * connections (AE2: {@code IManagedGridNode.setExposedOnSides}). For blocks whose connectable sides depend on
     * their state, e.g. the blocks of a quantum network bridge. {@code null} gives the node the sides of its type
     * again. Virtual connections are kept.
     * <p>
     * Do not call this from inside a machine tick ({@code IGridTickable}) or a node listener: grids merge and split.
     * Defer it with {@link #runAtEndOfTick}.
     */
    public void setExposedSides(GridNode node, Set<Direction> sides) {
        if (node.removed || node.getWorld() != this) {
            return;
        }
        var before = Set.copyOf(node.getExposedSides());
        node.setExposedSidesOverride(sides);
        if (!before.equals(node.getExposedSides())) {
            refreshConnections(node);
        }
    }

    private static boolean shouldConnect(GridNode a, Direction dirFromA, GridNode b) {
        return !a.removed && !b.removed
                && a.isExposedOnSide(dirFromA)
                && b.isExposedOnSide(dirFromA.getOpposite())
                && a.getGridColor().isCompatibleWith(b.getGridColor());
    }

    private GridConnection connect(GridNode a, GridNode b, Direction fromAtoB) {
        var connection = new GridConnection(a, b, fromAtoB);
        if (a.grid != b.grid) {
            merge(a.grid, b.grid);
        }
        a.connections.add(connection);
        b.connections.add(connection);
        a.grid.getPathingService().controllersChanged();
        return connection;
    }

    /**
     * Connects two nodes regardless of their position (AE2: {@code GridHelper.createConnection(a, b)}, a
     * {@code GridConnection} without direction). The connection behaves like a cable between them: both grids merge,
     * and it carries as many channels as its two end nodes allow, at most 32 - give both nodes
     * {@link GridFlag#DENSE_CAPACITY} for a dense link (ME P2P tunnel, quantum bridge).
     * <p>
     * The connection is kept by {@link #refreshConnections} / {@link #updateNode}, is not shown in
     * {@link GridNode#getConnectionMask()} and is not persisted: whoever created it creates it again after a reload.
     * It ends with {@link #removeVirtualConnection} or when one of its nodes is removed
     * ({@link GridConnection#isDestroyed()}).
     * <p>
     * Do not call this from inside a machine tick ({@code IGridTickable}) or a node listener: grids merge. Defer it
     * with {@link #runAtEndOfTick}.
     *
     * @return the new connection
     * @throws IllegalArgumentException if a node is removed, belongs to another world or both are the same node
     * @throws IllegalStateException    if the two nodes are already connected ({@link GridNode#hasConnection})
     */
    public GridConnection addVirtualConnection(GridNode a, GridNode b) {
        if (a == null || b == null || a == b) {
            throw new IllegalArgumentException("A virtual connection needs two different nodes");
        }
        if (a.removed || b.removed || a.getWorld() != this || b.getWorld() != this) {
            throw new IllegalArgumentException("Cannot connect " + a + " and " + b + ": not in this world");
        }
        if (a.hasConnection(b)) {
            throw new IllegalStateException("Connection between " + a + " and " + b + " already exists");
        }
        var connection = connect(a, b, null);
        a.notifyStatusChange(NodeState.CONNECTIONS);
        b.notifyStatusChange(NodeState.CONNECTIONS);
        return connection;
    }

    /**
     * Removes a connection created by {@link #addVirtualConnection} (AE2: {@code IGridConnection.destroy}); the grid
     * splits if nothing else joins the two sides. No-op if the connection is already destroyed.
     *
     * @throws IllegalArgumentException for an in-world connection (those follow the blocks)
     */
    public void removeVirtualConnection(GridConnection connection) {
        if (connection == null || connection.destroyed) {
            return;
        }
        if (connection.isInWorld()) {
            throw new IllegalArgumentException("Not a virtual connection: " + connection);
        }
        var a = connection.a();
        var b = connection.b();
        a.connections.remove(connection);
        b.connections.remove(connection);
        connection.destroyed = true;
        var grid = a.grid;
        grid.getPathingService().controllersChanged();
        checkSplit(grid, List.of(a, b));
        a.notifyStatusChange(NodeState.CONNECTIONS);
        b.notifyStatusChange(NodeState.CONNECTIONS);
    }

    /**
     * Runs {@code task} after all grids finished the current tick (or the next one, if no tick is running): the place
     * for topology changes requested by a ticking machine. Port of AE2's {@code TickHandler.addCallable}.
     */
    public void runAtEndOfTick(Runnable task) {
        endOfTickTasks.add(task);
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
     * drain, storage watchers, crafting CPUs), then the tasks of {@link #runAtEndOfTick}.
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
        // Tasks queued while they run wait for the next tick
        for (int i = endOfTickTasks.size(); i > 0; i--) {
            endOfTickTasks.poll().run();
        }
    }

    /** Convenience: ticks {@code n} times. */
    public void tick(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }
}
