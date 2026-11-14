/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.energy;

/**
 * A generator that injects a fixed amount of AE each tick into the grid (crystal resonance generator). Only the
 * strongest generator of a grid is active, all others are suppressed.
 * <p>
 * Port of {@code appeng.api.networking.energy.IPassiveEnergyGenerator}.
 */
public interface IPassiveEnergyGenerator {
    /** AE per tick. */
    double getRate();

    boolean isSuppressed();

    void setSuppressed(boolean suppressed);
}
