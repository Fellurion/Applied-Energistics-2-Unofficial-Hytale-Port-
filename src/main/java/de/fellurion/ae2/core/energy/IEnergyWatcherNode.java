/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.energy;

/**
 * Receives a notification when the stored energy of the grid passes a watched amount. Port of
 * {@code appeng.api.networking.energy.IEnergyWatcherNode}.
 * <p>
 * Node owners implementing this interface get a watcher automatically when joining a grid (energy level emitters).
 */
public interface IEnergyWatcherNode {
    /**
     * Called with the watcher of the grid the node just joined; the watcher of the previous grid is already reset.
     * Register the amounts to watch on it.
     */
    void updateWatcher(IEnergyWatcher newWatcher);

    /** Called at the end of a tick in which the stored energy passed one of the watched amounts. */
    void onThresholdPass(EnergyService energyGrid);
}
