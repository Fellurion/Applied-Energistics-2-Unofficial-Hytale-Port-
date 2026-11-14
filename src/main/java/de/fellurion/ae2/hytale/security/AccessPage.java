/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.client.gui.implementations.SecurityStationScreen – hier eine einfache Spielerliste).
 */
package de.fellurion.ae2.hytale.security;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Zugriffsverwaltung („Zugriff“ im Terminal, nur Besitzer und Admins): oben der Besitzer, darunter „Spieler
 * hinzufügen“ – Suchfeld + Auswahlfeld (Online-Spieler und alle, die schon einmal da waren; der Suchtext filtert die
 * Auswahl, der beste Treffer ist gleich gewählt) + „Hinzufügen“ – und die Liste der eingetragenen Spieler mit
 * „Entfernen“. Die Liste gilt für alle ME-Netze des Besitzers.
 *
 * <p>Jede Aktion prüft erneut, ob der Zuschauer die Liste bearbeiten darf.
 */
public final class AccessPage extends InteractiveCustomUIPage<AccessPage.Payload> {

    /** Öffnet die vorige Ansicht wieder (Knopf „Zurück zum Terminal“). */
    @FunctionalInterface
    public interface Reopener {
        void reopen(Player player, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store);
    }

    static final String PAGE = "Pages/AE2/Access.ui";
    static final String ROW = "Pages/AE2/AccessRow.ui";
    /** Höchstens so viele Einträge im Auswahlfeld (der Suchtext grenzt weiter ein). */
    static final int MAX_CHOICES = 40;

    private static final String A_SEARCH = "search";
    private static final String A_ADD = "add";
    private static final String A_REMOVE = "remove";
    private static final String A_BACK = "back";

    private final Owner owner;
    private final String locale;
    @Nullable
    private final Reopener back;
    private String search = "";
    private String feedback = "";
    private boolean feedbackError;

    AccessPage(PlayerRef viewer, Owner owner, String locale, @Nullable Reopener back) {
        super(viewer, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Payload.CODEC);
        this.owner = owner;
        this.locale = locale == null ? "en-US" : locale;
        this.back = back;
    }

    private String tr(String key, Object... params) {
        return TerminalTexts.tr(locale, key, params);
    }

    // ------------------------------------------------------------------ Aufbau

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE);
        boolean self = owner.uuid().equals(playerRef.getUuid());
        cmd.set("#AccessTitle.Text", tr("ae2access.page.title"));
        cmd.set("#AccessOwner.Text", tr("ae2access.page.owner", "owner", owner.name()));
        cmd.set("#AccessInfo.Text", tr(self ? "ae2access.page.info" : "ae2access.page.infoAdmin", "owner", owner.name()));
        cmd.set("#AccessAddHeader.Text", tr("ae2access.page.add"));
        cmd.set("#AccessAdd.Text", tr("ae2access.add"));
        cmd.set("#AccessBack.Text", tr("ae2access.back"));
        cmd.set("#AccessBack.Visible", back != null);
        cmd.set("#AccessSearch.Value", search);

        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#AccessSearch",
                new EventData().append("Action", A_SEARCH).append(Payload.F_SEARCH, "#AccessSearch.Value"), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AccessAdd",
                new EventData().append("Action", A_ADD).append(Payload.F_SEARCH, "#AccessSearch.Value")
                        .append(Payload.F_PLAYER, "#AccessPlayer.Value"), false);
        if (back != null) {
            evt.addEventBinding(CustomUIEventBindingType.Activating, "#AccessBack", EventData.of("Action", A_BACK), false);
        }
        fillChoices(cmd);
        fillList(cmd, evt);
    }

    /** Auswahlfeld aus Suchtext; der beste Treffer ist vorgewählt (leere Suche: „Spieler auswählen …“). */
    private void fillChoices(UICommandBuilder cmd) {
        List<PlayerSearch.Candidate> matches = matches(search);
        List<DropdownEntryInfo> entries = new ArrayList<>(matches.size() + 1);
        String online = tr("ae2access.dropdown.online");
        boolean hasQuery = !PlayerSearch.normalize(search).isEmpty();
        if (matches.isEmpty()) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(tr("ae2access.dropdown.none")), ""));
        } else if (!hasQuery) {
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(tr("ae2access.dropdown.choose")), ""));
        }
        for (PlayerSearch.Candidate c : matches) {
            String label = c.online() ? c.name() + " · " + online : c.name();
            entries.add(new DropdownEntryInfo(LocalizableString.fromString(label), c.uuid().toString()));
        }
        cmd.set("#AccessPlayer.Entries", entries);
        cmd.set("#AccessPlayer.Value", hasQuery && !matches.isEmpty() ? matches.get(0).uuid().toString() : "");
        // Zwei Labels statt Farbwechsel per Befehl (Stilwerte lassen sich nicht sicher setzen)
        cmd.set("#AccessFeedback.Text", feedbackError ? "" : feedback);
        cmd.set("#AccessFeedbackError.Text", feedbackError ? feedback : "");
    }

    private void fillList(UICommandBuilder cmd, UIEventBuilder evt) {
        AccessStore store = AE2Security.store();
        List<AccessStore.Entry> rows = store == null ? List.of() : store.trusted(owner.uuid());
        cmd.set("#AccessListHeader.Text", tr("ae2access.list", "count", Integer.toString(rows.size())));
        cmd.set("#AccessEmpty.Visible", rows.isEmpty());
        cmd.set("#AccessEmpty.Text", tr("ae2access.empty", "owner", owner.name()));
        cmd.clear("#AccessRows");
        for (int i = 0; i < rows.size(); i++) {
            AccessStore.Entry e = rows.get(i);
            String sel = "#AccessRows[" + i + "]";
            String known = AE2Security.knownName(e.uuid());
            String name = Owner.displayName(e.uuid(), known != null ? known : e.name());
            if (known != null && store != null) {
                store.rename(owner.uuid(), e.uuid(), known);
            }
            boolean on = AE2Security.isOnline(e.uuid());
            cmd.append("#AccessRows", ROW);
            cmd.set(sel + " #Name.Text", name);
            cmd.set(sel + " #State.Text", tr(on ? "ae2access.state.online" : "ae2access.state.offline"));
            cmd.set(sel + " #Remove.Text", tr("ae2access.remove"));
            evt.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Remove",
                    new EventData().append("Action", A_REMOVE).append(Payload.F_TARGET, e.uuid().toString()), false);
        }
    }

    /** Wer im Auswahlfeld stehen kann: online + bekannt, ohne Besitzer und schon Eingetragene. */
    private List<PlayerSearch.Candidate> matches(String query) {
        Set<UUID> exclude = new HashSet<>();
        exclude.add(owner.uuid());
        AccessStore store = AE2Security.store();
        if (store != null) {
            for (AccessStore.Entry e : store.trusted(owner.uuid())) {
                exclude.add(e.uuid());
            }
        }
        List<PlayerSearch.Candidate> all = PlayerSearch.merge(AE2Security.onlinePlayers(), AE2Security.knownPlayers());
        return PlayerSearch.filter(all, query, exclude, MAX_CHOICES);
    }

    // ------------------------------------------------------------------ Ereignisse

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Payload p) {
        if (p.action == null) {
            return;
        }
        if (!AE2Security.mayManage(owner.uuid(), playerRef.getUuid())) {
            close();
            return;
        }
        switch (p.action) {
            case A_SEARCH -> {
                String s = p.search == null ? "" : p.search;
                if (s.equals(search)) {
                    return;
                }
                search = s;
                feedback = "";
                UICommandBuilder cmd = new UICommandBuilder();
                fillChoices(cmd);
                sendUpdate(cmd, false);
            }
            case A_ADD -> {
                if (p.search != null) {
                    search = p.search;
                }
                add(p.player);
                refresh();
            }
            case A_REMOVE -> {
                remove(JsonFiles.uuid(p.target));
                refresh();
            }
            case A_BACK -> {
                Player player = store.getComponent(ref, Player.getComponentType());
                if (back != null && player != null) {
                    back.reopen(player, playerRef, ref, store);
                }
            }
            default -> {
                // unbekannt
            }
        }
    }

    private void add(@Nullable String selected) {
        AccessStore store = AE2Security.store();
        if (store == null) {
            return;
        }
        UUID target = JsonFiles.uuid(selected);
        String name;
        if (target != null) {
            String known = AE2Security.knownName(target);
            name = known != null ? known : target.toString().substring(0, 8);
        } else {
            PlayerSearch.Candidate c = PlayerSearch.resolve(matches(search), search);
            if (c == null) {
                setFeedback(PlayerSearch.normalize(search).isEmpty() ? "ae2access.msg.choose" : "ae2access.msg.notFound",
                        true, "");
                return;
            }
            target = c.uuid();
            name = c.name();
        }
        if (target.equals(owner.uuid())) {
            setFeedback("ae2access.msg.self", true, name);
            return;
        }
        if (!store.add(owner.uuid(), owner.name(), target, name, System.currentTimeMillis())) {
            setFeedback("ae2access.msg.already", true, name);
            return;
        }
        search = "";
        setFeedback("ae2access.msg.added", false, name);
        PlayerRef added = onlinePlayer(target);
        if (added != null) {
            AE2Security.notify(added, Message.translation("ae2access.notify.title"),
                    Message.translation("ae2access.notify.granted").param("owner", owner.name()), NotificationStyle.Success);
        }
    }

    private void remove(@Nullable UUID target) {
        AccessStore store = AE2Security.store();
        if (store == null || target == null) {
            return;
        }
        String known = AE2Security.knownName(target);
        String name = Owner.displayName(target, known);
        if (!store.remove(owner.uuid(), target)) {
            return;
        }
        setFeedback("ae2access.msg.removed", false, name);
        PlayerRef removed = onlinePlayer(target);
        if (removed != null) {
            AE2Security.notify(removed, Message.translation("ae2access.notify.title"),
                    Message.translation("ae2access.notify.revoked").param("owner", owner.name()), NotificationStyle.Warning);
        }
        AE2Security.enforce(target);
    }

    private void setFeedback(String key, boolean error, String name) {
        feedback = tr(key, "name", name, "owner", owner.name());
        feedbackError = error;
    }

    @Nullable
    private static PlayerRef onlinePlayer(UUID uuid) {
        try {
            Universe u = Universe.get();
            return u == null ? null : u.getPlayer(uuid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Liste, Auswahl und Rückmeldung neu (Zeilen-Bindungen neu, die der Seite bleiben stehen). */
    private void refresh() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder evt = new UIEventBuilder();
        cmd.set("#AccessSearch.Value", search);
        fillChoices(cmd);
        fillList(cmd, evt);
        sendUpdate(cmd, evt, false);
    }

    // ------------------------------------------------------------------ Event-Daten

    public static final class Payload {
        static final String F_SEARCH = "@Search";
        static final String F_PLAYER = "@Player";
        static final String F_TARGET = "Target";

        String action;
        String search;
        String player;
        String target;

        public static final BuilderCodec<Payload> CODEC = BuilderCodec.builder(Payload.class, Payload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .append(new KeyedCodec<>(F_SEARCH, Codec.STRING), (o, v) -> o.search = v, o -> o.search).add()
                .append(new KeyedCodec<>(F_PLAYER, Codec.STRING), (o, v) -> o.player = v, o -> o.player).add()
                .append(new KeyedCodec<>(F_TARGET, Codec.STRING), (o, v) -> o.target = v, o -> o.target).add()
                .build();
    }
}
