/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.cells;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IActionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte math and upgrade behaviour of {@link BasicCellInventory}, checked against AE2's formulas.
 */
class BasicCellInventoryTest {
    static final AEItemKey COBBLE = AEItemKey.of("Rock_Stone_Cobble");
    static final AEItemKey DIRT = AEItemKey.of("Soil_Dirt");
    static final IActionSource SRC = IActionSource.empty();

    @BeforeEach
    void setUp() {
        AEConfig.reset();
    }

    static BasicCellInventory cell(CellDefinition def, CellItemData data) {
        return BasicCellInventory.create(def, data, null);
    }

    static BasicCellInventory itemCell1k() {
        return cell(CellDefinition.itemCell(StorageCellTier.SIZE_1K), new CellItemData());
    }

    @Test
    void tierValuesMatchAE2() {
        var expectedBytes = new int[] { 1024, 4096, 16384, 65536, 262144 };
        var expectedPerType = new int[] { 8, 32, 128, 512, 2048 };
        var expectedDrain = new double[] { 0.5, 1.0, 1.5, 2.0, 2.5 };
        for (int i = 0; i < 5; i++) {
            var item = CellDefinition.ITEM_CELLS.get(i);
            assertEquals(expectedBytes[i], item.bytes());
            assertEquals(expectedPerType[i], item.bytesPerType());
            assertEquals(expectedDrain[i], item.idleDrain());
            assertEquals(63, item.totalTypes());
            assertEquals(18, CellDefinition.FLUID_CELLS.get(i).totalTypes());
            var portable = CellDefinition.PORTABLE_ITEM_CELLS.get(i);
            assertEquals(expectedBytes[i] / 2, portable.bytes());
            assertEquals(63 - (i + 1) * 9, portable.totalTypes());
        }
        assertEquals("AE2_Item_Storage_Cell_1k", CellDefinition.ITEM_CELLS.getFirst().itemId());
        assertSame(CellDefinition.ITEM_CELLS.get(4), CellDefinition.byItemId("AE2_Item_Storage_Cell_256k"));
        assertEquals(20000, CellDefinition.PORTABLE_ITEM_CELLS.getFirst().portableMaxPower(0));
        assertEquals(20000 * 17, CellDefinition.PORTABLE_ITEM_CELLS.getFirst().portableMaxPower(2));
    }

    @Test
    void oneKCellSingleTypeHolds8128Items() {
        var inv = itemCell1k();
        // (1024 bytes - 8 bytes for the type) * 8 items per byte
        assertEquals(8128, inv.insert(COBBLE, 100_000, Actionable.SIMULATE, SRC));
        assertEquals(8128, inv.insert(COBBLE, 100_000, Actionable.MODULATE, SRC));
        assertEquals(8128, inv.getStoredItemCount());
        assertEquals(1024, inv.getUsedBytes());
        assertEquals(0, inv.getFreeBytes());
        assertEquals(CellState.FULL, inv.getStatus());
        assertEquals(0, inv.insert(DIRT, 1, Actionable.SIMULATE, SRC));
    }

    @Test
    void oneKCellWith63TypesHolds4160Items() {
        var inv = itemCell1k();
        for (int i = 0; i < 63; i++) {
            assertEquals(1, inv.insert(AEItemKey.of("Item_" + i), 1, Actionable.MODULATE, SRC), "type " + i);
        }
        assertEquals(63, inv.getStoredItemTypes());
        assertEquals(0, inv.getRemainingItemTypes());
        assertEquals(CellState.TYPES_FULL, inv.getStatus());
        // a 64th type is rejected
        assertEquals(0, inv.insert(AEItemKey.of("Item_63"), 1, Actionable.SIMULATE, SRC));
        // (1024 - 63*8) * 8 = 4160 items in total
        long more = 0;
        for (int i = 0; i < 63; i++) {
            more += inv.insert(AEItemKey.of("Item_" + i), 10_000, Actionable.MODULATE, SRC);
        }
        assertEquals(4160, inv.getStoredItemCount());
        assertEquals(4160 - 63, more);
        assertEquals(CellState.FULL, inv.getStatus());
    }

    @Test
    void partialBytesAreRoundedUp() {
        var inv = itemCell1k();
        inv.insert(COBBLE, 1, Actionable.MODULATE, SRC);
        // 8 bytes for the type + 1 byte for 1..8 items
        assertEquals(9, inv.getUsedBytes());
        assertEquals(7, inv.getUnusedItemCount());
        assertEquals(CellState.NOT_EMPTY, inv.getStatus());
        inv.insert(COBBLE, 7, Actionable.MODULATE, SRC);
        assertEquals(9, inv.getUsedBytes());
        inv.insert(COBBLE, 1, Actionable.MODULATE, SRC);
        assertEquals(10, inv.getUsedBytes());
    }

    @Test
    void largerTiersAndFluids() {
        var c4k = cell(CellDefinition.itemCell(StorageCellTier.SIZE_4K), new CellItemData());
        assertEquals((4096 - 32) * 8, c4k.insert(COBBLE, Long.MAX_VALUE, Actionable.MODULATE, SRC));
        var c256k = cell(CellDefinition.itemCell(StorageCellTier.SIZE_256K), new CellItemData());
        assertEquals((262144L - 2048) * 8, c256k.insert(COBBLE, Long.MAX_VALUE, Actionable.MODULATE, SRC));

        var fluid = cell(CellDefinition.fluidCell(StorageCellTier.SIZE_1K), new CellItemData());
        var water = AEFluidKey.of("Water");
        assertEquals(0, fluid.insert(COBBLE, 1, Actionable.SIMULATE, SRC), "fluid cell rejects items");
        // 8 buckets per byte
        assertEquals((1024 - 8) * 8000L, fluid.insert(water, Long.MAX_VALUE, Actionable.MODULATE, SRC));
    }

    @Test
    void extractAndPersistRoundTrip() {
        var data = new CellItemData();
        var saves = new int[1];
        var inv = BasicCellInventory.create(CellDefinition.itemCell(StorageCellTier.SIZE_1K), data, () -> saves[0]++);
        inv.insert(COBBLE, 100, Actionable.MODULATE, SRC);
        inv.insert(DIRT, 5, Actionable.MODULATE, SRC);
        assertEquals(2, saves[0]);
        assertTrue(data.getContents().isEmpty(), "not persisted before persist()");
        assertEquals(30, inv.extract(COBBLE, 30, Actionable.MODULATE, SRC));
        assertEquals(5, inv.extract(DIRT, 50, Actionable.MODULATE, SRC));
        inv.persist();
        assertEquals(70L, data.getContents().get(COBBLE));
        assertNull(data.getContents().get(DIRT));

        var json = data.toJson();
        var restored = CellItemData.fromJson(json);
        var inv2 = cell(CellDefinition.itemCell(StorageCellTier.SIZE_1K), restored);
        assertEquals(70, inv2.getAvailableStacks().get(COBBLE));
        assertEquals(json, restored.toJson(), "serialization is canonical");
        assertEquals("{}", new CellItemData().toJson());
    }

    @Test
    void partitionWhitelistAndInverterBlacklist() {
        var data = new CellItemData().setConfig(List.of(COBBLE));
        var inv = cell(CellDefinition.itemCell(StorageCellTier.SIZE_1K), data);
        assertTrue(inv.isPreformatted());
        assertEquals(10, inv.insert(COBBLE, 10, Actionable.MODULATE, SRC));
        assertEquals(0, inv.insert(DIRT, 10, Actionable.MODULATE, SRC));

        var inverted = cell(CellDefinition.itemCell(StorageCellTier.SIZE_1K),
                new CellItemData().setConfig(List.of(COBBLE)).addUpgrade(UpgradeCard.INVERTER));
        assertEquals(0, inverted.insert(COBBLE, 10, Actionable.MODULATE, SRC));
        assertEquals(10, inverted.insert(DIRT, 10, Actionable.MODULATE, SRC));
    }

    @Test
    void fuzzyCardMatchesVariants() {
        var sword = AEItemKey.of("Weapon_Sword", "{\"dmg\":3}");
        var otherSword = AEItemKey.of("Weapon_Sword", "{\"dmg\":90}");
        var precise = cell(CellDefinition.itemCell(StorageCellTier.SIZE_1K), new CellItemData().setConfig(List.of(sword)));
        assertEquals(0, precise.insert(otherSword, 1, Actionable.SIMULATE, SRC));
        var fuzzy = cell(CellDefinition.itemCell(StorageCellTier.SIZE_1K),
                new CellItemData().setConfig(List.of(sword)).addUpgrade(UpgradeCard.FUZZY)
                        .setFuzzyMode(FuzzyMode.PERCENT_50));
        assertTrue(fuzzy.isFuzzy());
        // Without a durability resolver all variants of the same item match (like non-damageable items in AE2)
        assertEquals(1, fuzzy.insert(otherSword, 1, Actionable.SIMULATE, SRC));
        assertEquals(0, fuzzy.insert(COBBLE, 1, Actionable.SIMULATE, SRC));
    }

    @Test
    void fluidCellsDoNotAcceptFuzzyCardButItemCellsDo() {
        var itemCellUpgrades = new UpgradeInventory("AE2_Item_Storage_Cell_1k", 4);
        assertTrue(itemCellUpgrades.insert(UpgradeCard.FUZZY));
        assertFalse(itemCellUpgrades.insert(UpgradeCard.FUZZY), "max 1 fuzzy card");
        assertFalse(itemCellUpgrades.insert(UpgradeCard.SPEED), "cells don't take acceleration cards");
        var fluidCellUpgrades = new UpgradeInventory("AE2_Fluid_Storage_Cell_1k", 3);
        assertFalse(fluidCellUpgrades.insert(UpgradeCard.FUZZY));
        assertTrue(fluidCellUpgrades.insert(UpgradeCard.VOID));
    }

    @Test
    void voidCardDestroysOverflow() {
        var inv = cell(CellDefinition.itemCell(StorageCellTier.SIZE_1K), new CellItemData().addUpgrade(UpgradeCard.VOID));
        inv.insert(COBBLE, 8128, Actionable.MODULATE, SRC);
        assertEquals(CellState.FULL, inv.getStatus());
        // the stored type is voided
        assertEquals(500, inv.insert(COBBLE, 500, Actionable.MODULATE, SRC));
        assertEquals(8128, inv.getStoredItemCount());
        // an unformatted full cell does not void types it can't store
        assertEquals(0, inv.insert(DIRT, 500, Actionable.MODULATE, SRC));

        // a partitioned cell with a void card voids everything matching the partition
        var partitioned = cell(CellDefinition.itemCell(StorageCellTier.SIZE_1K),
                new CellItemData().setConfig(List.of(DIRT)).addUpgrade(UpgradeCard.VOID));
        partitioned.insert(COBBLE, 10, Actionable.MODULATE, SRC);
        assertEquals(0, partitioned.getStoredItemCount());
        assertEquals(10_000, partitioned.insert(DIRT, 10_000, Actionable.MODULATE, SRC));
        assertEquals(8128, partitioned.getStoredItemCount());
    }

    @Test
    void equalDistributionCard() {
        var data = new CellItemData().setConfig(List.of(COBBLE, DIRT)).addUpgrade(UpgradeCard.EQUAL_DISTRIBUTION);
        var inv = cell(CellDefinition.itemCell(StorageCellTier.SIZE_1K), data);
        // (1024 - 2*8) * 8 = 8064 → 4032 per type
        assertEquals(4032, inv.getMaxItemsPerType());
        assertEquals(4032, inv.insert(COBBLE, 100_000, Actionable.MODULATE, SRC));
        assertEquals(4032, inv.insert(DIRT, 100_000, Actionable.MODULATE, SRC));

        // unpartitioned: divided by the 63 type limit: (1024 - 504) * 8 / 63 rounded up = 67
        var free = cell(CellDefinition.itemCell(StorageCellTier.SIZE_1K),
                new CellItemData().addUpgrade(UpgradeCard.EQUAL_DISTRIBUTION));
        assertEquals(67, free.getMaxItemsPerType());
        assertEquals(67, free.insert(COBBLE, 1000, Actionable.MODULATE, SRC));
    }

    @Test
    void nonEmptyCellsCannotBeStoredInCells() {
        var inv = itemCell1k();
        var emptyCell = AEItemKey.of("AE2_Item_Storage_Cell_1k");
        assertEquals(1, inv.insert(emptyCell, 1, Actionable.SIMULATE, SRC));
        var fullData = new CellItemData();
        fullData.getContents().put(COBBLE, 5L);
        var fullCell = AEItemKey.of("AE2_Item_Storage_Cell_1k", fullData.toJson());
        assertEquals(0, inv.insert(fullCell, 1, Actionable.SIMULATE, SRC));
    }

    @Test
    void creativeCell() {
        var data = new CellItemData().setConfig(List.of(COBBLE));
        var inv = StorageCells.getCellInventory(CellDefinition.CREATIVE_CELL_ID, data, null);
        assertInstanceOf(CreativeCellInventory.class, inv);
        assertEquals(Integer.MAX_VALUE, inv.getAvailableStacks().get(COBBLE));
        assertEquals(1_000_000, inv.extract(COBBLE, 1_000_000, Actionable.MODULATE, SRC));
        assertEquals(5, inv.insert(COBBLE, 5, Actionable.MODULATE, SRC));
        assertEquals(0, inv.insert(DIRT, 5, Actionable.MODULATE, SRC));
        assertEquals(0, inv.getIdleDrain());
        assertNull(StorageCells.getCellInventory("Rock_Stone", data, null));
    }

    @Test
    void cellItemDataJsonFormat() {
        var data = new CellItemData();
        data.getContents().put(COBBLE, 128L);
        data.getContents().put(AEFluidKey.of("Water"), 5000L);
        data.setConfig(java.util.Arrays.asList((AEKey) COBBLE, null, DIRT));
        data.addUpgrade(UpgradeCard.VOID);
        data.setFuzzyMode(FuzzyMode.PERCENT_25);
        var json = data.toJson();
        assertEquals("{\"inv\":[{\"t\":\"i\",\"id\":\"Rock_Stone_Cobble\",\"a\":128},{\"t\":\"f\",\"id\":\"Water\",\"a\":5000}],"
                + "\"cfg\":[{\"t\":\"i\",\"id\":\"Rock_Stone_Cobble\"},null,{\"t\":\"i\",\"id\":\"Soil_Dirt\"}],"
                + "\"up\":[\"AE2_Void_Card\"],\"fz\":\"PERCENT_25\"}", json);
        var back = CellItemData.fromJson(json);
        assertEquals(json, back.toJson());
        assertEquals(List.of(COBBLE, DIRT), back.getConfigKeys());
    }
}
