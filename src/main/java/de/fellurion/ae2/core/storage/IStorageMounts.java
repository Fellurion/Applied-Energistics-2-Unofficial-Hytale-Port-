/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.storage;

/**
 * Mount table handed to an {@link IStorageProvider}. Port of {@code appeng.api.storage.IStorageMounts}.
 */
public interface IStorageMounts {
    /** Mounts an inventory into the network storage with the given priority. */
    void mount(MEStorage inventory, int priority);

    default void mount(MEStorage inventory) {
        mount(inventory, 0);
    }
}
