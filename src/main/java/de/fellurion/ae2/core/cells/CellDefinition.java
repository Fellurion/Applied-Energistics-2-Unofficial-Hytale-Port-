/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.cells;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.stacks.AEKeyType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static properties of a storage cell item type.
 * <p>
 * Ports {@code appeng.items.storage.BasicStorageCell} and {@code appeng.items.tools.powered.PortableCellItem} with the
 * values from {@code AEItems}:
 * <ul>
 * <li>Item cells: 1k/4k/16k/64k/256k = 1024/4096/16384/65536/262144 bytes, 8/32/128/512/2048 bytes per type, 63
 * types, idle drain 0.5/1.0/1.5/2.0/2.5 AE/t, 4 upgrade slots.</li>
 * <li>Fluid cells: same bytes, 18 types, 3 upgrade slots.</li>
 * <li>Portable item cells: half the bytes, bytes/128 per type, 63 − 9·tierIndex types (54..18), 0.5 AE/t, battery
 * 20k AE (×(1 + 8·energy cards)), charge rate 80 + 80·energy cards.</li>
 * <li>Portable fluid cells: half the bytes, 18 types.</li>
 * </ul>
 *
 * @param itemId       Hytale item id (e.g. {@code AE2_Item_Storage_Cell_1k})
 * @param keyType      items or fluids
 * @param tier         storage tier
 * @param bytes        total bytes
 * @param bytesPerType bytes used by each stored type
 * @param totalTypes   max number of types
 * @param idleDrain    AE/t in a drive
 * @param upgradeSlots number of upgrade slots
 * @param portable     true for portable cells
 */
public record CellDefinition(String itemId, AEKeyType keyType, StorageCellTier tier, int bytes, int bytesPerType,
        int totalTypes, double idleDrain, int upgradeSlots, boolean portable) {

    private static final Map<String, CellDefinition> BY_ID = new LinkedHashMap<>();

    public static final List<CellDefinition> ITEM_CELLS;
    public static final List<CellDefinition> FLUID_CELLS;
    public static final List<CellDefinition> PORTABLE_ITEM_CELLS;
    public static final List<CellDefinition> PORTABLE_FLUID_CELLS;

    static {
        var items = new ArrayList<CellDefinition>();
        var fluids = new ArrayList<CellDefinition>();
        var pItems = new ArrayList<CellDefinition>();
        var pFluids = new ArrayList<CellDefinition>();
        for (var t : StorageCellTier.ALL) {
            items.add(reg(new CellDefinition("AE2_Item_Storage_Cell_" + t.namePrefix(), AEKeyType.ITEMS, t,
                    t.bytes(), t.bytesPerType(), 63, t.idleDrain(), 4, false)));
            fluids.add(reg(new CellDefinition("AE2_Fluid_Storage_Cell_" + t.namePrefix(), AEKeyType.FLUIDS, t,
                    t.bytes(), t.bytesPerType(), 18, t.idleDrain(), 3, false)));
            pItems.add(reg(new CellDefinition("AE2_Portable_Item_Cell_" + t.namePrefix(), AEKeyType.ITEMS, t,
                    t.bytes() / 2, t.bytes() / 128, 63 - t.index() * 9, 0.5, 4, true)));
            pFluids.add(reg(new CellDefinition("AE2_Portable_Fluid_Cell_" + t.namePrefix(), AEKeyType.FLUIDS, t,
                    t.bytes() / 2, t.bytes() / 128, 18, 0.5, 3, true)));
        }
        ITEM_CELLS = Collections.unmodifiableList(items);
        FLUID_CELLS = Collections.unmodifiableList(fluids);
        PORTABLE_ITEM_CELLS = Collections.unmodifiableList(pItems);
        PORTABLE_FLUID_CELLS = Collections.unmodifiableList(pFluids);
    }

    /** Hytale item id of the creative cell. */
    public static final String CREATIVE_CELL_ID = "AE2_Creative_Storage_Cell";

    private static CellDefinition reg(CellDefinition d) {
        BY_ID.put(d.itemId(), d);
        return d;
    }

    /** @return the definition for a Hytale item id, or null if the item is no basic/portable cell. */
    public static CellDefinition byItemId(String itemId) {
        return BY_ID.get(itemId);
    }

    public static boolean isCell(String itemId) {
        return BY_ID.containsKey(itemId) || CREATIVE_CELL_ID.equals(itemId);
    }

    public static Map<String, CellDefinition> all() {
        return Collections.unmodifiableMap(BY_ID);
    }

    public static CellDefinition itemCell(StorageCellTier tier) {
        return ITEM_CELLS.get(tier.index() - 1);
    }

    public static CellDefinition fluidCell(StorageCellTier tier) {
        return FLUID_CELLS.get(tier.index() - 1);
    }

    /** Portable cell battery capacity: 20k AE × (1 + 8 · energy cards). 0 for non-portable cells. */
    public double portableMaxPower(int energyCards) {
        return portable ? AEConfig.get().portableCellBattery() * (1 + 8.0 * energyCards) : 0;
    }

    /** Portable cell charge rate (AE/t in a charger): 80 + 80 · energy cards. */
    public double portableChargeRate(int energyCards) {
        return portable ? 80d + 80d * energyCards : 0;
    }
}
