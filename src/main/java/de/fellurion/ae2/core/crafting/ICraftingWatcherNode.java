/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IStackWatcher;

/**
 * Receives changes of what the crafting CPUs of the grid are waiting for. Port of
 * {@code appeng.api.networking.crafting.ICraftingWatcherNode}.
 * <p>
 * Node owners implementing this interface get a watcher automatically when joining a grid (level emitters with a
 * crafting card).
 * <p>
 * Deviations: the watcher is handed over through {@link #updateCraftingWatcher} instead of {@code updateWatcher},
 * because here the node owner itself implements the watcher interfaces and
 * {@link de.fellurion.ae2.core.storage.IStorageWatcherNode#updateWatcher} has the same signature;
 * {@code onCraftableChange} is not ported (use {@link CraftingService#isCraftable} on demand).
 */
public interface ICraftingWatcherNode {
    /**
     * Called with the watcher of the grid the node just joined; the previous watcher is already destroyed. Register
     * the keys to watch on it ({@code add} / {@code setWatchAll}).
     */
    void updateCraftingWatcher(IStackWatcher newWatcher);

    /** Called at the end of a tick in which a CPU started or stopped waiting for the watched key. */
    void onRequestChange(AEKey what);
}
