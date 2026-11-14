/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

import de.fellurion.ae2.core.config.Enums.ChannelMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A node of the ME network: one networked block in the world.
 * <p>
 * Port of {@code appeng.me.GridNode} / {@code InWorldGridNode} (+ the relevant parts of {@code IGridNode}). Nodes are
 * created and destroyed through {@link MEWorld#addNode} / {@link MEWorld#removeNode}; never construct them directly.
 * <p>
 * Services: the node's {@linkplain #getOwner() owner} object provides services simply by implementing the service
 * interfaces ({@code IStorageProvider}, {@code IGridTickable}, {@code IAEPowerStorage},
 * {@code IPassiveEnergyGenerator}, {@code IStorageWatcherNode}, {@code ICraftingProvider}, {@link IGridMultiblock},
 * {@link IGridNodeListener}, ...). Additional service objects can be registered with {@link #addService} before the
 * node is added to the world (AE2: {@code IManagedGridNode.addService}).
 */
public final class GridNode implements IPathItem {
    private final MEWorld world;
    private final long pos;
    private final NodeType type;
    private final Object owner;
    private final Map<Class<?>, Object> extraServices;
    private Direction facing;
    private AEColor color;
    private Set<Direction> exposedSides;
    private double idlePowerUsage;
    double previousDraw;
    Grid grid;
    boolean removed;
    final List<GridConnection> connections = new ArrayList<>();

    // --- pathing state (package private, used by PathingCalculation) ---
    int usedChannels = 0;
    private int lastUsedChannels = 0;
    private GridNode highestSimilarAncestor = null;
    private int subtreeMaxChannels;
    private boolean subtreeAllowsCompressedChannels;

    GridNode(MEWorld world, long pos, NodeType type, Direction facing, AEColor color, Object owner,
            Map<Class<?>, Object> extraServices) {
        this.world = world;
        this.pos = pos;
        this.type = type;
        this.facing = facing;
        this.color = color == null ? AEColor.TRANSPARENT : color;
        this.owner = owner;
        this.extraServices = extraServices == null ? Map.of() : new HashMap<>(extraServices);
        this.idlePowerUsage = type.idlePower();
        this.exposedSides = type.exposedSides(facing);
    }

    public MEWorld getWorld() {
        return world;
    }

    /** Packed position, see {@link BlockPos}. */
    public long getPos() {
        return pos;
    }

    public NodeType getType() {
        return type;
    }

    public Direction getFacing() {
        return facing;
    }

    public AEColor getGridColor() {
        return color;
    }

    /** The integration/logic object that owns this node and provides its services. */
    public Object getOwner() {
        return owner;
    }

    /** The grid this node belongs to; null after removal. */
    public Grid getGrid() {
        return grid;
    }

    public boolean isRemoved() {
        return removed;
    }

    void setOrientation(Direction facing, AEColor color) {
        this.facing = facing;
        this.color = color == null ? AEColor.TRANSPARENT : color;
        this.exposedSides = type.exposedSides(facing);
    }

    public boolean isExposedOnSide(Direction side) {
        return exposedSides.contains(side);
    }

    public Set<Direction> getExposedSides() {
        return Collections.unmodifiableSet(exposedSides);
    }

    /**
     * Returns a service of this node: the owner if it implements {@code type}, else an extra service.
     */
    public <T> T getService(Class<T> serviceType) {
        if (serviceType.isInstance(owner)) {
            return serviceType.cast(owner);
        }
        var s = extraServices.get(serviceType);
        return s == null ? null : serviceType.cast(s);
    }

    public boolean hasFlag(GridFlag flag) {
        return type.hasFlag(flag);
    }

    public boolean isController() {
        return type.controller();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Status

    /** True if the network is powered. */
    public boolean isPowered() {
        return grid != null && grid.getEnergyService().isNetworkPowered();
    }

    /** True if the grid exists and is not booting (channel assignment done). */
    public boolean hasGridBooted() {
        return grid != null && !grid.getPathingService().isNetworkBooting();
    }

    /** True if the node needs no channel or has one. */
    public boolean meetsChannelRequirements() {
        return !hasFlag(GridFlag.REQUIRE_CHANNEL) || getUsedChannels() > 0;
    }

    /**
     * Powered, booted and has its channel: use for network I/O. Port of {@code IGridNode.isActive}.
     */
    public boolean isActive() {
        return isPowered() && hasGridBooted() && meetsChannelRequirements();
    }

    /**
     * Powered and has its channel: use for visuals (block state {@code Online}). Port of {@code IGridNode.isOnline}.
     */
    public boolean isOnline() {
        return isPowered() && meetsChannelRequirements();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Power

    public double getIdlePowerUsage() {
        return idlePowerUsage;
    }

    /** Changes the idle power usage (AE/t), e.g. a drive when cells are inserted. */
    public void setIdlePowerUsage(double usagePerTick) {
        this.idlePowerUsage = usagePerTick;
        if (grid != null) {
            grid.getEnergyService().nodeIdlePowerChanged(this);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Connections / channels

    public List<GridConnection> getConnections() {
        return List.copyOf(connections);
    }

    public boolean hasConnection(GridNode other) {
        for (var c : connections) {
            if (c.a() == other || c.b() == other) {
                return true;
            }
        }
        return false;
    }

    public GridConnection getConnection(Direction side) {
        for (var c : connections) {
            if (c.getDirection(this) == side) {
                return c;
            }
        }
        return null;
    }

    public Set<Direction> getConnectedSides() {
        var result = EnumSet.noneOf(Direction.class);
        for (var c : connections) {
            result.add(c.getDirection(this));
        }
        return result;
    }

    /** 6-bit cable shape mask (bit0 = +X ... bit5 = −Z). */
    public int getConnectionMask() {
        int m = 0;
        for (var c : connections) {
            m |= c.getDirection(this).bit();
        }
        return m;
    }

    /** Channels carried per connected side (for smart cable visuals). */
    public Map<Direction, Integer> getChannelsPerSide() {
        var map = new EnumMap<Direction, Integer>(Direction.class);
        for (var c : connections) {
            map.put(c.getDirection(this), c.getUsedChannels());
        }
        return map;
    }

    /** Channels used by / passing through this node after the last calculation. */
    public int getUsedChannels() {
        return lastUsedChannels;
    }

    @Override
    public int getMaxChannels() {
        if (hasFlag(GridFlag.CANNOT_CARRY)) {
            return 0;
        }
        var channelMode = grid == null ? ChannelMode.DEFAULT : grid.getPathingService().getChannelMode();
        if (channelMode == ChannelMode.INFINITE) {
            return Integer.MAX_VALUE;
        }
        if (!hasFlag(GridFlag.DENSE_CAPACITY)) {
            return 8 * channelMode.getCableCapacityFactor();
        } else {
            return 32 * channelMode.getCableCapacityFactor();
        }
    }

    @Override
    public void setAdHocChannels(int channels) {
        this.usedChannels = channels;
    }

    @Override
    public IPathItem getControllerRoute() {
        if (connections.isEmpty()) {
            throw new IllegalStateException("Node " + this + " has no connections, cannot have a controller route!");
        }
        return connections.getFirst();
    }

    GridNode getHighestSimilarAncestor() {
        return highestSimilarAncestor;
    }

    boolean getSubtreeAllowsCompressedChannels() {
        return subtreeAllowsCompressedChannels;
    }

    @Override
    public void setControllerRoute(IPathItem fast) {
        this.usedChannels = 0;

        var nodeParent = (GridNode) fast.getControllerRoute();
        if (nodeParent.isController()) {
            this.highestSimilarAncestor = null;
            this.subtreeMaxChannels = getMaxChannels();
            this.subtreeAllowsCompressedChannels = !hasFlag(GridFlag.CANNOT_CARRY_COMPRESSED);
        } else {
            if (nodeParent.highestSimilarAncestor == null) {
                // Parent is connected to a controller, it is the bottleneck.
                this.highestSimilarAncestor = nodeParent;
            } else if (nodeParent.subtreeMaxChannels == nodeParent.highestSimilarAncestor.subtreeMaxChannels) {
                // Parent is not restricting the number of channels, go as high as possible.
                this.highestSimilarAncestor = nodeParent.highestSimilarAncestor;
            } else {
                // Parent is restricting the number of channels, link to it directly.
                this.highestSimilarAncestor = nodeParent;
            }
            this.subtreeMaxChannels = Math.min(nodeParent.subtreeMaxChannels, getMaxChannels());
            this.subtreeAllowsCompressedChannels = nodeParent.subtreeAllowsCompressedChannels
                    && !hasFlag(GridFlag.CANNOT_CARRY_COMPRESSED);
        }

        var connection = (GridConnection) fast;
        int idx = connections.indexOf(connection);
        if (idx > 0) {
            connections.remove(connection);
            connections.addFirst(connection);
        }
    }

    @Override
    public Iterable<IPathItem> getPossibleOptions() {
        return List.<IPathItem>copyOf(connections);
    }

    int propagateChannelsUpwards(boolean consumesChannel) {
        this.usedChannels = 0;
        for (var connection : connections) {
            if (connection.getControllerRoute() == this) {
                this.usedChannels += connection.usedChannels;
            }
        }
        if (consumesChannel) {
            this.usedChannels++;
        }
        return this.usedChannels;
    }

    void incrementChannelCount(int used) {
        this.usedChannels += used;
    }

    void resetPathingState() {
        this.usedChannels = 0;
        this.highestSimilarAncestor = null;
    }

    @Override
    public void finalizeChannels() {
        this.highestSimilarAncestor = null;
        if (hasFlag(GridFlag.CANNOT_CARRY)) {
            return;
        }
        if (this.lastUsedChannels != this.usedChannels) {
            this.lastUsedChannels = this.usedChannels;
            if (grid != null) {
                notifyStatusChange(NodeState.CHANNEL);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ticking helpers

    /** Wakes this node's tickable (if sleeping). */
    public boolean wake() {
        return grid != null && grid.getTickManager().wakeDevice(this);
    }

    /** Wakes the tickable and ticks it on the next game tick. */
    public boolean alert() {
        return grid != null && grid.getTickManager().alertDevice(this);
    }

    /** Puts this node's tickable to sleep. */
    public boolean sleep() {
        return grid != null && grid.getTickManager().sleepDevice(this);
    }

    // ---------------------------------------------------------------------------------------------------------------

    void notifyStatusChange(NodeState state) {
        if (grid != null) {
            grid.onNodeStateChanged(this, state);
        }
        var listener = getService(IGridNodeListener.class);
        if (listener != null) {
            listener.onStateChanged(this, state);
        }
        world.fireStateChanged(this, state);
    }

    @Override
    public String toString() {
        return "GridNode[" + type.kind() + (type.cableType() != null ? "/" + type.cableType().id : "") + " @ "
                + BlockPos.toString(pos) + "]";
    }
}
