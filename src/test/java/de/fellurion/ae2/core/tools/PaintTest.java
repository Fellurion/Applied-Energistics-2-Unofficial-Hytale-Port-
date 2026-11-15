/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.grid.CableType;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Color Applicator: stored paint, colour cycling and the ids of everything coloured (AE2: ColorApplicatorItem). */
class PaintTest {

    @Test
    void textFormRoundTrips() {
        var paint = PaintStorage.parse("red:300, white:12,fluix:5,nonsense:9,blue:x,green:0");
        assertEquals("white:12,red:300,fluix:5", paint.format(), "in the order of AEColor");
        assertEquals(317, paint.total());
        assertEquals(300, paint.get(AEColor.RED));
        assertEquals(5, paint.get(AEColor.TRANSPARENT));
        assertEquals(0, paint.get(AEColor.GREEN));
        assertNull(PaintStorage.parse(null).format());
        assertTrue(PaintStorage.parse("").isEmpty());
    }

    @Test
    void capacityFollowsTheCellMaths() {
        var paint = new PaintStorage();
        // one colour: (2048 − 32) bytes · 8
        assertEquals(16128, paint.room(AEColor.RED));
        assertEquals(16128, paint.add(AEColor.RED, 20_000));
        assertEquals(0, paint.room(AEColor.RED));
        assertEquals(0, paint.add(AEColor.BLUE, 1), "no bytes left for a second colour");

        var two = new PaintStorage();
        two.add(AEColor.RED, 100);
        assertEquals((2048 - 64) * 8 - 100, two.room(AEColor.BLUE), "a new colour costs 32 bytes");
        assertEquals((2048 - 32) * 8 - 100, two.room(AEColor.RED));
    }

    @Test
    void consumingTheLastUseRemovesTheColour() {
        var paint = PaintStorage.parse("red:2");
        assertTrue(paint.consume(AEColor.RED));
        assertTrue(paint.consume(AEColor.RED));
        assertFalse(paint.consume(AEColor.RED));
        assertTrue(paint.isEmpty());
    }

    @Test
    void coloursCycleInAe2Order() {
        var paint = PaintStorage.parse("green:1,white:1,red:1");
        assertEquals(AEColor.WHITE, paint.active(null), "nothing selected: the first colour");
        assertEquals(AEColor.RED, paint.active(AEColor.RED));
        assertEquals(AEColor.RED, paint.next(AEColor.WHITE, 1));
        assertEquals(AEColor.GREEN, paint.next(AEColor.RED, 1));
        assertEquals(AEColor.WHITE, paint.next(AEColor.GREEN, 1), "wraps around");
        assertEquals(AEColor.GREEN, paint.next(AEColor.WHITE, -1));
        assertEquals(AEColor.WHITE, paint.next(null, 1));
        assertNull(new PaintStorage().next(AEColor.RED, 1));
        assertNull(new PaintStorage().active(null));
        assertEquals(AEColor.RED, PaintStorage.parse("red:1").next(AEColor.RED, 1), "a single colour stays");
    }

    @Test
    void paintBallsAndTheCleaningItemCarryAColour() {
        assertEquals(AEColor.RED, PaintItems.paintColor("AE2_Red_Paint_Ball"));
        assertEquals(AEColor.LIGHT_GRAY, PaintItems.paintColor("AE2_Light_Gray_Paint_Ball"));
        assertEquals(AEColor.LIGHT_BLUE, PaintItems.paintColor("AE2_Light_Blue_Lumen_Paint_Ball"));
        assertTrue(PaintItems.isLumen("AE2_Light_Blue_Lumen_Paint_Ball"));
        assertFalse(PaintItems.isLumen("AE2_Light_Blue_Paint_Ball"));
        assertEquals(AEColor.TRANSPARENT, PaintItems.paintColor("Soil_Snow"));
        assertNull(PaintItems.paintColor("AE2_Fluix_Paint_Ball"), "there is no fluix paint ball");
        assertNull(PaintItems.paintColor("AE2_Matter_Ball"));
        assertNull(PaintItems.paintColor("Plant_Petals_Red"), "dyes are no paint here");
        assertNull(PaintItems.paintColor(null));
        assertEquals("AE2_Light_Gray_Paint_Ball", PaintItems.paintBall(AEColor.LIGHT_GRAY));
        assertNull(PaintItems.paintBall(AEColor.TRANSPARENT));
    }

    @Test
    void cablesAreRecolouredByTheirId() {
        assertEquals("AE2_Red_Glass_Cable", PaintItems.recoloredCable("AE2_Fluix_Glass_Cable", AEColor.RED));
        assertEquals("AE2_Fluix_Smart_Dense_Cable", PaintItems.recoloredCable("AE2_Light_Blue_Smart_Dense_Cable", AEColor.TRANSPARENT));
        assertEquals("AE2_Light_Gray_Covered_Dense_Cable", PaintItems.recoloredCable("AE2_Gray_Covered_Dense_Cable", AEColor.LIGHT_GRAY));
        assertNull(PaintItems.recoloredCable("AE2_Red_Glass_Cable", AEColor.RED), "already red");
        assertNull(PaintItems.recoloredCable("AE2_Cable_Anchor", AEColor.RED));
        assertNull(PaintItems.recoloredCable("AE2_Red_Quartz_Cable", AEColor.BLUE));
        assertNull(PaintItems.recoloredCable(null, AEColor.BLUE));
        assertEquals(AEColor.LIGHT_BLUE, PaintItems.cableColor("AE2_Light_Blue_Covered_Cable"));
        assertEquals(CableType.COVERED, PaintItems.cableType("AE2_Light_Blue_Covered_Cable"));
        assertEquals(CableType.SMART_DENSE, PaintItems.cableType("AE2_Blue_Smart_Dense_Cable"));
    }

    @Test
    void everyCableOfEveryColourIsAShippedItem() throws IOException {
        String items = EntropyRecipesTest.resource("items.json");
        for (var color : AEColor.values()) {
            for (var type : CableType.values()) {
                String id = PaintItems.cable(color, type);
                assertTrue(items.contains("\"" + id + "\""), id);
                assertEquals(color, PaintItems.cableColor(id), id);
                assertEquals(type, PaintItems.cableType(id), id);
            }
            if (color != AEColor.TRANSPARENT) {
                assertTrue(items.contains("\"" + PaintItems.paintBall(color) + "\""), PaintItems.paintBall(color));
            }
        }
    }
}
