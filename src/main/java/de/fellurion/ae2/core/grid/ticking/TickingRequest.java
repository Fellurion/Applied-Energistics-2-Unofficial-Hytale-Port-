/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.grid.ticking;

import de.fellurion.ae2.core.config.TickRates;

/**
 * Describes how a device wants to be ticked. Port of {@code appeng.api.networking.ticking.TickingRequest}.
 *
 * @param minTickRate     minimum number of game ticks between two calls
 * @param maxTickRate     maximum number of game ticks between two calls
 * @param isSleeping      whether the device starts asleep
 * @param initialTickRate initial rate (default (min+max)/2)
 */
public record TickingRequest(int minTickRate, int maxTickRate, boolean isSleeping, int initialTickRate) {
    public TickingRequest(int minTickRate, int maxTickRate, boolean isSleeping) {
        this(minTickRate, maxTickRate, isSleeping, (minTickRate + maxTickRate) / 2);
    }

    public TickingRequest(TickRates tickRates, boolean isSleeping) {
        this(tickRates.getMin(), tickRates.getMax(), isSleeping);
    }
}
