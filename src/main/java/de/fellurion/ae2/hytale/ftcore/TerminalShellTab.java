/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.client.gui.me.common.MEStorageScreen).
 */
package de.fellurion.ae2.hytale.ftcore;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.ui.TerminalTexts;
import de.fellurion.ae2.hytale.ui.TerminalView;
import de.fellurion.ftcore.FTCorePlugin;
import de.fellurion.ftcore.shell.ShellContext;
import de.fellurion.ftcore.shell.ShellEventData;
import de.fellurion.ftcore.shell.ShellPage;
import de.fellurion.ftcore.shell.ShellTab;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Reiter „ME-Terminal“ der FTCore-Schale: Netzinhalt des benutzten Terminals links, Lager/Schnellleiste/Rucksack
 * rechts, Ziehen &amp; Ablegen zwischen beiden (Logik in {@link TerminalView}).
 *
 * <p>Sichtbar nur mit offener Terminal-Sitzung (also nach Benutzen eines Terminal-Blocks); über {@code /ft} oder die
 * Inventar-Taste erscheint er nicht. Suche, Sortierung, Klicks und Ablegen schicken nur den geänderten Inhalt an den
 * Client (die Schale würde sonst das ganze Dokument neu aufbauen und das Suchfeld verlöre bei jedem Buchstaben den
 * Fokus); {@link #handleAction} meldet deshalb {@code false}.
 */
final class TerminalShellTab implements ShellTab {

    @Override
    @Nonnull
    public String id() {
        return FtCoreTerminals.TAB_ID;
    }

    @Override
    @Nonnull
    public String label(@Nonnull String locale) {
        return TerminalTexts.tr(locale, "ae2ui.terminal.tab");
    }

    @Override
    public int order() {
        return 15;
    }

    @Override
    public boolean isVisible(@Nonnull PlayerRef playerRef) {
        return FtCoreTerminals.SESSIONS.containsKey(playerRef.getUuid());
    }

    @Override
    @Nonnull
    public List<String> eventFields() {
        // Zieh-Felder unter denselben Namen wie FTCores InventoryDrag (Zahlen mit '#'), dazu Suche, Maustaste, Item
        return List.of(TerminalView.F_SEARCH, TerminalView.F_GRID, TerminalView.F_ITEM,
                TerminalView.F_SLOT + ShellEventData.NUMBER, TerminalView.F_SOURCE_SECTION + ShellEventData.NUMBER,
                TerminalView.F_SOURCE_SLOT + ShellEventData.NUMBER, TerminalView.F_QUANTITY + ShellEventData.NUMBER,
                TerminalView.F_BUTTON + ShellEventData.NUMBER);
    }

    @Override
    public void build(@Nonnull ShellContext context, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt) {
        cmd.append("#ContentArea", FtCoreTerminals.CONTENT);
        FtCoreTerminals.Session s = FtCoreTerminals.SESSIONS.get(context.playerRef().getUuid());
        Ref<EntityStore> ref = context.ref();
        if (s == null || ref == null || !ref.isValid()) {
            cmd.set("#AE2Empty.Visible", true);
            cmd.set("#AE2Empty.Text", TerminalTexts.tr(context.locale(), "ae2ui.terminal.noSession"));
            return;
        }
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        TerminalView.Viewer v = viewer(s, ref, store, player, context.locale());
        s.view.build(cmd, evt, action -> new EventData().put(ShellEventData.TAB, id()).put(ShellEventData.ACTION, action),
                s.backend, v);
        // Beim ersten Aufbau sind die Fenster noch nicht gemerkt (leer); die Schale zeichnet nach dem Öffnen neu
        s.view.fillSections(cmd, v);
        s.lastSnapshot = s.view.snapshot(s.backend, v);
    }

    @Override
    public boolean handleAction(@Nonnull ShellContext context, @Nonnull ShellEventData data,
                                @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        String action = data.getAction();
        FtCoreTerminals.Session s = FtCoreTerminals.SESSIONS.get(context.playerRef().getUuid());
        if (action == null || !action.startsWith("ae2.") || s == null) {
            return false;
        }
        TerminalView.Event e = new TerminalView.Event(action, data.get(TerminalView.F_SEARCH),
                data.get(TerminalView.F_GRID), data.getInt(TerminalView.F_SLOT, -1),
                data.getInt(TerminalView.F_SOURCE_SECTION, Integer.MIN_VALUE),
                data.getInt(TerminalView.F_SOURCE_SLOT, -1), data.getInt(TerminalView.F_QUANTITY, 0),
                data.getInt(TerminalView.F_BUTTON, 0), data.get(TerminalView.F_ITEM));
        Player player = store.getComponent(ref, Player.getComponentType());
        TerminalView.Viewer v = viewer(s, ref, store, player, context.locale());
        if (s.view.handle(e, s.backend, v) == TerminalView.Result.UPDATE && player != null) {
            sendPartial(player, s, v);
        }
        return false;
    }

    @Override
    public void onShellClosed(@Nonnull ShellContext context, @Nonnull Ref<EntityStore> ref,
                              @Nonnull Store<EntityStore> store) {
        FtCoreTerminals.SESSIONS.remove(context.playerRef().getUuid());
    }

    /** Schickt nur den geänderten Inhalt an die offene Schale (Dokument und Bindungen bleiben stehen). */
    private static void sendPartial(Player player, FtCoreTerminals.Session s, TerminalView.Viewer v) {
        CustomUIPage page = player.getPageManager().getCustomPage();
        if (!(page instanceof ShellPage)) {
            return;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        s.view.fillUpdate(cmd, s.backend, v);
        s.lastSnapshot = s.view.snapshot(s.backend, v);
        player.getPageManager().updateLegacyCustomPage(new CustomPage(page.getClass().getName(), false, false,
                page.getLifetime(), cmd.getCommands(), UIEventBuilder.EMPTY_EVENT_BINDING_ARRAY));
    }

    /** Zuschauer mit den Abschnittsnummern der Fenster, die die Schale beim Öffnen angelegt hat. */
    static TerminalView.Viewer viewer(FtCoreTerminals.Session s, Ref<EntityStore> ref, Store<EntityStore> store,
                                      @Nullable Player player, @Nullable String locale) {
        FTCorePlugin core = FTCorePlugin.get();
        Map<String, Integer> sections = core == null ? Map.of()
                : core.inventoryWindows().sectionsOf(s.playerRef.getUuid());
        String l = locale;
        if (l == null) {
            l = core == null ? null : core.messages().languageOf(s.playerRef.getUuid());
        }
        if (l == null) {
            l = s.playerRef.getLanguage();
        }
        return new TerminalView.Viewer(ref, store, player, s.playerRef, l == null ? "en-US" : l, sections);
    }
}
