/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.config;

/**
 * When the ME IO Port moves a storage cell from its input to its output slots. Port of
 * {@code appeng.api.config.FullnessMode}.
 */
public enum FullnessMode {
    /** "Move to output when empty." */
    EMPTY,
    /** "Move to output when work is done." (as soon as nothing more can be moved within one operation) */
    HALF,
    /** "Move to output when full." */
    FULL
}
