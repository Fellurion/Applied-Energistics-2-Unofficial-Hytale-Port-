/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.grid;

/**
 * Node behaviour flags. Port of {@code appeng.api.networking.GridFlags}.
 */
public enum GridFlag {
    /** Devices that use network features (buses, terminals, drives, ...). */
    REQUIRE_CHANNEL,
    /** Inner node of ME-P2P tunnels (not used yet). */
    COMPRESSED_CHANNEL,
    /** Cannot carry channels over this node (controller, quartz fiber). */
    CANNOT_CARRY,
    /** Outer node of ME-P2P tunnels (not used yet). */
    CANNOT_CARRY_COMPRESSED,
    /** Carries 32 channels (dense cables, controller); paths through these nodes are strongly preferred. */
    DENSE_CAPACITY,
    /** Part of a multiblock that only needs one channel (crafting CPU, spatial pylons). */
    MULTIBLOCK,
    /** Paths through these nodes are preferred (normal cables, toggle bus). */
    PREFERRED
}
