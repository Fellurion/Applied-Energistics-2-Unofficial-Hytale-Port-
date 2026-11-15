/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.quantum;

import java.util.HashMap;
import java.util.Map;

/**
 * The formed quantum network bridges of one world, found by the frequency of the singularity in their link chamber.
 * Port of the registry {@code appeng.api.features.Locatables.quantumNetworkBridges()} ({@code Locatables.Type}).
 * <p>
 * A frequency {@code f} has exactly two places, the keys {@code -f} and {@code f}: the first formed bridge holding
 * such a singularity registers under {@code -f}, the second under {@code f}, and each looks for its partner under
 * the other key. A third bridge finds both places taken and stays unlinked (see {@link QuantumCluster#updateStatus}).
 * <p>
 * Deviations: AE2 keeps one registry for all dimensions, this port one per world - a bridge joins two places of the
 * same world. AE2 unregisters by key alone; here an entry is only removed if it is the given bridge.
 */
public interface QuantumBridgeRegistry {
    /** The bridge registered under {@code key}, or null. */
    QuantumCluster get(long key);

    /** Registers {@code cluster} under {@code key}; an older entry is replaced. */
    void register(long key, QuantumCluster cluster);

    /** Removes the entry of {@code key} if it is {@code cluster}. */
    void unregister(long key, QuantumCluster cluster);

    /** A registry backed by a map; create one per world. */
    static QuantumBridgeRegistry create() {
        return new QuantumBridgeRegistry() {
            private final Map<Long, QuantumCluster> clusters = new HashMap<>();

            @Override
            public QuantumCluster get(long key) {
                return clusters.get(key);
            }

            @Override
            public void register(long key, QuantumCluster cluster) {
                clusters.put(key, cluster);
            }

            @Override
            public void unregister(long key, QuantumCluster cluster) {
                clusters.remove(key, cluster);
            }
        };
    }
}
