/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.stacks;

import java.util.Objects;

/**
 * An item variant: Hytale item id plus an opaque, canonical metadata string.
 * <p>
 * Port of {@code appeng.api.stacks.AEItemKey}. The metadata ({@code nbt}) must be canonical (the integration layer
 * must serialize the same metadata to the same string) because keys use string equality. An empty string is
 * normalized to {@code null} (= no metadata).
 *
 * @param itemId Hytale item id, e.g. {@code "Rock_Stone"} or {@code "AE2_Item_Storage_Cell_1k"}
 * @param nbt    opaque canonical metadata, or null
 */
public record AEItemKey(String itemId, String nbt) implements AEKey {
    public AEItemKey {
        Objects.requireNonNull(itemId, "itemId");
        if (nbt != null && nbt.isEmpty()) {
            nbt = null;
        }
    }

    public static AEItemKey of(String itemId) {
        return new AEItemKey(itemId, null);
    }

    public static AEItemKey of(String itemId, String nbt) {
        return new AEItemKey(itemId, nbt);
    }

    @Override
    public AEKeyType getType() {
        return AEKeyType.ITEMS;
    }

    @Override
    public String getId() {
        return itemId;
    }

    @Override
    public AEItemKey dropSecondary() {
        return nbt == null ? this : new AEItemKey(itemId, null);
    }

    public boolean hasMetadata() {
        return nbt != null;
    }

    /** True if this key's item id equals the given id (any metadata). */
    public boolean is(String otherItemId) {
        return itemId.equals(otherItemId);
    }

    @Override
    public int getFuzzySearchValue() {
        return FuzzySupport.getDamage(this);
    }

    @Override
    public int getFuzzySearchMaxValue() {
        return FuzzySupport.getMaxDamage(this);
    }

    @Override
    public String toString() {
        return nbt == null ? itemId : itemId + nbt;
    }
}
