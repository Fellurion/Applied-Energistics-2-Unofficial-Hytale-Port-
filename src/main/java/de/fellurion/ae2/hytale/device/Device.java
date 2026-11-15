/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.device;

import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;

/**
 * Hytale-seitiger Adapter eines AE2-Blocks: verbindet die Kernlogik (Owner des GridNodes) mit dem
 * Hytale-Container, der Block-Komponente und dem sichtbaren Zustand.
 */
public interface Device {

    /** Owner-Objekt für {@code MEWorld.addNode} (implementiert Kern-Schnittstellen), oder null. */
    @Nullable
    default Object owner() {
        return null;
    }

    /**
     * Knotentyp für {@code MEWorld.addNode}, oder null für den Standard der Block-Art ({@code NodeType.forBlock}).
     * Für Geräte, deren Knoten von ihrem Zustand abhängt (P2P-Tunnel je Tunnelart) oder die zwei Knoten haben
     * (Hauptknoten hinten, zweiter Knoten vorn: ME-P2P-Tunnel, Quartz Fiber, Toggle Bus).
     */
    @Nullable
    default NodeType nodeType(WorldNetworks nets, WorldNetworks.Node node) {
        return null;
    }

    /** Nach dem Anlegen im Kern (Zustand aus der Komponente laden, Container verbinden). */
    default void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
    }

    /** Vor dem Kern-Tick: Hytale → Kern (Container-Änderungen, Ziele vor Bussen). */
    default void tick(WorldNetworks nets, WorldNetworks.Node node) {
    }

    /** Nach dem Kern-Tick: Kern → Hytale (Metadaten zurückschreiben, Zustand speichern). */
    default void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
    }

    /** Block abgebaut ({@code broken}) oder Chunk entladen. */
    default void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
    }

    /**
     * Der Block wurde gedreht (Schraubenschlüssel); {@code node.facing()} zeigt schon in die neue Richtung, die
     * Verbindungen im Kern sind nachgezogen. Geräte, die sich etwas Richtungsabhängiges merken (das Ziel vor einem
     * Bus), werfen es hier weg; wer {@code node.facing()} bei jedem Gebrauch liest, braucht nichts zu tun.
     */
    default void onRotated(WorldNetworks nets, WorldNetworks.Node node) {
    }

    /** Gewünschter Block-State, oder null für die Standardregel (Online/default). */
    @Nullable
    default String visualState(WorldNetworks nets, WorldNetworks.Node node) {
        return null;
    }
}
