/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import de.fellurion.ae2.core.inv.SimpleStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolboxInventoryTest {
    private static final String SPEED = "AE2_Speed_Card";
    private static final String FUZZY = "AE2_Fuzzy_Card";

    private static ToolboxInventory box() {
        return new ToolboxInventory(id -> 64);
    }

    private static int total(ToolboxInventory box) {
        int n = 0;
        for (int i = 0; i < ToolboxInventory.SLOTS; i++) {
            n += box.get(i) == null ? 0 : box.get(i).count();
        }
        return n;
    }

    @Test
    void onlyUpgradeCardsFit() {
        var box = box();
        assertTrue(ToolboxInventory.isUpgradeCard(SPEED));
        assertFalse(ToolboxInventory.isUpgradeCard("Rock_Stone"));
        assertFalse(ToolboxInventory.isUpgradeCard(null));
        assertEquals(0, box.insert(SimpleStack.of("Rock_Stone", 10)));
        assertEquals(0, box.insert(null));
        assertTrue(box.isEmpty());
        assertNull(box.toJson(), "an unused tool carries no metadata");
    }

    @Test
    void insertFillsExistingStacksFirstThenEmptySlots() {
        var box = box();
        assertEquals(60, box.insert(SimpleStack.of(SPEED, 60)));
        assertEquals(5, box.insert(SimpleStack.of(FUZZY, 5)));
        assertEquals(10, box.simulateInsert(SimpleStack.of(SPEED, 10)));
        assertEquals(60, box.get(0).count(), "simulation changes nothing");
        assertEquals(10, box.insert(SimpleStack.of(SPEED, 10)));
        assertEquals(64, box.get(0).count());
        assertEquals(FUZZY, box.get(1).itemId());
        assertEquals(SimpleStack.of(SPEED, 6), box.get(2));
        assertEquals(75, total(box));
    }

    @Test
    void aFullToolboxTakesOnlyWhatFits() {
        var box = new ToolboxInventory(id -> 2);
        assertEquals(18, box.insert(SimpleStack.of(SPEED, 30)), "9 slots of 2");
        assertEquals(0, box.insert(SimpleStack.of(FUZZY, 1)));
        assertEquals(0, box.simulateInsert(SimpleStack.of(SPEED, 1)));
        assertEquals(18, total(box));
    }

    @Test
    void extractMovesItemsOutAndPutBackRestoresThem() {
        var box = box();
        box.insert(SimpleStack.of(SPEED, 10));
        assertNull(box.extract(3, 1), "empty slot");
        assertNull(box.extract(0, 0));
        assertNull(box.extract(-1, 5));
        assertEquals(SimpleStack.of(SPEED, 4), box.extract(0, 4));
        assertEquals(6, box.get(0).count());
        var rest = box.extract(0, 99);
        assertEquals(SimpleStack.of(SPEED, 6), rest);
        assertNull(box.get(0));
        assertEquals(0, box.putBack(0, rest));
        assertEquals(SimpleStack.of(SPEED, 6), box.get(0));
        assertEquals(0, box.putBack(0, SimpleStack.of(SPEED, 2)));
        assertEquals(8, box.get(0).count());
        assertEquals(0, box.putBack(0, null));
    }

    @Test
    void removeCardsTakesPlainCardsAcrossSlots() {
        var box = new ToolboxInventory(id -> 4);
        box.insert(SimpleStack.of(SPEED, 6));
        box.insert(SimpleStack.of(FUZZY, 1));
        assertEquals(6, box.count(SPEED));
        assertEquals(5, box.removeCards(SPEED, 5));
        assertEquals(1, box.count(SPEED));
        assertEquals(1, box.removeCards(SPEED, 5), "only what is there");
        assertEquals(0, box.removeCards(SPEED, 5));
        assertEquals(0, box.removeCards(FUZZY, 0));
        assertEquals(0, box.removeCards(FUZZY, -3));
        assertEquals(1, box.count(FUZZY));
    }

    @Test
    void jsonRoundTripKeepsSlotsCountsAndMetadata() {
        var box = box();
        box.insert(SimpleStack.of(SPEED, 3));
        box.insert(new SimpleStack(FUZZY, "{\"m\":{\"x\":1}}", 2));
        box.insert(SimpleStack.of("AE2_Capacity_Card", 64));
        box.extract(0, 3); // leaves a gap in front
        String json = box.toJson();
        assertEquals("[null,{\"id\":\"AE2_Fuzzy_Card\",\"nbt\":\"{\\\"m\\\":{\\\"x\\\":1}}\",\"n\":2},"
                + "{\"id\":\"AE2_Capacity_Card\",\"n\":64}]", json);
        var back = ToolboxInventory.fromJson(json, id -> 64);
        for (int i = 0; i < ToolboxInventory.SLOTS; i++) {
            assertEquals(box.get(i), back.get(i), "slot " + i);
        }
        assertEquals(json, back.toJson());
        assertEquals(0, back.count(FUZZY), "cards with metadata are not plain cards");
    }

    @Test
    void brokenJsonYieldsAnEmptyToolbox() {
        assertTrue(ToolboxInventory.fromJson(null, id -> 64).isEmpty());
        assertTrue(ToolboxInventory.fromJson("", id -> 64).isEmpty());
        assertTrue(ToolboxInventory.fromJson("{not json", id -> 64).isEmpty());
        assertTrue(ToolboxInventory.fromJson("{\"id\":\"AE2_Speed_Card\"}", id -> 64).isEmpty());
        var partly = ToolboxInventory.fromJson("[{\"id\":\"AE2_Speed_Card\",\"n\":0},{\"n\":3},7,"
                + "{\"id\":\"AE2_Speed_Card\",\"n\":200}]", id -> 64);
        assertNull(partly.get(0));
        assertNull(partly.get(1));
        assertNull(partly.get(2));
        assertEquals(200, partly.get(3).count(), "stored counts are kept, never cut");
    }

    @Test
    void summarySumsPerCardLargestFirst() {
        var box = new ToolboxInventory(id -> 4);
        box.insert(SimpleStack.of(FUZZY, 2));
        box.insert(SimpleStack.of(SPEED, 7));
        assertEquals(List.of(SimpleStack.of(SPEED, 7), SimpleStack.of(FUZZY, 2)), box.summary());
        assertTrue(box().summary().isEmpty());
    }
}
