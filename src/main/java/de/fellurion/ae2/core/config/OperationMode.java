/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.config;

/**
 * Transfer direction of the ME IO Port. Port of {@code appeng.api.config.OperationMode}.
 */
public enum OperationMode {
    /** Network → storage cell ("Transfer data to Storage Cell"). */
    FILL,
    /** Storage cell → network ("Transfer data to Network"). */
    EMPTY
}
