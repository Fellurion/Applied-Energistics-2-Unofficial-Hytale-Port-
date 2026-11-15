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
    /**
     * Main node of ME P2P tunnels: its channel may not be routed through a {@link #CANNOT_CARRY_COMPRESSED} node (no
     * tunnels through tunnels).
     */
    COMPRESSED_CHANNEL,
    /** Cannot carry channels over this node (controller, quartz fiber). */
    CANNOT_CARRY,
    /** Outer node of ME P2P tunnels: {@link #COMPRESSED_CHANNEL} nodes behind it get no channel. */
    CANNOT_CARRY_COMPRESSED,
    /** Carries 32 channels (dense cables, controller); paths through these nodes are strongly preferred. */
    DENSE_CAPACITY,
    /** Part of a multiblock that only needs one channel (crafting CPU, spatial pylons). */
    MULTIBLOCK,
    /** Paths through these nodes are preferred (normal cables, toggle bus). */
    PREFERRED
}
