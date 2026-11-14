/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.client.gui.me.common.MEStorageScreen / appeng.menu.me.common.MEStorageMenu).
 */
package de.fellurion.ae2.hytale.ui;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.setsys.SetSysItems;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Gemeinsamer Inhalt der ME-Terminal-Ansichten (eigene Seite und FTCore-Schale): Netzinhalt als Gitter mit Suche,
 * Sortierung und Seiten, daneben das Inventar des Spielers (Lager, Schnellleiste, Rucksack).
 *
 * <p><b>Bedienung</b> (wie AE2): Klick auf einen Netz-Eintrag entnimmt einen Stapel, Rechtsklick ein Item, Mittelklick
 * bzw. Klick im Herstellen-Modus oder auf einen Eintrag mit Menge 0 öffnet die Craft-Anfrage. Klick auf ein
 * Inventarfeld lagert den Stapel ein (Rechtsklick: ein Item). Ziehen aus dem Inventar auf das Netz-Gitter lagert ein,
 * Ziehen aus dem Netz-Gitter auf ein Inventarfeld entnimmt dorthin.
 *
 * <p><b>Keine Dupes, kein Verlust:</b> Das Netz-Gitter zeigt nur Anzeige-Kopien. Sein Fenster (für die Abschnittsnummer,
 * die der Client zum Ziehen braucht) hängt an einem immer leeren Behälter mit {@link FilterType#DENY_ALL}; was dennoch
 * hineingerät, wird sofort eingelagert bzw. zurückgegeben ({@link #newDropContainer}). Jede Bewegung rechnet der
 * Server: Einlagern nimmt die Items erst aus dem Feld und gibt zurück, was das Netz nicht annimmt; Entnehmen geht
 * immer über {@link TerminalBackend#extract} (AE2-Energiekosten) und landet im Zielfeld, sonst im Inventar, sonst
 * zurück im Netz, als Letztes vor den Füßen des Spielers.
 *
 * <p><b>Zugriffsschutz:</b> Ohne Zugriff ({@link TerminalBackend#accessDeniedText}) zeigt die Ansicht nur, wem das Netz
 * gehört; jede Aktion am Netz prüft vorher {@link TerminalBackend#checkAccess}. Besitzer und Admins sehen den Knopf
 * „Zugriff“ ({@link #A_ACCESS}, Zugriffsliste in {@code security.AccessPage}).
 */
public final class TerminalView {

    public static final String NET_GRID = "#AE2NetGrid";
    public static final String STORAGE_GRID = "#StorageGrid";
    public static final String HOTBAR_GRID = "#HotbarGrid";
    public static final String BACKPACK_GRID = "#BackpackGrid";
    public static final List<String> BAG_GRIDS = List.of(STORAGE_GRID, HOTBAR_GRID, BACKPACK_GRID);
    public static final int COLUMNS = 9;

    // Aktionen (Präfix "ae2.", damit sie in der FTCore-Schale nicht mit anderen Reitern kollidieren)
    public static final String A_SEARCH = "ae2.search";
    public static final String A_SORT = "ae2.sort";
    public static final String A_PREV = "ae2.prev";
    public static final String A_NEXT = "ae2.next";
    public static final String A_INSERT_ALL = "ae2.insertAll";
    public static final String A_CRAFT_MODE = "ae2.craftMode";
    public static final String A_CPUS = "ae2.cpus";
    public static final String A_CLICK = "ae2.click";
    public static final String A_DROP = "ae2.drop";
    /** Zugriffsverwaltung öffnen (Knopf „Zugriff“, nur Besitzer/Admins). */
    public static final String A_ACCESS = "ae2.access";

    /** Aktionen, die das Netz anfassen: vorher Zugriff prüfen (die Seite kann nach einem Entzug noch offen sein). */
    private static final Set<String> NEEDS_ACCESS = Set.of(A_INSERT_ALL, A_CLICK, A_DROP, A_CPUS);

    // Ereignisfelder; die Namen der Zieh-Felder schickt der Client so (in FTCore nachgemessen)
    public static final String F_SEARCH = "@AE2Search";
    public static final String F_GRID = "Grid";
    public static final String F_SLOT = "SlotIndex";
    public static final String F_SOURCE_SECTION = "SourceInventorySectionId";
    public static final String F_SOURCE_SLOT = "SourceSlotId";
    public static final String F_QUANTITY = "ItemStackQuantity";
    public static final String F_BUTTON = "PressedMouseButton";
    public static final String F_ITEM = "ItemStackId";

    /** Maustasten wie im Client (1 links, 2 Mitte, 3 rechts) – Annahme, siehe Bericht. */
    static final int BUTTON_MIDDLE = 2;
    static final int BUTTON_RIGHT = 3;

    /** Ein Zug meldet sich über mehrere Ereignisse; dieselbe Bewegung zählt nur einmal (wie FTCore). */
    private static final long DROP_DEDUPE_MS = 400L;
    /** Ein Klick kurz vor dem Ablegen desselben Felds hat schon entnommen. */
    private static final long TAKE_GUARD_MS = 600L;

    private static final int STORAGE_LIMIT = 36;
    private static final int HOTBAR_LIMIT = 9;
    private static final int BACKPACK_LIMIT = 36;

    public enum SortMode { AMOUNT, NAME, ID }

    /** Ergebnis eines Ereignisses. */
    public enum Result {
        /** nichts zu tun */
        NONE,
        /** Inhalt neu schicken ({@link #fillUpdate}) */
        UPDATE,
        /** eine andere Seite wurde geöffnet (Craft-Anfrage, Status) – nichts mehr an diese Seite schicken */
        PAGE_OPENED
    }

    /**
     * Aufteilung der Seite.
     *
     * @param rows      Zeilen des Netz-Gitters (je {@value #COLUMNS} Felder)
     * @param amountRow Dokument einer Zeile Mengenangaben über dem Gitter
     */
    public record Layout(int rows, String amountRow) {
        public int pageSize() {
            return rows * COLUMNS;
        }
    }

    /** Ein Ereignis der Seite, unabhängig davon, ob es aus der eigenen Seite oder der FTCore-Schale kommt. */
    public record Event(String action, @Nullable String search, @Nullable String grid, int slot, int sourceSection,
                        int sourceSlot, int quantity, int button, @Nullable String itemId) {
    }

    /**
     * Wer zuschaut.
     *
     * @param sections Abschnittsnummern (= Fenster-Ids) je Gitter-Selektor; leer, wenn ohne Fenster geöffnet
     */
    public record Viewer(Ref<EntityStore> ref, Store<EntityStore> store, @Nullable Player player, PlayerRef playerRef,
                         String locale, Map<String, Integer> sections) {
    }

    private final Layout layout;
    private final String titleKey;
    private String search = "";
    private SortMode sort = SortMode.AMOUNT;
    private int page;
    private boolean craftMode;
    private List<TerminalBackend.Entry> shown = List.of();
    private long shownRevision = Long.MIN_VALUE;
    private int shownInventory;

    private long lastDropTime;
    private String lastDrop = "";
    private int lastTakeSlot = -1;
    private long lastTakeTime;

    public TerminalView(Layout layout, String titleKey) {
        this.layout = layout;
        this.titleKey = titleKey;
    }

    // ------------------------------------------------------------------ Aufbau

    /**
     * Vollständiger Aufbau nach dem Einfügen des Dokuments.
     *
     * @param base erzeugt die Ereignisdaten einer Aktion (für die Schale mit Reiter-Id)
     */
    public void build(UICommandBuilder cmd, UIEventBuilder evt, Function<String, EventData> base,
                      TerminalBackend backend, Viewer v) {
        String l = v.locale();
        cmd.set("#AE2Title.Text", TerminalTexts.tr(l, titleKey));
        cmd.set("#AE2Search.Value", search);
        cmd.set("#AE2InsertAll.Text", TerminalTexts.tr(l, "ae2ui.terminal.insertInventory"));
        cmd.set("#AE2Cpus.Text", TerminalTexts.tr(l, "ae2ui.terminal.craftingStatus"));
        cmd.set("#AE2Cpus.Visible", backend.hasCraftingStatus());
        cmd.set("#AE2CraftMode.Visible", backend.hasCraftingStatus());
        cmd.set("#AE2Hint.Text", TerminalTexts.tr(l, "ae2ui.terminal.hint"));
        cmd.set("#AE2InvHeader.Text", TerminalTexts.tr(l, "ae2ui.terminal.inventory"));
        cmd.set("#AE2HotbarHeader.Text", TerminalTexts.tr(l, "ae2ui.terminal.hotbar"));
        cmd.set("#AE2BackpackHeader.Text", TerminalTexts.tr(l, "ae2ui.terminal.backpack"));
        cmd.set("#AE2Access.Text", TerminalTexts.tr(l, "ae2access.terminal.button"));

        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2Access", base.apply(A_ACCESS), false);
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#AE2Search",
                base.apply(A_SEARCH).put(F_SEARCH, "#AE2Search.Value"), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2Sort", base.apply(A_SORT), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2Prev", base.apply(A_PREV), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2Next", base.apply(A_NEXT), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2InsertAll", base.apply(A_INSERT_ALL), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2CraftMode", base.apply(A_CRAFT_MODE), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2Cpus", base.apply(A_CPUS), false);
        for (String grid : allGrids()) {
            evt.addEventBinding(CustomUIEventBindingType.SlotClicking, grid, base.apply(A_CLICK).put(F_GRID, grid), false);
            evt.addEventBinding(CustomUIEventBindingType.Dropped, grid, base.apply(A_DROP).put(F_GRID, grid), false);
        }
        cmd.clear("#AE2NetAmounts");
        for (int r = 0; r < layout.rows(); r++) {
            cmd.append("#AE2NetAmounts", layout.amountRow());
        }
        fillUpdate(cmd, backend, v);
    }

    /**
     * Hängt die Gitter an ihre Fenster ({@code InventorySectionId}). Erst schicken, wenn der Client die Fenster kennt:
     * {@code openCustomPageWithWindows} schickt die Seite vor den Fenstern (FTCore setzt sie deshalb auch erst beim
     * Neuzeichnen nach dem Öffnen).
     */
    public void fillSections(UICommandBuilder cmd, Viewer v) {
        for (Map.Entry<String, Integer> s : v.sections().entrySet()) {
            if (s.getKey().equals(NET_GRID) || BAG_GRIDS.contains(s.getKey())) {
                cmd.set(s.getKey() + ".InventorySectionId", s.getValue());
            }
        }
    }

    /** Aktualisiert Netzinhalt, Status und Inventar (ohne Dokument und Bindungen neu zu schicken). */
    public void fillUpdate(UICommandBuilder cmd, TerminalBackend backend, Viewer v) {
        fillNetwork(cmd, backend, v.locale());
        fillBags(cmd, v);
    }

    private static List<String> allGrids() {
        List<String> g = new ArrayList<>(BAG_GRIDS.size() + 1);
        g.add(NET_GRID);
        g.addAll(BAG_GRIDS);
        return g;
    }

    private void fillNetwork(UICommandBuilder cmd, TerminalBackend backend, String l) {
        shownRevision = backend.revision();
        // Kein Zugriff (fremdes Netz, Zugriff entzogen): nichts zeigen außer dem Hinweis, wem es gehört
        String denied = backend.accessDeniedText(l);
        boolean online = denied == null && backend.isOnline();
        List<TerminalBackend.Entry> all = online ? filterAndSort(backend.entries(), l) : List.of();
        int size = layout.pageSize();
        int pages = Math.max(1, (all.size() + size - 1) / size);
        page = Math.max(0, Math.min(page, pages - 1));
        int from = page * size;
        shown = List.copyOf(all.subList(Math.min(from, all.size()), Math.min(from + size, all.size())));

        TerminalBackend.Status st = backend.status();
        if (denied != null) {
            cmd.set("#AE2Status.Text", TerminalTexts.tr(l, "ae2access.terminal.status"));
            cmd.set("#AE2Storage.Text", "");
        } else if (st == null) {
            cmd.set("#AE2Status.Text", backend.statusText());
            cmd.set("#AE2Storage.Text", backend.storageText());
        } else if (st.online()) {
            cmd.set("#AE2Status.Text", TerminalTexts.tr(l, "ae2ui.terminal.status.online",
                    "stored", TerminalTexts.formatAmount((long) st.storedPower()),
                    "max", TerminalTexts.formatAmount((long) st.maxPower()),
                    "usage", String.format(Locale.ROOT, "%.1f", st.avgUsage())));
            cmd.set("#AE2Storage.Text", TerminalTexts.tr(l, "ae2ui.terminal.storage",
                    "types", Integer.toString(st.types()), "items", TerminalTexts.formatAmount(st.items())));
        } else {
            cmd.set("#AE2Status.Text", TerminalTexts.tr(l, "ae2ui.terminal.status.offline"));
            cmd.set("#AE2Storage.Text", "");
        }
        cmd.set("#AE2Access.Visible", backend.canManageAccess());
        cmd.set("#AE2PageLabel.Text", (page + 1) + " / " + pages);
        cmd.set("#AE2Sort.Text", TerminalTexts.tr(l, "ae2ui.terminal.sort." + sort.name().toLowerCase(Locale.ROOT)));
        cmd.set("#AE2CraftMode.Text", TerminalTexts.tr(l, craftMode ? "ae2ui.terminal.craftMode.on"
                : "ae2ui.terminal.craftMode.off"));
        cmd.set("#AE2Empty.Visible", !online || all.isEmpty());
        cmd.set("#AE2Empty.Text", denied != null ? denied : TerminalTexts.tr(l, !online ? "ae2ui.terminal.offline"
                : search.isBlank() ? "ae2ui.terminal.empty" : "ae2ui.terminal.noMatch"));

        ItemGridSlot[] slots = new ItemGridSlot[size];
        for (int i = 0; i < size; i++) {
            ItemGridSlot slot = new ItemGridSlot();
            String cell = "#AE2NetAmounts[" + (i / COLUMNS) + "] ";
            int c = i % COLUMNS;
            if (i < shown.size()) {
                TerminalBackend.Entry e = shown.get(i);
                int qty = (int) Math.max(1, Math.min(e.amount(), TerminalTexts.maxStack(e.display())));
                slot.setItemStack(TerminalTexts.displayStack(e.display(), qty));
                slot.setDescription(TerminalTexts.entryDescription(e, l));
                // Menge 0 (nur herstellbar) lässt sich nicht herausziehen
                slot.setActivatable(e.amount() > 0);
                cmd.set(cell + "#A" + c + ".Text", e.amount() > 0 ? TerminalTexts.formatAmount(e.amount())
                        : TerminalTexts.tr(l, "ae2ui.terminal.craftable"));
                cmd.set(cell + "#S" + c + ".Visible", SetSysItems.bond(e.display()) != null);
                cmd.set(cell + "#K" + c + ".Visible", e.craftable() && e.amount() > 0);
            } else {
                cmd.set(cell + "#A" + c + ".Text", "");
                cmd.set(cell + "#S" + c + ".Visible", false);
                cmd.set(cell + "#K" + c + ".Visible", false);
            }
            slots[i] = slot;
        }
        cmd.set(NET_GRID + ".Slots", slots);
    }

    private void fillBags(UICommandBuilder cmd, Viewer v) {
        shownInventory = inventoryHash(v);
        for (String grid : BAG_GRIDS) {
            ItemContainer c = bagContainer(v, grid);
            int cap = c == null ? 0 : Math.min(c.getCapacity(), limit(grid));
            if (BACKPACK_GRID.equals(grid)) {
                // Der Rucksack ist eine Zusatztasche und fehlt oft ganz
                cmd.set(BACKPACK_GRID + ".Visible", cap > 0);
                cmd.set("#AE2BackpackHeader.Visible", cap > 0);
            }
            if (c == null || cap <= 0) {
                continue;
            }
            ItemGridSlot[] slots = new ItemGridSlot[cap];
            for (short s = 0; s < cap; s++) {
                ItemStack stack = c.getItemStack(s);
                ItemGridSlot slot = new ItemGridSlot();
                if (!ItemStack.isEmpty(stack)) {
                    slot.setItemStack(TerminalTexts.displayStack(stack, stack.getQuantity()));
                    String d = TerminalTexts.slotDescription(stack, v.locale());
                    if (!d.isEmpty()) {
                        slot.setDescription(d);
                    }
                }
                slot.setActivatable(true);
                slots[s] = slot;
            }
            cmd.set(grid + ".Slots", slots);
        }
    }

    private List<TerminalBackend.Entry> filterAndSort(List<TerminalBackend.Entry> entries, String l) {
        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<TerminalBackend.Entry> out = new ArrayList<>(entries.size());
        for (TerminalBackend.Entry e : entries) {
            String id = e.display().getItemId();
            if (q.isEmpty() || matches(q, id, TerminalTexts.itemName(l, id))) {
                out.add(e);
            }
        }
        Comparator<TerminalBackend.Entry> byId = Comparator.comparing(e -> e.display().getItemId());
        Comparator<TerminalBackend.Entry> byKey = byId.thenComparing(TerminalBackend.Entry::key);
        out.sort(switch (sort) {
            case AMOUNT -> Comparator.comparingLong(TerminalBackend.Entry::amount).reversed().thenComparing(byKey);
            case NAME -> Comparator.comparing((TerminalBackend.Entry e) ->
                    TerminalTexts.itemName(l, e.display().getItemId()).toLowerCase(Locale.ROOT)).thenComparing(byKey);
            case ID -> byKey;
        });
        return out;
    }

    static boolean matches(String query, String itemId, String name) {
        String id = itemId.toLowerCase(Locale.ROOT);
        return name.toLowerCase(Locale.ROOT).contains(query) || id.contains(query)
                || id.replace('_', ' ').contains(query);
    }

    // ------------------------------------------------------------------ Aktualisierung

    /** true, wenn sich Netzinhalt oder Inventar seit dem letzten Zeichnen geändert haben. */
    public boolean changed(TerminalBackend backend, Viewer v) {
        return backend.revision() != shownRevision || inventoryHash(v) != shownInventory;
    }

    /** Kennzahl des aktuellen Stands (Netz + Inventar), um wiederholtes Neuzeichnen zu vermeiden. */
    public long snapshot(TerminalBackend backend, Viewer v) {
        return backend.revision() * 31 + inventoryHash(v);
    }

    private static int inventoryHash(Viewer v) {
        int h = 1;
        for (String grid : BAG_GRIDS) {
            ItemContainer c = bagContainer(v, grid);
            if (c == null) {
                h = 31 * h;
                continue;
            }
            for (short s = 0; s < c.getCapacity(); s++) {
                ItemStack stack = c.getItemStack(s);
                h = 31 * h + (stack == null ? 0 : stack.hashCode());
            }
        }
        return h;
    }

    // ------------------------------------------------------------------ Ereignisse

    public Result handle(Event e, TerminalBackend backend, Viewer v) {
        if (e.action() == null) {
            return Result.NONE;
        }
        // Serverseitig bei jeder Aktion: ohne Zugriff Meldung „gehört X“ und neu zeichnen (zeigt den Hinweis)
        if (NEEDS_ACCESS.contains(e.action()) && !backend.checkAccess(v.playerRef())) {
            return Result.UPDATE;
        }
        switch (e.action()) {
            case A_ACCESS -> {
                return v.player() != null && backend.openAccess(v.player(), v.playerRef(), v.ref(), v.store(),
                        v.locale(), (p, pr, r, s) -> TerminalOpener.open(p, pr, r, s, titleKey, backend))
                        ? Result.PAGE_OPENED : Result.NONE;
            }
            case A_SEARCH -> {
                String s = e.search() == null ? "" : e.search();
                if (s.equals(search)) {
                    return Result.NONE;
                }
                search = s;
                page = 0;
                return Result.UPDATE;
            }
            case A_SORT -> {
                sort = SortMode.values()[(sort.ordinal() + 1) % SortMode.values().length];
                return Result.UPDATE;
            }
            case A_PREV -> {
                page--;
                return Result.UPDATE;
            }
            case A_NEXT -> {
                page++;
                return Result.UPDATE;
            }
            case A_CRAFT_MODE -> {
                craftMode = !craftMode;
                return Result.UPDATE;
            }
            case A_CPUS -> {
                return v.player() != null && backend.openCraftingStatus(v.player(), v.playerRef(), v.ref(), v.store())
                        ? Result.PAGE_OPENED : Result.NONE;
            }
            case A_INSERT_ALL -> {
                for (String grid : List.of(STORAGE_GRID, BACKPACK_GRID)) {
                    ItemContainer c = bagContainer(v, grid);
                    if (c != null) {
                        for (short s = 0; s < c.getCapacity(); s++) {
                            storeSlot(backend, v, c, s, Integer.MAX_VALUE);
                        }
                    }
                }
                return Result.UPDATE;
            }
            case A_CLICK -> {
                return click(e, backend, v);
            }
            case A_DROP -> {
                return drop(e, backend, v);
            }
            default -> {
                return Result.NONE;
            }
        }
    }

    private Result click(Event e, TerminalBackend backend, Viewer v) {
        if (NET_GRID.equals(e.grid())) {
            TerminalBackend.Entry entry = entryAt(e.slot(), e.itemId());
            if (entry == null) {
                return Result.NONE;
            }
            boolean craft = entry.craftable() && (entry.amount() <= 0 || craftMode || e.button() == BUTTON_MIDDLE);
            if (craft) {
                return v.player() != null && backend.requestCraft(entry.key(), v.player(), v.playerRef(), v.ref(), v.store())
                        ? Result.PAGE_OPENED : Result.NONE;
            }
            if (!backend.isOnline() || entry.amount() <= 0) {
                return Result.NONE;
            }
            int want = e.button() == BUTTON_RIGHT ? 1 : TerminalTexts.maxStack(entry.display());
            ItemStack got = backend.extract(entry.key(), want);
            if (got != null) {
                giveToInventory(backend, v, got);
            }
            lastTakeSlot = e.slot();
            lastTakeTime = System.currentTimeMillis();
            return Result.UPDATE;
        }
        ItemContainer c = bagContainer(v, e.grid());
        if (c == null || e.slot() < 0 || e.slot() >= c.getCapacity()) {
            return Result.NONE;
        }
        storeSlot(backend, v, c, (short) e.slot(), e.button() == BUTTON_RIGHT ? 1 : Integer.MAX_VALUE);
        return Result.UPDATE;
    }

    private Result drop(Event e, TerminalBackend backend, Viewer v) {
        if (e.grid() == null || e.slot() < 0 || e.sourceSlot() < 0 || e.sourceSection() == Integer.MIN_VALUE) {
            return Result.NONE;
        }
        long now = System.currentTimeMillis();
        String move = e.sourceSection() + "/" + e.sourceSlot() + ">" + e.grid() + "/" + e.slot();
        if (move.equals(lastDrop) && now - lastDropTime < DROP_DEDUPE_MS) {
            return Result.NONE;
        }
        lastDrop = move;
        lastDropTime = now;

        Integer netSection = v.sections().get(NET_GRID);
        boolean fromNet = netSection != null && netSection == e.sourceSection();
        if (NET_GRID.equals(e.grid())) {
            if (fromNet) {
                return Result.NONE;
            }
            ItemContainer src = ownContainer(v, e.sourceSection());
            if (src == null || e.sourceSlot() >= src.getCapacity()) {
                return Result.NONE;
            }
            storeSlot(backend, v, src, (short) e.sourceSlot(), e.quantity() > 0 ? e.quantity() : Integer.MAX_VALUE);
            return Result.UPDATE;
        }
        ItemContainer target = bagContainer(v, e.grid());
        if (target == null || e.slot() >= target.getCapacity()) {
            return Result.NONE;
        }
        if (fromNet) {
            if (e.sourceSlot() == lastTakeSlot && now - lastTakeTime < TAKE_GUARD_MS) {
                return Result.NONE;
            }
            extractToSlot(e, backend, v, target);
            return Result.UPDATE;
        }
        // Umsortieren im eigenen Inventar (wie im Inventar der Schale)
        ItemContainer src = ownContainer(v, e.sourceSection());
        Integer targetSection = sectionId(e.grid());
        if (src == null || targetSection == null || e.sourceSlot() >= src.getCapacity()) {
            return Result.NONE;
        }
        ItemStack moving = src.getItemStack((short) e.sourceSlot());
        if (ItemStack.isEmpty(moving)) {
            return Result.NONE;
        }
        int qty = Math.max(1, Math.min(e.quantity() > 0 ? e.quantity() : moving.getQuantity(), moving.getQuantity()));
        InventoryUtils.moveItem(v.ref(), e.sourceSection(), e.sourceSlot(), qty, targetSection, e.slot(), v.store());
        return Result.UPDATE;
    }

    /** Entnimmt den gezogenen Netz-Eintrag in das Zielfeld (Rest ins Inventar, notfalls zurück ins Netz). */
    private void extractToSlot(Event e, TerminalBackend backend, Viewer v, ItemContainer target) {
        if (!backend.isOnline() || e.sourceSlot() >= shown.size()) {
            return;
        }
        TerminalBackend.Entry entry = shown.get(e.sourceSlot());
        if (entry.amount() <= 0) {
            return;
        }
        int max = TerminalTexts.maxStack(entry.display());
        int want = Math.max(1, Math.min(e.quantity() > 0 ? e.quantity() : max, max));
        short slot = (short) e.slot();
        ItemStack there = target.getItemStack(slot);
        boolean intoSlot = ItemStack.isEmpty(there) || entry.display().isStackableWith(there);
        if (intoSlot && !ItemStack.isEmpty(there)) {
            int room = max - there.getQuantity();
            if (room <= 0) {
                intoSlot = false;
            } else {
                want = Math.min(want, room);
            }
        }
        ItemStack got = backend.extract(entry.key(), want);
        if (got == null) {
            return;
        }
        if (intoSlot) {
            ItemStackSlotTransaction tx = target.addItemStackToSlot(slot, got);
            got = tx.getRemainder();
        }
        if (!ItemStack.isEmpty(got)) {
            giveToInventory(backend, v, got);
        }
    }

    /**
     * Lagert bis zu {@code amount} Items aus einem Feld ein: erst aus dem Feld nehmen, dann ins Netz, Rest zurück.
     * Die Items liegen so zu jedem Zeitpunkt an genau einer Stelle.
     */
    static boolean storeSlot(TerminalBackend backend, Viewer v, ItemContainer container, short slot, int amount) {
        if (!backend.isOnline() || slot < 0 || slot >= container.getCapacity()) {
            return false;
        }
        ItemStack stack = container.getItemStack(slot);
        if (ItemStack.isEmpty(stack)) {
            return false;
        }
        int n = Math.max(1, Math.min(amount, stack.getQuantity()));
        ItemStackSlotTransaction tx = container.removeItemStackFromSlot(slot, n, true, false);
        if (!tx.succeeded()) {
            return false;
        }
        ItemStack taken = ItemStack.isEmpty(tx.getOutput()) ? stack.withQuantity(n) : tx.getOutput();
        long inserted;
        try {
            inserted = backend.insert(taken);
        } catch (RuntimeException ex) {
            inserted = 0;
        }
        int rest = (int) Math.max(0, taken.getQuantity() - inserted);
        if (rest > 0) {
            ItemStackSlotTransaction back = container.addItemStackToSlot(slot, taken.withQuantity(rest));
            ItemStack left = back.getRemainder();
            if (!ItemStack.isEmpty(left)) {
                giveToPlayer(v.ref(), v.store(), left);
            }
        }
        return inserted > 0;
    }

    /** Ins Inventar (Schnellleiste zuerst); was nicht passt, zurück ins Netz, sonst vor die Füße. */
    static void giveToInventory(TerminalBackend backend, Viewer v, ItemStack stack) {
        ItemContainer inv = InventoryComponent.getCombined(v.store(), v.ref(), InventoryComponent.HOTBAR_FIRST);
        ItemStackTransaction tx = inv.addItemStack(stack);
        ItemStack rest = tx.getRemainder();
        if (ItemStack.isEmpty(rest)) {
            return;
        }
        long back;
        try {
            back = backend.insert(rest);
        } catch (RuntimeException ex) {
            back = 0;
        }
        if (back < rest.getQuantity()) {
            ItemUtils.dropItem(v.ref(), rest.withQuantity((int) (rest.getQuantity() - back)), v.store());
        }
    }

    /** Ins Inventar, sonst vor die Füße (ohne Netz, z. B. Rückgabe beim Einlagern). */
    static void giveToPlayer(Ref<EntityStore> ref, Store<EntityStore> store, ItemStack stack) {
        ItemContainer inv = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
        ItemStack rest = inv.addItemStack(stack).getRemainder();
        if (!ItemStack.isEmpty(rest)) {
            ItemUtils.dropItem(ref, rest, store);
        }
    }

    @Nullable
    private TerminalBackend.Entry entryAt(int slot, @Nullable String itemId) {
        if (slot >= 0 && slot < shown.size()) {
            return shown.get(slot);
        }
        if (itemId != null) {
            for (TerminalBackend.Entry e : shown) {
                if (itemId.equals(e.display().getItemId())) {
                    return e;
                }
            }
        }
        return null;
    }

    /** Behälter eines Inventar-Gitters (direkt aus den Komponenten des Spielers, nie aus Fenstern). */
    @Nullable
    static ItemContainer bagContainer(Viewer v, @Nullable String grid) {
        ComponentType<EntityStore, ? extends InventoryComponent> type = componentType(grid);
        if (type == null || v.ref() == null || !v.ref().isValid()) {
            return null;
        }
        InventoryComponent comp = v.store().getComponent(v.ref(), type);
        return comp == null ? null : comp.getInventory();
    }

    @Nullable
    private static ComponentType<EntityStore, ? extends InventoryComponent> componentType(@Nullable String grid) {
        if (grid == null) {
            return null;
        }
        return switch (grid) {
            case STORAGE_GRID -> InventoryComponent.Storage.getComponentType();
            case HOTBAR_GRID -> InventoryComponent.Hotbar.getComponentType();
            case BACKPACK_GRID -> InventoryComponent.Backpack.getComponentType();
            default -> null;
        };
    }

    @Nullable
    private static Integer sectionId(String grid) {
        return switch (grid) {
            case STORAGE_GRID -> InventoryComponent.STORAGE_SECTION_ID;
            case HOTBAR_GRID -> InventoryComponent.HOTBAR_SECTION_ID;
            case BACKPACK_GRID -> InventoryComponent.BACKPACK_SECTION_ID;
            default -> null;
        };
    }

    private static int limit(String grid) {
        return switch (grid) {
            case HOTBAR_GRID -> HOTBAR_LIMIT;
            case BACKPACK_GRID -> BACKPACK_LIMIT;
            default -> STORAGE_LIMIT;
        };
    }

    /**
     * Löst die Quell-Abschnittsnummer eines Zugs auf und lässt nur das eigene Lager, die Schnellleiste und den
     * Rucksack des Spielers zu (keine fremden Fenster, nicht das Netz-Gitter).
     */
    @Nullable
    private static ItemContainer ownContainer(Viewer v, int section) {
        ItemContainer resolved;
        try {
            resolved = InventoryUtils.getSectionById(v.ref(), section, v.store());
        } catch (RuntimeException ex) {
            return null;
        }
        if (resolved == null) {
            return null;
        }
        for (String grid : BAG_GRIDS) {
            if (bagContainer(v, grid) == resolved) {
                return resolved;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Ablage-Behälter

    /**
     * Immer leerer Behälter hinter dem Fenster des Netz-Gitters. Er nimmt über normale Wege nichts an
     * ({@link FilterType#DENY_ALL}); landet trotzdem etwas darin, geht es sofort ins Netz, der Rest zum Spieler.
     */
    public static SimpleItemContainer newDropContainer(int capacity, Supplier<TerminalBackend> backend,
                                                       PlayerRef playerRef) {
        SimpleItemContainer c = new SimpleItemContainer((short) capacity);
        c.setGlobalFilter(FilterType.DENY_ALL);
        boolean[] busy = {false};
        c.registerChangeEvent(event -> {
            if (busy[0]) {
                return;
            }
            busy[0] = true;
            try {
                drain(c, backend.get(), playerRef);
            } finally {
                busy[0] = false;
            }
        });
        return c;
    }

    private static void drain(SimpleItemContainer c, @Nullable TerminalBackend backend, PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        for (short s = 0; s < c.getCapacity(); s++) {
            ItemStack stack = c.getItemStack(s);
            if (ItemStack.isEmpty(stack)) {
                continue;
            }
            c.removeItemStackFromSlot(s, false);
            long inserted = 0;
            if (backend != null) {
                try {
                    inserted = backend.insert(stack);
                } catch (RuntimeException ignored) {
                    inserted = 0;
                }
            }
            if (inserted < stack.getQuantity()) {
                ItemStack rest = stack.withQuantity((int) (stack.getQuantity() - inserted));
                if (ref != null && ref.isValid()) {
                    giveToPlayer(ref, ref.getStore(), rest);
                } else {
                    // Spieler weg: zurück in den Behälter (er bleibt liegen, bis ihn jemand leert)
                    c.setItemStackForSlot(s, rest, false);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Zugriff für Tests/Seiten

    public Layout layout() {
        return layout;
    }

    List<TerminalBackend.Entry> shown() {
        return shown;
    }
}
