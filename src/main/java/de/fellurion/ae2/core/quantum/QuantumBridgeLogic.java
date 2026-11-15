/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.quantum;

import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeState;
import de.fellurion.ae2.core.grid.NodeType;

import java.util.EnumSet;
import java.util.Set;

/**
 * Owner logic of a block of the quantum network bridge: a quantum ring or the quantum link chamber. Port of
 * {@code appeng.blockentity.qnb.QuantumBridgeBlockEntity} (with the block rules of {@code appeng.block.qnb}).
 * <p>
 * Use: create one logic per block with the registry of its world, add the node with {@link #nodeType()} and the
 * logic as owner, and tell the link chamber's logic the frequency of its singularity with {@link #setQEFrequency}.
 * Everything else follows from the nodes in the {@link MEWorld}:
 * <ul>
 * <li>Eight rings around one link chamber, 3x3 blocks in any of the three planes, form a {@link QuantumCluster}
 * (rules in {@link QuantumCalculator}). The bridge is checked again when one of its blocks or a bridge block next to
 * it enters or leaves the world, and after every status change of its grid.</li>
 * <li>An unformed block connects to nothing. In a formed bridge the four rings next to the link chamber connect on
 * all sides; the four corner rings and the link chamber only connect to the bridge blocks next to them, so cables
 * do not attach to them.</li>
 * <li>Every block draws 22 AE/t ({@link NodeType#QUANTUM_RING}, {@link NodeType#QUANTUM_LINK}) and is a dense node:
 * the bridge carries 32 channels.</li>
 * <li>Two formed bridges whose link chambers hold singularities of the same frequency are connected, see
 * {@link QuantumCluster}.</li>
 * </ul>
 * <p>
 * Scope of this port: one grid world per world, so a bridge joins two places of the SAME world (AE2 also links
 * across dimensions). As in AE2 both ends must be loaded.
 * <p>
 * Deviations from AE2:
 * <ul>
 * <li>AE2 re-checks a bridge in the block entity's tick and on Minecraft's neighbour updates, and changes the grid
 * right there. Here a block that enters or leaves the world tells the bridge blocks next to it, and all work is done
 * after the current tick ({@code MEWorld.runAtEndOfTick}): the check, the change of the connectable sides and the
 * connection to the other bridge. A bridge is therefore formed and linked one to two ticks after its last block
 * arrived.</li>
 * <li>When some blocks of a formed bridge unload, AE2 leaves the remaining blocks in their formed state (still
 * connected to cables, but without cluster and link). Here they become unformed until all nine are loaded
 * again.</li>
 * <li>The singularity is not stored here: the integration reads its frequency from the item in the link chamber and
 * passes it in. AE2's client sync flags (singularity present, powered) are not ported.</li>
 * </ul>
 */
public final class QuantumBridgeLogic implements IGridNodeListener, IActionHost {
    public static final String KIND_RING = "quantum_ring";
    public static final String KIND_LINK = "quantum_link";

    /** Why the blocks around a position are a bridge, or not. */
    public enum Formation {
        FORMED,
        /** The bridge blocks around do not fill a flat 3x3 square. */
        WRONG_SHAPE,
        /** Another bridge block touches the 3x3 square. */
        TOUCHED,
        /** Not eight rings around one link chamber: a block is missing or the link chamber is not in the center. */
        WRONG_LAYOUT
    }

    /** Flag of the four corner rings in {@link #constructed}; the low bits are the block's number 1..9. */
    static final byte CORNER = 16;

    private static final NodeType RING_NODE = NodeType.QUANTUM_RING.withSideRule(NodeType.SideRule.NONE);
    private static final NodeType LINK_NODE = NodeType.QUANTUM_LINK.withSideRule(NodeType.SideRule.NONE);

    private final boolean center;
    private final QuantumBridgeRegistry registry;
    private final QuantumCalculator calc = new QuantumCalculator(this);
    private GridNode node;
    private byte constructed = -1;
    private QuantumCluster cluster;
    private long frequency;
    private boolean updateStatus = false;
    private boolean sidesChanged = false;
    private boolean taskQueued = false;

    /**
     * @param center   true for the link chamber, false for a ring
     * @param registry the bridges of the world this block is in
     */
    public QuantumBridgeLogic(boolean center, QuantumBridgeRegistry registry) {
        this.center = center;
        this.registry = registry;
    }

    /** Logic for a block kind: {@value #KIND_RING} or {@value #KIND_LINK}. */
    public static QuantumBridgeLogic forKind(String kind, QuantumBridgeRegistry registry) {
        return switch (kind) {
            case KIND_RING -> new QuantumBridgeLogic(false, registry);
            case KIND_LINK -> new QuantumBridgeLogic(true, registry);
            default -> throw new IllegalArgumentException("Not a quantum bridge block kind: " + kind);
        };
    }

    /** The bridge block at {@code pos}, or null. */
    public static QuantumBridgeLogic at(MEWorld world, long pos) {
        var n = world.getNode(pos);
        return n != null && n.getOwner() instanceof QuantumBridgeLogic logic ? logic : null;
    }

    /**
     * The type to create this block's node with: 22 AE/t, dense, and no connectable side - the sides are opened when
     * the bridge forms ({@link #getGridConnectableSides}).
     */
    public NodeType nodeType() {
        return center ? LINK_NODE : RING_NODE;
    }

    QuantumBridgeRegistry getRegistry() {
        return registry;
    }

    public GridNode getNode() {
        return node;
    }

    @Override
    public GridNode getActionableNode() {
        return node;
    }

    /** The sides the node connects on, by the state of the bridge. */
    public Set<Direction> getGridConnectableSides() {
        if (!isFormed()) {
            return EnumSet.noneOf(Direction.class);
        }

        if (isCorner() || isCenter()) {
            return getAdjacentQuantumBridges();
        } else {
            return EnumSet.allOf(Direction.class);
        }
    }

    /** Runs after the tick in which something changed (AE2: the block entity's server tick). */
    private void serverTick() {
        taskQueued = false;
        if (!isValid()) {
            return;
        }
        if (sidesChanged) {
            sidesChanged = false;
            node.getWorld().setExposedSides(node, getGridConnectableSides());
        }
        if (updateStatus) {
            updateStatus = false;
            if (cluster != null) {
                cluster.updateStatus(true);
            }
            // AE2 rescans here as well: a bridge is not always told that it became complete
            updateMultiBlock();
        }
    }

    private void queueTask() {
        if (!taskQueued && isValid()) {
            taskQueued = true;
            node.getWorld().runAtEndOfTick(this::serverTick);
        }
    }

    /** Has the bridge checked and its link updated after the current tick. */
    private void requestUpdate() {
        updateStatus = true;
        queueTask();
    }

    /**
     * Sets the frequency of the singularity in this link chamber, 0 for none or an unentangled one (AE2: read from
     * the item in the block's inventory). A formed bridge links or unlinks accordingly.
     */
    public void setQEFrequency(long frequency) {
        if (this.frequency != frequency) {
            this.frequency = frequency;
            if (cluster != null) {
                cluster.updateStatus(true);
            }
        }
    }

    public long getQEFrequency() {
        return center ? frequency : 0;
    }

    /** True for the link chamber. */
    public boolean isCenter() {
        return center;
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
        requestUpdate();
        notifyNeighbours(node);
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        disconnect();
        this.node = null;
        notifyNeighbours(node);
    }

    @Override
    public void onStateChanged(GridNode node, NodeState state) {
        if (state != NodeState.CONNECTIONS) {
            requestUpdate();
        }
    }

    /** AE2: the block update that reaches the neighbours of a placed or removed block. */
    private static void notifyNeighbours(GridNode changed) {
        var world = changed.getWorld();
        for (var d : Direction.values()) {
            var neighbour = at(world, BlockPos.offset(changed.getPos(), d));
            if (neighbour != null) {
                neighbour.requestUpdate();
            }
        }
    }

    /** Takes this block out of its bridge, which dissolves the bridge. */
    void disconnect() {
        if (cluster != null) {
            cluster.destroy();
        }
        cluster = null;
    }

    /** The bridge this block belongs to, or null if unformed. */
    public QuantumCluster getCluster() {
        return cluster;
    }

    /** True while the block is in the world. */
    public boolean isValid() {
        return node != null && !node.isRemoved();
    }

    void updateStatus(QuantumCluster c, byte flags) {
        this.cluster = c;
        this.constructed = flags;
        // onGridConnectableSidesChanged
        this.sidesChanged = true;
        queueTask();
    }

    /** True for the four rings at the corners of a formed bridge; they do not connect to cables. */
    public boolean isCorner() {
        return (constructed & CORNER) == CORNER && constructed != -1;
    }

    /** The sides on which a bridge block is next to this one. */
    public Set<Direction> getAdjacentQuantumBridges() {
        var set = EnumSet.noneOf(Direction.class);

        if (isValid()) {
            for (var d : Direction.values()) {
                if (at(node.getWorld(), BlockPos.offset(node.getPos(), d)) != null) {
                    set.add(d);
                }
            }
        }

        return set;
    }

    public boolean isPowered() {
        return isValid() && node.isPowered();
    }

    public boolean isFormed() {
        return constructed != -1;
    }

    private void updateMultiBlock() {
        calc.calculateMultiblock(node.getWorld(), node.getPos());
    }

    /**
     * Whether the blocks around this one make up a bridge right now, and if not, which rule fails (for displays;
     * the bridge itself follows one to two ticks later).
     */
    public Formation checkFormation() {
        return isValid() ? QuantumCalculator.check(node.getWorld(), node.getPos()) : Formation.WRONG_SHAPE;
    }

    @Override
    public String toString() {
        return (center ? "QuantumLink" : "QuantumRing") + (node == null ? "" : "@" + BlockPos.toString(node.getPos()));
    }
}
