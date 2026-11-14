/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.grid;

/**
 * A per-grid service. Port of {@code appeng.api.networking.IGridServiceProvider}.
 */
public interface IGridService {
    default void addNode(GridNode node) {
    }

    default void removeNode(GridNode node) {
    }

    default void onServerStartTick() {
    }

    default void onServerEndTick() {
    }

    /** Called for every status notification of a node in this grid. */
    default void onNodeStateChanged(GridNode node, NodeState state) {
    }
}
