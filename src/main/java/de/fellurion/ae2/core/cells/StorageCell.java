/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.cells;

import de.fellurion.ae2.core.storage.MEStorage;

/**
 * A storage cell inventory. Port of {@code appeng.api.storage.cells.StorageCell}.
 */
public interface StorageCell extends MEStorage {
    CellState getStatus();

    /** Idle drain in AE/t while inside a drive or ME chest. */
    double getIdleDrain();

    /** Whether this cell may be stored inside another storage cell (non-empty cells may not). */
    default boolean canFitInsideCell() {
        return true;
    }

    /** Writes pending changes back into the cell's {@link CellItemData}. */
    void persist();
}
