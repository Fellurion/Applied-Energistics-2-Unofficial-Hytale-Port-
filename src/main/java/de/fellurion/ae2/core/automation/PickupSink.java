/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.automation;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEKey;

/**
 * Where an annihilation plane puts what it picked up (the network storage, paying the insertion energy). Port of
 * {@code appeng.api.behaviors.PickupSink}.
 */
@FunctionalInterface
public interface PickupSink {
    /** @return the amount that was (or with {@link Actionable#SIMULATE} would be) stored */
    long insert(AEKey what, long amount, Actionable mode);
}
