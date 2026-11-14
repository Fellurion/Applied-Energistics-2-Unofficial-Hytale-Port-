/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.inv;

import de.fellurion.ae2.core.stacks.AEItemKey;

/**
 * Insert/extract helpers with Minecraft item-handler semantics on top of {@link SlotAccess} (ports of the relevant
 * parts of {@code appeng.api.inventories.InternalInventory} / {@code ItemHandlerHelper}).
 */
public final class SlotInventories {
    private SlotInventories() {
    }

    /**
     * Inserts into one slot.
     *
     * @return the amount that was (or would be) inserted
     */
    public static int insertIntoSlot(SlotAccess inv, int slot, AEItemKey what, int amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        var probe = new SimpleStack(what.itemId(), what.nbt(), amount);
        var current = inv.get(slot);
        int max = inv.getMaxStackSize(slot, what.itemId());
        if (current == null) {
            if (!inv.isItemValid(slot, probe)) {
                return 0;
            }
            int toInsert = Math.min(amount, max);
            if (!simulate && toInsert > 0) {
                inv.set(slot, probe.withCount(toInsert));
            }
            return toInsert;
        }
        if (!current.matches(what)) {
            return 0;
        }
        int space = max - current.count();
        int toInsert = Math.max(0, Math.min(amount, space));
        if (!simulate && toInsert > 0) {
            inv.set(slot, current.withCount(current.count() + toInsert));
        }
        return toInsert;
    }

    /**
     * Inserts into the inventory: first stacks onto existing stacks of the same item, then into empty slots.
     *
     * @return the amount inserted
     */
    public static long insert(SlotAccess inv, AEItemKey what, long amount, boolean simulate) {
        long remaining = amount;
        // When simulating, track what we would have put into each slot
        int size = inv.size();
        int[] simulated = simulate ? new int[size] : null;
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int slot = 0; slot < size && remaining > 0; slot++) {
                var current = inv.get(slot);
                boolean occupied = current != null || simulate && simulated[slot] > 0;
                if (pass == 0 && !occupied || pass == 1 && occupied) {
                    continue;
                }
                int chunk = (int) Math.min(Integer.MAX_VALUE, remaining);
                int inserted;
                if (simulate) {
                    inserted = simulateInsert(inv, slot, what, chunk, simulated[slot]);
                    simulated[slot] += inserted;
                } else {
                    inserted = insertIntoSlot(inv, slot, what, chunk, false);
                }
                remaining -= inserted;
            }
        }
        return amount - remaining;
    }

    private static int simulateInsert(SlotAccess inv, int slot, AEItemKey what, int amount, int alreadyAdded) {
        var current = inv.get(slot);
        int max = inv.getMaxStackSize(slot, what.itemId());
        if (current == null) {
            if (alreadyAdded == 0 && !inv.isItemValid(slot, new SimpleStack(what.itemId(), what.nbt(), amount))) {
                return 0;
            }
            return Math.max(0, Math.min(amount, max - alreadyAdded));
        }
        if (!current.matches(what)) {
            return 0;
        }
        return Math.max(0, Math.min(amount, max - current.count() - alreadyAdded));
    }

    /** Extracts up to {@code amount} from one slot. @return the amount extracted */
    public static int extractFromSlot(SlotAccess inv, int slot, int amount, boolean simulate) {
        var current = inv.get(slot);
        if (current == null || amount <= 0) {
            return 0;
        }
        int extracted = Math.min(amount, current.count());
        if (!simulate) {
            inv.set(slot, current.withCount(current.count() - extracted));
        }
        return extracted;
    }

    /** Extracts up to {@code amount} of {@code what} from all slots. @return the amount extracted */
    public static long extract(SlotAccess inv, AEItemKey what, long amount, boolean simulate) {
        long remaining = amount;
        for (int slot = 0; slot < inv.size() && remaining > 0; slot++) {
            var current = inv.get(slot);
            if (current != null && current.matches(what)) {
                remaining -= extractFromSlot(inv, slot, (int) Math.min(Integer.MAX_VALUE, remaining), simulate);
            }
        }
        return amount - remaining;
    }

    /** Total count of {@code what} in the inventory. */
    public static long count(SlotAccess inv, AEItemKey what) {
        long sum = 0;
        for (int slot = 0; slot < inv.size(); slot++) {
            var s = inv.get(slot);
            if (s != null && s.matches(what)) {
                sum += s.count();
            }
        }
        return sum;
    }
}
