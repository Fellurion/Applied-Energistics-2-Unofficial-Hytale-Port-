/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.cells;

import de.fellurion.ae2.core.stacks.AEItemKey;

import java.util.function.Function;

/**
 * Creates cell inventories from cell items. Port of {@code appeng.api.storage.StorageCells} with
 * {@code BasicCellHandler} / {@code CreativeCellHandler}.
 */
public final class StorageCells {
    /**
     * Resolves the cell data stored in an item key's metadata, used to prevent non-empty cells from being stored in
     * other cells. Default: the item's {@code nbt} string is parsed as {@link CellItemData} JSON.
     */
    private static volatile Function<AEItemKey, CellItemData> cellDataResolver = key -> {
        try {
            return CellItemData.fromJson(key.nbt());
        } catch (RuntimeException e) {
            return new CellItemData();
        }
    };

    private StorageCells() {
    }

    public static void setCellDataResolver(Function<AEItemKey, CellItemData> resolver) {
        cellDataResolver = resolver;
    }

    public static boolean isCellItem(String itemId) {
        return CellDefinition.isCell(itemId);
    }

    /**
     * Creates the inventory for a cell item, or null if the item is no storage cell.
     *
     * @param itemId    Hytale item id of the cell
     * @param data      the cell data (parse it with {@link CellItemData#fromJson})
     * @param container change listener (may be null)
     */
    public static StorageCell getCellInventory(String itemId, CellItemData data, ISaveProvider container) {
        if (CellDefinition.CREATIVE_CELL_ID.equals(itemId)) {
            return new CreativeCellInventory(data);
        }
        var def = CellDefinition.byItemId(itemId);
        if (def == null) {
            return null;
        }
        return BasicCellInventory.create(def, data, container);
    }

    /** False if the key is a storage cell item that contains something (AE2: {@code canFitInsideCell}). */
    static boolean canFitInsideCell(AEItemKey key) {
        if (!CellDefinition.isCell(key.itemId()) || key.nbt() == null) {
            return true;
        }
        var data = cellDataResolver.apply(key);
        if (data == null) {
            return true;
        }
        if (CellDefinition.CREATIVE_CELL_ID.equals(key.itemId())) {
            return data.getConfigKeys().isEmpty();
        }
        return !data.hasContents();
    }
}
