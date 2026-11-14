/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.client.gui.me.common.MEStorageScreen / appeng.menu.me.common.MEStorageMenu).
 */
package de.fellurion.ae2.hytale.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.entity.entities.player.windows.WindowManager;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * ME-Terminal als eigene Seite (ohne FTCore): Netzinhalt als Gitter mit Suche, Sortierung, Seiten und Mengen im
 * AE2-Stil, daneben Lager, Schnellleiste und Rucksack des Spielers. Bedienung und Sicherheitsregeln siehe
 * {@link TerminalView}.
 *
 * <p>Ziehen &amp; Ablegen braucht – wie in FTCore – je Gitter ein geöffnetes Fenster: Der Client nennt beim Ablegen nur
 * Abschnitt und Feld, und auflösen kann der Server beides nur über die Fenster des Spielers. {@link #open} öffnet die
 * Seite deshalb mit Fenstern für Lager, Schnellleiste, Rucksack und den (immer leeren) Ablage-Behälter des Netzes.
 */
public class TerminalPage extends InteractiveCustomUIPage<TerminalPage.EventPayload> {

    private static final String PAGE = "Pages/AE2/Terminal.ui";
    private static final String AMOUNT_ROW = "Pages/AE2/TerminalAmountRow.ui";
    /** 9 × 7 Felder je Seite. */
    public static final TerminalView.Layout LAYOUT = new TerminalView.Layout(7, AMOUNT_ROW);

    /** Alle offenen Terminals, damit der Netz-Tick sie bei Änderungen aktualisieren kann. */
    private static final Set<TerminalPage> OPEN = ConcurrentHashMap.newKeySet();

    private final Supplier<TerminalBackend> backendSupplier;
    private final TerminalView view;
    /** Fenster je Gitter-Selektor, mit denen die Seite geöffnet wurde (leer = nur Klicks, kein Ziehen). */
    private final Map<String, Window> windows = new LinkedHashMap<>();

    public TerminalPage(PlayerRef playerRef, Supplier<TerminalBackend> backendSupplier, String titleKey) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, EventPayload.CODEC);
        this.backendSupplier = backendSupplier;
        this.view = new TerminalView(LAYOUT, titleKey);
    }

    /**
     * Öffnet das Terminal mit Fenstern für Ziehen &amp; Ablegen. Klappt das nicht, öffnet es ohne Fenster
     * (Klicks funktionieren weiter).
     */
    public static void open(Player player, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                            TerminalBackend backend, String titleKey) {
        TerminalPage page = new TerminalPage(playerRef, () -> backend, titleKey);
        List<Window> list = page.prepareWindows(ref, store);
        boolean withWindows = false;
        try {
            withWindows = !list.isEmpty() && player.getPageManager().openCustomPageWithWindows(ref, store, page,
                    list.toArray(new Window[0]));
        } catch (RuntimeException e) {
            withWindows = false;
        }
        if (!withWindows) {
            page.windows.clear();
            player.getPageManager().openCustomPage(ref, store, page);
            return;
        }
        // Die Fenster gehen erst nach der Seite an den Client; die Gitter deshalb jetzt erst an sie hängen
        UICommandBuilder cmd = new UICommandBuilder();
        page.view.fillSections(cmd, page.viewer(ref, store));
        page.sendUpdate(cmd, new UIEventBuilder(), false);
    }

    private List<Window> prepareWindows(Ref<EntityStore> ref, Store<EntityStore> store) {
        TerminalView.Viewer probe = new TerminalView.Viewer(ref, store, null, playerRef, "en-US", Map.of());
        for (String grid : TerminalView.BAG_GRIDS) {
            ItemContainer c = TerminalView.bagContainer(probe, grid);
            if (c != null && c.getCapacity() > 0) {
                windows.put(grid, new ContainerWindow(c));
            }
        }
        windows.put(TerminalView.NET_GRID, new ContainerWindow(
                TerminalView.newDropContainer(LAYOUT.pageSize(), backendSupplier, playerRef)));
        return new ArrayList<>(windows.values());
    }

    // ------------------------------------------------------------------ Aufbau

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        OPEN.add(this);
        cmd.append(PAGE);
        TerminalBackend backend = backendSupplier.get();
        if (backend == null) {
            return;
        }
        view.build(cmd, evt, action -> new EventData().put("Action", action), backend, viewer(ref, store));
    }

    private TerminalView.Viewer viewer(Ref<EntityStore> ref, Store<EntityStore> store) {
        Map<String, Integer> sections = new LinkedHashMap<>();
        windows.forEach((grid, w) -> {
            if (w.getId() > 0) {
                sections.put(grid, w.getId());
            }
        });
        Player player = store.getComponent(ref, Player.getComponentType());
        String lang = playerRef.getLanguage();
        return new TerminalView.Viewer(ref, store, player, playerRef, lang == null ? "en-US" : lang, sections);
    }

    /** AE2-Stil: max. 4 Zeichen (appeng.util.ReadableNumberConverter). */
    public static String formatAmount(long n) {
        return TerminalTexts.formatAmount(n);
    }

    // ------------------------------------------------------------------ Ereignisse

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull EventPayload data) {
        TerminalBackend backend = backendSupplier.get();
        if (backend == null || data.action == null) {
            return;
        }
        TerminalView.Event e = new TerminalView.Event(data.action, data.search, data.grid, toInt(data.slot, -1),
                toInt(data.sourceSection, Integer.MIN_VALUE), toInt(data.sourceSlot, -1), toInt(data.quantity, 0),
                toInt(data.button, 0), data.itemId);
        if (view.handle(e, backend, viewer(ref, store)) == TerminalView.Result.UPDATE) {
            refresh();
        }
    }

    private static int toInt(@Nullable Double d, int fallback) {
        return d == null || d.isNaN() || d.isInfinite() ? fallback : (int) Math.round(d);
    }

    /** Schickt Netzinhalt, Status und Inventar neu an den Client. */
    public void refresh() {
        TerminalBackend backend = backendSupplier.get();
        Ref<EntityStore> ref = playerRef.getReference();
        if (backend == null || ref == null || !ref.isValid()) {
            return;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        view.fillUpdate(cmd, backend, viewer(ref, ref.getStore()));
        sendUpdate(cmd, new UIEventBuilder(), false);
    }

    /** Vom Netz-Tick aufgerufen: aktualisiert nur, wenn sich Netz oder Inventar geändert haben. */
    public void refreshIfChanged() {
        TerminalBackend backend = backendSupplier.get();
        Ref<EntityStore> ref = playerRef.getReference();
        if (backend == null || ref == null || !ref.isValid()) {
            return;
        }
        World world = ref.getStore().getExternalData().getWorld();
        if (world == null || !world.isInThread()) {
            // Komponenten nur auf dem eigenen Welt-Thread lesen; die Welt des Spielers tickt selbst
            return;
        }
        if (view.changed(backend, viewer(ref, ref.getStore()))) {
            refresh();
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        OPEN.remove(this);
        closeWindows(ref, store);
        super.onDismiss(ref, store);
    }

    /** Schließt die mit der Seite geöffneten Fenster, soweit der Client das nicht schon getan hat. */
    private void closeWindows(Ref<EntityStore> ref, Store<EntityStore> store) {
        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            WindowManager wm = player == null ? null : player.getWindowManager();
            if (wm == null) {
                return;
            }
            for (Window w : windows.values()) {
                if (w.getId() > 0 && wm.getWindow(w.getId()) == w) {
                    wm.closeWindow(ref, w.getId(), store);
                }
            }
        } catch (RuntimeException ignored) {
            // Fenster schon zu
        }
        windows.clear();
    }

    /** Aktualisiert alle offenen Terminals (aufzurufen auf dem Welt-Thread), auch die in der FTCore-Schale. */
    public static void refreshOpenTerminals() {
        for (TerminalPage p : OPEN) {
            if (!p.playerRef.isValid()) {
                OPEN.remove(p);
                continue;
            }
            try {
                p.refreshIfChanged();
            } catch (RuntimeException ex) {
                OPEN.remove(p);
            }
        }
        TerminalOpener.refreshShells();
    }

    // ------------------------------------------------------------------ Event-Daten

    public static final class EventPayload {
        String action;
        String search;
        String grid;
        String itemId;
        Double slot;
        Double sourceSection;
        Double sourceSlot;
        Double quantity;
        Double button;

        /** Zahlenfelder als Gleitkommazahl: der Client schickt sie ohne Anführungszeichen (in FTCore erprobt). */
        public static final BuilderCodec<EventPayload> CODEC = BuilderCodec.builder(EventPayload.class, EventPayload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .append(new KeyedCodec<>(TerminalView.F_SEARCH, Codec.STRING), (o, v) -> o.search = v, o -> o.search).add()
                .append(new KeyedCodec<>(TerminalView.F_GRID, Codec.STRING), (o, v) -> o.grid = v, o -> o.grid).add()
                .append(new KeyedCodec<>(TerminalView.F_ITEM, Codec.STRING), (o, v) -> o.itemId = v, o -> o.itemId).add()
                .append(new KeyedCodec<>(TerminalView.F_SLOT, Codec.DOUBLE), (o, v) -> o.slot = v, o -> o.slot).add()
                .append(new KeyedCodec<>(TerminalView.F_SOURCE_SECTION, Codec.DOUBLE), (o, v) -> o.sourceSection = v,
                        o -> o.sourceSection).add()
                .append(new KeyedCodec<>(TerminalView.F_SOURCE_SLOT, Codec.DOUBLE), (o, v) -> o.sourceSlot = v,
                        o -> o.sourceSlot).add()
                .append(new KeyedCodec<>(TerminalView.F_QUANTITY, Codec.DOUBLE), (o, v) -> o.quantity = v,
                        o -> o.quantity).add()
                .append(new KeyedCodec<>(TerminalView.F_BUTTON, Codec.DOUBLE), (o, v) -> o.button = v, o -> o.button).add()
                .build();
    }
}
