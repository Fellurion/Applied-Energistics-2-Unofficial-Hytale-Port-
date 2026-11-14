/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.hud;

import de.fellurion.ae2.hytale.WorldNetworks;

/**
 * Liefert die Blockinfo für eine Block-Art ({@code Kind}), wie ein Jade-/TOP-Provider in AE2.
 * Anmelden über {@link InfoProviders#register}.
 *
 * <p>Läuft auf dem Welt-Thread, etwa alle 0,3 s, solange ein Spieler den Block ansieht. Nur zwischengespeicherte
 * Werte des Kerns lesen (Knotenzustand, Energie, Zellen des Blocks) – nie das ganze Netz durchgehen.
 */
@FunctionalInterface
public interface InfoProvider {

    /**
     * @param ctx  Sprache, Formate, Zwischenspeicher des Spielers
     * @param node angesehener AE2-Block
     * @param out  Name/Symbol sind vorbelegt; Zustand, Zeilen und Balken setzt der Provider
     */
    void describe(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out);
}
