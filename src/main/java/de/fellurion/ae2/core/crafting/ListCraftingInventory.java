/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A simple crafting inventory backed by a {@link KeyCounter}. Port of {@code appeng.crafting.inv.ListCraftingInventory}.
 */
public final class ListCraftingInventory implements ICraftingInventory {
    public final KeyCounter list = new KeyCounter();
    private final ChangeListener listener;

    @FunctionalInterface
    public interface ChangeListener {
        void onChange(AEKey key);
    }

    public ListCraftingInventory(ChangeListener listener) {
        this.listener = listener;
    }

    @Override
    public void insert(AEKey what, long amount, Actionable mode) {
        if (mode == Actionable.MODULATE) {
            list.add(what, amount);
            listener.onChange(what);
        }
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode) {
        var available = list.get(what);
        var extracted = Math.min(available, amount);
        if (mode == Actionable.MODULATE) {
            if (available > extracted) {
                list.remove(what, extracted);
            } else {
                list.remove(what);
            }
            listener.onChange(what);
        }
        return extracted;
    }

    @Override
    public Iterable<AEKey> findFuzzyTemplates(AEKey what) {
        var result = new ArrayList<AEKey>();
        for (var e : list.findFuzzy(what, FuzzyMode.IGNORE_ALL)) {
            result.add(e.getKey());
        }
        return result;
    }

    public void clear() {
        for (var stack : list) {
            list.set(stack.getKey(), 0);
            listener.onChange(stack.getKey());
        }
        list.removeZeros();
    }

    /** JSON list of {@code {key..., "a": amount}}. */
    public List<Object> toJson() {
        var out = new ArrayList<Object>();
        for (var e : list) {
            var m = e.getKey().toJsonMap();
            m.put("a", e.getLongValue());
            out.add(m);
        }
        return out;
    }

    public void fromJson(List<?> json) {
        list.clear();
        list.removeEmptySubmaps();
        for (var o : json) {
            if (o instanceof Map<?, ?> m) {
                insert(AEKey.fromJsonMap(m), MiniJson.getLong(m, "a", 0), Actionable.MODULATE);
            }
        }
    }
}
