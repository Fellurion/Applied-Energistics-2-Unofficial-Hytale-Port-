/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.crafting;

import java.util.UUID;

/**
 * Handle of a submitted crafting job. Port of {@code appeng.api.networking.crafting.ICraftingLink}.
 */
public interface ICraftingLink {
    boolean isCanceled();

    boolean isDone();

    void cancel();

    /** Standalone links have no requester (e.g. player requests from a terminal). */
    boolean isStandalone();

    UUID getCraftingID();
}
