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
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.config.TickRates;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.ticking.IGridTickable;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.grid.ticking.TickingRequest;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.partition.IPartitionList;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Common logic of import and export buses. Port of {@code appeng.parts.automation.IOBusPart} and
 * {@code UpgradeablePart}.
 * <ul>
 * <li>63 filter slots, of which {@code min(18 + 9 · capacity cards, 63)} are usable.</li>
 * <li>Operations per tick by acceleration (speed) cards: 1 / 8 / 32 / 64 / 96 (one operation = 1 item or
 * 125 mB).</li>
 * <li>Adaptive ticking between {@link TickRates#ImportBus} / {@link TickRates#ExportBus} min and max: FASTER after
 * work, SLOWER without, SLEEP while disabled by redstone.</li>
 * <li>Redstone card: IGNORE / HIGH_SIGNAL / LOW_SIGNAL / SIGNAL_PULSE (one operation round per rising edge). The
 * integration reports the block's redstone state with {@link #setRedstonePowered(boolean)}.</li>
 * </ul>
 * Usage: pass the logic as owner to {@code MEWorld.addNode(pos, NodeType.IMPORT_BUS, facing, color, logic)} and call
 * {@link #setTarget(ExternalStorage)} with the storage of the block in front (or null).
 */
public abstract class IOBusLogic implements IGridTickable, IGridNodeListener, IActionHost {
    private final TickRates tickRates;
    private final ConfigInventory config = ConfigInventory.configTypes(63);
    private final UpgradeInventory upgrades;
    protected final IActionSource source = IActionSource.ofMachine(this);
    private IPartitionList filter;
    private RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    private FuzzyMode fuzzyMode = FuzzyMode.IGNORE_ALL;
    private boolean hasRedstone;
    private boolean lastRedstone;
    private boolean pendingPulse;
    private ExternalStorage target;
    protected GridNode node;
    private Runnable saveListener;

    protected IOBusLogic(TickRates tickRates, String hostItemId) {
        this.tickRates = tickRates;
        this.upgrades = new UpgradeInventory(hostItemId, 5);
        this.upgrades.setChangeListener(this::upgradesChanged);
        this.config.changeListener(this::updateState);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Configuration

    /** Filter slots (types). Use {@code getConfig().setKey(slot, key)}. */
    public ConfigInventory getConfig() {
        return config;
    }

    public UpgradeInventory getUpgrades() {
        return upgrades;
    }

    public boolean isUpgradedWith(UpgradeCard card) {
        return upgrades.isInstalled(card);
    }

    public int getInstalledUpgrades(UpgradeCard card) {
        return upgrades.getInstalledUpgrades(card);
    }

    /** Called whenever persistent state changed (config, settings, upgrades). */
    public void setSaveListener(Runnable listener) {
        this.saveListener = listener;
    }

    protected void markDirty() {
        if (saveListener != null) {
            saveListener.run();
        }
    }

    public RedstoneMode getRedstoneMode() {
        return redstoneMode;
    }

    public void setRedstoneMode(RedstoneMode mode) {
        this.redstoneMode = mode;
        onSettingChanged();
    }

    public FuzzyMode getFuzzyMode() {
        return fuzzyMode;
    }

    public void setFuzzyMode(FuzzyMode mode) {
        this.fuzzyMode = mode;
        this.filter = null;
        onSettingChanged();
    }

    /**
     * The external inventory in front of the bus (null if none / not loaded). Like AE2's capability cache this does
     * not wake the bus; it picks the target up on its next scheduled tick.
     */
    public void setTarget(ExternalStorage target) {
        this.target = target;
    }

    public ExternalStorage getTarget() {
        return target;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Redstone

    private boolean isInPulseMode() {
        return redstoneMode == RedstoneMode.SIGNAL_PULSE;
    }

    /** Reports the redstone state of the bus block (port of {@code onRedstoneLevelMayHaveChanged}). */
    public void setRedstonePowered(boolean powered) {
        this.hasRedstone = powered;
        if (isInPulseMode()) {
            if (lastRedstone != powered) {
                lastRedstone = powered;
                if (lastRedstone && !pendingPulse) {
                    // Perform the action based on the pulse on the next tick
                    pendingPulse = true;
                    if (node != null) {
                        node.alert();
                    }
                }
            }
        } else {
            updateRedstoneState();
        }
    }

    /** Port of {@code UpgradeablePart.isSleeping} + {@code IOBusPart.isSleeping}. */
    protected boolean isSleeping() {
        if (isInPulseMode() && pendingPulse) {
            return false;
        }
        if (upgrades.isInstalled(UpgradeCard.REDSTONE)) {
            return switch (redstoneMode) {
                case IGNORE -> false;
                case HIGH_SIGNAL -> !hasRedstone;
                case LOW_SIGNAL -> hasRedstone;
                case SIGNAL_PULSE -> true;
            };
        }
        return false;
    }

    private void updateRedstoneState() {
        if (!isInPulseMode()) {
            pendingPulse = false;
        }
        if (node != null) {
            if (!isSleeping()) {
                node.wake();
            } else {
                node.sleep();
            }
        }
    }

    private void onSettingChanged() {
        updateRedstoneState();
        if (isInPulseMode()) {
            lastRedstone = hasRedstone;
        }
        markDirty();
    }

    protected void upgradesChanged() {
        updateState();
    }

    private void updateState() {
        filter = null; // rebuild the filter
        updateRedstoneState();
        markDirty();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Work

    /** Usable filter slots: {@code min(18 + 9 · capacity cards, 63)}. */
    protected int availableSlots() {
        return Math.min(18 + getInstalledUpgrades(UpgradeCard.CAPACITY) * 9, config.size());
    }

    /** Operations per tick: 1, 8, 32, 64, 96 with 0..4 acceleration cards. */
    protected int getOperationsPerTick() {
        return switch (getInstalledUpgrades(UpgradeCard.SPEED)) {
            case 1 -> 8;
            case 2 -> 32;
            case 3 -> 64;
            case 4 -> 96;
            default -> 1;
        };
    }

    protected final IPartitionList getFilter() {
        if (filter == null) {
            var builder = IPartitionList.builder();
            builder.addAll(config.keySet());
            if (isUpgradedWith(UpgradeCard.FUZZY)) {
                builder.fuzzyMode(fuzzyMode);
            }
            filter = builder.build();
        }
        return filter;
    }

    /** Bus can work if its node is active and there is a target. */
    protected final boolean canDoBusWork() {
        return node != null && node.isActive() && target != null;
    }

    @Override
    public final TickingRequest getTickingRequest(GridNode node) {
        return new TickingRequest(tickRates.getMin(), tickRates.getMax(), isSleeping());
    }

    @Override
    public final TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        if (isSleeping()) {
            return TickRateModulation.SLEEP;
        }
        if (!canDoBusWork()) {
            return TickRateModulation.IDLE;
        }
        // Reset a potential redstone pulse trigger
        this.pendingPulse = false;
        var hasDoneWork = doBusWork(node.getGrid());
        if (isSleeping()) {
            return TickRateModulation.SLEEP;
        }
        return hasDoneWork ? TickRateModulation.FASTER : TickRateModulation.SLOWER;
    }

    /** @return true if anything was transferred */
    protected abstract boolean doBusWork(Grid grid);

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
        // To correctly detect pulses, we need to know the current state when we are added to the world.
        this.lastRedstone = hasRedstone;
        if (pendingPulse) {
            node.alert();
        }
        updateRedstoneState();
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

    protected Map<String, Object> writeJsonMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("cfg", config.toJsonList());
        map.put("up", upgrades.toItemIds());
        map.put("rs", redstoneMode.name());
        map.put("fz", fuzzyMode.name());
        if (isInPulseMode() && pendingPulse) {
            map.put("pendingPulse", true);
        }
        return map;
    }

    protected void readJsonMap(Map<String, Object> map) {
        redstoneMode = MiniJson.getEnum(map, "rs", RedstoneMode.class, RedstoneMode.IGNORE);
        fuzzyMode = MiniJson.getEnum(map, "fz", FuzzyMode.class, FuzzyMode.IGNORE_ALL);
        upgrades.fromItemIds(MiniJson.getList(map, "up"));
        config.fromJsonList(MiniJson.getList(map, "cfg"));
        filter = null;
        pendingPulse = isInPulseMode() && MiniJson.getBool(map, "pendingPulse", false);
    }

    /** Saves config, upgrades and settings as JSON. */
    public String toJson() {
        return MiniJson.write(writeJsonMap());
    }

    /** Loads state written by {@link #toJson()}. */
    public void fromJson(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        readJsonMap(MiniJson.parseObject(json));
    }
}
