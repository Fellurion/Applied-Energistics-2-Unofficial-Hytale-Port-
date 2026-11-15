/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.NodeState;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Common logic of the level emitters. Port of {@code appeng.parts.automation.AbstractLevelEmitterPart}.
 * <ul>
 * <li>The emitter compares the last reported value (amount in the network, stored energy) with its reporting value
 * (the threshold): {@link RedstoneMode#HIGH_SIGNAL} emits while {@code value >= threshold},
 * {@link RedstoneMode#LOW_SIGNAL} while {@code value < threshold}. Subclasses with a "direct output" (crafting card)
 * decide on their own.</li>
 * <li>It only emits while its node is active (powered and booted; level emitters need no channel).</li>
 * <li>The output only changes in {@link #updateState()}; the integration is told through the
 * {@link OutputListener} and reads it with {@link #isEmitting()}. Like AE2 the last output is persisted, so a
 * freshly loaded emitter reports it until its grid has told it otherwise.</li>
 * </ul>
 * Usage: pass the logic as owner to {@code MEWorld.addNode(pos, NodeType.LEVEL_EMITTER, facing, color, logic)}.
 * <p>
 * Deviations: the output is a boolean instead of redstone strength 15 / 0; the comparison is written without
 * {@code lastReportedValue + 1} so that huge amounts cannot overflow; negative reporting values are clamped to 0 (the
 * AE2 screen cannot enter them); memory card import/export is left to the integration.
 */
public abstract class AbstractLevelEmitterLogic implements IGridNodeListener, IActionHost {

    /** Receives the new output whenever {@link #isEmitting()} changes. */
    @FunctionalInterface
    public interface OutputListener {
        void onOutputChanged(boolean emitting);
    }

    protected GridNode node;
    private boolean prevState;
    protected long lastReportedValue;
    private long reportingValue;
    private RedstoneMode redstoneMode = RedstoneMode.HIGH_SIGNAL;
    private OutputListener outputListener;
    private Runnable saveListener;

    // ------------------------------------------------------------------------------------------------------------
    // Configuration

    /** Called with the new output whenever it changes. */
    public void setOutputListener(OutputListener listener) {
        this.outputListener = listener;
    }

    /** Called whenever persistent state changed (settings, threshold, output). */
    public void setSaveListener(Runnable listener) {
        this.saveListener = listener;
    }

    protected void markDirty() {
        if (saveListener != null) {
            saveListener.run();
        }
    }

    /** AE2 setting {@code REDSTONE_EMITTER}: {@link RedstoneMode#HIGH_SIGNAL} (default) or {@code LOW_SIGNAL}. */
    public RedstoneMode getRedstoneMode() {
        return redstoneMode;
    }

    /**
     * @throws IllegalArgumentException for modes other than {@link RedstoneMode#HIGH_SIGNAL} and
     *                                  {@link RedstoneMode#LOW_SIGNAL}
     */
    public void setRedstoneMode(RedstoneMode mode) {
        if (mode != RedstoneMode.HIGH_SIGNAL && mode != RedstoneMode.LOW_SIGNAL) {
            throw new IllegalArgumentException("Level emitters only support HIGH_SIGNAL and LOW_SIGNAL, not " + mode);
        }
        if (this.redstoneMode != mode) {
            this.redstoneMode = mode;
            onSettingChanged();
        }
    }

    /** Port of {@code onSettingChanged}: every setting change re-registers the watchers. */
    protected final void onSettingChanged() {
        configureWatchers();
        markDirty();
    }

    /** The threshold ("reporting value"). */
    public final long getReportingValue() {
        return reportingValue;
    }

    public final void setReportingValue(long v) {
        v = Math.max(0, v);
        if (this.reportingValue == v) {
            return;
        }
        this.reportingValue = v;
        onReportingValueChanged();
        updateState();
        markDirty();
    }

    /** The value the threshold was last compared with (amount in the network, stored energy). */
    public final long getLastReportedValue() {
        return lastReportedValue;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Hooks

    /** Registers what the emitter watches on its grid and refreshes the reported value. Ends in {@link #updateState}. */
    protected abstract void configureWatchers();

    protected abstract boolean hasDirectOutput();

    protected abstract boolean getDirectOutput();

    protected void onReportingValueChanged() {
    }

    // ------------------------------------------------------------------------------------------------------------
    // Output

    /** The current output (AE2: {@code isProvidingStrongPower() > 0}). */
    public final boolean isEmitting() {
        return prevState;
    }

    protected void updateState() {
        var isOn = isLevelEmitterOn();
        if (prevState != isOn) {
            prevState = isOn;
            markDirty();
            if (outputListener != null) {
                outputListener.onOutputChanged(isOn);
            }
        }
    }

    /** What the output should be right now. */
    protected boolean isLevelEmitterOn() {
        if (node == null || !node.isActive()) {
            return false;
        }
        if (hasDirectOutput()) {
            return getDirectOutput();
        }
        boolean flipState = redstoneMode == RedstoneMode.LOW_SIGNAL;
        // AE2: flipState ? reportingValue >= lastReportedValue + 1 : reportingValue < lastReportedValue + 1
        return flipState ? reportingValue > lastReportedValue : reportingValue <= lastReportedValue;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Node

    /** The grid of the node, or null while the emitter is not part of one. */
    protected final Grid grid() {
        return node == null ? null : node.getGrid();
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
        // The watchers were handed over while the node was created; only now the grid can be read.
        configureWatchers();
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        // Keeps the last output: it is what gets saved (AE2 "prevState").
        this.node = null;
    }

    @Override
    public void onStateChanged(GridNode node, NodeState state) {
        if (state != NodeState.CONNECTIONS && node.hasGridBooted()) {
            updateState();
        }
    }

    @Override
    public GridNode getActionableNode() {
        return node;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Persistence

    protected Map<String, Object> writeJsonMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("lastReportedValue", lastReportedValue);
        map.put("reportingValue", reportingValue);
        map.put("prevState", prevState);
        map.put("rs", redstoneMode.name());
        return map;
    }

    protected void readJsonMap(Map<String, Object> map) {
        lastReportedValue = MiniJson.getLong(map, "lastReportedValue", 0);
        reportingValue = Math.max(0, MiniJson.getLong(map, "reportingValue", 0));
        prevState = MiniJson.getBool(map, "prevState", false);
        var mode = MiniJson.getEnum(map, "rs", RedstoneMode.class, RedstoneMode.HIGH_SIGNAL);
        redstoneMode = mode == RedstoneMode.LOW_SIGNAL ? RedstoneMode.LOW_SIGNAL : RedstoneMode.HIGH_SIGNAL;
    }

    /** Saves settings, threshold and the last output as JSON. */
    public String saveState() {
        return MiniJson.write(writeJsonMap());
    }

    /**
     * Loads state written by {@link #saveState()}. Meant to be called before the node is created; on an attached
     * emitter the watchers are registered again.
     */
    public void loadState(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        readJsonMap(MiniJson.parseObject(json));
        if (node != null) {
            configureWatchers();
        }
    }
}
