/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.crafting;

/**
 * Port of {@code appeng.api.networking.crafting.CraftingSubmitErrorCode}.
 */
public enum CraftingSubmitErrorCode {
    /** The plan is a simulation (missing items). */
    INCOMPLETE_PLAN,
    /** No crafting CPU exists in the grid. */
    NO_CPU_FOUND,
    /** CPUs exist but none is suitable (detail: {@link UnsuitableCpus}). */
    NO_SUITABLE_CPU_FOUND,
    CPU_BUSY,
    CPU_OFFLINE,
    CPU_TOO_SMALL,
    /** An ingredient could not be extracted from the network (detail: the missing {@code GenericStack}). */
    MISSING_INGREDIENT
}
