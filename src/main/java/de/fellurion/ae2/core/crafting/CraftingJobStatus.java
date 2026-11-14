/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.stacks.GenericStack;

/**
 * Status of a running job for UIs. Port of {@code appeng.api.networking.crafting.CraftingJobStatus}.
 *
 * @param crafting        the final output of the job
 * @param totalItems      total work units
 * @param progress        completed work units
 * @param elapsedTimeNanos elapsed time in nanoseconds
 * @param suspended       whether the job is suspended
 */
public record CraftingJobStatus(GenericStack crafting, long totalItems, long progress, long elapsedTimeNanos,
        boolean suspended) {
}
