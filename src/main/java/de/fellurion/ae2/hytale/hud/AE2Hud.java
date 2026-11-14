/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.hud;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import de.fellurion.ae2.hytale.lang.AE2Language;

import java.util.logging.Level;

/**
 * Blockinfo beim Anschauen (wie Jade/WAILA bzw. The One Probe in Minecraft): Wer einen AE2-Block ansieht, bekommt
 * oben mittig eine kleine Platte mit Symbol, Name, Netzzustand und den wichtigsten Werten – Kanäle eines Kabels,
 * Ladung einer Energiezelle, Zellen eines Drives, Netzübersicht am Controller, Fortschritt von Maschinen und CPUs.
 * Weitere Block-Arten: {@link InfoProviders#register}.
 *
 * <p>Die Platte ist ein eigenes HUD ({@link #HUD_KEY}) und liegt neben den HUDs anderer Plugins (Element
 * {@code #AE2Look}). Die Texte kommen in der AE2-Sprache des Spielers ({@link AE2Language}).
 */
public final class AE2Hud {

    /** Schlüssel des HUDs beim Client ({@code HudManager}). */
    public static final String HUD_KEY = "AE2.LookAt";

    private static volatile boolean installed;

    private AE2Hud() {
    }

    /** Einmal beim Plugin-Start aufrufen (HytaleBridge.setup): meldet System und Abmelde-Ereignis an. */
    public static synchronized void setup(JavaPlugin p) {
        if (installed) {
            return;
        }
        installed = true;
        InfoProviders.get("cable"); // mitgelieferte Provider anmelden
        p.getEntityStoreRegistry().registerSystem(new LookAtSystem());
        p.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class,
                e -> LookAtSystem.forget(e.getPlayerRef().getUuid()));
        p.getLogger().at(Level.INFO).log("AE2: look-at block info enabled (HUD %s)", HUD_KEY);
    }

    /** Sprache des Spielers für die Blockinfo: seine AE2-Sprache (Standard Englisch, {@code /ae2 language}). */
    static String localeOf(PlayerRef playerRef) {
        return AE2Language.of(playerRef);
    }
}
