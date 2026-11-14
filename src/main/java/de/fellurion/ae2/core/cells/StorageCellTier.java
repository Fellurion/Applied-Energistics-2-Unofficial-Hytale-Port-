/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.cells;

/**
 * Storage tiers of cells and components. Port of {@code appeng.items.storage.StorageTier}.
 *
 * @param index      1..5
 * @param namePrefix "1k".."256k"
 * @param bytes      total bytes of a cell of this tier (1024 * k)
 * @param idleDrain  AE/t while the cell is in a drive
 */
public record StorageCellTier(int index, String namePrefix, int bytes, double idleDrain) {
    public static final StorageCellTier SIZE_1K = new StorageCellTier(1, "1k", 1024, 0.5);
    public static final StorageCellTier SIZE_4K = new StorageCellTier(2, "4k", 4096, 1.0);
    public static final StorageCellTier SIZE_16K = new StorageCellTier(3, "16k", 16384, 1.5);
    public static final StorageCellTier SIZE_64K = new StorageCellTier(4, "64k", 65536, 2.0);
    public static final StorageCellTier SIZE_256K = new StorageCellTier(5, "256k", 262144, 2.5);

    public static final StorageCellTier[] ALL = { SIZE_1K, SIZE_4K, SIZE_16K, SIZE_64K, SIZE_256K };

    /** Bytes per type of a basic cell of this tier (8, 32, 128, 512, 2048). */
    public int bytesPerType() {
        return bytes / 128;
    }

    /** Hytale item id of the storage component of this tier, e.g. {@code AE2_Cell_Component_1k}. */
    public String componentItemId() {
        return "AE2_Cell_Component_" + namePrefix;
    }
}
