/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.config;

/**
 * Whether an operation should only be simulated or actually performed.
 * <p>
 * Port of {@code appeng.api.config.Actionable}.
 */
public enum Actionable {
    /** Perform the action. */
    MODULATE,
    /** Only simulate the action, nothing is changed. */
    SIMULATE;

    public boolean isSimulate() {
        return this == SIMULATE;
    }

    public static Actionable ofSimulate(boolean simulate) {
        return simulate ? SIMULATE : MODULATE;
    }
}
