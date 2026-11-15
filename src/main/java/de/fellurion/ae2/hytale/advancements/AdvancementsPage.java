/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: Minecraft-Fortschrittsbildschirm, Reiter „Applied Energistics“).
 */
package de.fellurion.ae2.hytale.advancements;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Übersicht aller AE2-Erfolge in Baumreihenfolge: Symbol, Titel, Beschreibung, Zustand (erzielt / offen /
 * Teilfortschritt / noch nicht portiert). Wird bei neuen Erfolgen live aktualisiert.
 */
final class AdvancementsPage extends CustomUIPage {

    private static final String PAGE = "Pages/AE2/Advancements.ui";
    private static final String ENTRY = "Pages/AE2/AdvancementEntry.ui";
    /** Einrückung je Baumebene in Pixeln. */
    private static final int INDENT = 22;

    private static final String COLOR_DONE = "#e8a93b";
    private static final String COLOR_OPEN = "#d3d6db";
    private static final String COLOR_PENDING = "#6b7886";
    private static final String STATE_DONE = "#7cd67c";
    private static final String STATE_OPEN = "#96a9be";
    private static final String STATE_PENDING = "#5a6a7a";

    /** Offene Seiten je Spieler, damit neue Erfolge sofort erscheinen. */
    private static final Map<UUID, AdvancementsPage> OPEN = new ConcurrentHashMap<>();

    AdvancementsPage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        OPEN.put(playerRef.getUuid(), this);
        cmd.append(PAGE);
        cmd.set("#AdvTitle.Text", Message.translation("ae2adv.page.title"));
        fill(cmd);
    }

    private void fill(UICommandBuilder cmd) {
        AdvancementRegistry registry = AdvancementRegistry.get();
        PlayerAdvancements progress = AE2Advancements.progress(playerRef.getUuid());
        int done = 0;
        int i = 0;
        cmd.clear("#List");
        for (Advancement adv : registry.all()) {
            String sel = "#List[" + i++ + "]";
            cmd.append("#List", ENTRY);
            Anchor indent = new Anchor();
            indent.setWidth(Value.of(adv.depth() * INDENT));
            cmd.setObject(sel + " #Indent.Anchor", indent);
            cmd.set(sel + " #Icon.ItemId", adv.icon());
            cmd.set(sel + " #Name.Text", Message.translation(adv.titleKey()));
            cmd.set(sel + " #Desc.Text", Message.translation(adv.descKey()));

            boolean isDone = progress.isDone(adv.id());
            if (isDone) {
                done++;
            }
            String nameColor;
            String stateColor;
            Message state;
            if (isDone) {
                nameColor = COLOR_DONE;
                stateColor = STATE_DONE;
                state = Message.translation("ae2adv.state.done");
            } else if (!adv.available()) {
                nameColor = COLOR_PENDING;
                stateColor = STATE_PENDING;
                state = Message.translation("ae2adv.state.pending");
            } else {
                nameColor = COLOR_OPEN;
                stateColor = STATE_OPEN;
                Set<String> crit = progress.criteriaOf(adv.id());
                int need = adv.requirements().size();
                int have = adv.requirementsMet(crit);
                state = need > 1 && have > 0
                        ? Message.translation("ae2adv.state.partial").param("have", have).param("need", need)
                        : Message.translation("ae2adv.state.open");
            }
            cmd.set(sel + " #Accent.Visible", isDone);
            cmd.set(sel + " #Name.Style.TextColor", nameColor);
            cmd.set(sel + " #State.Style.TextColor", stateColor);
            cmd.set(sel + " #State.TextSpans", state);
        }
        int total = registry.size();
        cmd.set("#Progress.Text", Message.translation("ae2adv.page.progress").param("done", done).param("total", total));
        cmd.set("#ProgressBar.Value", total == 0 ? 0f : (float) done / total);
    }

    /** Baut die Liste neu und schickt sie an den Client (Welt-Thread des Spielers). */
    private void refresh() {
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd);
        sendUpdate(cmd, false);
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        OPEN.remove(playerRef.getUuid(), this);
        super.onDismiss(ref, store);
    }

    /** Aktualisiert die offene Seite des Spielers (falls offen) auf dessen Welt-Thread. */
    static void refreshFor(UUID player) {
        AdvancementsPage page = OPEN.get(player);
        if (page == null) {
            return;
        }
        Ref<EntityStore> ref = page.playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            OPEN.remove(player, page);
            return;
        }
        ref.getStore().getExternalData().getWorld().execute(() -> {
            if (OPEN.get(player) != page || !ref.isValid()) {
                return;
            }
            Player p = ref.getStore().getComponent(ref, Player.getComponentType());
            if (p == null || p.getPageManager().getCustomPage() != page) {
                OPEN.remove(player, page); // inzwischen eine andere Seite offen
                return;
            }
            try {
                page.refresh();
            } catch (RuntimeException e) {
                OPEN.remove(player, page);
            }
        });
    }

    static void forget(UUID player) {
        OPEN.remove(player);
    }
}
