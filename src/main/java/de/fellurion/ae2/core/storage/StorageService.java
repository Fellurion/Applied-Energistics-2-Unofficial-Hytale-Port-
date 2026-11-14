/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.storage;

import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IGridService;
import de.fellurion.ae2.core.grid.NodeState;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Storage of a grid: mounts the storages of all {@link IStorageProvider} nodes into one {@link NetworkStorage},
 * maintains a cached view of all available stacks and notifies watchers about changes.
 * <p>
 * Port of {@code appeng.me.service.StorageService} (+ {@code StackWatcher}, {@code InterestManager}).
 * <ul>
 * <li>{@link #getInventory()}: the live network storage; insert/extract here (use {@link StorageHelper} for powered
 * operations as AE2 does for terminals, buses and interfaces).</li>
 * <li>{@link #getCachedInventory()}: snapshot of all stacks, rebuilt at the end of every tick if any watcher exists,
 * otherwise lazily on access.</li>
 * <li>Watchers ({@link #createWatcher}) receive {@code onStackChange(key, newAmount)} at the end of a tick for each
 * watched key whose amount changed. Terminals should use {@code setWatchAll(true)}.</li>
 * </ul>
 * Addition: storage providers are remounted automatically when their node's online state changes (AE2 does this in
 * each provider's node listener).
 */
public final class StorageService implements IGridService {
    private final Map<GridNode, ProviderState> nodeProviders = new IdentityHashMap<>();
    private final List<ProviderState> globalProviders = new ArrayList<>();
    private final NetworkStorage storage = new NetworkStorage();
    private final KeyCounter cachedAvailableStacks = new KeyCounter();
    private final Map<AEKey, Long> cachedAvailableAmounts = new HashMap<>();
    private boolean cachedStacksNeedUpdate = true;
    private final Map<GridNode, Watcher> nodeWatchers = new IdentityHashMap<>();
    private final Set<Watcher> watchers = new LinkedHashSet<>();

    @Override
    public void onServerEndTick() {
        if (watchers.isEmpty()) {
            cachedStacksNeedUpdate = true; // lazily rebuild
        } else {
            updateCachedStacks();
        }
    }

    private void updateCachedStacks() {
        cachedStacksNeedUpdate = false;
        cachedAvailableStacks.clear();
        storage.getAvailableStacks(cachedAvailableStacks);
        cachedAvailableStacks.removeEmptySubmaps();

        // Collect changes first, then notify (listeners may access the storage)
        var changes = new LinkedHashMap<AEKey, Long>();
        for (var entry : cachedAvailableStacks) {
            var what = entry.getKey();
            var newAmount = entry.getLongValue();
            if (newAmount != cachedAvailableAmounts.getOrDefault(what, 0L)) {
                changes.put(what, newAmount);
            }
        }
        for (var what : cachedAvailableAmounts.keySet()) {
            if (cachedAvailableStacks.get(what) == 0) {
                changes.put(what, 0L);
            }
        }
        cachedAvailableAmounts.clear();
        for (var entry : cachedAvailableStacks) {
            cachedAvailableAmounts.put(entry.getKey(), entry.getLongValue());
        }
        if (!changes.isEmpty() && !watchers.isEmpty()) {
            for (var w : List.copyOf(watchers)) {
                for (var change : changes.entrySet()) {
                    if (w.watchAll || w.keys.contains(change.getKey())) {
                        w.host.onStackChange(change.getKey(), change.getValue());
                    }
                }
            }
        }
    }

    @Override
    public void addNode(GridNode node) {
        var provider = node.getService(IStorageProvider.class);
        if (provider != null) {
            var state = new ProviderState(provider, node);
            nodeProviders.put(node, state);
            state.mount();
        }
        var watcherHost = node.getService(IStorageWatcherNode.class);
        if (watcherHost != null) {
            var w = new Watcher(watcherHost);
            nodeWatchers.put(node, w);
            watchers.add(w);
            watcherHost.updateWatcher(w);
        }
    }

    @Override
    public void removeNode(GridNode node) {
        var w = nodeWatchers.remove(node);
        if (w != null) {
            w.destroy();
        }
        var state = nodeProviders.remove(node);
        if (state != null) {
            state.unmount();
        }
    }

    @Override
    public void onNodeStateChanged(GridNode node, NodeState state) {
        if (state == NodeState.CONNECTIONS) {
            return;
        }
        var ps = nodeProviders.get(node);
        if (ps != null && ps.mounted && ps.mountedOnline != node.isOnline()) {
            ps.update();
        }
    }

    /** The live network inventory. */
    public MEStorage getInventory() {
        return storage;
    }

    /** Cached snapshot of all available stacks (do not modify). */
    public KeyCounter getCachedInventory() {
        if (cachedStacksNeedUpdate) {
            updateCachedStacks();
        }
        return cachedAvailableStacks;
    }

    /** Forces the cache to be rebuilt on next access. */
    public void invalidateCache() {
        cachedStacksNeedUpdate = true;
    }

    /** Registers a watcher not bound to a grid node (e.g. an open terminal UI). Call destroy() when done. */
    public IStackWatcher createWatcher(IStorageWatcherNode host) {
        var w = new Watcher(host);
        watchers.add(w);
        host.updateWatcher(w);
        return w;
    }

    public void addGlobalStorageProvider(IStorageProvider provider) {
        for (var s : globalProviders) {
            if (s.provider == provider) {
                throw new IllegalArgumentException("Duplicate storage provider registration for " + provider);
            }
        }
        var state = new ProviderState(provider, null);
        globalProviders.add(state);
        state.mount();
    }

    public void removeGlobalStorageProvider(IStorageProvider provider) {
        var it = globalProviders.iterator();
        while (it.hasNext()) {
            var s = it.next();
            if (s.provider == provider) {
                it.remove();
                s.unmount();
            }
        }
    }

    public void refreshNodeStorageProvider(GridNode node) {
        var state = nodeProviders.get(node);
        if (state == null) {
            throw new IllegalArgumentException("The given node is not part of this grid or has no storage provider.");
        }
        state.update();
    }

    public void refreshGlobalStorageProvider(IStorageProvider provider) {
        for (var s : globalProviders) {
            if (s.provider == provider) {
                s.update();
                return;
            }
        }
        throw new IllegalArgumentException("Storage provider " + provider + " is not part of this grid.");
    }

    private final class ProviderState implements IStorageMounts {
        private final IStorageProvider provider;
        private final GridNode node;
        private final Set<MEStorage> inventories = new HashSet<>();
        boolean mounted;
        boolean mountedOnline;

        ProviderState(IStorageProvider provider, GridNode node) {
            this.provider = provider;
            this.node = node;
        }

        void mount() {
            if (mounted) {
                throw new IllegalStateException("Can't mount a provider's inventories when it's already mounted");
            }
            mounted = true;
            // Deviation: AE2 providers check isOnline() themselves; here node providers are only asked to mount
            // while their node is online (global providers always).
            mountedOnline = node == null || node.isOnline();
            if (mountedOnline) {
                provider.mountInventories(this);
            }
        }

        @Override
        public void mount(MEStorage inventory, int priority) {
            if (!mounted) {
                throw new IllegalStateException("Cannot use StorageMounts after the storage has been unmounted.");
            }
            if (!inventories.add(inventory)) {
                throw new IllegalStateException("Cannot mount the same inventory twice.");
            }
            storage.mount(priority, inventory);
            cachedStacksNeedUpdate = true;
        }

        void update() {
            unmount();
            mount();
        }

        void unmount() {
            if (!mounted) {
                return;
            }
            mounted = false;
            for (var inv : inventories) {
                storage.unmount(inv);
            }
            inventories.clear();
            cachedStacksNeedUpdate = true;
        }
    }

    private final class Watcher implements IStackWatcher {
        final IStorageWatcherNode host;
        final Set<AEKey> keys = new HashSet<>();
        boolean watchAll;

        Watcher(IStorageWatcherNode host) {
            this.host = host;
        }

        @Override
        public void setWatchAll(boolean watchAll) {
            this.watchAll = watchAll;
        }

        @Override
        public boolean add(AEKey key) {
            return keys.add(key);
        }

        @Override
        public boolean remove(AEKey key) {
            return keys.remove(key);
        }

        @Override
        public void reset() {
            keys.clear();
            watchAll = false;
        }

        @Override
        public void destroy() {
            reset();
            watchers.remove(this);
        }
    }
}
