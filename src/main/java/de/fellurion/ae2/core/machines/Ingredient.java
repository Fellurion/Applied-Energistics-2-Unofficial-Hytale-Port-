/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * A recipe ingredient: {@code {"ItemId": "...", "Quantity": n}} or
 * {@code {"ResourceTypeId": "...", "Quantity": n}}. Replaces Minecraft's {@code Ingredient} / {@code ItemStackTemplate}.
 * <p>
 * Resource types are Hytale item groups; since the core cannot read Hytale assets, membership is resolved through
 * {@link #setResourceTypeMatcher}. Without a matcher, resource-type ingredients never match.
 *
 * @param itemId         item id or null
 * @param resourceTypeId resource type id or null
 * @param quantity       amount (&gt;= 1)
 */
public record Ingredient(String itemId, String resourceTypeId, int quantity) {
    private static volatile BiPredicate<String, String> resourceTypeMatcher = (item, type) -> false;

    public Ingredient {
        if (itemId == null && resourceTypeId == null) {
            throw new IllegalArgumentException("Ingredient needs ItemId or ResourceTypeId");
        }
        if (quantity < 1) {
            quantity = 1;
        }
    }

    public static Ingredient of(String itemId) {
        return new Ingredient(itemId, null, 1);
    }

    public static Ingredient of(String itemId, int quantity) {
        return new Ingredient(itemId, null, quantity);
    }

    /** Sets the predicate (itemId, resourceTypeId) → item belongs to resource type. */
    public static void setResourceTypeMatcher(BiPredicate<String, String> matcher) {
        resourceTypeMatcher = Objects.requireNonNull(matcher);
    }

    /** True if the stack's item matches (ignores count). */
    public boolean testItem(SimpleStack stack) {
        if (stack == null) {
            return false;
        }
        if (itemId != null) {
            return itemId.equals(stack.itemId());
        }
        return resourceTypeMatcher.test(stack.itemId(), resourceTypeId);
    }

    /** True if the stack's item matches and it has at least {@link #quantity()} items. */
    public boolean test(SimpleStack stack) {
        return testItem(stack) && stack.count() >= quantity;
    }

    /** Output stack (requires an item id). */
    public SimpleStack toStack() {
        if (itemId == null) {
            throw new IllegalStateException("Resource type ingredients cannot be outputs: " + resourceTypeId);
        }
        return SimpleStack.of(itemId, quantity);
    }

    /** Parses {@code {"ItemId": ..., "Quantity": n}}; returns null for a null/non-map value. */
    public static Ingredient fromJson(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new Ingredient(MiniJson.getString(map, "ItemId", null), MiniJson.getString(map, "ResourceTypeId", null),
                MiniJson.getInt(map, "Quantity", 1));
    }
}
