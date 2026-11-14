/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.AccessRestriction;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.energy.IAEPowerStorage;
import de.fellurion.ae2.core.energy.StoredEnergyAmount;

/**
 * Internal energy buffer of a powered machine (inscriber, charger, growth accelerator). Port of the energy part of
 * {@code appeng.blockentity.powersink.AEBasePoweredBlockEntity} and of
 * {@code AENetworkedPoweredBlockEntity.Crankable}.
 * <p>
 * The buffer is <b>not</b> public network storage (AE2 default {@code internalPublicPowerStorage = false}): the grid
 * neither drains nor fills it. It is filled by cranks ({@link #crankable()}) or by the machine itself.
 */
public final class PoweredMachineBuffer implements IAEPowerStorage {
    private final StoredEnergyAmount stored;

    public PoweredMachineBuffer(double maxPower) {
        this.stored = new StoredEnergyAmount(0, maxPower);
    }

    /**
     * Inject energy.
     *
     * @return the amount that could NOT be stored
     */
    @Override
    public double injectAEPower(double amt, Actionable mode) {
        return amt - stored.insert(amt, mode == Actionable.MODULATE);
    }

    @Override
    public double extractAEPower(double amt, Actionable mode, PowerMultiplier multiplier) {
        return multiplier.divide(extractAEPower(multiplier.multiply(amt), mode));
    }

    /** Raw extraction without multiplier ({@code AEBasePoweredBlockEntity.extractAEPower(double, Actionable)}). */
    public double extractAEPower(double amt, Actionable mode) {
        return stored.extract(amt, mode == Actionable.MODULATE);
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

    public void setAEMaxPower(double max) {
        stored.setMaximum(max);
    }

    public boolean isFull() {
        return stored.getAmount() >= stored.getMaximum();
    }

    @Override
    public boolean isAEPublicPowerStorage() {
        return false;
    }

    @Override
    public AccessRestriction getPowerFlow() {
        return AccessRestriction.READ_WRITE;
    }

    /**
     * A crank target feeding this buffer: can turn while not full, each turn injects
     * {@link CrankLogic#POWER_PER_CRANK_TURN} AE.
     */
    public ICrankable crankable() {
        return new ICrankable() {
            @Override
            public boolean canTurn() {
                return getAECurrentPower() < getAEMaxPower();
            }

            @Override
            public void applyTurn() {
                injectAEPower(CrankLogic.POWER_PER_CRANK_TURN, Actionable.MODULATE);
            }
        };
    }
}
