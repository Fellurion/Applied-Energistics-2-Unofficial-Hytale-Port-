/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.cells;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.IncludeExclude;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.partition.IPartitionList;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Inventory of a basic (or portable) storage cell. Exact port of {@code appeng.me.cells.BasicCellInventory}.
 * <p>
 * Byte math: every stored type costs {@code bytesPerType} bytes; items cost 1 byte per 8 items (fluids: 1 byte per
 * 8000 mB), rounded up per cell (partial bytes are "unused item count"). Example 1k item cell (1024 bytes, 8 bytes per
 * type): one type fits (1024 − 8) · 8 = 8128 items; with 63 types, (1024 − 63·8) · 8 = 4160 items in total.
 * <p>
 * Upgrades: inverter card turns the partition into a blacklist, fuzzy card enables fuzzy partitioning, equal
 * distribution card limits each type to an equal share, void (overflow destruction) card voids items that do not fit.
 */
public final class BasicCellInventory implements StorageCell {
    private static final int MAX_ITEM_TYPES = 63;

    private final CellDefinition cellType;
    private final CellItemData data;
    private final ISaveProvider container;
    private final AEKeyType keyType;
    private final IPartitionList partitionList;
    private final IncludeExclude partitionListMode;
    private final UpgradeInventory upgrades;
    private final int maxItemTypes;
    private int storedItems;
    private long storedItemCount;
    private Map<AEKey, Long> storedAmounts;
    private final long maxItemsPerType;
    private final boolean hasVoidUpgrade;
    private boolean isPersisted = true;

    private BasicCellInventory(CellDefinition cellType, CellItemData data, ISaveProvider container) {
        this.cellType = cellType;
        this.data = data;
        this.container = container;
        this.keyType = cellType.keyType();
        this.maxItemTypes = Math.max(1, Math.min(MAX_ITEM_TYPES, cellType.totalTypes()));

        long count = 0;
        int types = 0;
        for (var v : data.getContents().values()) {
            if (v > 0) {
                count += v;
                types++;
            }
        }
        this.storedItems = types;
        this.storedItemCount = count;
        this.storedAmounts = null;

        this.upgrades = new UpgradeInventory(cellType.itemId(), cellType.upgradeSlots());
        this.upgrades.fromItemIds(data.getUpgrades());

        var builder = IPartitionList.builder();
        boolean hasInverter = upgrades.isInstalled(UpgradeCard.INVERTER);
        boolean isFuzzy = upgrades.isInstalled(UpgradeCard.FUZZY);
        if (isFuzzy) {
            builder.fuzzyMode(data.getFuzzyMode());
        }
        // AE2's cell ConfigInventory only accepts keys of the cell's key type
        var configKeys = data.getConfigKeys().stream().filter(keyType::contains).toList();
        builder.addAll(configKeys);
        this.partitionListMode = hasInverter ? IncludeExclude.BLACKLIST : IncludeExclude.WHITELIST;
        this.partitionList = builder.build();

        if (upgrades.isInstalled(UpgradeCard.EQUAL_DISTRIBUTION)) {
            // Compute max possible amount of types based on whitelist size, and bound by type limit.
            long maxTypes = Integer.MAX_VALUE;
            if (!isFuzzy && partitionListMode == IncludeExclude.WHITELIST && !configKeys.isEmpty()) {
                maxTypes = configKeys.size();
            }
            maxTypes = Math.min(maxTypes, this.maxItemTypes);
            long totalStorage = (getTotalBytes() - getBytesPerType() * maxTypes) * keyType.getAmountPerByte();
            // Technically not exactly evenly distributed, but close enough!
            this.maxItemsPerType = Math.max(0, (totalStorage + maxTypes - 1) / maxTypes);
        } else {
            this.maxItemsPerType = Long.MAX_VALUE;
        }
        this.hasVoidUpgrade = upgrades.isInstalled(UpgradeCard.VOID);
    }

    /**
     * Creates the inventory of a basic cell.
     *
     * @param cellType  the cell definition
     * @param data      the cell's item data (modified on {@link #persist()})
     * @param container notified on changes (may be null: then changes are persisted into {@code data} immediately)
     */
    public static BasicCellInventory create(CellDefinition cellType, CellItemData data, ISaveProvider container) {
        Objects.requireNonNull(cellType, "cellType");
        Objects.requireNonNull(data, "data");
        return new BasicCellInventory(cellType, data, container);
    }

    public CellDefinition getCellType() {
        return cellType;
    }

    public CellItemData getData() {
        return data;
    }

    public IncludeExclude getPartitionListMode() {
        return partitionListMode;
    }

    public boolean isPreformatted() {
        return !partitionList.isEmpty();
    }

    public boolean isFuzzy() {
        return partitionList instanceof IPartitionList.FuzzyPriorityList;
    }

    public FuzzyMode getFuzzyMode() {
        return data.getFuzzyMode();
    }

    public UpgradeInventory getUpgradesInventory() {
        return upgrades;
    }

    @Override
    public boolean canFitInsideCell() {
        return getAvailableStacks().isEmpty();
    }

    private Map<AEKey, Long> getCellItems() {
        if (storedAmounts == null) {
            storedAmounts = new LinkedHashMap<>();
            for (var e : data.getContents().entrySet()) {
                if (e.getValue() > 0) {
                    storedAmounts.put(e.getKey(), e.getValue());
                }
            }
        }
        return storedAmounts;
    }

    @Override
    public void persist() {
        if (isPersisted) {
            return;
        }
        long itemCount = 0;
        var contents = data.getContents();
        contents.clear();
        for (var entry : storedAmounts.entrySet()) {
            long amount = entry.getValue();
            itemCount += amount;
            if (amount > 0) {
                contents.put(entry.getKey(), amount);
            }
        }
        this.storedItems = storedAmounts.size();
        this.storedItemCount = itemCount;
        this.isPersisted = true;
    }

    private void saveChanges() {
        this.storedItems = storedAmounts.size();
        this.storedItemCount = 0;
        for (var v : storedAmounts.values()) {
            this.storedItemCount += v;
        }
        this.isPersisted = false;
        if (container != null) {
            container.saveChanges();
        } else {
            persist();
        }
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        for (var entry : getCellItems().entrySet()) {
            out.add(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public int getEstimatedStackCount() {
        return getCellItems().size();
    }

    @Override
    public double getIdleDrain() {
        return cellType.idleDrain();
    }

    public int getBytesPerType() {
        return cellType.bytesPerType();
    }

    public boolean canHoldNewItem() {
        final long bytesFree = getFreeBytes();
        return (bytesFree > getBytesPerType() || bytesFree == getBytesPerType() && getUnusedItemCount() > 0)
                && getRemainingItemTypes() > 0;
    }

    public long getTotalBytes() {
        return cellType.bytes();
    }

    public long getFreeBytes() {
        return getTotalBytes() - getUsedBytes();
    }

    public long getTotalItemTypes() {
        return maxItemTypes;
    }

    public long getStoredItemCount() {
        return storedItemCount;
    }

    public long getStoredItemTypes() {
        return storedItems;
    }

    public long getRemainingItemTypes() {
        var basedOnStorage = getFreeBytes() / getBytesPerType();
        var baseOnTotal = getTotalItemTypes() - getStoredItemTypes();
        return Math.min(basedOnStorage, baseOnTotal);
    }

    public long getUsedBytes() {
        var bytesForItemCount = (getStoredItemCount() + getUnusedItemCount()) / keyType.getAmountPerByte();
        return getStoredItemTypes() * getBytesPerType() + bytesForItemCount;
    }

    public long getRemainingItemCount() {
        final long remaining = getFreeBytes() * keyType.getAmountPerByte() + getUnusedItemCount();
        return remaining > 0 ? remaining : 0;
    }

    public int getUnusedItemCount() {
        final int div = (int) (getStoredItemCount() % keyType.getAmountPerByte());
        if (div == 0) {
            return 0;
        }
        return keyType.getAmountPerByte() - div;
    }

    /** Max amount per type with an equal distribution card ({@link Long#MAX_VALUE} without). */
    public long getMaxItemsPerType() {
        return maxItemsPerType;
    }

    @Override
    public CellState getStatus() {
        if (getStoredItemTypes() == 0) {
            return CellState.EMPTY;
        }
        if (canHoldNewItem()) {
            return CellState.NOT_EMPTY;
        }
        if (getRemainingItemCount() > 0) {
            return CellState.TYPES_FULL;
        }
        return CellState.FULL;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount == 0 || !keyType.contains(what)) {
            return 0;
        }
        if (!partitionList.matchesFilter(what, partitionListMode)) {
            return 0;
        }

        // Run regular insert logic and then apply void upgrade to the returned value.
        long inserted = innerInsert(what, amount, mode);

        // In the event that a void card is being used on a (full) unformatted cell, ensure it doesn't void any items
        // that the cell isn't even storing and cannot store to begin with
        if (!isPreformatted() && hasVoidUpgrade && !canHoldNewItem()) {
            return getCellItems().containsKey(what) ? amount : inserted;
        }

        return hasVoidUpgrade ? amount : inserted;
    }

    private long innerInsert(AEKey what, long amount, Actionable mode) {
        // Prevent non-empty storage cells from being recursively stored inside this cell
        if (what instanceof AEItemKey itemKey && !StorageCells.canFitInsideCell(itemKey)) {
            return 0;
        }

        var currentAmount = getCellItems().getOrDefault(what, 0L);
        long remainingItemCount = getRemainingItemCount();

        // Deduct the required storage for a new type if the type is new
        if (currentAmount <= 0) {
            if (!canHoldNewItem()) {
                return 0;
            }
            remainingItemCount -= (long) getBytesPerType() * keyType.getAmountPerByte();
            if (remainingItemCount <= 0) {
                return 0;
            }
        }

        // Apply max items per type
        remainingItemCount = Math.max(0, Math.min(maxItemsPerType - currentAmount, remainingItemCount));

        if (amount > remainingItemCount) {
            amount = remainingItemCount;
        }

        if (mode == Actionable.MODULATE && amount > 0) {
            getCellItems().put(what, currentAmount + amount);
            saveChanges();
        }
        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        var currentAmount = getCellItems().getOrDefault(what, 0L);
        if (currentAmount > 0) {
            if (amount >= currentAmount) {
                if (mode == Actionable.MODULATE) {
                    getCellItems().remove(what);
                    saveChanges();
                }
                return currentAmount;
            } else {
                if (mode == Actionable.MODULATE) {
                    getCellItems().put(what, currentAmount - amount);
                    saveChanges();
                }
                return amount;
            }
        }
        return 0;
    }

    @Override
    public String getDescription() {
        return cellType.itemId();
    }
}
