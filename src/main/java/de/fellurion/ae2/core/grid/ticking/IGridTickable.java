/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.grid.ticking;

import de.fellurion.ae2.core.grid.GridNode;

/**
 * Implemented by node owners that want to be ticked by the grid's {@link TickManager}. Port of
 * {@code appeng.api.networking.ticking.IGridTickable}.
 */
public interface IGridTickable {
    /** Called once when the node joins a grid. */
    TickingRequest getTickingRequest(GridNode node);

    /**
     * Called when it is time to tick.
     *
     * @param ticksSinceLastCall game ticks since the previous call
     */
    TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall);
}
