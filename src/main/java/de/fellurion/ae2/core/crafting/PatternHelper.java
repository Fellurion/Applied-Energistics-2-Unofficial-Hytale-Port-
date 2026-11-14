/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pattern utilities. Port of {@code appeng.crafting.pattern.AEPatternHelper} plus JSON helpers for stacks.
 */
public final class PatternHelper {
    private PatternHelper() {
    }

    /** Merges equal keys (summing amounts), preserving first-occurrence order and skipping nulls. */
    public static List<GenericStack> condenseStacks(List<GenericStack> sparseInput) {
        var map = new LinkedHashMap<AEKey, Long>();
        for (var input : sparseInput) {
            if (input != null) {
                map.merge(input.what(), input.amount(), Long::sum);
            }
        }
        if (map.isEmpty()) {
            throw new IllegalStateException("No pattern here!");
        }
        var out = new ArrayList<GenericStack>(map.size());
        for (var entry : map.entrySet()) {
            out.add(new GenericStack(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    static Map<String, Object> stackToJson(GenericStack stack) {
        var m = stack.what().toJsonMap();
        m.put("a", stack.amount());
        return m;
    }

    static GenericStack stackFromJson(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return null;
        }
        return new GenericStack(AEKey.fromJsonMap(m), MiniJson.getLong(m, "a", 1));
    }

    static List<Object> stacksToJson(List<GenericStack> stacks) {
        var list = new ArrayList<Object>(stacks.size());
        for (var s : stacks) {
            list.add(s == null ? null : stackToJson(s));
        }
        return list;
    }

    static List<GenericStack> stacksFromJson(List<?> list) {
        var out = new ArrayList<GenericStack>(list.size());
        for (var o : list) {
            out.add(stackFromJson(o));
        }
        return out;
    }
}
