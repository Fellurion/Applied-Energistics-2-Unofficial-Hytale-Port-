/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.machines;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.energy.IPassiveEnergyGenerator;

import java.util.function.DoubleSupplier;

/**
 * Crystal resonance generator: a passive generator injecting {@code crystalResonanceGeneratorRate} (20) AE/t. Only
 * the strongest generator per grid is active; the others are suppressed (shown via {@link #isSuppressed()}). Port of
 * {@code appeng.blockentity.networking.CrystalResonanceGeneratorBlockEntity}. Connects on its back side only.
 */
public final class CrystalResonanceGeneratorLogic implements IPassiveEnergyGenerator {
    private final DoubleSupplier rate;
    private boolean suppressed;
    private Runnable stateListener;

    public CrystalResonanceGeneratorLogic() {
        this(() -> AEConfig.get().crystalResonanceGeneratorRate());
    }

    /** Generator with a custom rate (AE/t). */
    public CrystalResonanceGeneratorLogic(DoubleSupplier rate) {
        this.rate = rate;
    }

    /** Called when the suppressed flag changes (visuals). */
    public void setStateListener(Runnable listener) {
        this.stateListener = listener;
    }

    @Override
    public double getRate() {
        return rate.getAsDouble();
    }

    @Override
    public boolean isSuppressed() {
        return suppressed;
    }

    @Override
    public void setSuppressed(boolean suppressed) {
        if (this.suppressed != suppressed) {
            this.suppressed = suppressed;
            if (stateListener != null) {
                stateListener.run();
            }
        }
    }
}
