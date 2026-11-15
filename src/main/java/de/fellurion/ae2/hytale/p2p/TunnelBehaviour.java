/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: die Unterklassen von appeng.parts.p2p.P2PTunnelPart).
 */
package de.fellurion.ae2.hytale.p2p;

import de.fellurion.ae2.core.p2p.P2PTunnelType;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.hud.HudContext;
import de.fellurion.ae2.hytale.hud.LookInfo;

import javax.annotation.Nullable;

/**
 * Was ein P2P-Tunnel einer Art in der Hytale-Welt transportiert. Frequenz, Rolle und das Finden der Gegenstellen
 * gehören dem Kern ({@code core.p2p}); hier steht nur, was am Eingang gelesen und am Ausgang abgegeben wird. Je
 * Tunnel-Block gibt es eine eigene Instanz.
 *
 * <p>Neue Tunnelarten kommen in {@link #forType} dazu – das ist die einzige Stelle, an der die Art entschieden
 * wird.
 */
interface TunnelBehaviour {

    /** Tunnel, der nur verknüpft wird und nichts transportiert. */
    TunnelBehaviour PASSIVE = new TunnelBehaviour() {
    };

    /**
     * Das Verhalten für eine Tunnelart:
     * <ul>
     *   <li>{@code me}: Kanäle – vollständig im Kern ({@code MEP2PTunnelLogic}), hier nichts zu tun.</li>
     *   <li>{@code item}: {@link ItemTunnel}.</li>
     *   <li>{@code redstone}: {@link RedstoneTunnel} (AE2-Signal statt Redstone).</li>
     *   <li>{@code light}: {@link LightTunnel}.</li>
     *   <li>{@code fluid}: {@link FluidTunnel}.</li>
     *   <li>{@code fe}: ohne Transport – Hytale kennt keine fremde Energie, die zu tunneln wäre.</li>
     * </ul>
     */
    static TunnelBehaviour forType(P2PTunnelType type) {
        return switch (type) {
            case ITEM -> new ItemTunnel();
            case REDSTONE -> new RedstoneTunnel();
            case LIGHT -> new LightTunnel();
            case FLUID -> new FluidTunnel();
            case ME, FE -> PASSIVE;
        };
    }

    /** true, wenn diese Tunnelart in dieser Fassung nur verknüpft und nichts überträgt (Hinweis in der Anzeige). */
    static boolean linksOnly(P2PTunnelType type) {
        return type == P2PTunnelType.FE;
    }

    default void onAttached(TunnelDevice device, WorldNetworks nets, WorldNetworks.Node node) {
    }

    /** Je AE2-Tick vor dem Kern; muss im Leerlauf ohne Allokation auskommen. */
    default void tick(TunnelDevice device, WorldNetworks nets, WorldNetworks.Node node) {
    }

    /** Block abgebaut, ersetzt oder entladen: alles zurücknehmen, was der Tunnel in der Welt bewirkt. */
    default void onRemoved(TunnelDevice device, WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
    }

    /** Eigener Block-State, oder null für die Standardregel (Online/default). */
    @Nullable
    default String visualState(TunnelDevice device, WorldNetworks nets, WorldNetworks.Node node) {
        return null;
    }

    /** Zusätzliche Zeile der Blickanzeige (z. B. Signal an/aus). */
    default void describe(TunnelDevice device, HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
    }

    /** Kurzer Zustand für {@code /ae2 p2p info} (leer = nichts zu sagen). */
    default String debugState() {
        return "";
    }
}
