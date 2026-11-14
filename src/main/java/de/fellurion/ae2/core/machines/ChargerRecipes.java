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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Registry of charger recipes. Port of {@code appeng.blockentity.misc.ChargerRecipes}.
 */
public final class ChargerRecipes {
    private final List<ChargerRecipe> recipes = new ArrayList<>();

    /** Loads the content of {@code ae2data/charger.json}. */
    public static ChargerRecipes fromJson(String json) {
        var result = new ChargerRecipes();
        if (MiniJson.parse(json) instanceof List<?> list) {
            for (var o : list) {
                if (o instanceof Map<?, ?> m) {
                    result.add(ChargerRecipe.fromJson(m));
                }
            }
        }
        return result;
    }

    public ChargerRecipes add(ChargerRecipe recipe) {
        recipes.add(recipe);
        return this;
    }

    public List<ChargerRecipe> getRecipes() {
        return Collections.unmodifiableList(recipes);
    }

    public ChargerRecipe findRecipe(SimpleStack input) {
        for (var r : recipes) {
            if (r.input().testItem(input)) {
                return r;
            }
        }
        return null;
    }

    public boolean allowInsert(SimpleStack stack) {
        return findRecipe(stack) != null;
    }

    public boolean allowExtract(SimpleStack stack) {
        return findRecipe(stack) == null;
    }
}
