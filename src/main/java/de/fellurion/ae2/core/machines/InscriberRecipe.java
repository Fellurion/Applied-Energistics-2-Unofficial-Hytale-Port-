/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.util.MiniJson;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An inscriber recipe. Port of {@code appeng.recipes.handlers.InscriberRecipe} as data object, matching the
 * {@code ae2data/inscriber.json} entries {@code {id, mode, top, middle, bottom, output}}.
 * <p>
 * Deviation: AE2 always consumes exactly one middle item; here {@code middle.Quantity} items are consumed (the
 * bridge recipes use 9). Top/bottom plates consume one item each in {@link ProcessType#PRESS} mode.
 *
 * @param id     recipe id
 * @param mode   inscribe (plates are kept) or press (all inputs consumed)
 * @param top    top plate or null
 * @param middle middle input
 * @param bottom bottom plate or null
 * @param output result (item id + quantity)
 */
public record InscriberRecipe(String id, ProcessType mode, Ingredient top, Ingredient middle, Ingredient bottom,
        Ingredient output) {

    /** Port of {@code appeng.recipes.handlers.InscriberProcessType}. */
    public enum ProcessType {
        /** The plates (top/bottom) are not consumed. */
        INSCRIBE,
        /** All inputs are consumed. */
        PRESS
    }

    public InscriberRecipe {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(middle, "middle");
        Objects.requireNonNull(output, "output");
        if (output.itemId() == null) {
            throw new IllegalArgumentException("Inscriber output needs an ItemId");
        }
    }

    /** Parses one entry of {@code ae2data/inscriber.json}. */
    public static InscriberRecipe fromJson(Map<?, ?> map) {
        var mode = "press".equalsIgnoreCase(MiniJson.getString(map, "mode", "inscribe")) ? ProcessType.PRESS
                : ProcessType.INSCRIBE;
        return new InscriberRecipe(MiniJson.getString(map, "id", ""), mode, Ingredient.fromJson(map.get("top")),
                Ingredient.fromJson(map.get("middle")), Ingredient.fromJson(map.get("bottom")),
                Ingredient.fromJson(map.get("output")));
    }

    @Override
    public String toString() {
        return "InscriberRecipe[" + id + " " + mode.name().toLowerCase(Locale.ROOT) + "]";
    }
}
