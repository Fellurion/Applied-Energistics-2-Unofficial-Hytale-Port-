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
 * Registry and lookup of inscriber recipes. Port of {@code appeng.blockentity.misc.InscriberRecipes} and the slot
 * filter {@code InscriberBlockEntity.BaseFilter}.
 * <p>
 * Recipes may be "flipped": the top ingredient may be in the bottom slot and vice versa.
 * <p>
 * Deviation: the name press (renaming items) is not supported.
 */
public final class InscriberRecipes {
    private final List<InscriberRecipe> recipes = new ArrayList<>();

    public InscriberRecipes() {
    }

    public InscriberRecipes(List<InscriberRecipe> recipes) {
        this.recipes.addAll(recipes);
    }

    /** Loads the content of {@code ae2data/inscriber.json}. */
    public static InscriberRecipes fromJson(String json) {
        var result = new InscriberRecipes();
        var parsed = MiniJson.parse(json);
        if (parsed instanceof List<?> list) {
            for (var o : list) {
                if (o instanceof Map<?, ?> m) {
                    result.add(InscriberRecipe.fromJson(m));
                }
            }
        }
        return result;
    }

    public InscriberRecipes add(InscriberRecipe recipe) {
        recipes.add(recipe);
        return this;
    }

    public List<InscriberRecipe> getRecipes() {
        return Collections.unmodifiableList(recipes);
    }

    /** Empty optional ingredient matches an empty slot; otherwise the item must match. */
    static boolean testOptional(Ingredient ingredient, SimpleStack stack) {
        return ingredient == null ? stack == null : ingredient.test(stack);
    }

    private static boolean testOptionalItem(Ingredient ingredient, SimpleStack stack) {
        return ingredient == null ? stack == null : ingredient.testItem(stack);
    }

    /**
     * Finds the recipe for the given slot contents (top/bottom may be swapped). Port of
     * {@code InscriberRecipes.findRecipe}.
     */
    public InscriberRecipe findRecipe(SimpleStack input, SimpleStack plateA, SimpleStack plateB) {
        if (input == null) {
            return null;
        }
        for (var recipe : recipes) {
            boolean matchA = testOptional(recipe.top(), plateA) && testOptional(recipe.bottom(), plateB);
            boolean matchB = testOptional(recipe.top(), plateB) && testOptional(recipe.bottom(), plateA);
            if ((matchA || matchB) && recipe.middle().test(input)) {
                return recipe;
            }
        }
        return null;
    }

    /** True if any recipe accepts the combination of plates (in either order). */
    public boolean isValidOptionalIngredientCombination(SimpleStack pressA, SimpleStack pressB) {
        for (var recipe : recipes) {
            if (testOptionalItem(recipe.top(), pressA) && testOptionalItem(recipe.bottom(), pressB)
                    || testOptionalItem(recipe.top(), pressB) && testOptionalItem(recipe.bottom(), pressA)) {
                return true;
            }
        }
        return false;
    }

    /** True if any recipe uses the stack as top or bottom plate. */
    public boolean isValidOptionalIngredient(SimpleStack stack) {
        for (var recipe : recipes) {
            if (recipe.top() != null && recipe.top().testItem(stack)
                    || recipe.bottom() != null && recipe.bottom().testItem(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code stack} may be inserted into the given inscriber slot, considering the other slots. Port of
     * {@code InscriberBlockEntity.BaseFilter.allowInsert} (without name press).
     *
     * @param slot   {@link InscriberLogic#SLOT_TOP}, {@link InscriberLogic#SLOT_MIDDLE} or
     *               {@link InscriberLogic#SLOT_BOTTOM}
     * @param top    current top slot content
     * @param middle current middle slot content
     * @param bottom current bottom slot content
     */
    public boolean allowInsert(int slot, SimpleStack stack, SimpleStack top, SimpleStack middle, SimpleStack bottom) {
        if (slot == InscriberLogic.SLOT_OUTPUT) {
            return true; // internal logic only; automation is blocked separately
        }
        if (slot == InscriberLogic.SLOT_BOTTOM) {
            bottom = stack;
        } else if (slot == InscriberLogic.SLOT_MIDDLE) {
            middle = stack;
        } else if (slot == InscriberLogic.SLOT_TOP) {
            top = stack;
        }
        for (var recipe : recipes) {
            if (middle != null && !recipe.middle().testItem(middle)) {
                continue;
            }
            if (bottom == null && top == null) {
                return true;
            } else if (bottom == null) {
                if (testOptionalItem(recipe.top(), top) || testOptionalItem(recipe.bottom(), top)) {
                    return true;
                }
            } else if (top == null) {
                if (testOptionalItem(recipe.bottom(), bottom) || testOptionalItem(recipe.top(), bottom)) {
                    return true;
                }
            } else {
                if (testOptionalItem(recipe.top(), top) && testOptionalItem(recipe.bottom(), bottom)
                        || testOptionalItem(recipe.bottom(), top) && testOptionalItem(recipe.top(), bottom)) {
                    return true;
                }
            }
        }
        return false;
    }
}
