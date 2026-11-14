/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.grid;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Static description of a grid node: flags, base idle power and which sides it connects on.
 * <p>
 * The values are taken from the constructors of the corresponding AE2 block entities / parts (e.g.
 * {@code ControllerBlockEntity}: idle 3 AE/t, flags CANNOT_CARRY + DENSE_CAPACITY; {@code AbstractReportingPart}
 * (terminals): 0.5 AE/t + REQUIRE_CHANNEL; {@code CablePart}: 0 AE/t + PREFERRED; {@code GridNode} default idle
 * power 1.0 AE/t) and from their {@code getGridConnectableSides} overrides.
 * <p>
 * Deviation: in Minecraft parts live on a cable bus; in Hytale they are separate directional blocks which connect only
 * on their back side ({@link SideRule#BACK_ONLY}). Use {@link #forPart(String)} for them.
 *
 * @param kind       the block {@code Kind} string ("controller", "drive", "cable", ...)
 * @param flags      grid flags
 * @param idlePower  base idle power usage in AE/t (devices like drives add their cells' drain at runtime)
 * @param sideRule   which sides are connectable relative to the block facing
 * @param controller true for ME controllers
 * @param cableType  the cable type for cables, else null
 */
public record NodeType(String kind, Set<GridFlag> flags, double idlePower, SideRule sideRule, boolean controller,
        CableType cableType) {

    /** Which sides of a block are grid-connectable, relative to its facing ("front"). */
    public enum SideRule {
        ALL,
        /** All sides except the front (drive, inscriber, charger). */
        ALL_EXCEPT_FRONT,
        /** Only the side opposite of the facing (all part-blocks, wireless access point, resonance generator). */
        BACK_ONLY,
        /** Front and back (growth accelerator, toggle bus, quartz fiber). */
        FRONT_AND_BACK,
        NONE;

        public Set<Direction> sides(Direction facing) {
            if (facing == null && this != NONE) {
                return EnumSet.allOf(Direction.class);
            }
            return switch (this) {
                case ALL -> EnumSet.allOf(Direction.class);
                case ALL_EXCEPT_FRONT -> EnumSet.complementOf(EnumSet.of(facing));
                case BACK_ONLY -> EnumSet.of(facing.getOpposite());
                case FRONT_AND_BACK -> EnumSet.of(facing, facing.getOpposite());
                case NONE -> EnumSet.noneOf(Direction.class);
            };
        }
    }

    public NodeType {
        flags = flags.isEmpty() ? Collections.unmodifiableSet(EnumSet.noneOf(GridFlag.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(flags));
    }

    public boolean hasFlag(GridFlag flag) {
        return flags.contains(flag);
    }

    public boolean isCable() {
        return cableType != null;
    }

    public boolean requiresChannel() {
        return flags.contains(GridFlag.REQUIRE_CHANNEL);
    }

    public NodeType withIdlePower(double idle) {
        return new NodeType(kind, flags, idle, sideRule, controller, cableType);
    }

    public NodeType withSideRule(SideRule rule) {
        return new NodeType(kind, flags, idlePower, rule, controller, cableType);
    }

    public NodeType withFlags(GridFlag... newFlags) {
        var set = newFlags.length == 0 ? EnumSet.noneOf(GridFlag.class) : EnumSet.of(newFlags[0], newFlags);
        return new NodeType(kind, set, idlePower, sideRule, controller, cableType);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static final Map<String, NodeType> BLOCKS = new HashMap<>();

    private static NodeType reg(String kind, double idle, SideRule rule, GridFlag... flags) {
        var set = flags.length == 0 ? EnumSet.noneOf(GridFlag.class) : EnumSet.of(flags[0], flags);
        var t = new NodeType(kind, set, idle, rule, false, null);
        BLOCKS.put(kind, t);
        return t;
    }

    private static final SideRule A = SideRule.ALL;
    private static final SideRule B = SideRule.BACK_ONLY;
    private static final GridFlag RC = GridFlag.REQUIRE_CHANNEL;

    public static final NodeType CONTROLLER;

    static {
        CONTROLLER = new NodeType("controller", EnumSet.of(GridFlag.CANNOT_CARRY, GridFlag.DENSE_CAPACITY), 3.0,
                SideRule.ALL, true, null);
        BLOCKS.put("controller", CONTROLLER);
    }

    // Full blocks
    public static final NodeType DRIVE = reg("drive", 2.0, SideRule.ALL_EXCEPT_FRONT, RC);
    public static final NodeType ME_CHEST = reg("me_chest", 1.0, A, RC);
    public static final NodeType INTERFACE = reg("interface", 1.0, A, RC);
    public static final NodeType PATTERN_PROVIDER = reg("pattern_provider", 1.0, A, RC);
    public static final NodeType ENERGY_ACCEPTOR = reg("energy_acceptor", 0.0, A);
    public static final NodeType ENERGY_CELL = reg("energy_cell", 0.0, A);
    public static final NodeType DENSE_ENERGY_CELL = reg("dense_energy_cell", 0.0, A);
    public static final NodeType CREATIVE_ENERGY_CELL = reg("creative_energy_cell", 0.0, A);
    public static final NodeType VIBRATION_CHAMBER = reg("vibration_chamber", 0.0, A);
    public static final NodeType CRYSTAL_RESONANCE_GENERATOR = reg("crystal_resonance_generator", 0.0, B);
    /** Idle power = {@code AEConfig.wirelessPowerDrain(boosters)}, 8 AE/t without boosters. */
    public static final NodeType WIRELESS_ACCESS_POINT = reg("wireless_access_point", 8.0, B, RC);
    public static final NodeType QUANTUM_RING = reg("quantum_ring", 22.0, A, GridFlag.DENSE_CAPACITY);
    public static final NodeType QUANTUM_LINK = reg("quantum_link", 22.0, A, GridFlag.DENSE_CAPACITY);
    public static final NodeType SPATIAL_PYLON = reg("spatial_pylon", 0.5, A, GridFlag.MULTIBLOCK, RC);
    public static final NodeType SPATIAL_IO_PORT = reg("spatial_io_port", 1.0, A, RC);
    public static final NodeType SPATIAL_ANCHOR = reg("spatial_anchor", 1.0, A, RC);
    public static final NodeType IO_PORT = reg("io_port", 1.0, A, RC);
    public static final NodeType CRAFTING_UNIT = reg("crafting_unit", 1.0, A, GridFlag.MULTIBLOCK, RC);
    public static final NodeType CRAFTING_ACCELERATOR = reg("crafting_accelerator", 1.0, A, GridFlag.MULTIBLOCK, RC);
    public static final NodeType CRAFTING_STORAGE = reg("crafting_storage", 1.0, A, GridFlag.MULTIBLOCK, RC);
    public static final NodeType CRAFTING_MONITOR = reg("crafting_monitor", 1.0, A, GridFlag.MULTIBLOCK, RC);
    public static final NodeType MOLECULAR_ASSEMBLER = reg("molecular_assembler", 0.0, A);
    public static final NodeType INSCRIBER = reg("inscriber", 0.0, SideRule.ALL_EXCEPT_FRONT);
    public static final NodeType CHARGER = reg("charger", 0.0, SideRule.ALL_EXCEPT_FRONT);
    /** Growth accelerator: 8 AE/t idle (POWER_PER_TICK), connects front and back. */
    public static final NodeType GROWTH_ACCELERATOR = reg("growth_accelerator", 8.0, SideRule.FRONT_AND_BACK);

    // Part-blocks (own directional blocks, connected on the back side)
    public static final NodeType IMPORT_BUS = reg("import_bus", 1.0, B, RC);
    public static final NodeType EXPORT_BUS = reg("export_bus", 1.0, B, RC);
    public static final NodeType STORAGE_BUS = reg("storage_bus", 1.0, B, RC);
    public static final NodeType TERMINAL = reg("terminal", 0.5, B, RC);
    public static final NodeType CRAFTING_TERMINAL = reg("crafting_terminal", 0.5, B, RC);
    public static final NodeType PATTERN_ENCODING_TERMINAL = reg("pattern_encoding_terminal", 0.5, B, RC);
    public static final NodeType PATTERN_ACCESS_TERMINAL = reg("pattern_access_terminal", 0.5, B, RC);
    public static final NodeType LEVEL_EMITTER = reg("level_emitter", 1.0, B, RC);
    public static final NodeType ENERGY_LEVEL_EMITTER = reg("energy_level_emitter", 1.0, B, RC);
    public static final NodeType STORAGE_MONITOR = reg("storage_monitor", 0.5, B, RC);
    public static final NodeType CONVERSION_MONITOR = reg("conversion_monitor", 0.5, B, RC);
    public static final NodeType ANNIHILATION_PLANE = reg("annihilation_plane", 1.0, B, RC);
    public static final NodeType FORMATION_PLANE = reg("formation_plane", 1.0, B, RC);
    public static final NodeType TOGGLE_BUS = reg("toggle_bus", 0.0, SideRule.FRONT_AND_BACK, GridFlag.PREFERRED);
    public static final NodeType INVERTED_TOGGLE_BUS = reg("inverted_toggle_bus", 0.0, SideRule.FRONT_AND_BACK);
    /** P2P tunnels: 1 AE/t base ({@code P2PTunnelPart}); ME/item/fluid/FE tunnels 2 AE/t, light/redstone 0.5. */
    public static final NodeType P2P_TUNNEL = reg("p2p_tunnel", 1.0, B, RC);
    public static final NodeType QUARTZ_FIBER = reg("quartz_fiber", 0.0, SideRule.FRONT_AND_BACK,
            GridFlag.CANNOT_CARRY);
    /** Illuminated panels: 1/16 AE/t, no channel. */
    public static final NodeType PANEL = reg("panel", 1.0 / 16.0, B);

    private static final Map<CableType, NodeType> CABLES = new HashMap<>();

    static {
        for (var ct : CableType.values()) {
            var flags = EnumSet.of(ct.dense ? GridFlag.DENSE_CAPACITY : GridFlag.PREFERRED);
            CABLES.put(ct, new NodeType("cable", flags, 0.0, SideRule.ALL, false, ct));
        }
    }

    /** Node type of a cable ({@code CablePart}: 0 AE/t idle; dense cables carry 32 channels). */
    public static NodeType cable(CableType type) {
        return CABLES.get(type);
    }

    /**
     * Node type for a full block (or a part-block, whose registered type is already BACK_ONLY).
     *
     * @throws IllegalArgumentException for unknown kinds
     */
    public static NodeType forBlock(String kind) {
        var t = BLOCKS.get(kind);
        if (t == null) {
            throw new IllegalArgumentException("Unknown or non-networked AE2 block kind: " + kind);
        }
        return t;
    }

    /** Node type for the cable-part variant of a kind (e.g. cable_interface → "interface" connected on the back). */
    public static NodeType forPart(String kind) {
        return forBlock(kind).withSideRule(SideRule.BACK_ONLY);
    }

    /** @return true if the kind has a grid node at all (cell_workbench, condenser, crank, ... have none). */
    public static boolean isNetworked(String kind) {
        return BLOCKS.containsKey(kind) || "cable".equals(kind);
    }

    /** Sides this node exposes for the given facing (null facing = all sides). */
    public Set<Direction> exposedSides(Direction facing) {
        return sideRule.sides(facing);
    }
}
