/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import de.fellurion.ae2.core.automation.ConfigInventory;
import de.fellurion.ae2.core.automation.ExportBusLogic;
import de.fellurion.ae2.core.automation.StorageBusLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.tools.MemoryCardSettings.CardStock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCardSettingsTest {

    /** A player's cards: counts what it holds, so nothing can appear or vanish unnoticed. */
    private static final class Stock implements CardStock {
        final Map<UpgradeCard, Integer> cards = new EnumMap<>(UpgradeCard.class);

        Stock with(UpgradeCard card, int n) {
            cards.put(card, n);
            return this;
        }

        int of(UpgradeCard card) {
            return cards.getOrDefault(card, 0);
        }

        @Override
        public int take(UpgradeCard card, int amount) {
            int n = Math.min(amount, of(card));
            cards.put(card, of(card) - n);
            return n;
        }

        @Override
        public void give(UpgradeCard card, int amount) {
            cards.merge(card, amount, Integer::sum);
        }
    }

    private static int installed(UpgradeInventory inv, UpgradeCard card) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            n += inv.get(i) == card ? 1 : 0;
        }
        return n;
    }

    private static Map<String, Integer> cards(Object... idAndCount) {
        var map = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < idAndCount.length; i += 2) {
            map.put(((UpgradeCard) idAndCount[i]).itemId, (Integer) idAndCount[i + 1]);
        }
        return map;
    }

    @Test
    void jsonRoundTripKeepsEveryPart() {
        var config = ConfigInventory.configTypes(4);
        config.setKey(0, AEItemKey.of("Rock_Stone"));
        config.setKey(2, AEItemKey.of("Soil_Dirt", "{\"d\":1.0}"));
        var settings = new LinkedHashMap<String, String>();
        settings.put("fuzzy_mode", "PERCENT_99");
        settings.put("craft_only", "YES");
        var s = new MemoryCardSettings().upgrades(cards(UpgradeCard.SPEED, 2, UpgradeCard.FUZZY, 1)).settings(settings)
                .priority(-7).config(config.toJsonList());
        assertFalse(s.isEmpty());
        var back = MemoryCardSettings.fromJson(s.toJson());
        assertEquals(s.upgrades(), back.upgrades());
        assertEquals(settings, back.settings());
        assertEquals(-7, back.priority());
        var target = ConfigInventory.configTypes(4);
        target.setKey(1, AEItemKey.of("Wood_Oak"));
        MemoryCardSettings.readConfig(target, back.config());
        assertEquals(config.toJsonList(), target.toJsonList());
        assertEquals(s.toJson(), back.toJson());
    }

    @Test
    void absentPartsStayAbsentAndForeignPayloadsYieldNothing() {
        assertEquals("{}", new MemoryCardSettings().toJson());
        assertTrue(MemoryCardSettings.fromJson("{}").isEmpty());
        assertTrue(MemoryCardSettings.fromJson(null).isEmpty());
        assertTrue(MemoryCardSettings.fromJson("  ").isEmpty());
        assertTrue(MemoryCardSettings.fromJson("not json at all").isEmpty());
        assertTrue(MemoryCardSettings.fromJson("[1,2]").isEmpty());
        assertTrue(MemoryCardSettings.fromJson("{\"freq\":123,\"type\":\"me\"}").isEmpty(), "another device's format");
        var odd = MemoryCardSettings.fromJson("{\"upgrades\":[1],\"settings\":5,\"priority\":\"x\",\"config\":{}}");
        assertTrue(odd.isEmpty(), "parts of the wrong shape are ignored");
        var some = MemoryCardSettings.fromJson("{\"upgrades\":{\"AE2_Speed_Card\":0,\"AE2_Fuzzy_Card\":\"x\"},"
                + "\"settings\":{\"a\":1,\"b\":\"B\"},\"priority\":3}");
        assertTrue(some.upgrades().isEmpty(), "a device without installed cards still has the part");
        assertEquals(Map.of("b", "B"), some.settings());
        assertEquals(3, some.priority());
        assertNull(some.config());
    }

    @Test
    void storeUpgradesSumsCards() {
        var inv = new UpgradeInventory(ExportBusLogic.ITEM_ID, 4);
        assertTrue(MemoryCardSettings.storeUpgrades(inv).isEmpty());
        inv.insert(UpgradeCard.SPEED);
        inv.insert(UpgradeCard.FUZZY);
        inv.insert(UpgradeCard.SPEED);
        assertEquals(cards(UpgradeCard.SPEED, 2, UpgradeCard.FUZZY, 1), MemoryCardSettings.storeUpgrades(inv));
    }

    @Test
    void restoreTakesMissingCardsFromTheStockAndNothingElse() {
        var inv = new UpgradeInventory(ExportBusLogic.ITEM_ID, 4);
        var stock = new Stock().with(UpgradeCard.SPEED, 5).with(UpgradeCard.FUZZY, 1).with(UpgradeCard.REDSTONE, 9);
        var missing = MemoryCardSettings.restoreUpgrades(inv, cards(UpgradeCard.SPEED, 2, UpgradeCard.FUZZY, 1), stock);
        assertTrue(missing.isEmpty());
        assertEquals(2, installed(inv, UpgradeCard.SPEED));
        assertEquals(1, installed(inv, UpgradeCard.FUZZY));
        assertEquals(3, stock.of(UpgradeCard.SPEED));
        assertEquals(0, stock.of(UpgradeCard.FUZZY));
        assertEquals(9, stock.of(UpgradeCard.REDSTONE), "untouched");
        // a second time changes nothing
        assertTrue(MemoryCardSettings.restoreUpgrades(inv, cards(UpgradeCard.SPEED, 2, UpgradeCard.FUZZY, 1), stock)
                .isEmpty());
        assertEquals(3, stock.of(UpgradeCard.SPEED));
    }

    @Test
    void restoreReportsWhatThePlayerDoesNotHave() {
        var inv = new UpgradeInventory(ExportBusLogic.ITEM_ID, 4);
        var stock = new Stock().with(UpgradeCard.SPEED, 1);
        var missing = MemoryCardSettings.restoreUpgrades(inv,
                cards(UpgradeCard.SPEED, 3, UpgradeCard.FUZZY, 1), stock);
        assertEquals(Map.of(UpgradeCard.SPEED, 2, UpgradeCard.FUZZY, 1), missing);
        assertEquals(1, installed(inv, UpgradeCard.SPEED));
        assertEquals(0, installed(inv, UpgradeCard.FUZZY));
        assertEquals(0, stock.of(UpgradeCard.SPEED));
    }

    @Test
    void restoreGivesBackCardsTheTargetHasTooManyOf() {
        var inv = new UpgradeInventory(ExportBusLogic.ITEM_ID, 4);
        inv.insert(UpgradeCard.SPEED);
        inv.insert(UpgradeCard.SPEED);
        inv.insert(UpgradeCard.SPEED);
        inv.insert(UpgradeCard.REDSTONE);
        var stock = new Stock().with(UpgradeCard.FUZZY, 4);
        var missing = MemoryCardSettings.restoreUpgrades(inv, cards(UpgradeCard.SPEED, 1, UpgradeCard.FUZZY, 1), stock);
        assertTrue(missing.isEmpty());
        assertEquals(1, installed(inv, UpgradeCard.SPEED));
        assertEquals(0, installed(inv, UpgradeCard.REDSTONE));
        assertEquals(1, installed(inv, UpgradeCard.FUZZY));
        assertEquals(2, stock.of(UpgradeCard.SPEED));
        assertEquals(1, stock.of(UpgradeCard.REDSTONE));
        assertEquals(3, stock.of(UpgradeCard.FUZZY));
        // an empty card clears the slots
        assertTrue(MemoryCardSettings.restoreUpgrades(inv, Map.of(), stock).isEmpty());
        assertEquals(0, installed(inv, UpgradeCard.SPEED) + installed(inv, UpgradeCard.FUZZY));
        assertEquals(3, stock.of(UpgradeCard.SPEED));
        assertEquals(4, stock.of(UpgradeCard.FUZZY));
    }

    @Test
    void restoreInstallsOnlyWhatTheTargetAccepts() {
        // the storage bus takes no acceleration cards and at most one fuzzy card
        var inv = new UpgradeInventory(StorageBusLogic.ITEM_ID, 5);
        var stock = new Stock().with(UpgradeCard.SPEED, 4).with(UpgradeCard.FUZZY, 4).with(UpgradeCard.CAPACITY, 9);
        var desired = cards(UpgradeCard.SPEED, 4, UpgradeCard.FUZZY, 3, UpgradeCard.CAPACITY, 5);
        var missing = MemoryCardSettings.restoreUpgrades(inv, desired, stock);
        assertTrue(missing.isEmpty(), "cards that do not fit are not reported as missing");
        assertEquals(0, installed(inv, UpgradeCard.SPEED));
        assertEquals(1, installed(inv, UpgradeCard.FUZZY));
        assertEquals(4, installed(inv, UpgradeCard.CAPACITY), "the remaining four slots");
        assertEquals(4, stock.of(UpgradeCard.SPEED));
        assertEquals(3, stock.of(UpgradeCard.FUZZY));
        assertEquals(5, stock.of(UpgradeCard.CAPACITY));
    }

    @Test
    void restoreNeverLosesOrCreatesCards() {
        var inv = new UpgradeInventory(ExportBusLogic.ITEM_ID, 4);
        inv.insert(UpgradeCard.REDSTONE);
        inv.insert(UpgradeCard.CRAFTING);
        var stock = new Stock().with(UpgradeCard.SPEED, 2).with(UpgradeCard.CAPACITY, 1);
        int before = 2 + 3;
        // a stock that hands out more than asked must not make cards appear either
        CardStock generous = new CardStock() {
            @Override
            public int take(UpgradeCard card, int amount) {
                return stock.take(card, amount);
            }

            @Override
            public void give(UpgradeCard card, int amount) {
                stock.give(card, amount);
            }
        };
        MemoryCardSettings.restoreUpgrades(inv, cards(UpgradeCard.SPEED, 4, UpgradeCard.CAPACITY, 2,
                UpgradeCard.FUZZY, 1), generous);
        int after = 0;
        for (UpgradeCard c : UpgradeCard.values()) {
            after += installed(inv, c) + stock.of(c);
        }
        assertEquals(before, after);
        assertEquals(2, installed(inv, UpgradeCard.SPEED));
        var unknown = new LinkedHashMap<String, Integer>();
        unknown.put("Rock_Stone", 3);
        unknown.put(UpgradeCard.SPEED.itemId, 2);
        assertTrue(MemoryCardSettings.restoreUpgrades(inv, unknown, stock).isEmpty(), "unknown ids are no cards");
    }

    @Test
    void readConfigNormalizesToTheTargetAndClearsTheRest() {
        var stock = ConfigInventory.configStacks(3);
        stock.setStack(0, new GenericStack(AEItemKey.of("Rock_Stone"), 32));
        stock.setStack(2, new GenericStack(AEItemKey.of("Soil_Dirt"), 5));
        // amounts -> filter: types only
        var filter = ConfigInventory.configTypes(5);
        filter.setKey(1, AEItemKey.of("Wood_Oak"));
        filter.setKey(4, AEItemKey.of("Wood_Oak"));
        MemoryCardSettings.readConfig(filter, stock.toJsonList());
        assertEquals(AEItemKey.of("Rock_Stone"), filter.getKey(0));
        assertEquals(0, filter.getAmount(0));
        assertNull(filter.getKey(1));
        assertEquals(AEItemKey.of("Soil_Dirt"), filter.getKey(2));
        assertNull(filter.getKey(4), "slots beyond the list are cleared");
        // filter -> amounts: at least one, also over an occupied slot
        var target = ConfigInventory.configStacks(2);
        target.setStack(0, new GenericStack(AEItemKey.of("Wood_Oak"), 9));
        MemoryCardSettings.readConfig(target, filter.toJsonList());
        assertEquals(new GenericStack(AEItemKey.of("Rock_Stone"), 1), target.getStack(0));
        assertNull(target.getStack(1));
        // broken entries leave their slot empty
        List<Object> broken = new ArrayList<>();
        broken.add("x");
        broken.add(Map.of("t", "zzz", "id", "Rock_Stone"));
        MemoryCardSettings.readConfig(target, broken);
        assertTrue(target.isEmpty());
    }

    @Test
    void settingHelpersFollowAe2Names() {
        assertEquals("YES", MemoryCardSettings.yesNo(true));
        assertEquals("NO", MemoryCardSettings.yesNo(false));
        var in = Map.of("fuzzy_mode", "PERCENT_25", "craft_only", "YES", "bad", "nope", "flag", "maybe");
        FuzzyMode[] mode = {null};
        assertTrue(MemoryCardSettings.importEnum(in, "fuzzy_mode", FuzzyMode.class, m -> mode[0] = m));
        assertEquals(FuzzyMode.PERCENT_25, mode[0]);
        assertFalse(MemoryCardSettings.importEnum(in, "bad", FuzzyMode.class, m -> mode[0] = m), "unknown value");
        assertFalse(MemoryCardSettings.importEnum(in, "absent", FuzzyMode.class, m -> mode[0] = m));
        assertFalse(MemoryCardSettings.importEnum(null, "fuzzy_mode", FuzzyMode.class, m -> mode[0] = m));
        assertEquals(FuzzyMode.PERCENT_25, mode[0]);
        Boolean[] flag = {null};
        assertTrue(MemoryCardSettings.importYesNo(in, "craft_only", v -> flag[0] = v));
        assertEquals(Boolean.TRUE, flag[0]);
        assertFalse(MemoryCardSettings.importYesNo(in, "flag", v -> flag[0] = v));
        assertFalse(MemoryCardSettings.importYesNo(in, "absent", v -> flag[0] = v));
        assertEquals(Boolean.TRUE, flag[0]);
        assertEquals("upgrades", MemoryCardSettings.Part.UPGRADES.id());
        assertEquals("config_inv", MemoryCardSettings.Part.CONFIG_INV.id());
    }
}
