/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.quantum;

import de.fellurion.ae2.core.grid.GridConnection;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;

/**
 * A formed quantum network bridge: eight quantum rings around one link chamber. Port of
 * {@code appeng.me.cluster.implementations.QuantumCluster}.
 * <p>
 * The bridge registers in the world's {@link QuantumBridgeRegistry} under the frequency of the singularity in its
 * link chamber. Two registered bridges of one frequency get ONE grid connection between the nodes of their link
 * chambers ({@code MEWorld.addVirtualConnection}); both hold it in a shared {@link ConnectionWrapper}. As in AE2 the
 * link needs no power, only two formed bridges with matching singularities that are both loaded. It carries 32
 * channels, the blocks of a bridge are dense nodes. The connection is taken down as soon as one end is no longer
 * active: a block of it was removed or unloaded, or its singularity was taken out or exchanged.
 * <p>
 * A frequency links exactly two bridges. A third bridge with the same frequency finds both places of the frequency
 * taken, stays unlinked and tries again whenever its state is updated (a change of its grid, its singularity or its
 * blocks), so it takes over once one of the two is gone.
 * <p>
 * Deviations from AE2:
 * <ul>
 * <li>One grid world per world: the two ends are in the same world; AE2 also links across dimensions.</li>
 * <li>{@link #updateStatus} updates the registration at once but changes the grid connection after the current tick
 * ({@code MEWorld.runAtEndOfTick}), because it may be called from a node listener.</li>
 * <li>A bridge that finds both places of its frequency taken holds no place. AE2 leaves the places of the
 * singularity it held before untouched in that case (a bridge whose singularity was exchanged for a third one of a
 * frequency in use would stay linked to its old partner), and registers a bridge without place under key 0.</li>
 * <li>AE2 destroys the clusters of a level when the level unloads (an event handler per cluster); here the blocks
 * leave the grid world on unload, which destroys their cluster.</li>
 * </ul>
 */
public final class QuantumCluster {
    /** How far the link of a formed bridge got. */
    public enum LinkState {
        /** The link chamber holds no entangled singularity. */
        NO_SINGULARITY,
        /** Two other bridges already use the frequency of the singularity. */
        FREQUENCY_IN_USE,
        /** No active bridge with the same frequency is loaded. */
        WAITING,
        /** Connected to the other bridge. */
        LINKED
    }

    private final long boundsMin;
    private final long boundsMax;
    private final QuantumBridgeRegistry registry;
    private boolean isDestroyed = false;
    private QuantumBridgeLogic[] ring = new QuantumBridgeLogic[8];
    /** True while the cluster has its center (AE2: registered for level unload events). */
    private boolean registered = false;
    private ConnectionWrapper connection;
    private long thisSide;
    private long otherSide;
    private QuantumBridgeLogic center;
    private MEWorld world;
    private boolean linkUpdateQueued;

    QuantumCluster(long boundsMin, long boundsMax, QuantumBridgeRegistry registry) {
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
        this.registry = registry;
    }

    /**
     * Brings the registration in line with the singularity in the link chamber and schedules the update of the
     * connection to the other bridge. Call it whenever the bridge, its grid or its singularity changed; it may be
     * called from anywhere.
     *
     * @param updateGrid unused, as in AE2
     */
    public void updateStatus(boolean updateGrid) {
        if (isDestroyed || center == null) {
            return;
        }
        final long qe = center.getQEFrequency();

        if (thisSide != qe && thisSide != -qe) {
            if (thisSide != 0) {
                registry.unregister(thisSide, this);
            }
            thisSide = 0;
            otherSide = 0;
            if (qe != 0) {
                if (canUseNode(-qe)) {
                    otherSide = qe;
                    thisSide = -qe;
                } else if (canUseNode(qe)) {
                    thisSide = qe;
                    otherSide = -qe;
                }
                if (thisSide != 0) {
                    registry.register(thisSide, this);
                }
            }
        }

        scheduleLinkUpdate();
    }

    private void scheduleLinkUpdate() {
        if (!linkUpdateQueued && world != null) {
            linkUpdateQueued = true;
            world.runAtEndOfTick(this::updateLink);
        }
    }

    /** Second half of AE2's {@code updateStatus}: connects to the other side or takes the connection down. */
    private void updateLink() {
        linkUpdateQueued = false;

        var myOtherSide = isDestroyed || otherSide == 0 ? null : registry.get(otherSide);

        boolean shutdown = false;

        if (myOtherSide != null && myOtherSide != this) {
            var sideA = this;
            var sideB = myOtherSide;

            if (sideA.isActive() && sideB.isActive()) {
                final GridNode sa = sideA.getNode();
                final GridNode sb = sideB.getNode();
                var current = currentConnection(this.connection);
                if (current != null) {
                    final GridNode a = current.a();
                    final GridNode b = current.b();
                    if ((a == sa || b == sa) && (a == sb || b == sb)) {
                        return;
                    }
                }

                sideA.dropConnection();
                sideB.dropConnection();

                // Never two connections between the same two link chambers
                var stale = sa.getVirtualConnection(sb);
                if (stale != null) {
                    world.removeVirtualConnection(stale);
                }
                if (sa.hasConnection(sb)) {
                    return;
                }

                sideA.connection = sideB.connection = new ConnectionWrapper(world.addVirtualConnection(sa, sb));
            } else {
                shutdown = true;
            }
        } else {
            shutdown = true;
        }

        if (shutdown) {
            dropConnection();
        }
    }

    private static GridConnection currentConnection(ConnectionWrapper wrapper) {
        var c = wrapper == null ? null : wrapper.getConnection();
        return c == null || c.isDestroyed() ? null : c;
    }

    /** Destroys the connection this side takes part in; the other side sees it gone through the shared wrapper. */
    private void dropConnection() {
        if (connection != null && connection.getConnection() != null) {
            var c = connection.getConnection();
            if (!c.isDestroyed()) {
                c.a().getWorld().removeVirtualConnection(c);
            }
            connection.setConnection(null);
            connection = new ConnectionWrapper(null);
        }
    }

    /**
     * True if the place {@code qe} of a frequency is free: nothing is registered there, or what is registered is no
     * longer a bridge in the world.
     */
    private boolean canUseNode(long qe) {
        var qc = registry.get(qe);
        if (qc != null && !qc.isDestroyed) {
            var node = qc.center == null ? null : qc.center.getNode();
            return node == null || node.isRemoved();
        }
        return true;
    }

    private boolean isActive() {
        return !isDestroyed && registered && hasQES() && getNode() != null;
    }

    private GridNode getNode() {
        var node = center == null ? null : center.getNode();
        return node == null || node.isRemoved() ? null : node;
    }

    /** True if the bridge holds a place of its singularity's frequency. */
    private boolean hasQES() {
        return thisSide != 0;
    }

    /** Lower corner of the 3x3x1 area (packed, {@code BlockPos.asLong}). */
    public long getBoundsMin() {
        return boundsMin;
    }

    public long getBoundsMax() {
        return boundsMax;
    }

    public boolean isDestroyed() {
        return isDestroyed;
    }

    /**
     * Dissolves the bridge: it leaves the registry, its connection is taken down after the current tick and its
     * blocks become unformed.
     */
    public void destroy() {
        if (isDestroyed) {
            return;
        }
        isDestroyed = true;
        registered = false;

        // AE2 calls updateStatus here: the cluster is no longer active, so the connection is destroyed
        scheduleLinkUpdate();
        if (thisSide != 0) {
            registry.unregister(thisSide, this);
        }

        var oldCenter = center;
        var oldRing = ring;
        center = null;
        ring = new QuantumBridgeLogic[8];
        if (oldCenter != null) {
            oldCenter.updateStatus(null, (byte) -1);
        }
        for (var r : oldRing) {
            if (r != null) {
                r.updateStatus(null, (byte) -1);
            }
        }
    }

    /** The link chamber, or null once the bridge is destroyed. */
    public QuantumBridgeLogic getCenter() {
        return center;
    }

    void setCenter(QuantumBridgeLogic c) {
        this.registered = true;
        this.center = c;
        this.world = c.getNode().getWorld();
    }

    QuantumBridgeLogic[] getRing() {
        return ring;
    }

    /** The frequency of the singularity in the link chamber, 0 for none. */
    public long getFrequency() {
        return center == null ? 0 : center.getQEFrequency();
    }

    /** The place this bridge holds in the registry ({@code -f} or {@code f}), 0 for none. */
    public long getThisSide() {
        return thisSide;
    }

    /** The place of the bridge this one links to, 0 for none. */
    public long getOtherSide() {
        return otherSide;
    }

    /** The bridge registered as this one's partner, or null if there is none (not built, or not loaded). */
    public QuantumCluster getOtherCluster() {
        if (isDestroyed || otherSide == 0) {
            return null;
        }
        var other = registry.get(otherSide);
        return other == this ? null : other;
    }

    /** True while the connection to the other bridge exists. */
    public boolean isLinked() {
        return currentConnection(connection) != null;
    }

    public LinkState getLinkState() {
        if (getFrequency() == 0) {
            return LinkState.NO_SINGULARITY;
        }
        if (!hasQES()) {
            return LinkState.FREQUENCY_IN_USE;
        }
        return isLinked() ? LinkState.LINKED : LinkState.WAITING;
    }

    @Override
    public String toString() {
        if (center == null) {
            return "QuantumCluster{no-center}";
        }
        return "QuantumCluster{" + center + "}";
    }
}
