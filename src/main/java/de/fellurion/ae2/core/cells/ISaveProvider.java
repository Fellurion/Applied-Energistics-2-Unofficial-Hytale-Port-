/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.cells;

/**
 * Notified when a cell's content changed and should be saved. Port of {@code appeng.api.storage.cells.ISaveProvider}.
 * <p>
 * The integration layer should eventually call {@link StorageCell#persist()} and write
 * {@link CellItemData#toJson()} into the cell's ItemStack metadata (e.g. when the containing block is saved, or
 * immediately).
 */
@FunctionalInterface
public interface ISaveProvider {
    void saveChanges();
}
