/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.storage;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Aggregates all storages mounted into a grid. Port of {@code appeng.me.storage.NetworkStorage}.
 * <p>
 * Insertion: storages are visited from the highest to the lowest priority. Within one priority, storages that are
 * "preferred" for the key ({@link MEStorage#isPreferredStorageFor}: whitelisted partition or already containing the
 * key) get the first chance, then all others in mount order.
 * <p>
 * Extraction: storages are visited from the <b>lowest</b> to the highest priority (in mount order within a priority).
 * <p>
 * Mount/unmount operations during an ongoing operation are queued, and recursive use returns 0.
 */
public final class NetworkStorage implements MEStorage {
    private boolean mountsInUse;
    /** Highest priority first. */
    private final NavigableMap<Integer, List<MEStorage>> priorityInventory = new TreeMap<>((a, b) -> Integer.compare(b, a));
    private final List<MEStorage> secondPassInventories = new ArrayList<>();
    private List<Object[]> queuedOperations;
    private long changeCount;

    /**
     * Counts everything that changed the content seen through this storage: mounts, unmounts and every insert or
     * extract that actually moved something. Not in AE2; lets {@link StorageService} skip rebuilding its snapshot
     * while nothing happened. Storages that change behind the network's back (a chest behind a storage bus) are not
     * counted.
     */
    public long getChangeCount() {
        return changeCount;
    }

    public void mount(int priority, MEStorage inventory) {
        if (mountsInUse) {
            queue(new Object[] { Boolean.TRUE, priority, inventory });
        } else {
            priorityInventory.computeIfAbsent(priority, k -> new ArrayList<>()).add(inventory);
            changeCount++;
        }
    }

    public void unmount(MEStorage inventory) {
        if (mountsInUse) {
            queue(new Object[] { Boolean.FALSE, 0, inventory });
        } else {
            changeCount++;
            var it = priorityInventory.entrySet().iterator();
            while (it.hasNext()) {
                var list = it.next().getValue();
                if (list.remove(inventory) && list.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    private void queue(Object[] op) {
        if (queuedOperations == null) {
            queuedOperations = new ArrayList<>();
        }
        queuedOperations.add(op);
    }

    private void flushQueuedOperations() {
        var ops = queuedOperations;
        if (ops != null) {
            queuedOperations = null;
            for (var op : ops) {
                if ((Boolean) op[0]) {
                    mount((Integer) op[1], (MEStorage) op[2]);
                } else {
                    unmount((MEStorage) op[2]);
                }
            }
        }
    }

    private boolean isQueuedForRemoval(MEStorage inv) {
        if (queuedOperations != null) {
            for (var op : queuedOperations) {
                if (!(Boolean) op[0] && op[2] == inv) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable type, IActionSource src) {
        if (mountsInUse) {
            return 0; // Prevent recursive use
        }
        var remaining = amount;
        mountsInUse = true;
        try {
            for (var invList : priorityInventory.values()) {
                secondPassInventories.clear();
                // First give every preferred inventory a chance to accept the item
                var ii = invList.iterator();
                while (ii.hasNext() && remaining > 0) {
                    var inv = ii.next();
                    if (isQueuedForRemoval(inv)) {
                        continue;
                    }
                    if (inv.isPreferredStorageFor(what, src)) {
                        remaining -= inv.insert(what, remaining, type, src);
                    } else {
                        secondPassInventories.add(inv);
                    }
                }
                // Then give every remaining inventory a chance
                for (var inv : secondPassInventories) {
                    if (remaining <= 0) {
                        break;
                    }
                    if (isQueuedForRemoval(inv)) {
                        continue;
                    }
                    remaining -= inv.insert(what, remaining, type, src);
                }
            }
        } finally {
            mountsInUse = false;
        }
        flushQueuedOperations();
        if (type == Actionable.MODULATE && remaining != amount) {
            changeCount++;
        }
        return amount - remaining;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (mountsInUse) {
            return 0;
        }
        var extracted = 0L;
        mountsInUse = true;
        try {
            for (var invList : priorityInventory.descendingMap().values()) {
                var ii = invList.iterator();
                while (ii.hasNext() && extracted < amount) {
                    var inv = ii.next();
                    if (isQueuedForRemoval(inv)) {
                        continue;
                    }
                    extracted += inv.extract(what, amount - extracted, mode, source);
                }
            }
        } finally {
            mountsInUse = false;
        }
        flushQueuedOperations();
        if (mode == Actionable.MODULATE && extracted != 0) {
            changeCount++;
        }
        return extracted;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (mountsInUse) {
            return;
        }
        mountsInUse = true;
        try {
            for (var list : priorityInventory.values()) {
                for (var inv : list) {
                    inv.getAvailableStacks(out);
                }
            }
        } finally {
            mountsInUse = false;
        }
    }

    @Override
    public int getEstimatedStackCount() {
        if (mountsInUse) {
            return 0;
        }
        mountsInUse = true;
        int total = 0;
        try {
            for (var list : priorityInventory.values()) {
                for (var inv : list) {
                    total += inv.getEstimatedStackCount();
                }
            }
        } finally {
            mountsInUse = false;
        }
        return total;
    }

    /** Number of mounted storages (debugging/tests). */
    public int getMountCount() {
        int n = 0;
        for (var l : priorityInventory.values()) {
            n += l.size();
        }
        return n;
    }

    @Override
    public String getDescription() {
        return "ae2.me_network_storage";
    }
}
