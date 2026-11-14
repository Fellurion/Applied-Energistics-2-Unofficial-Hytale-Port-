/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.energy;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;

/**
 * Something AE energy can be extracted from. Port of {@code appeng.api.networking.energy.IEnergySource}.
 */
public interface IEnergySource {
    /**
     * Extract power from the source.
     *
     * @param amt        amount (AE) to extract
     * @param mode       simulate or modulate
     * @param multiplier usage multiplier, see {@link PowerMultiplier}
     * @return amount of energy extracted
     */
    double extractAEPower(double amt, Actionable mode, PowerMultiplier multiplier);
}
