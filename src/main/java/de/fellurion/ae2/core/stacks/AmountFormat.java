/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.stacks;

/**
 * How an amount of a key is written. Port of {@code appeng.api.stacks.AmountFormat}; used with
 * {@link AEKeyType#formatAmount(long, AmountFormat)}.
 */
public enum AmountFormat {
    /** The full amount, for example "1,234" items or "1.5 B" of a fluid. */
    FULL,
    /** A summary of at most four characters that fits into a slot, for example "1.2K" instead of 1234. */
    SLOT,
    /** A summary of at most three characters for a large font. */
    SLOT_LARGE_FONT
}
