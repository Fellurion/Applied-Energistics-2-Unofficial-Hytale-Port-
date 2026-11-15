/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import de.fellurion.ae2.core.tools.EntropyRecipes.Mode;
import de.fellurion.ae2.core.tools.EntropyRecipes.Recipe;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Entropy Manipulator recipes: the shipped file and the lookup (AE2: EntropyRecipe, findRecipe). */
class EntropyRecipesTest {

    static String resource(String name) throws IOException {
        try (var in = EntropyRecipesTest.class.getResourceAsStream("/ae2data/" + name)) {
            assertNotNull(in, "ae2data/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static EntropyRecipes shipped() throws IOException {
        return EntropyRecipes.fromJson(resource("entropy.json"));
    }

    @Test
    void shippedRecipesAreRead() throws IOException {
        var recipes = shipped();
        assertEquals(9, recipes.getRecipes().size());
        assertEquals(5, recipes.getRecipes().stream().filter(r -> r.mode() == Mode.COOL).count());
        assertEquals(4, recipes.getRecipes().stream().filter(r -> r.mode() == Mode.HEAT).count());
    }

    @Test
    void coolingStoneGivesCobbleAndHeatingReversesIt() throws IOException {
        var recipes = shipped();
        Recipe cool = recipes.find(Mode.COOL, "Rock_Stone", null);
        assertNotNull(cool);
        assertEquals("Rock_Stone_Cobble", cool.placedBlock());
        assertFalse(cool.removesBlock(), "the block is replaced, not removed");
        assertFalse(cool.removesFluid());
        Recipe heat = recipes.find(Mode.HEAT, "Rock_Stone_Cobble", null);
        assertNotNull(heat);
        assertEquals("Rock_Stone", heat.placedBlock());
        assertNull(recipes.find(Mode.HEAT, "Rock_Stone", null), "no heat recipe for stone");
        assertNull(recipes.find(Mode.COOL, "Rock_Stone_Cobble", null));
    }

    @Test
    void waterFreezesAndIceMelts() throws IOException {
        var recipes = shipped();
        Recipe freeze = recipes.find(Mode.COOL, EntropyRecipes.EMPTY, "Water_Source");
        assertNotNull(freeze);
        assertEquals("Rock_Ice", freeze.placedBlock());
        assertTrue(freeze.removesFluid(), "the ice takes the place of the water");
        Recipe melt = recipes.find(Mode.HEAT, "Rock_Ice", null);
        assertNotNull(melt);
        assertNull(melt.placedBlock());
        assertTrue(melt.removesBlock());
        assertEquals("Water_Source", melt.outputFluid());
        assertFalse(melt.removesFluid());
    }

    @Test
    void waterEvaporatesAndFlowingWaterGivesSnow() throws IOException {
        var recipes = shipped();
        Recipe evaporate = recipes.find(Mode.HEAT, null, "Water_Source");
        assertNotNull(evaporate);
        assertNull(evaporate.placedBlock(), "air is no block to place");
        assertFalse(evaporate.removesBlock(), "only the fluid goes");
        assertTrue(evaporate.removesFluid());
        assertTrue(evaporate.drops().isEmpty());

        Recipe snow = recipes.find(Mode.COOL, null, "Water");
        assertNotNull(snow);
        assertTrue(snow.removesFluid());
        assertEquals(1, snow.drops().size());
        assertEquals("Soil_Snow", snow.drops().getFirst().itemId());
        assertNull(recipes.find(Mode.HEAT, null, "Water"), "flowing water does not evaporate (AE2 has no such recipe)");
    }

    @Test
    void lavaCoolsToItsHytaleStone() throws IOException {
        Recipe r = shipped().find(Mode.COOL, null, "Lava_Source");
        assertNotNull(r);
        assertEquals("Rock_Magma_Cooled", r.placedBlock());
        assertNull(shipped().find(Mode.COOL, null, "Lava"), "only the source cools (AE2: lava, not flowing lava)");
    }

    @Test
    void aFluidRecipeIgnoresTheBlockInTheCellAndABlockRecipeIgnoresTheFluid() throws IOException {
        var recipes = shipped();
        // AE2 Input.matches: only the parts that are given are compared
        assertNotNull(recipes.find(Mode.COOL, "Plant_Seaweed", "Water_Source"));
        assertNotNull(recipes.find(Mode.COOL, "Soil_Grass", "Water"));
        assertEquals("Soil_Dirt", recipes.find(Mode.COOL, "Soil_Grass", null).placedBlock());
    }

    @Test
    void brokenEntriesAreSkipped() {
        var recipes = EntropyRecipes.fromJson("""
                [{"id": "a", "mode": "melt", "input": {"BlockId": "X"}, "output": {"BlockId": "Y"}},
                 {"id": "b", "mode": "heat", "input": {}, "output": {"BlockId": "Y"}},
                 {"id": "c", "mode": "HEAT", "input": {"BlockId": "X"}, "output": {"drops": [{"ItemId": "Z", "Quantity": 3}]}},
                 "nonsense"]
                """);
        assertEquals(1, recipes.getRecipes().size());
        Recipe c = recipes.getRecipes().getFirst();
        assertEquals("c", c.id());
        assertTrue(c.removesBlock(), "a block that only leaves drops is removed");
        assertEquals(3, c.drops().getFirst().quantity());
        assertEquals(0, EntropyRecipes.fromJson("{}").getRecipes().size());
    }
}
