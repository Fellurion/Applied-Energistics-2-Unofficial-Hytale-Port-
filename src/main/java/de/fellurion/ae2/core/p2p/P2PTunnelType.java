/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.p2p;

import de.fellurion.ae2.core.grid.NodeType;

import java.util.Locale;

/**
 * The six kinds of P2P tunnel, with the idle power of their main node ({@code getPowerDrainPerTick} of the AE2
 * part classes): {@code MEP2PTunnelPart} and the capability tunnels ({@code ItemP2PTunnelPart},
 * {@code FluidP2PTunnelPart}, {@code FEP2PTunnelPart}) 2 AE/t, {@code LightP2PTunnelPart} and
 * {@code RedstoneP2PTunnelPart} 0.5 AE/t.
 * <p>
 * Replaces the part classes as the identity of a tunnel: where AE2 asks {@code getClass().isInstance(other)}, the
 * port compares types.
 */
public enum P2PTunnelType {
    ME(2.0),
    ITEM(2.0),
    FLUID(2.0),
    FE(2.0),
    LIGHT(0.5),
    REDSTONE(0.5);

    private final String id;
    private final double idlePower;
    private final NodeType mainNodeType;

    P2PTunnelType(double idlePower) {
        this.id = name().toLowerCase(Locale.ROOT);
        this.idlePower = idlePower;
        this.mainNodeType = "ME".equals(name()) ? NodeType.P2P_TUNNEL_ME : NodeType.P2P_TUNNEL.withIdlePower(idlePower);
    }

    /** The {@code TunnelType} string of the block component: me, item, fluid, fe, light, redstone. */
    public String id() {
        return id;
    }

    /** Idle power of the main node in AE/t. */
    public double idlePower() {
        return idlePower;
    }

    /**
     * Type of the tunnel's main node (back side, on the carrying network): needs a channel; the ME tunnel's also has
     * COMPRESSED_CHANNEL.
     */
    public NodeType mainNodeType() {
        return mainNodeType;
    }

    /** @return the type with that id, or null */
    public static P2PTunnelType fromId(String id) {
        if (id != null) {
            for (var t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
        }
        return null;
    }
}
