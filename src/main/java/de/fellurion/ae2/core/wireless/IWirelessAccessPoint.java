/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.wireless;

import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.MEWorld;

/**
 * A block that gives wireless terminals access to its grid. Port of
 * {@code appeng.api.implementations.blockentities.IWirelessAccessPoint}.
 * <p>
 * Deviation: AE2 returns the location as a {@code DimensionalBlockPos}; here it is the node's {@link MEWorld} (one per
 * game world) plus the packed block position.
 */
public interface IWirelessAccessPoint extends IActionHost {
    /** The world the access point is in, or null while it is not attached. */
    MEWorld getWorld();

    /** Packed block position ({@code BlockPos.asLong}). */
    long getPos();

    /** @return the range of this access point in blocks */
    double getRange();

    /** @return true if the access point is powered and has its channel */
    boolean isActive();

    /** @return the grid of the access point, or null */
    Grid getGrid();
}
