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
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.IncludeExclude;
import de.fellurion.ae2.core.config.Enums.YesNo;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.IStorageMounts;
import de.fellurion.ae2.core.storage.IStorageProvider;
import de.fellurion.ae2.core.storage.MEStorage;
import de.fellurion.ae2.core.storage.partition.IPartitionList;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Formation plane: a storage of the network that puts whatever is inserted into it in front of itself, as a block or
 * as an item entity. Port of {@code appeng.parts.automation.FormationPlanePart}.
 * <ul>
 * <li>Mounted with {@link #getPriority() priority} while the node is online; insert-only (nothing can be extracted
 * or listed).</li>
 * <li>Filter from {@code 18 + 9 · capacity cards} of 63 config slots; an empty filter accepts everything; inverter
 * card = blacklist, fuzzy card = fuzzy matching with {@link #getFuzzyMode()}.</li>
 * <li>Setting "place block" (AE2 {@code Settings.PLACE_BLOCK}): YES places blocks, NO drops item entities.</li>
 * <li>The work itself is done by the {@link PlacementStrategy} of the key's type. Only what the strategy really
 * placed is reported as inserted; the rest stays with the caller, so nothing is voided.</li>
 * <li>5 upgrade slots: fuzzy 1, inverter 1, capacity 5 (see {@code Upgrades}).</li>
 * </ul>
 * Usage: pass the logic as owner to {@code MEWorld.addNode(pos, NodeType.FORMATION_PLANE, facing, color, logic)}, give
 * it its {@linkplain #setPlacementStrategy placement strategies} and report changes of the block in front with
 * {@link #onNeighborChanged()}.
 * <p>
 * Deviations: the online check before mounting is done by the storage service (see {@link IStorageProvider}), which
 * also remounts when the node goes online or offline (AE2: {@code onMainNodeStateChanged}). The connected-plane model
 * is purely visual and not part of the logic.
 */
public class FormationPlaneLogic implements IStorageProvider, IGridNodeListener, IActionHost {
    public static final String ITEM_ID = "AE2_Formation_Plane";

    private int priority = 0;
    private final MEStorage inventory = new InWorldStorage();
    private final ConfigInventory config = ConfigInventory.configTypes(63);
    private final UpgradeInventory upgrades = new UpgradeInventory(ITEM_ID, 5);
    private PlacementStrategy placementStrategies = PlacementStrategy.noop();
    private IncludeExclude filterMode = IncludeExclude.WHITELIST;
    private IPartitionList filter;
    private YesNo placeBlock = YesNo.YES;
    private FuzzyMode fuzzyMode = FuzzyMode.IGNORE_ALL;
    private GridNode node;
    private Runnable saveListener;

    public FormationPlaneLogic() {
        this.config.changeListener(this::onConfigurationChanged);
        this.upgrades.setChangeListener(this::onConfigurationChanged);
    }

    /** Filter slots (types). Use {@code getConfig().setKey(slot, key)}. */
    public ConfigInventory getConfig() {
        return config;
    }

    public UpgradeInventory getUpgrades() {
        return upgrades;
    }

    /** Called whenever persistent state changed (config, settings, upgrades, priority). */
    public void setSaveListener(Runnable listener) {
        this.saveListener = listener;
    }

    private void markDirty() {
        if (saveListener != null) {
            saveListener.run();
        }
    }

    /**
     * The strategy that puts stacks into the world, normally a {@link PlacementStrategyFacade} with one strategy per
     * key type. The filter then accepts the key types that can be placed (AE2: the config inventory supports
     * {@code StackWorldBehaviors.withPlacementStrategy()}).
     */
    public void setPlacementStrategy(PlacementStrategy strategy) {
        this.placementStrategies = strategy == null ? PlacementStrategy.noop() : strategy;
        if (strategy instanceof PlacementStrategyFacade facade && !facade.supportedTypes().isEmpty()) {
            Set<AEKeyType> types = facade.supportedTypes();
            this.config.supportedTypes(types);
        }
    }

    public PlacementStrategy getPlacementStrategy() {
        return placementStrategies;
    }

    /** Usable filter slots: {@code min(18 + 9 · capacity cards, 63)}. */
    public int availableSlots() {
        return Math.min(18 + upgrades.getInstalledUpgrades(UpgradeCard.CAPACITY) * 9, config.size());
    }

    /** AE2 {@code Settings.PLACE_BLOCK}: YES = place as block, NO = drop as item entity. */
    public YesNo getPlaceBlock() {
        return placeBlock;
    }

    public void setPlaceBlock(YesNo placeBlock) {
        this.placeBlock = placeBlock == YesNo.NO ? YesNo.NO : YesNo.YES;
        markDirty();
    }

    public FuzzyMode getFuzzyMode() {
        return fuzzyMode;
    }

    public void setFuzzyMode(FuzzyMode mode) {
        this.fuzzyMode = mode;
        onConfigurationChanged();
    }

    public int getPriority() {
        return this.priority;
    }

    public void setPriority(int newValue) {
        this.priority = newValue;
        markDirty();
        remountStorage();
    }

    /** Whether the filter works as a blacklist (inverter card). */
    public boolean isInverted() {
        return upgrades.isInstalled(UpgradeCard.INVERTER);
    }

    private void onConfigurationChanged() {
        updateFilter();
        markDirty();
    }

    protected final void updateFilter() {
        this.filter = createFilter();
        this.filterMode = upgrades.isInstalled(UpgradeCard.INVERTER)
                ? IncludeExclude.BLACKLIST
                : IncludeExclude.WHITELIST;
    }

    private void remountStorage() {
        IStorageProvider.requestUpdate(node);
    }

    /** Port of {@code onUpdateShape} for the plane's own side: a block might have been changed in front of us. */
    public void onNeighborChanged() {
        placementStrategies.clearBlocked();
    }

    /**
     * Places the given stacks in-world.
     *
     * @return The amount that was placed.
     * @see MEStorage#insert
     */
    protected long placeInWorld(AEKey what, long amount, Actionable type) {
        return placementStrategies.placeInWorld(what, amount, type, placeBlock != YesNo.YES);
    }

    @Override
    public void mountInventories(IStorageMounts mounts) {
        // Update the filter at least once before registering the inventory
        updateFilter();
        mounts.mount(inventory, priority);
    }

    /** The storage the network sees (insert-only). */
    public MEStorage getInventory() {
        return inventory;
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
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

    /**
     * Creates a partition list to filter stacks being injected into the plane against. If an inverter card is
     * present, it's a blacklist. If a fuzzy card is present and the key supports fuzzy search, it'll be a list with
     * fuzzy support.
     */
    private IPartitionList createFilter() {
        var builder = IPartitionList.builder();
        if (upgrades.isInstalled(UpgradeCard.FUZZY)) {
            builder.fuzzyMode(fuzzyMode);
        }
        var slotsToUse = 18 + upgrades.getInstalledUpgrades(UpgradeCard.CAPACITY) * 9;
        for (var x = 0; x < this.config.size() && x < slotsToUse; x++) {
            builder.add(this.config.getKey(x));
        }
        return builder.build();
    }

    /** Models the block adjacent to this formation plane as storage. */
    private final class InWorldStorage implements MEStorage {
        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            if (filter != null && !filter.matchesFilter(what, filterMode)) {
                return 0;
            }

            return placeInWorld(what, amount, mode);
        }

        @Override
        public String getDescription() {
            return ITEM_ID;
        }
    }

    /** Saves config, upgrades, priority and settings. */
    public String toJson() {
        var map = new LinkedHashMap<String, Object>();
        map.put("cfg", config.toJsonList());
        map.put("up", upgrades.toItemIds());
        map.put("prio", priority);
        map.put("fz", fuzzyMode.name());
        map.put("place", placeBlock.name());
        return MiniJson.write(map);
    }

    public void fromJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        var map = MiniJson.parseObject(json);
        priority = MiniJson.getInt(map, "prio", 0);
        fuzzyMode = MiniJson.getEnum(map, "fz", FuzzyMode.class, FuzzyMode.IGNORE_ALL);
        placeBlock = MiniJson.getEnum(map, "place", YesNo.class, YesNo.YES) == YesNo.NO ? YesNo.NO : YesNo.YES;
        upgrades.fromItemIds(MiniJson.getList(map, "up"));
        config.fromJsonList(MiniJson.getList(map, "cfg"));
        onConfigurationChanged();
        remountStorage();
    }
}
