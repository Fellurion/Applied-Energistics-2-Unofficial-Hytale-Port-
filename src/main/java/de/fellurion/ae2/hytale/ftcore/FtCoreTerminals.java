/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ftcore;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.ui.TerminalBackend;
import de.fellurion.ae2.hytale.ui.TerminalView;
import de.fellurion.ftcore.FTCorePlugin;
import de.fellurion.ftcore.shell.ShellAccess;
import de.fellurion.ftcore.shell.ShellPage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anbindung an FTCore: ME-Terminals öffnen als Reiter „ME-Terminal“ in der FTCore-Schale, neben dem Inventar.
 *
 * <p>Nur über {@code de.fellurion.ae2.hytale.ui.TerminalOpener} aufrufen – der prüft vorher, ob FTCore geladen ist.
 * Signaturen dieser Klasse enthalten bewusst keine FTCore-Typen.
 *
 * <p>Ablauf: {@link #open} legt für den Spieler eine Sitzung an (Backend des benutzten Blocks, Ansicht, leerer
 * Ablage-Behälter) und öffnet die Schale mit dem Reiter vorn. Die Schale öffnet beim Öffnen für jeden angemeldeten
 * {@code ShellContainer} ein Fenster – {@link TerminalShellContainer} liefert nur für Spieler mit Sitzung einen
 * Behälter. Der Reiter ist nur sichtbar, solange die Sitzung besteht; beim Schließen der Schale endet sie.
 */
public final class FtCoreTerminals {

    static final String TAB_ID = "ae2.terminal";
    /** So lange nach dem letzten Tastendruck in einem Suchfeld zeichnet sich der Reiter nicht von selbst neu. */
    static final long TYPING_PAUSE_MS = 1500L;
    static final String CONTENT = "Pages/AE2/TerminalShell.ui";
    static final String CRAFTING_CONTENT = "Pages/AE2/CraftingTerminalShell.ui";
    static final TerminalView.Layout LAYOUT = new TerminalView.Layout(7, "Pages/AE2/TerminalShellAmountRow.ui");
    /** Crafting-Terminal: 4 Netz-Zeilen, darunter das Herstellen mit 10 Rezepten je Zeile. */
    static final TerminalView.Layout CRAFTING_LAYOUT = new TerminalView.Layout(4,
            "Pages/AE2/TerminalShellAmountRow.ui", 10);

    /** Offene Terminal-Sitzungen je Spieler. */
    static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private static TerminalShellTab tab;
    private static TerminalShellContainer container;

    private FtCoreTerminals() {
    }

    /** Ein in der Schale geöffnetes Terminal. */
    static final class Session {
        final PlayerRef playerRef;
        final TerminalBackend backend;
        final TerminalView view;
        final SimpleItemContainer dropContainer;
        /** Stand, zu dem zuletzt ein Neuzeichnen angestoßen wurde (gegen Dauer-Neuzeichnen in anderen Reitern). */
        long lastSnapshot = Long.MIN_VALUE;
        /**
         * Bis dahin nicht von selbst neu zeichnen: Der Spieler tippt gerade in ein Suchfeld. Das Neuzeichnen der
         * Schale baut das Dokument neu auf und nähme dem Feld den Fokus – in einem Netz, in dem Maschinen laufen,
         * alle halbe Sekunde. Jeder Tastendruck schickt ohnehin den aktuellen Stand.
         */
        long typingUntil;
        /**
         * true, solange die Schale für diese Sitzung gerade geöffnet wird: Schließt dabei eine ältere Schale, gilt
         * deren Abmeldung nicht dieser Sitzung.
         */
        boolean opening;

        Session(PlayerRef playerRef, TerminalBackend backend, String titleKey, boolean crafting) {
            this.playerRef = playerRef;
            this.backend = backend;
            this.view = new TerminalView(crafting ? CRAFTING_LAYOUT : LAYOUT, titleKey);
            // Immer so groß wie das größte Gitter: die Schale meldet das Fenster mit fester Feldzahl an
            this.dropContainer = TerminalView.newDropContainer(LAYOUT.pageSize(), () -> backend, playerRef);
        }

        /** Dokument des Reiters: ME-Terminal oder ME-Crafting-Terminal. */
        String content() {
            return view.layout().crafting() ? CRAFTING_CONTENT : CONTENT;
        }
    }

    /** Meldet Reiter und Ablage-Behälter bei FTCore an; false, solange FTCore noch nicht eingerichtet ist. */
    public static synchronized boolean install() {
        FTCorePlugin core = FTCorePlugin.get();
        if (core == null) {
            return false;
        }
        if (tab == null) {
            tab = new TerminalShellTab();
            container = new TerminalShellContainer();
        }
        core.shellContainers().register(container);
        core.shellRegistry().register(tab);
        return true;
    }

    /**
     * Öffnet die Schale mit dem Terminal vorn.
     *
     * @param crafting true = ME-Crafting-Terminal (mit Herstellen)
     * @return false, wenn die Schale nicht geöffnet werden konnte (dann eigene Seite verwenden)
     */
    public static boolean open(Player player, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                               String titleKey, TerminalBackend backend, boolean crafting) {
        UUID id = playerRef.getUuid();
        Session session = new Session(playerRef, backend, titleKey, crafting);
        Session old = SESSIONS.put(id, session);
        if (old != null && old.backend != backend) {
            closed(old);
        }
        boolean opened;
        session.opening = true;
        try {
            opened = ShellAccess.open(player, playerRef, ref, store, TAB_ID);
        } catch (RuntimeException e) {
            SESSIONS.remove(id, session);
            throw e;
        } finally {
            session.opening = false;
        }
        if (!opened) {
            SESSIONS.remove(id, session);
        }
        return opened;
    }

    /** Vom Netz-Tick (Welt-Thread): zeichnet die Schale neu, wenn sich Netz oder Inventar geändert haben. */
    public static void refreshOpen() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
            Session s = e.getValue();
            Ref<EntityStore> ref = s.playerRef.getReference();
            if (ref == null || !ref.isValid() || now < s.typingUntil) {
                continue;
            }
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            if (world == null || !world.isInThread()) {
                continue;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            CustomUIPage page = player == null ? null : player.getPageManager().getCustomPage();
            if (!(page instanceof ShellPage shell)) {
                continue;
            }
            TerminalView.Viewer v = TerminalShellTab.viewer(s, ref, store, player, null);
            // lastSnapshot wird bei jedem Zeichnen des Reiters gesetzt; ungleich = Netz oder Inventar geändert
            long snap = s.view.snapshot(s.backend, v);
            if (snap == s.lastSnapshot) {
                continue;
            }
            s.lastSnapshot = snap;
            // Sicher auch dann, wenn gerade ein anderer Reiter vorn liegt: die Schale zeichnet nur den aktiven
            shell.refresh();
        }
    }

    /** Spieler hat den Server verlassen. */
    public static void forget(UUID playerId) {
        closed(SESSIONS.remove(playerId));
    }

    /** Meldet dem Backend einer beendeten Sitzung, dass seine Ansicht zu ist (Terminals in einem Item). */
    static void closed(@javax.annotation.Nullable Session session) {
        if (session == null) {
            return;
        }
        try {
            session.backend.onClose();
        } catch (RuntimeException ignored) {
            // das Schließen der Schale darf nie am Backend scheitern
        }
    }
}
