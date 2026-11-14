/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.machines;

/**
 * A machine that can be powered by a crank. Port of {@code appeng.api.implementations.blockentities.ICrankable}.
 */
public interface ICrankable {
    /** Whether the crank may currently be turned (e.g. internal buffer not full). */
    boolean canTurn();

    /** Called once per completed crank rotation. */
    void applyTurn();
}
