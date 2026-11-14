/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.grid;

/**
 * Something that acts on the network through a grid node. Port of
 * {@code appeng.api.networking.security.IActionHost}.
 */
public interface IActionHost {
    /** The node that performs actions, or null if not connected. */
    GridNode getActionableNode();
}
