/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

/**
 * The rules of spatial storage cells. Port of the data side of {@code appeng.items.storage.SpatialStorageCellItem}
 * ({@code appeng.api.implementations.items.ISpatialStorageCell}): which items are spatial cells and how large a
 * region each tier can store (2, 16 or 128 blocks per edge).
 * <p>
 * The transfer itself ({@code doSpatialTransition}) is ported in {@link SpatialIOPortLogic}, which drives the world
 * through {@link SpatialIOPortLogic.RegionSwapper}.
 */
public final class SpatialStorageCell {
    public static final String CELL_2 = "AE2_Spatial_Storage_Cell_2";
    public static final String CELL_16 = "AE2_Spatial_Storage_Cell_16";
    public static final String CELL_128 = "AE2_Spatial_Storage_Cell_128";

    private SpatialStorageCell() {
    }

    /** AE2: {@code isSpatialStorage}. */
    public static boolean isSpatialCell(String itemId) {
        return getMaxStoredDim(itemId) > 0;
    }

    /**
     * AE2: {@code getMaxStoredDim} - the largest region edge the cell can hold.
     *
     * @return 2, 16 or 128; 0 if the item is not a spatial storage cell
     */
    public static int getMaxStoredDim(String itemId) {
        if (itemId == null) {
            return 0;
        }
        return switch (itemId) {
            case CELL_2 -> 2;
            case CELL_16 -> 16;
            case CELL_128 -> 128;
            default -> 0;
        };
    }
}
