/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.energy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * The set of {@link EnergyService}s that share their energy through {@link IEnergyOverlayGridConnection}s (quartz
 * fibers), shared by all of its members. Port of {@code appeng.me.service.EnergyOverlayGrid}.
 * <p>
 * A grid without such connections is an overlay grid of its own. Deviation: if a member still holds an older overlay
 * grid while a new one is built, the older one is invalidated instead of logging an error.
 */
final class EnergyOverlayGrid {
    /** Prefer grids with high energy storage for operations by sorting them to the front of the list. */
    private static final Comparator<EnergyService> SERVICE_COMPARATOR = Comparator
            .comparingDouble(EnergyService::getMaxStoredPower)
            .reversed();

    final List<EnergyService> energyServices;
    /** Which passive energy generator is currently active (one per overlay grid). */
    private IPassiveEnergyGenerator currentPassiveGenerator;

    private EnergyOverlayGrid(List<EnergyService> energyServices) {
        this.energyServices = energyServices;
    }

    void invalidate() {
        currentPassiveGenerator = null;
        for (var service : energyServices) {
            if (service.overlayGrid == this) {
                service.overlayGrid = null;
            }
        }
    }

    IPassiveEnergyGenerator getCurrentPassiveGenerator() {
        return currentPassiveGenerator;
    }

    void setCurrentPassiveGenerator(IPassiveEnergyGenerator generator) {
        this.currentPassiveGenerator = generator;
    }

    /** Builds the overlay grid of all energy services reachable from {@code startingService} and assigns it. */
    static void buildCache(EnergyService startingService) {
        var connected = Collections.newSetFromMap(new IdentityHashMap<EnergyService, Boolean>());
        var ordered = new ArrayList<EnergyService>();
        var open = new ArrayDeque<EnergyService>();
        open.add(startingService);
        while (!open.isEmpty()) {
            var service = open.pop();
            if (connected.add(service)) {
                ordered.add(service);
                for (var provider : service.getOverlayGridConnections()) {
                    open.addAll(provider.connectedEnergyServices());
                }
            }
        }

        for (var service : ordered) {
            if (service.overlayGrid != null) {
                service.overlayGrid.invalidate();
            }
        }

        ordered.sort(SERVICE_COMPARATOR);
        var overlayGrid = new EnergyOverlayGrid(List.copyOf(ordered));
        for (var service : ordered) {
            service.overlayGrid = overlayGrid;
        }
    }
}
