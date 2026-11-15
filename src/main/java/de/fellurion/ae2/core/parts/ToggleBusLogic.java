/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.parts;

import de.fellurion.ae2.core.grid.GridConnection;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;

/**
 * Toggle bus: a piece of cable that can be switched on and off. Port of {@code appeng.parts.misc.ToggleBusPart} and
 * {@code InvertedToggleBusPart}.
 * <p>
 * The bus has two grid nodes, the main node on the back of the block ({@link NodeType#TOGGLE_BUS_MAIN}) and the
 * outer node on the front ({@link NodeType#TOGGLE_BUS_OUTER}, created by {@link #createOuterNode}). While the bus is
 * enabled a connection joins them, which merges the networks on both sides and carries channels like a normal cable;
 * while it is disabled they are two networks. The toggle bus is enabled while it receives a signal (AE2: redstone),
 * the inverted toggle bus while it receives none.
 * <p>
 * Call {@link #setSignal} from outside a machine tick, grids merge and split.
 */
public final class ToggleBusLogic {
    private final boolean inverted;
    private boolean hasSignal;
    private GridNode mainNode;
    private GridNode outerNode;
    private GridConnection connection;

    /** @param inverted true for the inverted toggle bus */
    public ToggleBusLogic(boolean inverted) {
        this.inverted = inverted;
    }

    public boolean isInverted() {
        return inverted;
    }

    /** Node type of the main node for this variant. */
    public NodeType mainNodeType() {
        return inverted ? NodeType.INVERTED_TOGGLE_BUS_MAIN : NodeType.TOGGLE_BUS_MAIN;
    }

    /**
     * Creates the outer node (front side of the block) once the main node exists, and connects the two if the bus
     * is enabled. The outer node is removed together with the main node.
     */
    public GridNode createOuterNode(GridNode mainNode) {
        this.mainNode = mainNode;
        this.outerNode = mainNode.getWorld().addSecondaryNode(mainNode.getPos(),
                inverted ? NodeType.INVERTED_TOGGLE_BUS_OUTER : NodeType.TOGGLE_BUS_OUTER, mainNode.getFacing(),
                mainNode.getGridColor(), this, null);
        updateInternalState();
        return outerNode;
    }

    public GridNode getMainNode() {
        return mainNode;
    }

    public GridNode getOuterNode() {
        return outerNode;
    }

    /** Whether the bus currently receives a signal. */
    public boolean hasSignal() {
        return hasSignal;
    }

    /** Sets whether the bus receives a signal (AE2: {@code onRedstoneLevelMayHaveChanged}). */
    public void setSignal(boolean signal) {
        if (this.hasSignal != signal) {
            this.hasSignal = signal;
            updateInternalState();
        }
    }

    /** True if the two sides are (meant to be) connected. */
    public boolean isEnabled() {
        return inverted != hasSignal;
    }

    private void updateInternalState() {
        if (connection != null && connection.isDestroyed()) {
            connection = null;
        }
        boolean intention = isEnabled();
        if (intention == (connection == null) && mainNode != null && outerNode != null
                && !mainNode.isRemoved() && !outerNode.isRemoved()) {
            if (intention) {
                connection = mainNode.getWorld().addVirtualConnection(mainNode, outerNode);
            } else {
                mainNode.getWorld().removeVirtualConnection(connection);
                connection = null;
            }
        }
    }
}
