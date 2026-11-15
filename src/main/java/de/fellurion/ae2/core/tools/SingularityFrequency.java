/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Frequencies of Quantum Entangled Singularities: both singularities of a pair carry the same number, a Quantum
 * Network Bridge connects to the bridge that holds the other one. Port of
 * {@code appeng.blockentity.qnb.QuantumBridgeBlockEntity#assignFrequency}.
 */
public final class SingularityFrequency {

    private static final AtomicLong SEED = new AtomicLong();

    private SingularityFrequency() {
    }

    /**
     * A new frequency, never 0. AE2: {@code new Date().getTime() * 100 + singularitySeed++ % 100} – the time in
     * milliseconds times 100 plus a counter, so that up to 100 pairs made in the same millisecond stay distinct.
     */
    public static long next() {
        return next(System.currentTimeMillis());
    }

    static long next(long timeMillis) {
        long frequency = timeMillis * 100 + SEED.getAndIncrement() % 100;
        return frequency == 0 ? 1 : frequency;
    }
}
