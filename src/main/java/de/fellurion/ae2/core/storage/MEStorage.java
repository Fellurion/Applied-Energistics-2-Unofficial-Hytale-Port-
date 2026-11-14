/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.storage;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.KeyCounter;

import java.util.Objects;

/**
 * An ME inventory: insert, extract and list arbitrary amounts of {@link AEKey}s, ignoring stack size limits.
 * <p>
 * Port of {@code appeng.api.storage.MEStorage}. Deviation: {@link #getDescription()} returns a plain string (AE2: a
 * Minecraft text component); it is meant as a translation key or display name for the integration layer.
 */
public interface MEStorage {
    /**
     * Returns whether this inventory is the preferred storage location for the given key when being compared to other
     * inventories of the same priority (e.g. it already contains the key, or is partitioned to it).
     */
    default boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        return false;
    }

    /**
     * Store new items, or simulate the addition of new items.
     *
     * @return the amount inserted
     */
    default long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        return 0;
    }

    /**
     * Extract the specified key.
     *
     * @return the amount extracted
     */
    default long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        return 0;
    }

    /** Adds the amounts of all available keys to {@code out}. */
    default void getAvailableStacks(KeyCounter out) {
    }

    /** Human readable description / translation key of this storage. */
    String getDescription();

    /** @return a new counter with this inventory's content */
    default KeyCounter getAvailableStacks() {
        var result = new KeyCounter();
        getAvailableStacks(result);
        return result;
    }

    /** A small over-estimate of the number of distinct keys stored. */
    default int getEstimatedStackCount() {
        return 1;
    }

    static void checkPreconditions(AEKey what, long amount, Actionable mode, IActionSource source) {
        Objects.requireNonNull(what, "Cannot pass a null key");
        Objects.requireNonNull(mode, "Cannot pass a null mode");
        Objects.requireNonNull(source, "Cannot pass a null source");
        if (amount < 0) {
            throw new IllegalArgumentException("Cannot pass a negative amount");
        }
    }
}
