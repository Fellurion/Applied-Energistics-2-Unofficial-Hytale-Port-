/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.config;

/**
 * Multiplier applied to AE energy usage.
 * <p>
 * Port of {@code appeng.api.config.PowerMultiplier}. {@link #CONFIG} uses {@link AEConfig#powerUsageMultiplier()}
 * (default 1.0); {@link #ONE} is always 1.0.
 * <p>
 * Deviation: AE2 stores the multiplier in a mutable enum field; here it is read from {@link AEConfig} on every call.
 */
public enum PowerMultiplier {
    ONE,
    CONFIG;

    public double multiplier() {
        return this == ONE ? 1.0 : AEConfig.get().powerUsageMultiplier();
    }

    public double multiply(double in) {
        return in * multiplier();
    }

    public double divide(double in) {
        return in / multiplier();
    }
}
