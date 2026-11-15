/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.wireless;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IGridNodeListener;
import de.fellurion.ae2.core.grid.MEWorld;

/**
 * Logic of the ME Wireless Access Point. Port of {@code appeng.blockentity.networking.WirelessAccessPointBlockEntity}.
 * <ul>
 * <li>One slot of wireless boosters; the number of boosters sets range and idle power.</li>
 * <li>Range: {@code 16 + boosters^1.5} blocks ({@code AEConfig.wireless_getMaxRange}).</li>
 * <li>Idle power: {@code 8 + boosters^(1 + boosters/64)} AE/t ({@code AEConfig.wireless_getPowerDrain}).</li>
 * <li>Active while the node is online (powered and has its channel).</li>
 * </ul>
 * Usage: pass the logic as owner to {@code MEWorld.addNode} (node type {@code wireless_access_point}) and call
 * {@link #setBoosters} whenever the booster slot changes.
 */
public final class WirelessAccessPointLogic implements IWirelessAccessPoint, IGridNodeListener {
    private GridNode node;
    private int boosters;

    /** Number of wireless boosters in the slot. */
    public int getBoosters() {
        return boosters;
    }

    /** Sets the number of boosters in the slot and adjusts the node's idle power. */
    public void setBoosters(int count) {
        int n = Math.max(0, count);
        if (n != boosters) {
            boosters = n;
            updatePower();
        }
    }

    /** Idle power in AE/t for the current number of boosters. */
    public double getIdlePowerUsage() {
        return AEConfig.get().wirelessPowerDrain(boosters);
    }

    private void updatePower() {
        if (node != null) {
            node.setIdlePowerUsage(getIdlePowerUsage());
        }
    }

    @Override
    public double getRange() {
        return AEConfig.get().wirelessMaxRange(boosters);
    }

    @Override
    public boolean isActive() {
        return node != null && node.isOnline();
    }

    @Override
    public Grid getGrid() {
        return node == null ? null : node.getGrid();
    }

    @Override
    public MEWorld getWorld() {
        return node == null ? null : node.getWorld();
    }

    @Override
    public long getPos() {
        return node == null ? 0L : node.getPos();
    }

    @Override
    public GridNode getActionableNode() {
        return node;
    }

    @Override
    public void onNodeAttached(GridNode node) {
        this.node = node;
        updatePower();
    }

    @Override
    public void onNodeRemoved(GridNode node) {
        this.node = null;
    }
}
