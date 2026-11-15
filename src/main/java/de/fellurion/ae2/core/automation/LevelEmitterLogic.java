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
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.YesNo;
import de.fellurion.ae2.core.crafting.ICraftingProvider;
import de.fellurion.ae2.core.crafting.ICraftingWatcherNode;
import de.fellurion.ae2.core.crafting.IPatternDetails;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IStackWatcher;
import de.fellurion.ae2.core.storage.IStorageWatcherNode;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ME Level Emitter. Port of {@code appeng.parts.automation.StorageLevelEmitterPart}.
 * <ul>
 * <li>One config slot holds the watched key. Without a key the emitter compares the total amount of everything in
 * the network.</li>
 * <li>One upgrade slot: a Fuzzy Card makes it sum up all fuzzy matches of the key ({@link #getFuzzyMode()}), a
 * Crafting Card switches it to "direct output".</li>
 * <li>With a Crafting Card the threshold is ignored: the emitter is on while a crafting CPU waits for the key (or
 * for anything, without a key). With {@link #isCraftViaRedstone()} the key is additionally offered to the crafting
 * calculation as "emitable": a job that needs it waits for whatever the emitter switches on to deliver it.</li>
 * <li>Amounts come from the storage watcher of the grid, requests from its crafting watcher; nothing is polled.</li>
 * </ul>
 * Deviations: the rescan for a blank or fuzzy emitter is limited to once per snapshot rebuild of the storage service
 * ({@code StorageService.getCacheUpdateCount}) instead of once per tick, because here the snapshot can be rebuilt
 * between two ticks; totals saturate at {@link Long#MAX_VALUE}.
 */
public final class LevelEmitterLogic extends AbstractLevelEmitterLogic
        implements IStorageWatcherNode, ICraftingWatcherNode, ICraftingProvider {
    /** Item id of the block; decides which upgrade cards fit ({@code core.cells.Upgrades}). */
    public static final String ITEM_ID = "AE2_Level_Emitter";

    private final ConfigInventory config = ConfigInventory.configTypes(1).changeListener(this::onConfigChanged);
    private final UpgradeInventory upgrades = new UpgradeInventory(ITEM_ID, 1);
    private FuzzyMode fuzzyMode = FuzzyMode.IGNORE_ALL;
    private YesNo craftViaRedstone = YesNo.NO;
    private IStackWatcher storageWatcher;
    private IStackWatcher craftingWatcher;
    private long lastUpdate = -1;
    private boolean loading;

    public LevelEmitterLogic() {
        upgrades.setChangeListener(this::onConfigChanged);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Configuration

    /** The slot with the watched key. Use {@code getConfig().setKey(0, key)}. */
    public ConfigInventory getConfig() {
        return config;
    }

    /** The watched key, or null for "everything". */
    public AEKey getConfiguredKey() {
        return config.getKey(0);
    }

    /** One slot: Fuzzy Card or Crafting Card. */
    public UpgradeInventory getUpgrades() {
        return upgrades;
    }

    public boolean isUpgradedWith(UpgradeCard card) {
        return upgrades.isInstalled(card);
    }

    /** AE2 setting {@code FUZZY_MODE}; only used with a Fuzzy Card. */
    public FuzzyMode getFuzzyMode() {
        return fuzzyMode;
    }

    public void setFuzzyMode(FuzzyMode mode) {
        if (this.fuzzyMode != mode) {
            this.fuzzyMode = mode;
            onSettingChanged();
        }
    }

    /**
     * AE2 setting {@code CRAFT_VIA_REDSTONE}; only used with a Crafting Card. false: "emit while the item is being
     * crafted", true: "emit to craft the item".
     */
    public boolean isCraftViaRedstone() {
        return craftViaRedstone == YesNo.YES;
    }

    public void setCraftViaRedstone(boolean craftViaRedstone) {
        var value = craftViaRedstone ? YesNo.YES : YesNo.NO;
        if (this.craftViaRedstone != value) {
            this.craftViaRedstone = value;
            onSettingChanged();
        }
    }

    /** Config slot or upgrade card changed. */
    private void onConfigChanged() {
        if (loading) {
            return;
        }
        configureWatchers();
        markDirty();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Watchers

    @Override
    public void updateWatcher(IStackWatcher newWatcher) {
        storageWatcher = newWatcher;
        configureWatchers();
    }

    @Override
    public void onStackChange(AEKey what, long amount) {
        if (what.equals(getConfiguredKey()) && !isUpgradedWith(UpgradeCard.FUZZY)) {
            lastReportedValue = amount;
            updateState();
        } else { // either fuzzy upgrade or null filter
            // With a fuzzy upgrade or no filter at all, the level emitter actively scans the grid. Do this only once
            // for all stacks that changed together.
            var grid = grid();
            if (grid != null) {
                long update = grid.getStorageService().getCacheUpdateCount();
                if (update != lastUpdate) {
                    lastUpdate = update;
                    updateReportingValue(grid);
                }
            }
        }
    }

    @Override
    public void updateCraftingWatcher(IStackWatcher newWatcher) {
        craftingWatcher = newWatcher;
        configureWatchers();
    }

    @Override
    public void onRequestChange(AEKey what) {
        updateState();
    }

    @Override
    protected boolean hasDirectOutput() {
        return isUpgradedWith(UpgradeCard.CRAFTING);
    }

    @Override
    protected boolean getDirectOutput() {
        var grid = grid();
        if (grid != null) {
            var key = getConfiguredKey();
            if (key != null) {
                return grid.getCraftingService().isRequesting(key);
            } else {
                return grid.getCraftingService().isRequestingAny();
            }
        }
        return false;
    }

    @Override
    protected void onReportingValueChanged() {
        // The scan below stops once lastReportedValue > reportingValue, so lastReportedValue must be recomputed when
        // reportingValue changes.
        var grid = grid();
        if (grid != null && !hasDirectOutput()) {
            updateReportingValue(grid);
        }
    }

    @Override
    protected void configureWatchers() {
        var myStack = getConfiguredKey();

        if (storageWatcher != null) {
            storageWatcher.reset();
        }
        if (craftingWatcher != null) {
            craftingWatcher.reset();
        }

        ICraftingProvider.requestUpdate(node);

        if (isUpgradedWith(UpgradeCard.CRAFTING)) {
            if (craftingWatcher != null) {
                if (myStack == null) {
                    craftingWatcher.setWatchAll(true);
                } else {
                    craftingWatcher.add(myStack);
                }
            }
        } else {
            if (storageWatcher != null) {
                if (isUpgradedWith(UpgradeCard.FUZZY) || myStack == null) {
                    storageWatcher.setWatchAll(true);
                } else {
                    storageWatcher.add(myStack);
                }
            }
            var grid = grid();
            if (grid != null) {
                updateReportingValue(grid);
            }
        }

        updateState();
    }

    private void updateReportingValue(Grid grid) {
        var stacks = grid.getStorageService().getCachedInventory();
        var myStack = getConfiguredKey();

        if (myStack == null) {
            lastReportedValue = sumUntilAboveThreshold(stacks);
        } else if (isUpgradedWith(UpgradeCard.FUZZY)) {
            lastReportedValue = sumUntilAboveThreshold(stacks.findFuzzy(myStack, fuzzyMode));
        } else {
            lastReportedValue = stacks.get(myStack);
        }

        updateState();
    }

    /** Stops once the sum exceeds the threshold: that is all the emitter needs to know ("blank emitter spam"). */
    private long sumUntilAboveThreshold(Iterable<KeyCounter.Entry> stacks) {
        long sum = 0;
        long threshold = getReportingValue();
        for (var st : stacks) {
            long amount = st.getLongValue();
            sum = Long.MAX_VALUE - sum < amount ? Long.MAX_VALUE : sum + amount;
            if (sum > threshold) {
                break;
            }
        }
        return sum;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Crafting provider ("emit to craft")

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return List.of();
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        return false;
    }

    @Override
    public boolean isBusy() {
        return true;
    }

    @Override
    public Set<AEKey> getEmitableItems() {
        if (isUpgradedWith(UpgradeCard.CRAFTING) && craftViaRedstone == YesNo.YES) {
            var key = getConfiguredKey();
            if (key != null) {
                return Set.of(key);
            }
        }
        return Set.of();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Persistence

    @Override
    protected Map<String, Object> writeJsonMap() {
        var map = super.writeJsonMap();
        map.put("cfg", config.toJsonList());
        map.put("up", upgrades.toItemIds());
        map.put("fz", fuzzyMode.name());
        map.put("craftViaRedstone", craftViaRedstone == YesNo.YES);
        return map;
    }

    @Override
    protected void readJsonMap(Map<String, Object> map) {
        super.readJsonMap(map);
        loading = true;
        try {
            fuzzyMode = MiniJson.getEnum(map, "fz", FuzzyMode.class, FuzzyMode.IGNORE_ALL);
            craftViaRedstone = MiniJson.getBool(map, "craftViaRedstone", false) ? YesNo.YES : YesNo.NO;
            upgrades.fromItemIds(MiniJson.getList(map, "up"));
            config.fromJsonList(MiniJson.getList(map, "cfg"));
        } finally {
            loading = false;
        }
    }
}
