/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.inv;

/**
 * Minimal slot-based item container that the integration layer implements on top of Hytale containers (block
 * {@code ItemContainer}s, player inventories, chests next to buses, ...). Replaces Minecraft's
 * {@code InternalInventory} / item handler capabilities in the ported logic.
 * <p>
 * Empty slots are represented by {@code null}. Use {@link SlotInventories} for AE2-like insert/extract semantics on
 * top of this interface.
 */
public interface SlotAccess {
    int size();

    /** @return the stack in the slot, or null if empty */
    SimpleStack get(int slot);

    /** Replaces the slot content (null = empty). */
    void set(int slot, SimpleStack stack);

    /** Maximum stack size of the item in this slot (Hytale item max stack, possibly limited by the slot). */
    int getMaxStackSize(int slot, String itemId);

    /** Whether the item may be placed in the slot (filters). Default: always. */
    default boolean isItemValid(int slot, SimpleStack stack) {
        return true;
    }
}
