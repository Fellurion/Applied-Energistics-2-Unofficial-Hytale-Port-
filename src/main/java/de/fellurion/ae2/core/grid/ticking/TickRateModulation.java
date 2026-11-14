/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.grid.ticking;

/**
 * Returned by {@link IGridTickable#tickingRequest} to adapt the tick rate. Port of
 * {@code appeng.api.networking.ticking.TickRateModulation}.
 */
public enum TickRateModulation {
    /** Same as idle, but also puts the node to sleep. */
    SLEEP,
    /** Set tick rate to maximum. */
    IDLE,
    /** Decrease the tick rate marginally (+1 tick). */
    SLOWER,
    /** Continue at current rate. */
    SAME,
    /** Increase the tick rate marginally (−2 ticks). */
    FASTER,
    /** Change the tick rate to the minimum tick rate. */
    URGENT
}
