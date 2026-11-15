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
 * The name press (renaming the middle item) is the one job that is no registered recipe, see
 * {@link #findNamePressRecipe}. Item names live in the integration layer's metadata, so reading the name of a press
 * and writing it to an item is delegated to a {@link NameHandler}; without one the name press does nothing.
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
        var namePress = findNamePressRecipe(input, plateA, plateB);
        if (namePress != null) {
            return namePress;
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
     * {@code InscriberBlockEntity.BaseFilter.allowInsert}.
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
        if (allowInsertForNamePress(slot, stack, top, bottom)) {
            return true;
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

    // ------------------------------------------------------------------------------------------------------------
    // Name press (AE2: InscriberRecipes.findRecipe with supportNamePress, makeNamePressRecipe, BaseFilter)

    /** Item id of the name press. */
    public static final String NAME_PRESS_ITEM_ID = "AE2_Name_Press";
    /** Id of the recipe object {@link #findNamePressRecipe} creates. */
    public static final String NAME_PRESS_RECIPE_ID = "ae2:name_press";

    /**
     * Item names for the name press. Implemented by the integration layer, which knows how a press stores its name
     * and how an item carries a custom name.
     */
    public interface NameHandler {
        /** The name inscribed on a name press, or null for a blank press. */
        String nameOf(SimpleStack press);

        /**
         * A single item like {@code item} (same item, same other metadata, count 1) with the custom name
         * {@code name}; {@code name == null} removes the custom name.
         */
        SimpleStack rename(SimpleStack item, String name);
    }

    private static volatile NameHandler nameHandler;

    /** Enables the name press (null disables it again). */
    public static void setNameHandler(NameHandler handler) {
        nameHandler = handler;
    }

    public static boolean isNamePress(SimpleStack stack) {
        return stack != null && stack.is(NAME_PRESS_ITEM_ID);
    }

    public static boolean isNamePressRecipe(InscriberRecipe recipe) {
        return recipe != null && NAME_PRESS_RECIPE_ID.equals(recipe.id());
    }

    /** The presses of a name press job in AE2's order (first name, second name), or null if it is no such job. */
    private static SimpleStack[] namePresses(SimpleStack plateA, SimpleStack plateB) {
        boolean isNameA = isNamePress(plateA);
        boolean isNameB = isNamePress(plateB);
        if (isNameA && isNameB || isNameA && plateB == null) {
            return new SimpleStack[] {plateA, plateB};
        } else if (plateA == null && isNameB) {
            return new SimpleStack[] {plateB, plateA};
        }
        return null;
    }

    /**
     * Port of the name press branch of {@code InscriberRecipes.findRecipe}: with a name press on top and/or bottom
     * (and nothing else there) the inscriber renames the middle item. The returned recipe keeps the presses
     * ({@link InscriberRecipe.ProcessType#INSCRIBE}) and consumes one middle item; its {@code output()} only names
     * the item, the real result with the item's metadata and the new name is {@link #namePressResult}.
     *
     * @return the job, or null if the plates are no name press combination or no {@link NameHandler} is set
     */
    public InscriberRecipe findNamePressRecipe(SimpleStack input, SimpleStack plateA, SimpleStack plateB) {
        if (input == null || nameHandler == null) {
            return null;
        }
        var presses = namePresses(plateA, plateB);
        if (presses == null) {
            return null;
        }
        return new InscriberRecipe(NAME_PRESS_RECIPE_ID, InscriberRecipe.ProcessType.INSCRIBE,
                Ingredient.of(presses[0].itemId()), Ingredient.of(input.itemId()),
                presses[1] == null ? null : Ingredient.of(presses[1].itemId()), Ingredient.of(input.itemId()));
    }

    /**
     * Port of {@code InscriberRecipes.makeNamePressRecipe}: one middle item renamed to the name of the press, or to
     * both names joined by a space when both plates are name presses. Blank presses remove the custom name.
     *
     * @return the renamed item (count 1), or null if this is no name press job
     */
    public static SimpleStack namePressResult(SimpleStack input, SimpleStack plateA, SimpleStack plateB) {
        var handler = nameHandler;
        var presses = namePresses(plateA, plateB);
        if (input == null || handler == null || presses == null) {
            return null;
        }
        String name = null;
        for (var press : presses) {
            String pressName = press == null ? null : handler.nameOf(press);
            if (pressName != null) {
                name = name == null ? pressName : name + " " + pressName;
            }
        }
        return handler.rename(input, name);
    }

    /**
     * The name press rules of {@code BaseFilter.allowInsert}: a name press always fits into the top and bottom slot,
     * and with a name press there anything can be renamed.
     */
    private static boolean allowInsertForNamePress(int slot, SimpleStack stack, SimpleStack top, SimpleStack bottom) {
        if (nameHandler == null) {
            return false;
        }
        if (slot == InscriberLogic.SLOT_TOP || slot == InscriberLogic.SLOT_BOTTOM) {
            return isNamePress(stack);
        }
        return slot == InscriberLogic.SLOT_MIDDLE && (isNamePress(top) || isNamePress(bottom));
    }
}
