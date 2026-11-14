/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.energy;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.Enums.AccessRestriction;

/**
 * An energy buffer that can be part of the network's energy storage (energy cells, machines' internal buffers).
 * Port of {@code appeng.api.networking.energy.IAEPowerStorage} (+ {@code IEnergyStorage}).
 * <p>
 * A grid node whose owner implements this interface and returns {@code true} from
 * {@link #isAEPublicPowerStorage()} is automatically registered as energy provider/requester by the
 * {@link EnergyService}.
 */
public interface IAEPowerStorage extends IEnergySource {
    /**
     * Inject energy.
     *
     * @return the amount that could NOT be injected
     */
    double injectAEPower(double amt, Actionable mode);

    double getAEMaxPower();

    double getAECurrentPower();

    /** True if the network may use this buffer as a provider/requester. */
    boolean isAEPublicPowerStorage();

    AccessRestriction getPowerFlow();

    /** Higher priorities are drained first and filled last. */
    default int getPriority() {
        return 0;
    }
}
