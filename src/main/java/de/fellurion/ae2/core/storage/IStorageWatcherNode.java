/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.storage;

import de.fellurion.ae2.core.stacks.AEKey;

/**
 * Receives storage changes of the network. Port of {@code appeng.api.networking.storage.IStorageWatcherNode}.
 * <p>
 * Node owners implementing this interface get a watcher automatically when joining a grid. Terminals (UI) can use
 * {@link StorageService#createWatcher(IStorageWatcherNode)} directly.
 */
public interface IStorageWatcherNode {
    /** Called once with the new watcher; configure which keys to watch on it. */
    default void updateWatcher(IStackWatcher newWatcher) {
    }

    /**
     * Called at the end of a tick for each watched key whose network amount changed.
     *
     * @param amount the new total amount in the network (0 if gone)
     */
    void onStackChange(AEKey what, long amount);
}
