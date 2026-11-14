/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.grid;

/**
 * Reasons for node status notifications. Port of {@code IGridNodeListener.State} plus {@link #CONNECTIONS}.
 */
public enum NodeState {
    /** The network's power state changed. */
    POWER,
    /** The node's channel count changed (also used for smart cable visuals). */
    CHANNEL,
    /** The network finished (re)booting / channel assignment. */
    GRID_BOOT,
    /** The node's in-world connections changed (cable shape mask). Not in AE2 (handled by block updates there). */
    CONNECTIONS
}
