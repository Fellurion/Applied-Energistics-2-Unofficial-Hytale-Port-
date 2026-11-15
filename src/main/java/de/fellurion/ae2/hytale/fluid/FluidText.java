/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.api.stacks.AEKeyType#formatAmount mit appeng.api.stacks.AmountFormat).
 */
package de.fellurion.ae2.hytale.fluid;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.core.stacks.AmountFormat;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Mengenangaben für Flüssigkeiten wie in AE2: gezählt wird in mB, geschrieben in Eimern (B) – „1.5 B“ ausführlich,
 * „1.5“ in einem Feld des Terminals. Dazu kurze Hinweise an den Spieler.
 */
public final class FluidText {

    private FluidText() {
    }

    private static Locale locale(@Nullable String tag) {
        return tag == null || tag.isBlank() ? Locale.ROOT : Locale.forLanguageTag(tag);
    }

    /** Ausführlich mit Einheit: 12500 mB → „12.5 B“ bzw. „12,5 B“ in der Sprache des Spielers. */
    public static String full(long millibuckets, @Nullable String locale) {
        return AEKeyType.FLUIDS.formatAmount(Math.max(0, millibuckets), AmountFormat.FULL, locale(locale));
    }

    /**
     * Für ein Feld des Terminals: höchstens vier Zeichen in Eimern, ohne Einheit (AE2: {@code AmountFormat.SLOT}).
     * Das Tausender-Kürzel ist klein geschrieben wie bei den Item-Mengen daneben („12k“).
     */
    public static String slot(long millibuckets) {
        String s = AEKeyType.FLUIDS.formatAmount(Math.max(0, millibuckets), AmountFormat.SLOT);
        return s.endsWith("K") ? s.substring(0, s.length() - 1) + "k" : s;
    }

    /** „12.5 / 16 B“ für Füllstände. */
    public static String of(long millibuckets, long capacity, @Nullable String locale) {
        String stored = full(millibuckets, locale);
        int unit = stored.lastIndexOf(' ');
        return (unit > 0 ? stored.substring(0, unit) : stored) + " / " + full(capacity, locale);
    }

    /** Kurzer Hinweis am Bildschirmrand; ohne Spieler oder Verbindung passiert nichts. */
    public static void tell(@Nullable PlayerRef player, Message message, boolean warning) {
        if (player == null) {
            return;
        }
        try {
            NotificationUtil.sendNotification(player.getPacketHandler(), message,
                    warning ? NotificationStyle.Warning : NotificationStyle.Default);
        } catch (RuntimeException e) {
            // Verbindung weg – dann eben ohne
        }
    }
}
