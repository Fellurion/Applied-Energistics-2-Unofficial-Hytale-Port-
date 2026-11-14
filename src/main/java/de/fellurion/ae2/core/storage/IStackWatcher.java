/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.storage;

import de.fellurion.ae2.core.stacks.AEKey;

/**
 * Handle to register interest in network storage changes. Port of
 * {@code appeng.api.networking.storage.IStackWatcher}.
 */
public interface IStackWatcher {
    /** Watch all keys. */
    void setWatchAll(boolean watchAll);

    boolean add(AEKey key);

    boolean remove(AEKey key);

    void reset();

    /** Unregisters this watcher. */
    void destroy();
}
