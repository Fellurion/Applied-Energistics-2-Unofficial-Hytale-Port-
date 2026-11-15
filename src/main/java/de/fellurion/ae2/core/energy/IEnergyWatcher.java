/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.energy;

/**
 * Handle to register interest in the stored energy of a grid crossing certain amounts. Port of
 * {@code appeng.api.networking.energy.IEnergyWatcher}.
 */
public interface IEnergyWatcher {
    /**
     * Watches the given amount of stored energy (AE). The host is notified whenever the stored energy passes it in
     * either direction.
     *
     * @return true if the threshold was added, false if it was already watched
     */
    boolean add(double amount);

    /** Stops watching the given amount. */
    boolean remove(double amount);

    /** Removes all thresholds of this watcher. */
    void reset();
}
