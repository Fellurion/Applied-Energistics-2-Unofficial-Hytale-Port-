/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.AccessRestriction;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.IncludeExclude;
import de.fellurion.ae2.core.config.Enums.StorageFilter;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.storage.IStorageMounts;
import de.fellurion.ae2.core.storage.IStorageProvider;
import de.fellurion.ae2.core.storage.MEInventoryHandler;
import de.fellurion.ae2.core.storage.MEStorage;
import de.fellurion.ae2.core.storage.NullStorage;
import de.fellurion.ae2.core.storage.partition.IPartitionList;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;

/**
 * Storage bus: mounts the inventory in front of it into the network. Port of
 * {@code appeng.parts.storagebus.StorageBusPart}.
 * <ul>
 * <li>The target is any {@link MEStorage}: an {@link ExternalStorageFacade} over an adjacent container, or the
 * {@linkplain InterfaceLogic#getExposedStorage() exposed storage of an interface} (sub-networks). The integration
 * calls {@link #setTarget} / {@link #clearTarget} whenever the block in front changes.</li>
 * <li>Mounted with {@link #getPriority() priority} while the node is online.</li>
 * <li>Access restriction (READ, WRITE, READ_WRITE), partition from {@code 18 + 9 · capacity cards} config slots,
 * inverter card = blacklist, fuzzy card, overflow-destruction (void) card, "filter on extract" and "extractable only"
 * (only show extractable stacks; requires filter on extract like AE2).</li>
 * </ul>
 * Deviations: AE2 polls external inventories through a ticking cache ({@code CompositeStorage}) and discovers targets
 * via capabilities; here the grid's storage cache is rebuilt from the live target every tick when watchers exist, so
 * no ticking is needed, and target discovery is the integration's job.
 */
public class StorageBusLogic implements IStorageProvider, IGridNodeListener, IActionHost {
    public static final String ITEM_ID = "AE2_Storage_Bus";

    private final ConfigInventory config = ConfigInventory.configTypes(63);
    private final UpgradeInventory upgrades = new UpgradeInventory(ITEM_ID, 5);
    /**
     * The virtual inventory exposed to the network. Stays the same object while the target changes underneath, like
     * AE2's {@code StorageBusInventory}.
     */
    private final MEInventoryHandler handler = new MEInventoryHandler(NullStorage.of());
    private AccessRestriction access = AccessRestriction.READ_WRITE;
    private FuzzyMode fuzzyMode = FuzzyMode.IGNORE_ALL;
    private StorageFilter storageFilter = StorageFilter.EXTRACTABLE_ONLY;
    private boolean filterOnExtract = true;
    private int priority = 0;
    private GridNode node;
    private Runnable saveListener;

    public StorageBusLogic() {
        config.changeListener(this::onConfigurationChanged);
        upgrades.setChangeListener(this::onConfigurationChanged);
    }

    public ConfigInventory getConfig() {
        return config;
    }

    public UpgradeInventory getUpgrades() {
        return upgrades;
    }

    public void setSaveListener(Runnable listener) {
        this.saveListener = listener;
    }

    private void markDirty() {
        if (saveListener != null) {
            saveListener.run();
        }
    }

    /** Sets the storage in front of the bus. */
    public void setTarget(MEStorage target) {
        var newInventory = target == null ? NullStorage.of() : target;
        if (newInventory == handler.getDelegate()) {
            return;
        }
        boolean wasRegistered = hasRegisteredCellToNetwork();
        handler.setDelegate(newInventory);
        if (newInventory instanceof ExternalStorageFacade facade) {
            facade.setExtractableOnly(isExtractableOnly());
        }
        applySettings();
        if (wasRegistered != hasRegisteredCellToNetwork()) {
            remountStorage();
        } else if (node != null && node.getGrid() != null) {
            node.getGrid().getStorageService().invalidateCache();
        }
    }

    /** Removes the target (block in front removed / unloaded). */
    public void clearTarget() {
        setTarget(null);
    }

    /** The currently connected storage (unfiltered), or {@link NullStorage}. */
    public MEStorage getTarget() {
        return handler.getDelegate();
    }

    /** The filtered view that is mounted into the network. */
    public MEStorage getNetworkFacingInventory() {
        return handler;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
        markDirty();
        remountStorage();
    }

    public AccessRestriction getAccess() {
        return access;
    }

    public void setAccess(AccessRestriction access) {
        this.access = access;
        onConfigurationChanged();
    }

    public FuzzyMode getFuzzyMode() {
        return fuzzyMode;
    }

    public void setFuzzyMode(FuzzyMode mode) {
        this.fuzzyMode = mode;
        onConfigurationChanged();
    }

    public StorageFilter getStorageFilter() {
        return storageFilter;
    }

    public void setStorageFilter(StorageFilter filter) {
        this.storageFilter = filter;
        onConfigurationChanged();
    }

    public boolean isFilterOnExtract() {
        return filterOnExtract;
    }

    public void setFilterOnExtract(boolean filterOnExtract) {
        this.filterOnExtract = filterOnExtract;
        onConfigurationChanged();
    }

    private boolean isExtractableOnly() {
        return storageFilter == StorageFilter.EXTRACTABLE_ONLY;
    }

    private boolean hasRegisteredCellToNetwork() {
        return node != null && node.isOnline() && !(handler.getDelegate() instanceof NullStorage);
    }

    private void remountStorage() {
        IStorageProvider.requestUpdate(node);
    }

    private void onConfigurationChanged() {
        if (handler.getDelegate() instanceof ExternalStorageFacade facade) {
            facade.setExtractableOnly(isExtractableOnly());
        }
        applySettings();
        markDirty();
        if (node != null && node.getGrid() != null) {
            node.getGrid().getStorageService().invalidateCache();
        }
    }

    /** Port of the handler configuration part of {@code StorageBusPart.updateTarget}. */
    private void applySettings() {
        handler.setAllowExtraction(access.isAllowExtraction());
        handler.setAllowInsertion(access.isAllowInsertion());
        handler.setWhitelist(upgrades.isInstalled(UpgradeCard.INVERTER) ? IncludeExclude.BLACKLIST
                : IncludeExclude.WHITELIST);
        handler.setPartitionList(createFilter());
        handler.setVoidOverflow(upgrades.isInstalled(UpgradeCard.VOID));
        handler.setExtractFiltering(filterOnExtract, isExtractableOnly() && filterOnExtract);
    }

    private IPartitionList createFilter() {
        var builder = IPartitionList.builder();
        if (upgrades.isInstalled(UpgradeCard.FUZZY)) {
            builder.fuzzyMode(fuzzyMode);
        }
        var slotsToUse = 18 + upgrades.getInstalledUpgrades(UpgradeCard.CAPACITY) * 9;
        for (var x = 0; x < config.size() && x < slotsToUse; x++) {
            builder.add(config.getKey(x));
        }
        return builder.build();
    }

    @Override
    public void mountInventories(IStorageMounts mounts) {
        if (!(handler.getDelegate() instanceof NullStorage)) {
            mounts.mount(handler, priority);
        }
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
        applySettings();
        remountStorage();
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
    }

    @Override
    public GridNode getActionableNode() {
        return node;
    }

    /** Saves config, upgrades, priority and settings. */
    public String toJson() {
        var map = new LinkedHashMap<String, Object>();
        map.put("cfg", config.toJsonList());
        map.put("up", upgrades.toItemIds());
        map.put("prio", priority);
        map.put("acc", access.name());
        map.put("fz", fuzzyMode.name());
        map.put("sf", storageFilter.name());
        map.put("foe", filterOnExtract);
        return MiniJson.write(map);
    }

    public void fromJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        var map = MiniJson.parseObject(json);
        priority = MiniJson.getInt(map, "prio", 0);
        access = MiniJson.getEnum(map, "acc", AccessRestriction.class, AccessRestriction.READ_WRITE);
        fuzzyMode = MiniJson.getEnum(map, "fz", FuzzyMode.class, FuzzyMode.IGNORE_ALL);
        storageFilter = MiniJson.getEnum(map, "sf", StorageFilter.class, StorageFilter.EXTRACTABLE_ONLY);
        filterOnExtract = MiniJson.getBool(map, "foe", true);
        upgrades.fromItemIds(MiniJson.getList(map, "up"));
        config.fromJsonList(MiniJson.getList(map, "cfg"));
        onConfigurationChanged();
        remountStorage();
    }
}
