/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.grid;

/**
 * Optional callbacks for a node's owner (and for the world-wide listener of {@link MEWorld}). Port of
 * {@code appeng.api.networking.IGridNodeListener}.
 */
public interface IGridNodeListener {
    /** Called after the node was attached to the world (it already has a grid). */
    default void onNodeAttached(GridNode node) {
    }

    /** Called after the node was removed from the world. */
    default void onNodeRemoved(GridNode node) {
    }

    /** Called when the node moved to another grid (merge/split). */
    default void onGridChanged(GridNode node) {
    }

    /** Status change (power, channels, booting, connections). Use it to update block states/visuals. */
    default void onStateChanged(GridNode node, NodeState state) {
    }
}
