/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.security;

import java.util.UUID;

/**
 * Besitzer eines AE2-Blocks oder ME-Netzes.
 *
 * @param uuid Spieler-UUID
 * @param name zuletzt bekannter Name (zur Anzeige; nie leer)
 */
public record Owner(UUID uuid, String name) {

    /** Anzeigename aus bekanntem Namen, sonst die ersten Zeichen der UUID. */
    static String displayName(UUID uuid, String known) {
        if (known != null && !known.isBlank()) {
            return known;
        }
        String s = uuid.toString();
        return s.substring(0, Math.min(8, s.length()));
    }
}
