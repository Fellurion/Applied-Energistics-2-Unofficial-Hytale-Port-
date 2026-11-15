/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.p2p;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.p2p.P2PTunnelLogic;
import de.fellurion.ae2.core.p2p.P2PTunnelType;
import de.fellurion.ae2.hytale.AE2Command;
import de.fellurion.ae2.hytale.UseHandlers;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Devices;
import de.fellurion.ae2.hytale.hud.InfoProviders;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** P2P-Tunnel, Quartz Fiber und Toggle Bus. */
public final class P2P {

    public static final String KIND_TUNNEL = "p2p_tunnel";
    public static final String KIND_FIBER = "quartz_fiber";
    public static final String KIND_TOGGLE_BUS = "toggle_bus";
    public static final String KIND_INVERTED_TOGGLE_BUS = "inverted_toggle_bus";

    /** So viele Ticks darf zwischen dem Tausch eines Tunnel-Blocks und dem Anmelden des neuen liegen. */
    private static final long HANDOFF_TICKS = 100;

    /** Zustand eines Tunnels, dessen Block gerade gegen eine andere Tunnelart getauscht wird. */
    private record Handoff(Map<String, String> config, long tick) {
    }

    /** Je Welt und Position; nur auf dem jeweiligen Welt-Thread benutzt. */
    private static final Map<String, Handoff> HANDOFFS = new ConcurrentHashMap<>();

    private P2P() {
    }

    /** Meldet Geräte, Benutzen-Aktionen, Befehle und Systeme dieses Bereichs an (einmal beim Plugin-Start). */
    public static void setup(JavaPlugin plugin) {
        Devices.register(KIND_TUNNEL, TunnelDevice::create);
        Devices.register(KIND_FIBER, (nets, node) -> new FiberDevice());
        Devices.register(KIND_TOGGLE_BUS, (nets, node) -> new ToggleBusDevice(false, node));
        Devices.register(KIND_INVERTED_TOGGLE_BUS, (nets, node) -> new ToggleBusDevice(true, node));

        UseHandlers.register(KIND_TUNNEL, TunnelDevice::use);
        UseHandlers.register(KIND_TOGGLE_BUS, ToggleBusDevice::use);
        UseHandlers.register(KIND_INVERTED_TOGGLE_BUS, ToggleBusDevice::use);

        InfoProviders.register(P2PHud::tunnel, KIND_TUNNEL);
        InfoProviders.register(P2PHud::fiber, KIND_FIBER);
        InfoProviders.register(P2PHud::toggleBus, KIND_TOGGLE_BUS, KIND_INVERTED_TOGGLE_BUS);

        AE2Command.registerSub(new P2PCommands());
    }

    /** Item (= Block) einer Tunnelart. */
    public static String itemId(P2PTunnelType type) {
        return switch (type) {
            case ME -> "AE2_ME_P2P_Tunnel";
            case ITEM -> "AE2_Item_P2P_Tunnel";
            case FLUID -> "AE2_Fluid_P2P_Tunnel";
            case FE -> "AE2_FE_P2P_Tunnel";
            case LIGHT -> "AE2_Light_P2P_Tunnel";
            case REDSTONE -> "AE2_Redstone_P2P_Tunnel";
        };
    }

    /** Das Gerät zu einem Tunnel des Kerns (über die Position seines Knotens), oder null. */
    @Nullable
    static TunnelDevice device(WorldNetworks nets, @Nullable P2PTunnelLogic tunnel) {
        WorldNetworks.Node node = node(nets, tunnel);
        return node != null && node.device() instanceof TunnelDevice d && d.logic() == tunnel ? d : null;
    }

    /** Der Block zu einem Tunnel des Kerns, oder null. */
    @Nullable
    static WorldNetworks.Node node(WorldNetworks nets, @Nullable P2PTunnelLogic tunnel) {
        GridNode g = tunnel == null ? null : tunnel.getMainNode();
        if (g == null || g.isRemoved()) {
            return null;
        }
        long p = g.getPos();
        return nets.node(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
    }

    // ------------------------------------------------------------------ Tausch der Tunnelart

    private static String key(WorldNetworks nets, long pos) {
        return nets.world().getName() + '/' + pos;
    }

    /** Merkt die Einstellungen eines Tunnels vor, dessen Block gleich ersetzt wird. */
    static void handoff(WorldNetworks nets, long pos, Map<String, String> config) {
        HANDOFFS.put(key(nets, pos), new Handoff(new HashMap<>(config), nets.tickCount()));
    }

    static void clearHandoff(WorldNetworks nets, long pos) {
        HANDOFFS.remove(key(nets, pos));
    }

    /** Holt vorgemerkte Einstellungen für den neuen Tunnel an dieser Stelle ab (null = keine oder veraltet). */
    @Nullable
    static Map<String, String> takeHandoff(WorldNetworks nets, long pos) {
        if (HANDOFFS.isEmpty()) {
            return null;
        }
        Handoff h = HANDOFFS.remove(key(nets, pos));
        return h == null || nets.tickCount() - h.tick() > HANDOFF_TICKS ? null : h.config();
    }
}
