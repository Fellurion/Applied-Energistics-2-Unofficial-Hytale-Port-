/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.stacks;

import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uniquely identifies a resource (item or fluid variant) stored in the ME network, without an amount.
 * <p>
 * Port of {@code appeng.api.stacks.AEKey}. In AE2 this is an abstract class; here it is a sealed interface
 * implemented by the records {@link AEItemKey} and {@link AEFluidKey}. Keys are immutable value objects and use value
 * equality.
 */
public sealed interface AEKey permits AEItemKey, AEFluidKey {

    /** Groups all variants of the same item / fluid (AE2's {@code getPrimaryKey()}, e.g. the Item object). */
    record PrimaryKey(AEKeyType type, String id) {
    }

    AEKeyType getType();

    /** The item or fluid id without metadata. */
    String getId();

    /** @return the key without secondary information (metadata / nbt). */
    AEKey dropSecondary();

    default PrimaryKey getPrimaryKey() {
        return new PrimaryKey(getType(), getId());
    }

    default int getAmountPerOperation() {
        return getType().getAmountPerOperation();
    }

    default int getAmountPerByte() {
        return getType().getAmountPerByte();
    }

    default int getAmountPerUnit() {
        return getType().getAmountPerUnit();
    }

    default boolean supportsFuzzyRangeSearch() {
        return getType().supportsFuzzyRangeSearch();
    }

    /** Damage value in [0, {@link #getFuzzySearchMaxValue()}], see {@link FuzzySupport}. */
    default int getFuzzySearchValue() {
        return 0;
    }

    /** Max damage; 0 means "not damageable". */
    default int getFuzzySearchMaxValue() {
        return 0;
    }

    /**
     * Tests if this and the given key are in the same fuzzy partition given a fuzzy matching mode. Exact port of
     * {@code AEKey.fuzzyEquals}.
     */
    default boolean fuzzyEquals(AEKey other, FuzzyMode fuzzyMode) {
        if (other == null || other.getClass() != getClass()) {
            return false;
        }
        // For any fuzzy mode, the primary key (item, fluid) must still match
        if (!getPrimaryKey().equals(other.getPrimaryKey())) {
            return false;
        }
        // If the type doesn't support fuzzy range search, it always behaves like IGNORE_ALL, which ignores NBT
        if (!supportsFuzzyRangeSearch()) {
            return true;
        } else if (fuzzyMode == FuzzyMode.IGNORE_ALL) {
            return true;
        } else if (fuzzyMode == FuzzyMode.PERCENT_99) {
            return getFuzzySearchValue() > 0 == other.getFuzzySearchValue() > 0;
        } else {
            final float percentA = (float) getFuzzySearchValue() / getFuzzySearchMaxValue();
            final float percentB = (float) other.getFuzzySearchValue() / other.getFuzzySearchMaxValue();
            return percentA > fuzzyMode.breakPoint == percentB > fuzzyMode.breakPoint;
        }
    }

    /** @return true iff the stack is non-null and its key equals this key. */
    default boolean matches(GenericStack stack) {
        return stack != null && stack.what().equals(this);
    }

    /** Serializes this key into a JSON-compatible map (used by {@link #toJson()}). */
    default Map<String, Object> toJsonMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("t", getType().shortId());
        map.put("id", getId());
        if (this instanceof AEItemKey item && item.nbt() != null) {
            map.put("nbt", item.nbt());
        }
        return map;
    }

    /** Canonical JSON form, e.g. {@code {"t":"i","id":"Rock_Stone","nbt":"..."}}. */
    default String toJson() {
        return MiniJson.write(toJsonMap());
    }

    static AEKey fromJsonMap(Map<?, ?> map) {
        var type = AEKeyType.byShortId(String.valueOf(map.get("t")));
        var id = String.valueOf(map.get("id"));
        return switch (type) {
            case ITEMS -> new AEItemKey(id, map.get("nbt") == null ? null : String.valueOf(map.get("nbt")));
            case FLUIDS -> new AEFluidKey(id);
        };
    }

    static AEKey fromJson(String json) {
        return fromJsonMap((Map<?, ?>) MiniJson.parse(json));
    }
}
