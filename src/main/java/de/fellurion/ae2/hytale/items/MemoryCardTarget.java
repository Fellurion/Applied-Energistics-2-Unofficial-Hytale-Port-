/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.api.implementations.items.IMemoryCard, appeng.util.SettingsFrom).
 */
package de.fellurion.ae2.hytale.items;

import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;

/**
 * Ein Gerät ({@code Device}), das mit der Memory Card selbst umgeht – etwa ein P2P-Tunnel, der damit verknüpft wird.
 *
 * <p>Die Memory Card (Bereich Werkzeuge) fragt das Gerät des angeklickten Blocks: geduckt + Rechtsklick speichert
 * ({@link #saveToCard}), Rechtsklick überträgt ({@link #loadFromCard}). Geräte ohne diese Schnittstelle, die
 * {@code ConfigHost} sind, kopiert die Memory Card von sich aus (Einstellungen, Filter, Priorität, Karten).
 */
public interface MemoryCardTarget {

    /** Ergebnis des Übertragens. */
    enum Result {
        /** Übernommen. */
        LOADED,
        /** Die Karte stammt von einer anderen Geräteart. */
        WRONG_TYPE,
        /** Die Karte passt, ließ sich aber nicht (ganz) übernehmen. */
        FAILED
    }

    /**
     * Speichern: liefert, was auf die Karte kommt, oder null, wenn dieses Gerät nichts zu speichern hat. Darf dabei
     * das Gerät selbst verändern (ein P2P-Tunnel wird so zum Eingang einer neuen Frequenz).
     */
    @Nullable
    MemoryCardData saveToCard(WorldNetworks nets, WorldNetworks.Node node);

    /** Übertragen: übernimmt den Inhalt der Karte in dieses Gerät. */
    Result loadFromCard(WorldNetworks nets, WorldNetworks.Node node, MemoryCardData data);
}
