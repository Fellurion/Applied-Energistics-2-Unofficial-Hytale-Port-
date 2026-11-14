/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.util.MiniJson;

import java.util.Map;
import java.util.Objects;

/**
 * A charger recipe (e.g. certus quartz → charged certus quartz). Port of
 * {@code appeng.recipes.handlers.ChargerRecipe} as data object matching {@code ae2data/charger.json}
 * {@code {id, input, output}}.
 *
 * @param id     recipe id
 * @param input  input ingredient (one item is converted)
 * @param output result
 */
public record ChargerRecipe(String id, Ingredient input, Ingredient output) {
    public ChargerRecipe {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
    }

    public static ChargerRecipe fromJson(Map<?, ?> map) {
        return new ChargerRecipe(MiniJson.getString(map, "id", ""), Ingredient.fromJson(map.get("input")),
                Ingredient.fromJson(map.get("output")));
    }
}
