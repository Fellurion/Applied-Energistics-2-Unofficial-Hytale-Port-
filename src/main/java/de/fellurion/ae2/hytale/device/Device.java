/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.device;

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

    /** Gewünschter Block-State, oder null für die Standardregel (Online/default). */
    @Nullable
    default String visualState(WorldNetworks nets, WorldNetworks.Node node) {
        return null;
    }
}
