/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.grid;

/**
 * Port of {@code appeng.api.networking.pathing.ControllerState}.
 */
public enum ControllerState {
    /** No controller blocks are present in the network (ad-hoc network, max 8 channels). */
    NO_CONTROLLER,
    /** Controller rules followed. */
    CONTROLLER_ONLINE,
    /** Controller rules not followed (too large, disconnected or crossed): all channels are lost. */
    CONTROLLER_CONFLICT
}
