/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ftcore;

import de.fellurion.ftcore.FTCorePlugin;
import de.fellurion.ftcore.i18n.Messages;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * FTCore-Anbindung der Blockinfo ({@code de.fellurion.ae2.hytale.hud.AE2Hud}): die Sprache, die der Spieler in FTCore
 * gewählt hat, damit die Blockinfo dieselbe Sprache spricht wie Leiste und Schale.
 *
 * <p>Nur über {@code AE2Hud} aufrufen – das prüft vorher, ob FTCore geladen ist. Signaturen ohne FTCore-Typen.
 */
public final class FtCoreHud {

    private FtCoreHud() {
    }

    /** Sprachcode aus FTCore, oder null, solange FTCore nicht eingerichtet ist. */
    @Nullable
    public static String languageOf(UUID playerId) {
        FTCorePlugin core = FTCorePlugin.get();
        Messages messages = core == null ? null : core.messages();
        return messages == null ? null : messages.languageOf(playerId);
    }
}
