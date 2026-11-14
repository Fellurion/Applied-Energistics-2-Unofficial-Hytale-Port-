/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.hud;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import de.fellurion.ae2.hytale.ftcore.FtCoreHud;

import javax.annotation.Nullable;
import java.util.logging.Level;

/**
 * Blockinfo beim Anschauen (wie Jade/WAILA bzw. The One Probe in Minecraft): Wer einen AE2-Block ansieht, bekommt
 * oben mittig eine kleine Platte mit Symbol, Name, Netzzustand und den wichtigsten Werten – Kanäle eines Kabels,
 * Ladung einer Energiezelle, Zellen eines Drives, Netzübersicht am Controller, Fortschritt von Maschinen und CPUs.
 * Weitere Block-Arten: {@link InfoProviders#register}.
 *
 * <p>Die Platte ist ein eigenes HUD ({@link #HUD_KEY}) und liegt neben den HUDs anderer Plugins. FTCore ist optional:
 * Ist es geladen, kommt die Sprache aus FTCores Spracheinstellung (wie in der FTCore-Schale), und FTCores
 * {@code hudcontrol.json} kann die Platte verschieben oder ausblenden (HUD {@value #HUD_KEY}, Element
 * {@code #AE2Look}). Eine Schnittstelle für Blick-Infos hat FTCore nicht – seine {@code HudRegistry} trägt nur kurze
 * Texte in der Leiste am oberen Rand. Alles, was FTCore-Klassen anfasst, liegt in
 * {@code de.fellurion.ae2.hytale.ftcore} und wird erst nach der Prüfung unten geladen.
 */
public final class AE2Hud {

    /** Schlüssel des HUDs beim Client ({@code HudManager}); FTCore-Regeln beziehen sich darauf. */
    public static final String HUD_KEY = "AE2.LookAt";

    private static final String[] FTCORE_CLASSES = {
            "de.fellurion.ftcore.FTCorePlugin",
            "de.fellurion.ftcore.i18n.Messages",
    };

    /** null = noch nicht geprüft. */
    @Nullable
    private static volatile Boolean ftcore;
    @Nullable
    private static volatile JavaPlugin plugin;
    private static volatile boolean installed;

    private AE2Hud() {
    }

    /** Einmal beim Plugin-Start aufrufen (HytaleBridge.setup): meldet System und Abmelde-Ereignis an. */
    public static synchronized void setup(JavaPlugin p) {
        if (installed) {
            return;
        }
        installed = true;
        plugin = p;
        InfoProviders.get("cable"); // mitgelieferte Provider anmelden
        p.getEntityStoreRegistry().registerSystem(new LookAtSystem());
        p.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class,
                e -> LookAtSystem.forget(e.getPlayerRef().getUuid()));
        p.getLogger().at(Level.INFO).log("AE2: Blockinfo beim Anschauen aktiv (HUD %s%s)", HUD_KEY,
                ftcorePresent() ? ", Sprache aus FTCore" : "");
    }

    /** Sprache des Spielers: FTCores Einstellung, sonst die des Clients, sonst en-US. */
    static String localeOf(PlayerRef playerRef) {
        String locale = null;
        if (ftcorePresent()) {
            try {
                locale = FtCoreHud.languageOf(playerRef.getUuid());
            } catch (LinkageError | RuntimeException e) {
                disableFtCore(e);
            }
        }
        if (locale == null || locale.isBlank()) {
            locale = playerRef.getLanguage();
        }
        return locale == null || locale.isBlank() ? "en-US" : locale;
    }

    private static boolean ftcorePresent() {
        Boolean present = ftcore;
        if (present == null) {
            present = classesPresent();
            ftcore = present;
        }
        return present;
    }

    private static boolean classesPresent() {
        ClassLoader cl = AE2Hud.class.getClassLoader();
        for (String name : FTCORE_CLASSES) {
            try {
                Class.forName(name, false, cl);
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
        }
        return true;
    }

    private static void disableFtCore(Throwable e) {
        ftcore = false;
        JavaPlugin p = plugin;
        if (p != null) {
            p.getLogger().at(Level.WARNING).withCause(e)
                    .log("AE2: FTCore-Sprache für die Blockinfo nicht lesbar – nutze die Sprache des Clients");
        }
    }
}
