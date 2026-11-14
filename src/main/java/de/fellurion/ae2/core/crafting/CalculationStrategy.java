/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.crafting;

/**
 * Port of {@code appeng.api.networking.crafting.CalculationStrategy}.
 */
public enum CalculationStrategy {
    /** If the full amount cannot be crafted, return a simulated plan that lists the missing items. */
    REPORT_MISSING_ITEMS,
    /** Try to craft less (binary search) if the full amount cannot be crafted; else report missing items. */
    CRAFT_LESS
}
