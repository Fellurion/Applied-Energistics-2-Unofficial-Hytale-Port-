/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.NodeState;
import de.fellurion.ae2.core.grid.ticking.IGridTickable;
import de.fellurion.ae2.core.grid.ticking.TickRateModulation;
import de.fellurion.ae2.core.grid.ticking.TickingRequest;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.LinkedHashMap;
import java.util.function.Consumer;

/**
 * Growth accelerator. Port of {@code appeng.blockentity.misc.GrowthAcceleratorBlockEntity}.
 * <ul>
 * <li>Ticks every {@code AEConfig.growthAcceleratorSpeed()} (10) game ticks.</li>
 * <li>Powered if the grid is powered (idle drain 8 AE/t, see {@code NodeType.GROWTH_ACCELERATOR}) or its local crank
 * buffer (max 10 cranks = 1600 AE) holds at least 8 AE.</li>
 * <li>Each tick while powered it drains {@code 8 · ticks} AE from the local buffer (in addition to the network idle
 * drain, as in AE2) and random-ticks all 6 neighbours: the integration's {@code neighbourTicker} is called for each
 * direction and should random-tick growth-acceleratable blocks there (budding quartz, quartz buds).</li>
 * </ul>
 * Crank: front and back sides ({@link #getCrankable()}).
 */
public final class GrowthAcceleratorLogic implements IGridTickable, IGridNodeListener {
    /** Allow storage of up to 10 cranks. */
    public static final int MAX_STORED_POWER = 10 * CrankLogic.POWER_PER_CRANK_TURN;
    /** AE per tick. */
    public static final int POWER_PER_TICK = 8;

    private final PoweredMachineBuffer buffer = new PoweredMachineBuffer(MAX_STORED_POWER);
    private final Consumer<Direction> neighbourTicker;
    private GridNode node;
    private boolean lastPowered;
    private Runnable stateListener;

    /**
     * @param neighbourTicker called for each of the 6 directions on every accelerator tick while powered
     */
    public GrowthAcceleratorLogic(Consumer<Direction> neighbourTicker) {
        this.neighbourTicker = neighbourTicker;
    }

    /** Called when {@link #isPowered()} changes (block state). */
    public void setStateListener(Runnable listener) {
        this.stateListener = listener;
    }

    public PoweredMachineBuffer getBuffer() {
        return buffer;
    }

    public ICrankable getCrankable() {
        return buffer.crankable();
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
    }

    @Override
    public void onStateChanged(GridNode node, NodeState state) {
        if (state == NodeState.POWER) {
            checkPoweredChanged();
        }
    }

    /** Grid powered or local buffer has at least 8 AE. */
    public boolean isPowered() {
        return node != null && node.isPowered()
                || buffer.extractAEPower(POWER_PER_TICK, Actionable.SIMULATE) >= POWER_PER_TICK;
    }

    @Override
    public TickingRequest getTickingRequest(GridNode node) {
        int speed = AEConfig.get().growthAcceleratorSpeed();
        return new TickingRequest(speed, speed, false);
    }

    @Override
    public TickRateModulation tickingRequest(GridNode node, int ticksSinceLastCall) {
        onTick(ticksSinceLastCall);
        return TickRateModulation.SAME;
    }

    /** One accelerator step (also usable without the tick manager). */
    public void onTick(int ticksSinceLastCall) {
        var powered = checkPoweredChanged();
        if (!powered) {
            return;
        }
        // We drain local power in *addition* to network power, which is handled via idle power consumption
        buffer.extractAEPower(POWER_PER_TICK * ticksSinceLastCall, Actionable.MODULATE);
        for (var direction : Direction.values()) {
            neighbourTicker.accept(direction);
        }
    }

    private boolean checkPoweredChanged() {
        var powered = isPowered();
        if (powered != lastPowered) {
            lastPowered = powered;
            if (stateListener != null) {
                stateListener.run();
            }
        }
        return powered;
    }

    public String saveState() {
        var map = new LinkedHashMap<String, Object>();
        map.put("power", buffer.getAECurrentPower());
        return MiniJson.write(map);
    }

    public void loadState(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        buffer.setAECurrentPower(MiniJson.getDouble(MiniJson.parseObject(json), "power", 0));
    }
}
