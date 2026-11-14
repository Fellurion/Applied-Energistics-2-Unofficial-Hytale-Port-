/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.energy;

/**
 * A clamped double energy buffer. Port of {@code appeng.me.energy.StoredEnergyAmount}.
 * <p>
 * Deviation: AE2 emits {@code GridPowerStorageStateChanged} events when the buffer starts providing/receiving
 * again; this port's {@link EnergyService} simply iterates all providers each time, so no events are needed.
 */
public final class StoredEnergyAmount {
    public static final double MIN_AMOUNT = 0.000001;
    public static final double MAX_MAXIMUM = (double) (Long.MAX_VALUE / 10000);

    private double maximum;
    private double stored;

    public StoredEnergyAmount(double stored, double maximum) {
        this.maximum = Math.min(MAX_MAXIMUM, maximum);
        this.stored = clamp(stored, 0, this.maximum);
    }

    public double getAmount() {
        return stored;
    }

    public double getMaximum() {
        return maximum;
    }

    /** Increase the amount, and return the amount that could actually be inserted. */
    public double insert(double amount, boolean commit) {
        if (amount < MIN_AMOUNT) {
            return 0;
        }
        double inserted = Math.min(amount, maximum - stored);
        if (commit) {
            setStored(stored + inserted);
        }
        return inserted;
    }

    /** Decrease the amount, and return how much was actually extracted. */
    public double extract(double amount, boolean commit) {
        if (amount < MIN_AMOUNT) {
            return 0;
        }
        double extracted = Math.min(amount, stored);
        if (commit) {
            setStored(stored - extracted);
        }
        return extracted;
    }

    public void setStored(double amount) {
        if (amount < MIN_AMOUNT) {
            amount = 0;
        }
        this.stored = clamp(amount, 0, maximum);
    }

    public void setMaximum(double maximum) {
        this.maximum = Math.min(MAX_MAXIMUM, maximum);
        this.stored = clamp(stored, 0, this.maximum);
    }

    public double remainingCapacity() {
        return maximum - stored;
    }

    static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
