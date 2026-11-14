/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.inv;

import java.util.Arrays;
import java.util.function.ToIntFunction;

/**
 * Array-backed {@link SlotAccess} (for machines' internal inventories, tests, or simple integrations).
 */
public class SimpleSlotAccess implements SlotAccess {
    private final SimpleStack[] slots;
    private final ToIntFunction<String> maxStackSize;
    private final int slotLimit;
    private Runnable changeListener;

    /** All items stack to 64. */
    public SimpleSlotAccess(int size) {
        this(size, id -> 64, Integer.MAX_VALUE);
    }

    /**
     * @param maxStackSize item id → max stack size
     * @param slotLimit    additional per-slot limit (e.g. 1 for upgrade slots)
     */
    public SimpleSlotAccess(int size, ToIntFunction<String> maxStackSize, int slotLimit) {
        this.slots = new SimpleStack[size];
        this.maxStackSize = maxStackSize;
        this.slotLimit = slotLimit;
    }

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    @Override
    public int size() {
        return slots.length;
    }

    @Override
    public SimpleStack get(int slot) {
        return slots[slot];
    }

    @Override
    public void set(int slot, SimpleStack stack) {
        slots[slot] = stack;
        if (changeListener != null) {
            changeListener.run();
        }
    }

    @Override
    public int getMaxStackSize(int slot, String itemId) {
        return Math.min(slotLimit, maxStackSize.applyAsInt(itemId));
    }

    public void clear() {
        Arrays.fill(slots, null);
    }

    @Override
    public String toString() {
        return Arrays.toString(slots);
    }
}
