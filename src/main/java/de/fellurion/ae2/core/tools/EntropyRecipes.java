/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import de.fellurion.ae2.core.machines.Ingredient;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Recipes of the Entropy Manipulator (heat / cool a block or fluid in the world). Port of
 * {@code appeng.recipes.entropy.EntropyRecipe}, {@code EntropyMode} and the lookup in
 * {@code appeng.items.tools.powered.EntropyManipulatorItem#findRecipe}.
 * <p>
 * File format ({@code ae2data/entropy.json}):
 * {@code {"id": "...", "mode": "heat|cool", "input": {"BlockId": "..."} | {"FluidId": "..."},
 * "output": {"BlockId": "..."} | {"FluidId": "..."} | {"drops": [{"ItemId": "...", "Quantity": n}]}}}.
 * {@code "BlockId": "Empty"} stands for air.
 * <p>
 * Deviations from AE2: block and fluid state properties ({@code properties}, {@code keep}) do not exist in Hytale and
 * are not read. In Hytale a cell holds a block <em>and</em> a fluid independently of each other; what a recipe does to
 * which of the two is described by {@link Recipe#removesBlock()} and {@link Recipe#removesFluid()}.
 */
public final class EntropyRecipes {

    /** Asset key of air. */
    public static final String EMPTY = "Empty";

    /** AE2: {@code EntropyMode}. */
    public enum Mode {
        HEAT, COOL;

        /** Parses {@code "heat"} / {@code "cool"} (case-insensitive); null for anything else. */
        public static Mode parse(String s) {
            if (s == null) {
                return null;
            }
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "heat" -> HEAT;
                case "cool" -> COOL;
                default -> null;
            };
        }
    }

    /**
     * One recipe.
     *
     * @param id          id from the file
     * @param mode        heat or cool
     * @param inputBlock  block that must be present, or null
     * @param inputFluid  fluid that must be present, or null
     * @param outputBlock block that replaces the input ({@link #EMPTY} = air), or null
     * @param outputFluid fluid that replaces the input, or null
     * @param drops       items dropped at the position
     */
    public record Recipe(String id, Mode mode, String inputBlock, String inputFluid, String outputBlock,
            String outputFluid, List<Ingredient> drops) {

        /**
         * AE2: {@code EntropyRecipe.Input#matches} – every part of the input that is given must be present.
         *
         * @param blockId block at the position, null or {@link #EMPTY} for none
         * @param fluidId fluid at the position, null for none
         */
        public boolean matches(Mode mode, String blockId, String fluidId) {
            if (this.mode != mode) {
                return false;
            }
            if (inputBlock != null && !inputBlock.equals(blockId)) {
                return false;
            }
            return inputFluid == null || inputFluid.equals(fluidId);
        }

        /** The block the recipe puts into the world, or null if it leaves no block (air, a fluid or only drops). */
        public String placedBlock() {
            return outputBlock == null || EMPTY.equals(outputBlock) ? null : outputBlock;
        }

        /**
         * True if the block at the position has to go: the recipe consumes a block and does not put another one
         * there. (AE2 sets the position to the output fluid or to air.)
         */
        public boolean removesBlock() {
            return inputBlock != null && placedBlock() == null;
        }

        /**
         * True if the fluid at the position has to go: the recipe consumes a fluid and does not put a fluid back.
         * (In AE2 the fluid is the block and is replaced as a whole.)
         */
        public boolean removesFluid() {
            return inputFluid != null && outputFluid == null;
        }
    }

    private final List<Recipe> recipes = new ArrayList<>();

    /** Loads the content of {@code ae2data/entropy.json}; unreadable entries are skipped. */
    public static EntropyRecipes fromJson(String json) {
        var result = new EntropyRecipes();
        if (MiniJson.parse(json) instanceof List<?> list) {
            for (var o : list) {
                if (o instanceof Map<?, ?> m) {
                    var r = parse(m);
                    if (r != null) {
                        result.add(r);
                    }
                }
            }
        }
        return result;
    }

    private static Recipe parse(Map<?, ?> m) {
        var mode = Mode.parse(MiniJson.getString(m, "mode", null));
        var in = MiniJson.getMap(m, "input");
        var out = MiniJson.getMap(m, "output");
        if (mode == null) {
            return null;
        }
        var inputBlock = MiniJson.getString(in, "BlockId", null);
        var inputFluid = MiniJson.getString(in, "FluidId", null);
        if (inputBlock == null && inputFluid == null) {
            return null;
        }
        var drops = new ArrayList<Ingredient>();
        for (var d : MiniJson.getList(out, "drops")) {
            try {
                var ing = Ingredient.fromJson(d);
                if (ing != null && ing.itemId() != null) {
                    drops.add(ing);
                }
            } catch (RuntimeException e) {
                return null; // neither ItemId nor ResourceTypeId
            }
        }
        return new Recipe(MiniJson.getString(m, "id", ""), mode, inputBlock, inputFluid,
                MiniJson.getString(out, "BlockId", null), MiniJson.getString(out, "FluidId", null), List.copyOf(drops));
    }

    public EntropyRecipes add(Recipe recipe) {
        recipes.add(recipe);
        return this;
    }

    public List<Recipe> getRecipes() {
        return Collections.unmodifiableList(recipes);
    }

    /**
     * First recipe for this mode and position, or null. AE2: {@code EntropyManipulatorItem#findRecipe}.
     *
     * @param blockId block at the position, null or {@link #EMPTY} for none
     * @param fluidId fluid at the position, null for none
     */
    public Recipe find(Mode mode, String blockId, String fluidId) {
        for (var r : recipes) {
            if (r.matches(mode, blockId, fluidId)) {
                return r;
            }
        }
        return null;
    }
}
