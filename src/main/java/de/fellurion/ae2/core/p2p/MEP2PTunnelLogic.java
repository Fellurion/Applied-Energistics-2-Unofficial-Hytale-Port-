/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.p2p;

import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.grid.GridConnection;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.grid.ticking.IGridTickable;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.grid.ticking.TickingRequest;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * ME P2P tunnel: carries up to 32 channels of another network from the input to its outputs. Port of
 * {@code appeng.parts.p2p.MEP2PTunnelPart}.
 * <p>
 * The tunnel has two grid nodes. The main node (back side, {@link NodeType#P2P_TUNNEL_ME}) sits on the carrying
 * network and needs one channel there. The outer node (front side, {@link NodeType#P2P_TUNNEL_ME_OUTER}, created by
 * {@link #createOuterNode}) belongs to the tunnelled network. While the input is online it keeps a virtual connection
 * from its outer node to the outer node of every online output, which joins everything in front of the tunnels into
 * one network. Tunnels cannot be nested: a tunnel whose channel would have to pass through another tunnel's outer
 * node gets none.
 * <p>
 * As in AE2 the connections are never changed directly: any change wakes the input, its tick queues the update, and
 * the update runs after the tick ({@code MEWorld.runAtEndOfTick}).
 */
public final class MEP2PTunnelLogic extends P2PTunnelLogic implements IGridTickable {
    private enum ConnectionUpdate {
        NONE,
        DISCONNECT,
        CONNECT
    }

    /**
     * Updates to the tunnel connections are always delayed until the end of the tick. This field stores which
     * operation should be performed; of several queued updates only the most recent is performed.
     */
    private ConnectionUpdate pendingUpdate = ConnectionUpdate.NONE;
    /**
     * Maps outputs to the connection between the outer node of this tunnel and the outer node of the output.
     * Maintained for the input side; may still have content for an output before cleanup.
     */
    private final Map<MEP2PTunnelLogic, GridConnection> connections = new IdentityHashMap<>();
    private GridNode outerNode;

    MEP2PTunnelLogic() {
        super(P2PTunnelType.ME);
    }

    /**
     * Creates the outer node (front side of the block) once the main node exists. It is removed together with the
     * main node.
     */
    public GridNode createOuterNode() {
        var main = getMainNode();
        if (main == null || main.isRemoved()) {
            throw new IllegalStateException("The main node must be added to the world first");
        }
        outerNode = main.getWorld().addSecondaryNode(main.getPos(), NodeType.P2P_TUNNEL_ME_OUTER, main.getFacing(),
                main.getGridColor(), this, null);
        return outerNode;
    }

    /** The node of the tunnelled network (front side), or null before {@link #createOuterNode}. */
    public GridNode getOuterNode() {
        return outerNode;
    }

    /** Number of outputs this input is currently connected to. */
    public int getConnectionCount() {
        int n = 0;
        for (var c : connections.values()) {
            if (!c.isDestroyed()) {
                n++;
            }
        }
        return n;
    }

    @Override
    protected void onTunnelNetworkChange() {
        super.onTunnelNetworkChange();
        // Trigger the delayed update of the tunnel connections. Usually this is only done for input tunnels, but if
        // an input is converted to an output, we need to clean up the connections.
        if (!isOutput() || !connections.isEmpty()) {
            wake();
        }
    }

    void wake() {
        var node = getMainNode();
        if (node != null && node.getGrid() != null) {
            node.getGrid().getTickManager().wakeDevice(node);
        }
    }

    @Override
    public TickingRequest getTickingRequest(GridNode node) {
        return new TickingRequest(TickRates.METunnel, true);
    }

    @Override
    public TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        if (!node.isOnline()) {
            pendingUpdate = ConnectionUpdate.DISCONNECT;
        } else {
            pendingUpdate = ConnectionUpdate.CONNECT;
        }
        node.getWorld().runAtEndOfTick(this::updateConnections);
        return TickRateModulation.SLEEP;
    }

    private void updateConnections() {
        var operation = pendingUpdate;
        pendingUpdate = ConnectionUpdate.NONE;

        var main = getMainNode();
        var mainGrid = main == null ? null : main.getGrid();

        if (isOutput()) {
            // It's possible to be woken up while we're still an input, then be made an output, and THEN this method
            // is called afterwards. Disconnect any potentially remaining connections.
            operation = ConnectionUpdate.DISCONNECT;
        } else if (mainGrid == null || outerNode == null || outerNode.isRemoved()) {
            // We got disconnected completely (are we being destroyed?)
            operation = ConnectionUpdate.DISCONNECT;
        }

        if (operation == ConnectionUpdate.DISCONNECT) {
            for (var connection : connections.values()) {
                destroy(connection);
            }
            connections.clear();
        } else if (operation == ConnectionUpdate.CONNECT) {
            var outputs = getOutputs();

            // Sever existing connections to tunnels that are no longer outputs of this input or that have become
            // invalid for other reasons.
            var it = connections.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                var output = entry.getKey();
                var connection = entry.getValue();

                // The previous tunnel could have moved to a different main grid (net-split), or lost its channel.
                // Or it may have been relinked to a different input, or it left the world.
                var outputMain = output.getMainNode();
                if (connection.isDestroyed()
                        || outputMain == null
                        || outputMain.getGrid() != mainGrid
                        || !outputMain.isOnline()
                        || !outputs.contains(output)) {
                    destroy(connection);
                    it.remove();
                }
            }

            for (var tunnel : outputs) {
                var output = (MEP2PTunnelLogic) tunnel;
                // The other tunnel has no channel, or it's already connected
                if (!output.getMainNode().isOnline() || connections.containsKey(output)) {
                    continue;
                }
                var otherOuter = output.outerNode;
                if (otherOuter == null || otherOuter.isRemoved() || otherOuter == outerNode
                        || outerNode.hasConnection(otherOuter)) {
                    continue; // not in the world yet, or the two fronts already touch each other
                }
                connections.put(output, main.getWorld().addVirtualConnection(outerNode, otherOuter));
            }
        }
    }

    private void destroy(GridConnection connection) {
        var main = getMainNode();
        if (main != null && !connection.isDestroyed()) {
            main.getWorld().removeVirtualConnection(connection);
        }
    }
}
