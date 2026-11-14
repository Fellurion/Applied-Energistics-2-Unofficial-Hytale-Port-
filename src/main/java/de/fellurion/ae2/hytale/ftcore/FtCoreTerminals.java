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
    static final String CONTENT = "Pages/AE2/TerminalShell.ui";
    static final TerminalView.Layout LAYOUT = new TerminalView.Layout(7, "Pages/AE2/TerminalShellAmountRow.ui");

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

        Session(PlayerRef playerRef, TerminalBackend backend, String titleKey) {
            this.playerRef = playerRef;
            this.backend = backend;
            this.view = new TerminalView(LAYOUT, titleKey);
            this.dropContainer = TerminalView.newDropContainer(LAYOUT.pageSize(), () -> backend, playerRef);
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
     * @return false, wenn die Schale nicht geöffnet werden konnte (dann eigene Seite verwenden)
     */
    public static boolean open(Player player, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                               String titleKey, TerminalBackend backend) {
        UUID id = playerRef.getUuid();
        Session session = new Session(playerRef, backend, titleKey);
        SESSIONS.put(id, session);
        boolean opened;
        try {
            opened = ShellAccess.open(player, playerRef, ref, store, TAB_ID);
        } catch (RuntimeException e) {
            SESSIONS.remove(id, session);
            throw e;
        }
        if (!opened) {
            SESSIONS.remove(id, session);
        }
        return opened;
    }

    /** Vom Netz-Tick (Welt-Thread): zeichnet die Schale neu, wenn sich Netz oder Inventar geändert haben. */
    public static void refreshOpen() {
        for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
            Session s = e.getValue();
            Ref<EntityStore> ref = s.playerRef.getReference();
            if (ref == null || !ref.isValid()) {
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
        SESSIONS.remove(playerId);
    }
}
