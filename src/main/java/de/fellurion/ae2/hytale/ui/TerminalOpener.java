/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction;
import de.fellurion.ae2.hytale.ftcore.FtCoreTerminals;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Einziger Einstieg zum Öffnen eines ME-Terminals: mit FTCore als Reiter der Schale (Inventar daneben, FTCore-Optik),
 * sonst als eigene {@link TerminalPage}.
 *
 * <p>Vor dem Öffnen wird der Zugriff geprüft ({@link TerminalBackend#checkAccess}, siehe
 * {@code de.fellurion.ae2.hytale.security.AE2Security}).
 *
 * <p>FTCore ist optional. Alles, was FTCore-Klassen anfasst, liegt in {@code de.fellurion.ae2.hytale.ftcore} und wird
 * erst aufgerufen, nachdem geprüft ist, dass FTCore geladen ist – ohne FTCore wird keine FTCore-Klasse geladen.
 */
public final class TerminalOpener {

    private static final String[] FTCORE_CLASSES = {
            "de.fellurion.ftcore.FTCorePlugin",
            "de.fellurion.ftcore.shell.ShellAccess",
            "de.fellurion.ftcore.shell.ShellContainer",
            "de.fellurion.ftcore.shell.ShellTab",
            "de.fellurion.ftcore.shell.ShellPage",
    };

    /** null = noch nicht geprüft. */
    @Nullable
    private static volatile Boolean ftcorePresent;
    private static volatile boolean ftcoreInstalled;
    @Nullable
    private static volatile JavaPlugin plugin;

    private TerminalOpener() {
    }

    /** Beim Start des Plugins aufrufen (HytaleBridge.setup): meldet bei FTCore Reiter und Ablage-Behälter an. */
    public static void setup(JavaPlugin p) {
        plugin = p;
        p.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, e -> forget(e.getPlayerRef().getUuid()));
        if (ftcoreReady()) {
            p.getLogger().at(Level.INFO).log("AE2: FTCore gefunden – ME-Terminals öffnen in der FTCore-Schale");
        }
    }

    /** Öffnet das Terminal für einen benutzten Block (Terminal, Crafting-/Pattern-Terminal, ME Chest). */
    public static void open(AE2UseBlockInteraction.UseContext ctx, String titleKey, TerminalBackend backend) {
        open(ctx.player(), ctx.playerRef(), ctx.playerEntity(), ctx.entityStore(), titleKey, backend);
    }

    /** Öffnet das Terminal (FTCore-Schale, sonst eigene Seite). */
    public static void open(Player player, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                            String titleKey, TerminalBackend backend) {
        // Zugriffsschutz: fremde Netze öffnen gar nicht erst (Meldung „Dieses ME-Netz gehört X“)
        if (!backend.checkAccess(playerRef)) {
            return;
        }
        if (ftcoreReady()) {
            try {
                if (FtCoreTerminals.open(player, playerRef, ref, store, titleKey, backend)) {
                    return;
                }
            } catch (LinkageError | RuntimeException e) {
                disableFtCore(e);
            }
        }
        TerminalPage.open(player, playerRef, ref, store, backend, titleKey);
    }

    /** Vom Netz-Tick (Welt-Thread): aktualisiert Terminals in der FTCore-Schale. */
    static void refreshShells() {
        if (!ftcoreInstalled) {
            return;
        }
        try {
            FtCoreTerminals.refreshOpen();
        } catch (LinkageError | RuntimeException e) {
            disableFtCore(e);
        }
    }

    private static void forget(UUID playerId) {
        if (!ftcoreInstalled) {
            return;
        }
        try {
            FtCoreTerminals.forget(playerId);
        } catch (LinkageError | RuntimeException ignored) {
            // FTCore weg – nichts zu vergessen
        }
    }

    /**
     * true, wenn FTCore geladen ist und Reiter/Behälter angemeldet sind. Klappt die Anmeldung beim Start nicht
     * (FTCore noch nicht eingerichtet), wird sie beim ersten Öffnen nachgeholt.
     */
    private static boolean ftcoreReady() {
        if (ftcoreInstalled) {
            return true;
        }
        if (ftcorePresent == null) {
            ftcorePresent = classesPresent();
        }
        if (!ftcorePresent) {
            return false;
        }
        synchronized (TerminalOpener.class) {
            if (!ftcoreInstalled) {
                try {
                    ftcoreInstalled = FtCoreTerminals.install();
                } catch (LinkageError | RuntimeException e) {
                    disableFtCore(e);
                }
            }
        }
        return ftcoreInstalled;
    }

    private static boolean classesPresent() {
        ClassLoader cl = TerminalOpener.class.getClassLoader();
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
        ftcorePresent = false;
        ftcoreInstalled = false;
        JavaPlugin p = plugin;
        if (p != null) {
            p.getLogger().at(Level.WARNING).withCause(e)
                    .log("AE2: FTCore-Anbindung fehlgeschlagen – Terminals öffnen als eigene Seite");
        }
    }
}
