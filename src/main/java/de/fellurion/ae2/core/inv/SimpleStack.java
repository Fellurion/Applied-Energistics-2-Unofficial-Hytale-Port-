/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.inv;

import de.fellurion.ae2.core.stacks.AEItemKey;

import java.util.Objects;

/**
 * An item stack as the core sees it: item id, opaque canonical metadata and count. The integration layer converts
 * Hytale {@code ItemStack}s to/from this record. Replaces Minecraft's {@code ItemStack} in the ported logic.
 *
 * @param itemId Hytale item id
 * @param nbt    opaque canonical metadata or null
 * @param count  stack size (&gt; 0)
 */
public record SimpleStack(String itemId, String nbt, int count) {
    public SimpleStack {
        Objects.requireNonNull(itemId, "itemId");
        if (nbt != null && nbt.isEmpty()) {
            nbt = null;
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0, use null for empty slots");
        }
    }

    public static SimpleStack of(String itemId, int count) {
        return new SimpleStack(itemId, null, count);
    }

    public static SimpleStack of(AEItemKey key, int count) {
        return new SimpleStack(key.itemId(), key.nbt(), count);
    }

    public AEItemKey key() {
        return new AEItemKey(itemId, nbt);
    }

    public boolean is(String id) {
        return itemId.equals(id);
    }

    /** Same item and metadata (ignores count). */
    public boolean isSameItem(SimpleStack other) {
        return other != null && itemId.equals(other.itemId) && Objects.equals(nbt, other.nbt);
    }

    public boolean matches(AEItemKey key) {
        return key != null && itemId.equals(key.itemId()) && Objects.equals(nbt, key.nbt());
    }

    /** @return a copy with the given count, or null if count &lt;= 0 */
    public SimpleStack withCount(int newCount) {
        return newCount <= 0 ? null : new SimpleStack(itemId, nbt, newCount);
    }
}
