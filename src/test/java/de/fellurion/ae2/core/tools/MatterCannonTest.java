/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Matter Cannon: ammo weights and the shot maths (AE2: MatterCannonItem). */
class MatterCannonTest {

    @Test
    void shippedAmmoIsRead() throws IOException {
        var ammo = MatterCannonAmmo.fromJson(EntropyRecipesTest.resource("matter_cannon.json"));
        assertEquals(7, ammo.getEntries().size());
        assertEquals(32.0f, ammo.penetration("AE2_Matter_Ball"));
        assertEquals(196.96655f, ammo.penetration("Ingredient_Bar_Gold"));
        assertEquals(55.845f, ammo.penetration("Ingredient_Bar_Iron"));
        assertEquals(0f, ammo.penetration("AE2_Red_Paint_Ball"), "paint balls have no weight");
        assertEquals(0f, ammo.penetration("Rock_Stone"));
        assertEquals(0f, ammo.penetration(null));
    }

    @Test
    void entriesWithoutWeightOrItemAreSkipped() {
        var ammo = MatterCannonAmmo.fromJson("""
                [{"ammo": {"ItemId": "A"}, "weight": 0}, {"ammo": {}, "weight": 5}, {"weight": 5},
                 {"ammo": {"ItemId": "B"}, "weight": 12.5}]
                """);
        assertEquals(1, ammo.getEntries().size());
        assertEquals(12.5f, ammo.penetration("B"));
    }

    @Test
    void shotPowerIsLimitedByCardsAmmoAndEnergy() {
        assertEquals(1, MatterCannonLogic.shotPower(0, 64, 200_000));
        assertEquals(5, MatterCannonLogic.shotPower(4, 64, 200_000), "1 + speed cards");
        assertEquals(2, MatterCannonLogic.shotPower(4, 2, 200_000), "not more than the ammo left");
        assertEquals(3, MatterCannonLogic.shotPower(4, 64, 3 * 1600 + 1599), "not more than the energy pays for");
        assertEquals(1, MatterCannonLogic.shotPower(0, 1, 1600), "exactly one shot's worth is enough (AE2: < 1600 fails)");
        assertEquals(0, MatterCannonLogic.shotPower(4, 64, 1599.9));
        assertEquals(0, MatterCannonLogic.shotPower(0, 0, 200_000));
        assertEquals(4800.0, MatterCannonLogic.energyFor(3));
    }

    @Test
    void damageIsAFractionOfThePenetration() {
        assertEquals(2, MatterCannonLogic.damageFromPenetration(32f), "matter ball: ceil(32 / 20)");
        assertEquals(10, MatterCannonLogic.damageFromPenetration(196.96655f), "gold");
        assertEquals(1, MatterCannonLogic.damageFromPenetration(0.5f));
        assertEquals(30f, MatterCannonLogic.afterEntity(32f));
    }

    @Test
    void aShotBreaksBlocksUntilItsPenetrationIsUsedUp() {
        float stone = MatterCannonLogic.hardnessFromGatherPower(0.035f);
        float soil = MatterCannonLogic.hardnessFromGatherPower(0.1f);
        assertEquals(0.5f, soil, 1e-6, "soil is as hard as Minecraft's dirt");
        assertEquals(1.4286f, stone, 1e-3, "rock is about as hard as Minecraft's stone (1.5)");
        assertEquals(50f, MatterCannonLogic.hardnessFromGatherPower(0.001f), 1e-3, "ore needs a tool");
        assertEquals(-1f, MatterCannonLogic.hardnessFromGatherPower(0f));

        // a matter ball goes through one block of stone and stops at the second
        float p = 32f;
        assertTrue(MatterCannonLogic.breaks(p, stone));
        p = MatterCannonLogic.afterBlock(p, stone);
        assertEquals((32f - stone * 9f) * 0.6f, p, 1e-4);
        assertFalse(MatterCannonLogic.breaks(p, stone), "11.5 left, stone costs 12.9");
        assertTrue(MatterCannonLogic.breaks(p, soil));

        // gold goes through four blocks of stone
        int broken = 0;
        p = 196.96655f;
        while (MatterCannonLogic.breaks(p, stone)) {
            p = MatterCannonLogic.afterBlock(p, stone);
            broken++;
        }
        assertEquals(4, broken);
        assertFalse(MatterCannonLogic.breaks(196.96655f, 50f), "nothing shoots through ore");
        assertFalse(MatterCannonLogic.breaks(1000f, -1f), "indestructible");
        assertTrue(MatterCannonLogic.breaks(0.1f, 0f), "plants cost nothing");
    }

    @Test
    void rayHitsBoxesInFront() {
        // box from (2,0,-0.5) to (3,2,0.5), ray along +x at height 1
        assertEquals(2.0, MatterCannonLogic.rayBox(0, 1, 0, 1, 0, 0, 2, 0, -0.5, 3, 2, 0.5, 100), 1e-9);
        assertEquals(-1, MatterCannonLogic.rayBox(0, 1, 0, -1, 0, 0, 2, 0, -0.5, 3, 2, 0.5, 100), "behind the shooter");
        assertEquals(-1, MatterCannonLogic.rayBox(0, 3, 0, 1, 0, 0, 2, 0, -0.5, 3, 2, 0.5, 100), "passes above");
        assertEquals(-1, MatterCannonLogic.rayBox(0, 1, 0, 1, 0, 0, 2, 0, -0.5, 3, 2, 0.5, 1.5), "out of range");
        assertEquals(0.0, MatterCannonLogic.rayBox(2.5, 1, 0, 1, 0, 0, 2, 0, -0.5, 3, 2, 0.5, 100), 1e-9, "starts inside");
        double diagonal = MatterCannonLogic.rayBox(0, 0, 0, Math.sqrt(0.5), Math.sqrt(0.5), 0, 1, 1, -1, 2, 2, 1, 100);
        assertEquals(Math.sqrt(2), diagonal, 1e-9);
    }

    @Test
    void theCannonHoldsWhatAe2sCellHolds() {
        assertEquals(4032, MatterCannonLogic.AMMO_CAPACITY, "512 bytes, 8 for the one type, 8 items per byte");
    }
}
