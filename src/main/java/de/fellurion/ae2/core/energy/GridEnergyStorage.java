/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.energy;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.AccessRestriction;
import de.fellurion.ae2.core.config.PowerMultiplier;

/**
 * Small energy buffer every grid has: {@code gridEnergyStoragePerNode} (25 AE) per node. Port of
 * {@code appeng.me.energy.GridEnergyStorage}. It has the lowest priority, so it is filled first and drained last.
 */
public final class GridEnergyStorage implements IAEPowerStorage {
    private final StoredEnergyAmount stored = new StoredEnergyAmount(0, 0);
    private int nodeCount;

    @Override
    public double injectAEPower(double amt, Actionable mode) {
        return amt - stored.insert(amt, mode == Actionable.MODULATE);
    }

    @Override
    public double extractAEPower(double amt, Actionable mode, PowerMultiplier usePowerMultiplier) {
        return usePowerMultiplier.divide(stored.extract(usePowerMultiplier.multiply(amt), mode == Actionable.MODULATE));
    }

    @Override
    public boolean isAEPublicPowerStorage() {
        return true;
    }

    @Override
    public AccessRestriction getPowerFlow() {
        return AccessRestriction.READ_WRITE;
    }

    @Override
    public double getAEMaxPower() {
        return stored.getMaximum();
    }

    @Override
    public double getAECurrentPower() {
        return stored.getAmount();
    }

    @Override
    public int getPriority() {
        return Integer.MIN_VALUE; // push it to the back
    }

    public void addNode() {
        nodeCount++;
        updateMaximum();
    }

    public double getNodeEnergyShare() {
        return nodeCount == 0 ? 0 : stored.getAmount() / nodeCount;
    }

    /** Removes a node, deducting its proportional share. @return the share that was removed */
    public double removeNode() {
        if (nodeCount < 1) {
            throw new IllegalStateException("Removing a node from energy storage while it has no nodes");
        }
        var deduction = getNodeEnergyShare();
        double removed = 0;
        if (deduction > 0) {
            removed = stored.extract(deduction, true);
        }
        nodeCount--;
        updateMaximum();
        return removed;
    }

    private void updateMaximum() {
        stored.setMaximum(AEConfig.get().gridEnergyStoragePerNode() * nodeCount);
    }
}
