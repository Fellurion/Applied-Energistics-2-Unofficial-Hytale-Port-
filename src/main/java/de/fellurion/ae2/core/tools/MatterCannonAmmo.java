/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.machines.Ingredient;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Ammunition of the Matter Cannon and its weight (penetration). Port of
 * {@code appeng.recipes.mattercannon.MatterCannonAmmo} and {@code MatterCannonItem#getPenetration}.
 * <p>
 * File format ({@code ae2data/matter_cannon.json}): {@code [{"ammo": {"ItemId": "..."}, "weight": 32.0}, ...]}.
 */
public final class MatterCannonAmmo {

    /**
     * @param ammo   the item (or resource type) that can be fired
     * @param weight AE2's weight; the penetration of a shot with shot power 1
     */
    public record Entry(Ingredient ammo, float weight) {
    }

    private final List<Entry> entries = new ArrayList<>();

    /** Loads the content of {@code ae2data/matter_cannon.json}; unreadable entries are skipped. */
    public static MatterCannonAmmo fromJson(String json) {
        var result = new MatterCannonAmmo();
        if (MiniJson.parse(json) instanceof List<?> list) {
            for (var o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Ingredient ammo;
                try {
                    ammo = Ingredient.fromJson(m.get("ammo"));
                } catch (RuntimeException e) {
                    continue; // neither ItemId nor ResourceTypeId
                }
                var weight = (float) MiniJson.getDouble(m, "weight", 0);
                if (ammo != null && weight > 0) {
                    result.add(new Entry(ammo, weight));
                }
            }
        }
        return result;
    }

    public MatterCannonAmmo add(Entry entry) {
        entries.add(entry);
        return this;
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Weight of the first ammo entry this item matches, or 0 if the item is no weighted ammo. AE2:
     * {@code MatterCannonItem#getPenetration}.
     */
    public float penetration(String itemId) {
        if (itemId == null) {
            return 0;
        }
        var stack = SimpleStack.of(itemId, 1);
        for (var e : entries) {
            if (e.ammo().testItem(stack)) {
                return e.weight();
            }
        }
        return 0;
    }
}
