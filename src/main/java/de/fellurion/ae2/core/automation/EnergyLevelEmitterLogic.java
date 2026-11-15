/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.energy.EnergyService;
import de.fellurion.ae2.core.energy.IEnergyWatcher;
import de.fellurion.ae2.core.energy.IEnergyWatcherNode;

/**
 * The ME Energy Level Emitter. Port of {@code appeng.parts.automation.EnergyLevelEmitterPart}: compares the energy
 * stored in the network (AE, rounded down) with its reporting value. It takes no upgrade cards.
 * <p>
 * The value comes from the energy watcher of the grid, which reports when the stored energy passes the threshold.
 * <p>
 * Deviation: AE2 keeps watching the old amount after the reporting value was changed (until a setting changes or the
 * part joins another grid); here a new reporting value is watched at once.
 */
public final class EnergyLevelEmitterLogic extends AbstractLevelEmitterLogic implements IEnergyWatcherNode {
    /** Item id of the block. */
    public static final String ITEM_ID = "AE2_Energy_Level_Emitter";

    private IEnergyWatcher energyWatcher;

    @Override
    public void updateWatcher(IEnergyWatcher newWatcher) {
        energyWatcher = newWatcher;
        configureWatchers();
    }

    @Override
    public void onThresholdPass(EnergyService energyGrid) {
        lastReportedValue = (long) energyGrid.getStoredPower();
        updateState();
    }

    @Override
    protected void configureWatchers() {
        if (energyWatcher != null) {
            energyWatcher.reset();
            energyWatcher.add(getReportingValue());
        }

        var grid = grid();
        if (grid != null) {
            // update to power...
            lastReportedValue = (long) grid.getEnergyService().getStoredPower();
        }
        updateState();
    }

    @Override
    protected void onReportingValueChanged() {
        configureWatchers();
    }

    @Override
    protected boolean hasDirectOutput() {
        return false;
    }

    @Override
    protected boolean getDirectOutput() {
        throw new UnsupportedOperationException("hasDirectOutput is false...");
    }
}
