/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.config;

/**
 * Min/max tick rates (in game ticks between two machine ticks) for adaptive ticking machines.
 * <p>
 * Port of {@code appeng.core.settings.TickRates} with the AE2 default values. The values are mutable to allow a
 * config file on the integration side to override them (like AE2's config does).
 */
public enum TickRates {
    Interface(5, 120),
    ImportBus(5, 40),
    ExportBus(5, 60),
    AnnihilationPlane(2, 120),
    METunnel(5, 20),
    Inscriber(1, 20),
    Charger(10, 10),
    IOPort(1, 5),
    VibrationChamber(10, 40),
    StorageBus(5, 60),
    ItemTunnel(5, 60),
    LightTunnel(5, 60);

    private final int defaultMin;
    private final int defaultMax;
    private int min;
    private int max;

    TickRates(int min, int max) {
        this.defaultMin = min;
        this.defaultMax = max;
        this.min = min;
        this.max = max;
    }

    public int getDefaultMin() {
        return defaultMin;
    }

    public int getDefaultMax() {
        return defaultMax;
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public void setMin(int min) {
        this.min = min;
    }

    public void setMax(int max) {
        this.max = max;
    }
}
