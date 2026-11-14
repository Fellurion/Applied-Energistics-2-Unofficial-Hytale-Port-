/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.energy;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.AccessRestriction;
import de.fellurion.ae2.core.config.PowerMultiplier;

/**
 * Energy cell / dense energy cell. Port of {@code appeng.blockentity.networking.EnergyCellBlockEntity} with the values
 * of {@code AEBlocks}: energy cell 200,000 AE (item charge rate 800, priority 200), dense energy cell 1,600,000 AE
 * (charge rate 1600, priority 1600).
 * <p>
 * Use {@link #getFillLevel()} (0..4) for the block states {@code Fill_0}..{@code Fill_4}; persist
 * {@link #getAECurrentPower()} when the block is saved/broken.
 */
public final class EnergyCellLogic implements IAEPowerStorage {
    public static final int MAX_FULLNESS = 4;

    private final StoredEnergyAmount stored;
    private final double chargeRate;
    private final int priority;
    private Runnable changeListener;

    public EnergyCellLogic(double maxPower, double chargeRate, int priority) {
        this.stored = new StoredEnergyAmount(0, maxPower);
        this.chargeRate = chargeRate;
        this.priority = priority;
    }

    /** {@code energy_cell}: 200k AE. */
    public static EnergyCellLogic energyCell() {
        return new EnergyCellLogic(200_000, 800, 200);
    }

    /** {@code dense_energy_cell}: 1.6M AE. */
    public static EnergyCellLogic denseEnergyCell() {
        return new EnergyCellLogic(1_600_000, 1600, 1600);
    }

    /** Called (synchronously) whenever the stored amount changes; e.g. to mark the block dirty. */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    @Override
    public double injectAEPower(double amt, Actionable mode) {
        var inserted = stored.insert(amt, mode == Actionable.MODULATE);
        if (mode == Actionable.MODULATE && inserted > 0) {
            changed();
        }
        return amt - inserted;
    }

    @Override
    public double extractAEPower(double amt, Actionable mode, PowerMultiplier pm) {
        var extracted = stored.extract(pm.multiply(amt), mode == Actionable.MODULATE);
        if (mode == Actionable.MODULATE && extracted > 0) {
            changed();
        }
        return pm.divide(extracted);
    }

    private void changed() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    @Override
    public double getAEMaxPower() {
        return stored.getMaximum();
    }

    @Override
    public double getAECurrentPower() {
        return stored.getAmount();
    }

    public void setAECurrentPower(double amount) {
        stored.setStored(amount);
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
    public int getPriority() {
        return priority;
    }

    /** AE/t when the cell item is charged in a charger. */
    public double getChargeRate() {
        return chargeRate;
    }

    /** 0..4 for block states, {@code floor(4 · clamp(fill + 0.01, 0, 1))}. */
    public int getFillLevel() {
        return getStorageLevelFromFillFactor(stored.getAmount() / stored.getMaximum());
    }

    public static int getStorageLevelFromFillFactor(double fillFactor) {
        return (int) Math.floor(MAX_FULLNESS * StoredEnergyAmount.clamp(fillFactor + 0.01, 0, 1));
    }
}
