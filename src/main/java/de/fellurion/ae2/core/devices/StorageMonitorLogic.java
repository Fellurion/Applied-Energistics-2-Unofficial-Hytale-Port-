/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.devices;

import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.IStackWatcher;
import de.fellurion.ae2.core.storage.IStorageWatcherNode;
import de.fellurion.ae2.core.storage.StorageHelper;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The ME Storage Monitor and ME Conversion Monitor. Port of {@code appeng.parts.reporting.AbstractMonitorPart},
 * {@code StorageMonitorPart} and the item transfer of {@code ConversionMonitorPart}.
 * <ul>
 * <li>Watches one key and knows its amount in the network (storage watcher of the grid, no polling).</li>
 * <li>{@link #configure(AEKey)} sets or clears the key while the monitor is unlocked; {@link #toggleLocked()} locks
 * and unlocks it. Both only work while the node is active.</li>
 * <li>Conversion monitor: {@link #insert} and {@link #extract} move items between a player and the network with the
 * usual energy cost ({@link StorageHelper}).</li>
 * </ul>
 * Usage: pass the logic as owner to
 * {@code MEWorld.addNode(pos, NodeType.STORAGE_MONITOR or CONVERSION_MONITOR, facing, color, logic)}.
 * <p>
 * Deviations: which gesture of the player calls which method is decided by the integration (AE2: right click, sneak
 * right click, left click); whether the key can be crafted is not tracked by a crafting watcher but asked on demand
 * ({@link #canCraft()}); keys contained in the held item (fluid containers) are not unpacked.
 */
public final class StorageMonitorLogic implements IStorageWatcherNode, IGridNodeListener, IActionHost {
    private AEKey configuredItem;
    private long amount;
    private boolean locked;
    private IStackWatcher storageWatcher;
    private GridNode node;
    private Runnable changeListener;
    private Runnable saveListener;

    /** Called whenever the watched key, its amount or the lock changed (to refresh a display). */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    /** Called whenever persistent state changed (watched key, lock). */
    public void setSaveListener(Runnable listener) {
        this.saveListener = listener;
    }

    private void changed(boolean save) {
        if (save && saveListener != null) {
            saveListener.run();
        }
        if (changeListener != null) {
            changeListener.run();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // State

    /** The watched key (AE2: {@code getDisplayed()}), or null. */
    public AEKey getConfiguredItem() {
        return configuredItem;
    }

    /** Sets the watched key regardless of lock and node state (loading, admin tools). */
    public void setConfiguredItem(AEKey configuredItem) {
        if (Objects.equals(this.configuredItem, configuredItem)) {
            return;
        }
        this.configuredItem = configuredItem;
        configureWatchers();
        changed(true);
    }

    /** Amount of the watched key in the network; 0 without a key. */
    public long getAmount() {
        return amount;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        if (this.locked != locked) {
            this.locked = locked;
            changed(true);
        }
    }

    /** True if the node is powered and booted (and, for the conversion monitor, has its channel). */
    public boolean isActive() {
        return node != null && node.isActive();
    }

    /** True if the network has a pattern (or an emitter) for the watched key. */
    public boolean canCraft() {
        var grid = grid();
        return grid != null && configuredItem != null && grid.getCraftingService().isCraftable(configuredItem);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Player actions

    /**
     * Port of the sneak-use of {@code AbstractMonitorPart.onUseWithoutItem}: locks or unlocks the monitor.
     *
     * @return false if the node is not active (nothing changed)
     */
    public boolean toggleLocked() {
        if (!isActive()) {
            return false;
        }
        setLocked(!locked);
        return true;
    }

    /**
     * Port of {@code AbstractMonitorPart.onUseItemOn}: watches the key of the held item; null (empty hand) clears
     * the monitor.
     *
     * @return false if the monitor is locked or its node is not active (nothing changed)
     */
    public boolean configure(AEKey held) {
        if (locked || !isActive()) {
            return false;
        }
        setConfiguredItem(held);
        return true;
    }

    /**
     * Port of {@code ConversionMonitorPart.insertItem}: stores up to {@code count} of {@code what} in the network.
     *
     * @return the amount that was stored
     */
    public long insert(AEKey what, long count, IActionSource source) {
        var grid = grid();
        if (grid == null || !isActive() || what == null || count <= 0) {
            return 0;
        }
        return StorageHelper.poweredInsert(grid.getEnergyService(), grid.getStorageService().getInventory(), what,
                count, source);
    }

    /**
     * Port of {@code ConversionMonitorPart.extractItem}: takes up to {@code count} of the watched key out of the
     * network.
     *
     * @return the amount that was taken
     */
    public long extract(long count, IActionSource source) {
        var grid = grid();
        if (grid == null || !isActive() || configuredItem == null || count <= 0) {
            return 0;
        }
        return StorageHelper.poweredExtraction(grid.getEnergyService(), grid.getStorageService().getInventory(),
                configuredItem, count, source);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Watcher

    @Override
    public void updateWatcher(IStackWatcher newWatcher) {
        storageWatcher = newWatcher;
        configureWatchers();
    }

    @Override
    public void onStackChange(AEKey what, long amount) {
        if (what.equals(configuredItem) && this.amount != amount) {
            this.amount = amount;
            changed(false);
        }
    }

    private void configureWatchers() {
        if (storageWatcher != null) {
            storageWatcher.reset();
        }
        long before = amount;
        if (configuredItem != null) {
            if (storageWatcher != null) {
                storageWatcher.add(configuredItem);
            }
            var grid = grid();
            if (grid != null) {
                amount = grid.getStorageService().getCachedInventory().get(configuredItem);
            }
        } else {
            amount = 0;
        }
        if (before != amount) {
            changed(false);
        }
    }

    private Grid grid() {
        return node == null ? null : node.getGrid();
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
        // The watcher was handed over while the node was created; only now the grid can be read.
        configureWatchers();
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
    }

    @Override
    public GridNode getActionableNode() {
        return node;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Persistence

    /** Saves the lock and the watched key as JSON. */
    public String saveState() {
        var map = new LinkedHashMap<String, Object>();
        map.put("isLocked", locked);
        if (configuredItem != null) {
            map.put("configuredItem", configuredItem.toJsonMap());
        }
        return MiniJson.write(map);
    }

    /** Loads state written by {@link #saveState()}. */
    public void loadState(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        var map = MiniJson.parseObject(json);
        locked = MiniJson.getBool(map, "isLocked", false);
        Map<?, ?> key = MiniJson.getMap(map, "configuredItem");
        configuredItem = key.isEmpty() ? null : AEKey.fromJsonMap(key);
        configureWatchers();
    }
}
