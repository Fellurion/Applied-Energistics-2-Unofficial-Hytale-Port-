/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.storage;

import de.fellurion.ae2.core.grid.GridNode;

/**
 * Implemented by node owners that contribute storage to the network (drives, ME chests, storage buses).
 * Port of {@code appeng.api.storage.IStorageProvider}.
 * <p>
 * {@link #mountInventories} is only called while the provider's node is online (powered + channel), as AE2's drives
 * and storage buses check {@code isOnline()} themselves. The storage service remounts automatically when the node's
 * online state changes; call {@link #requestUpdate(GridNode)} when the provided inventories change (e.g. a cell was
 * inserted or the priority changed).
 */
public interface IStorageProvider {
    void mountInventories(IStorageMounts mounts);

    /** Asks the grid to unmount and remount the node's inventories. */
    static void requestUpdate(GridNode node) {
        if (node != null && node.getGrid() != null) {
            node.getGrid().getStorageService().refreshNodeStorageProvider(node);
        }
    }
}
