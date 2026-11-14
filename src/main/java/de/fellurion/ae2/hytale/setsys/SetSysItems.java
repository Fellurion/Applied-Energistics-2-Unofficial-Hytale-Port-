/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.setsys;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Liest, was das Plugin SetSys an einen Gegenstand schreibt – ohne eine einzige SetSys-Klasse zu laden.
 *
 * <p>SetSys legt den Seelenbund als Zeichenkette unter {@value #ROLL_KEY} in die Item-Metadaten
 * ({@code "3|<uuid>|<Name>|<Werte>"}, ältere Fassungen {@code "2|…"} und {@code "1|<Werte>"} ohne Besitzer, siehe
 * {@code de.fellurion.setsys.service.ItemRolls}). Set-Zugehörigkeit und Seelenwerte stehen zusätzlich fertig formatiert
 * in der {@code ItemDisplayMetadata}-Beschreibung, die die Terminal-Seiten ohnehin anzeigen.
 *
 * <p>Weil hier nur Metadaten gelesen werden, braucht AE2 SetSys weder zum Kompilieren noch zur Laufzeit; fehlt SetSys,
 * tragen die Items den Schlüssel einfach nicht. SetSys verbietet gebundene Stücke weder in Behältern noch bei fremden
 * Spielern (ein fremdes Stück wirkt nur nicht) – das ME-Netz behandelt sie deshalb wie jedes andere Item.
 */
public final class SetSysItems {

    /** Metadaten-Schlüssel des Seelenbunds in SetSys ({@code ItemRolls.METADATA_KEY}). */
    public static final String ROLL_KEY = "SetSysRoll";

    /**
     * Seelenbund eines Stücks.
     *
     * @param owner     gebundener Spieler oder null (ältere Fassung / noch ohne Besitzer)
     * @param ownerName Anzeigename des Besitzers, leer wenn unbekannt
     */
    public record Bond(@Nullable UUID owner, String ownerName) {
    }

    private SetSysItems() {
    }

    /** Seelenbund des Stücks oder null, wenn es ungebunden ist bzw. SetSys es nie gesehen hat. */
    @Nullable
    public static Bond bond(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String raw;
        try {
            raw = stack.getFromMetadataOrNull(ROLL_KEY, Codec.STRING);
        } catch (RuntimeException e) {
            return null;
        }
        return raw == null ? null : parse(raw);
    }

    /** Zerlegt die SetSys-Zeichenkette; unbekannte Fassungen gelten als gebunden ohne bekannten Besitzer. */
    static Bond parse(String raw) {
        String[] header = raw.split("\\|", 4);
        if (header.length < 3 || "1".equals(header[0])) {
            return new Bond(null, "");
        }
        UUID owner = null;
        if (!header[1].isEmpty()) {
            try {
                owner = UUID.fromString(header[1]);
            } catch (IllegalArgumentException ignored) {
                // kaputte Spielernummer: Stück bleibt gebunden, Besitzer unbekannt
            }
        }
        return new Bond(owner, header[2]);
    }
}
