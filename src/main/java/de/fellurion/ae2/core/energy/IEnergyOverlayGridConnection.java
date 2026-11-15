/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.energy;

import java.util.Collection;

/**
 * Node service that makes the energy of other grids available to the node's grid without merging the grids. Port of
 * {@code appeng.me.energy.IEnergyOverlayGridConnection}; the quartz fiber provides it on both of its nodes.
 * <p>
 * All {@link EnergyService}s that are reachable through such connections form one overlay energy grid: energy is
 * extracted from and injected into every member, each member once. Because the answer is cached, call
 * {@link EnergyService#invalidateOverlayEnergyGrid()} when it changes for a reason other than the node joining or
 * leaving a grid.
 */
@FunctionalInterface
public interface IEnergyOverlayGridConnection {
    /**
     * The energy services behind this connection. May contain duplicates and the asking grid's own service; every
     * service is visited only once.
     */
    Collection<EnergyService> connectedEnergyServices();
}
