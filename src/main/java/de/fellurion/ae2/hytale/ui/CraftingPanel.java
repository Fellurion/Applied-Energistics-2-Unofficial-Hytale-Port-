/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.items.CraftingTermMenu / appeng.client.gui.me.items.CraftingTermScreen).
 */
package de.fellurion.ae2.hytale.ui;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.World;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.crafting.CraftPlanner;
import de.fellurion.ae2.hytale.crafting.CraftTransaction;
import de.fellurion.ae2.hytale.crafting.TerminalRecipes;
import org.bson.BsonDocument;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Das Herstellen im ME-Crafting-Terminal – Hytales Entsprechung zu AE2s 3×3-Feld.
 *
 * <p>Hytale kennt kein Rasterhandwerk: Rezepte sind Zutatenlisten, die an eine Werkbank gebunden sind. Statt Items in
 * ein Raster zu legen, wählt der Spieler ein Rezept (Suche, Liste) und stellt es mit „×1 / ×10 / Stapel / Max“ her.
 * Welche Rezepte angeboten werden, steht in {@link TerminalRecipes}. Wie in AE2 kommen die Zutaten zuerst aus dem
 * ME-Netz (Energie wie beim Entnehmen im Terminal), was dort fehlt aus dem Inventar des Spielers; das Ergebnis geht ins
 * Inventar, was nicht passt zurück ins Netz.
 *
 * <p><b>Keine Dupes, kein Verlust:</b> Erst wird geplant ({@link CraftPlanner}), dann simuliert
 * ({@link TerminalBackend#canExtract}), dann entnommen; liefert ein Schritt weniger als geplant, geht alles
 * Entnommene zurück ({@link CraftTransaction}). Erst wenn alle Zutaten bezahlt sind, entsteht das Ergebnis.
 *
 * <p>Die Anzeige gehört zu {@link TerminalView} (eigene Seite und FTCore-Schale); Dokumente
 * {@code CraftingTerminal.ui} und {@code CraftingTerminalShell.ui}.
 */
public final class CraftingPanel {

    public static final String GRID = "#AE2CraftGrid";
    /** Zeilen der Rezeptliste. */
    public static final int ROWS = 2;
    /** So viele Zutaten zeigt die Zeile des gewählten Rezepts (mehr hat kein Rezept der angebotenen Werkbänke). */
    public static final int MAX_INPUTS = 7;
    /** Obergrenze für „Max“ je Klick. */
    static final int MAX_CRAFTS = 999;

    // Aktionen (Präfix "ae2." wie in TerminalView)
    public static final String A_SEARCH = "ae2.craft.search";
    public static final String A_FILTER = "ae2.craft.filter";
    public static final String A_PREV = "ae2.craft.prev";
    public static final String A_NEXT = "ae2.craft.next";
    public static final String A_SELECT = "ae2.craft.select";
    public static final String A_X1 = "ae2.craft.x1";
    public static final String A_X10 = "ae2.craft.x10";
    public static final String A_STACK = "ae2.craft.stack";
    public static final String A_MAX = "ae2.craft.max";
    /** Ereignisfeld mit dem Text der Rezeptsuche. */
    public static final String F_SEARCH = "@AE2CraftSearch";

    private static final Set<String> MAKE = Set.of(A_X1, A_X10, A_STACK, A_MAX);

    private final int columns;
    private String search = "";
    private boolean onlyCraftable;
    private int page;
    @Nullable
    private String selected;
    /** Meldung der letzten Aktion; null = Zusammenfassung des gewählten Rezepts zeigen. */
    @Nullable
    private String status;
    private boolean statusError;
    private List<TerminalRecipes.Entry> shown = List.of();

    /** @param columns Rezepte je Zeile der Rezeptliste (eigene Seite 9, Schale 10) */
    public CraftingPanel(int columns) {
        this.columns = Math.max(1, columns);
    }

    /** Gehört die Aktion zum Herstellen? */
    public static boolean handles(@Nullable String action) {
        return action != null && action.startsWith("ae2.craft.");
    }

    /** Fasst die Aktion das Netz an (dann vorher Zugriff prüfen)? */
    public static boolean needsAccess(String action) {
        return MAKE.contains(action);
    }

    private int pageSize() {
        return columns * ROWS;
    }

    // ------------------------------------------------------------------ Aufbau

    void build(UICommandBuilder cmd, UIEventBuilder evt, Function<String, EventData> base, String l) {
        cmd.set("#AE2CraftSearch.Value", search);
        cmd.set("#AE2CraftX1.Text", "×1");
        cmd.set("#AE2CraftX10.Text", "×10");
        cmd.set("#AE2CraftXStack.Text", TerminalTexts.tr(l, "ae2ui.craftterm.stack"));
        cmd.set("#AE2CraftXMax.Text", TerminalTexts.tr(l, "ae2ui.craftterm.max"));
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#AE2CraftSearch",
                base.apply(A_SEARCH).put(F_SEARCH, "#AE2CraftSearch.Value"), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2CraftFilter", base.apply(A_FILTER), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2CraftPrev", base.apply(A_PREV), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2CraftNext", base.apply(A_NEXT), false);
        evt.addEventBinding(CustomUIEventBindingType.SlotClicking, GRID, base.apply(A_SELECT), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2CraftX1", base.apply(A_X1), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2CraftX10", base.apply(A_X10), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2CraftXStack", base.apply(A_STACK), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#AE2CraftXMax", base.apply(A_MAX), false);
    }

    /**
     * Zeichnet Rezeptliste, gewähltes Rezept und Zustand neu.
     *
     * @param network Einträge des Netzes aus {@link TerminalBackend#entries()} (leer, wenn offline/ohne Zugriff)
     * @param usable  Netz online und Zugriff erlaubt
     */
    void fill(UICommandBuilder cmd, List<TerminalBackend.Entry> network, boolean usable, TerminalView.Viewer v) {
        String l = v.locale();
        TerminalRecipes.Unlocks unlocks = TerminalRecipes.unlocks(v.player(), world(v));
        Supply supply = supply(network, v);

        // Rezepte: suchen, nach Namen ordnen, auf Wunsch nur was sich gerade herstellen lässt
        List<TerminalRecipes.Entry> all = usable ? TerminalRecipes.visible(unlocks) : List.of();
        String q = search.trim().toLowerCase(Locale.ROOT);
        List<TerminalRecipes.Entry> list = new ArrayList<>();
        Set<String> craftable = new HashSet<>();
        for (TerminalRecipes.Entry e : all) {
            if (!q.isEmpty() && !TerminalView.matches(q, e.outputItem(), TerminalTexts.itemName(l, e.outputItem()))) {
                continue;
            }
            if (onlyCraftable) {
                if (!CraftPlanner.plan(e.ingredients(), supply.stocks(), 1).complete()) {
                    continue;
                }
                craftable.add(e.id());
            }
            list.add(e);
        }
        list.sort(Comparator.comparing((TerminalRecipes.Entry e) ->
                        TerminalTexts.itemName(l, e.outputItem()).toLowerCase(Locale.ROOT))
                .thenComparing(TerminalRecipes.Entry::id));

        int size = pageSize();
        int pages = Math.max(1, (list.size() + size - 1) / size);
        page = Math.max(0, Math.min(page, pages - 1));
        int from = page * size;
        shown = List.copyOf(list.subList(Math.min(from, list.size()), Math.min(from + size, list.size())));

        // Auswahl: bleibt, solange der Spieler das Rezept sehen darf – auch wenn Suche oder Filter es gerade ausblenden
        // (sonst würde „×1“ nach dem letzten möglichen Stück plötzlich ein anderes Rezept herstellen) und auch, wenn
        // das Netz kurz offline ist.
        TerminalRecipes.Entry sel = usable ? TerminalRecipes.resolve(selected, unlocks) : null;
        if (usable && sel == null) {
            selected = null;
        }

        ItemGridSlot[] slots = new ItemGridSlot[size];
        for (int i = 0; i < size; i++) {
            ItemGridSlot slot = new ItemGridSlot();
            if (i < shown.size()) {
                TerminalRecipes.Entry e = shown.get(i);
                boolean can = onlyCraftable ? craftable.contains(e.id())
                        : CraftPlanner.plan(e.ingredients(), supply.stocks(), 1).complete();
                slot.setItemStack(displayStack(e.outputItem(), e.outputQuantity()));
                slot.setDescription(recipeDescription(e, l, true));
                // Nicht herstellbares abblenden
                slot.setItemUncraftable(!can);
                // Anklickbar wie die Felder des Inventars. Die Rezepte sind reine Anzeige: Zieht jemand eines
                // heraus, kommt beim Ablegen kein Abschnitt des Spielers an und TerminalView.drop verwirft es.
                slot.setActivatable(true);
            }
            slots[i] = slot;
        }
        cmd.set(GRID + ".Slots", slots);
        cmd.set("#AE2CraftPageLabel.Text", (page + 1) + " / " + pages);
        cmd.set("#AE2CraftFilter.Text", TerminalTexts.tr(l, onlyCraftable ? "ae2ui.craftterm.filter.craftable"
                : "ae2ui.craftterm.filter.all"));
        cmd.set("#AE2CraftEmpty.Visible", usable && list.isEmpty());
        cmd.set("#AE2CraftEmpty.Text", !usable || !list.isEmpty() ? "" : TerminalTexts.tr(l,
                all.isEmpty() ? "ae2ui.craftterm.noRecipes"
                        : !q.isEmpty() ? "ae2ui.craftterm.noMatch" : "ae2ui.craftterm.noneCraftable"));

        fillSelected(cmd, sel, supply, network, usable, l);
    }

    /** Gewähltes Rezept: Ergebnis, Zutaten mit „vorhanden/benötigt“, Zusammenfassung bzw. letzte Meldung. */
    private void fillSelected(UICommandBuilder cmd, @Nullable TerminalRecipes.Entry sel, Supply supply,
                              List<TerminalBackend.Entry> network, boolean usable, String l) {
        ItemGridSlot out = new ItemGridSlot();
        ItemGridSlot[] inputs = new ItemGridSlot[MAX_INPUTS];
        for (int i = 0; i < MAX_INPUTS; i++) {
            inputs[i] = new ItemGridSlot();
        }
        String summary;
        if (sel == null) {
            for (int i = 0; i < MAX_INPUTS; i++) {
                amount(cmd, i, "", true);
            }
            // Offline/ohne Zugriff erklärt schon das Netz-Gitter darüber, warum nichts geht
            summary = usable ? TerminalTexts.tr(l, "ae2ui.craftterm.choose") : "";
        } else {
            List<CraftPlanner.Ingredient> ingredients = sel.ingredients();
            CraftPlanner.Plan plan = CraftPlanner.plan(ingredients, supply.stocks(), 1);
            int max = CraftPlanner.maxCrafts(ingredients, supply.stocks(), MAX_CRAFTS);
            Set<String> autocraft = autocraftable(network);
            out.setItemStack(displayStack(sel.outputItem(), sel.outputQuantity()));
            out.setDescription(recipeDescription(sel, l, false));
            out.setItemUncraftable(max <= 0);
            for (int i = 0; i < MAX_INPUTS; i++) {
                if (i >= sel.inputs().size()) {
                    amount(cmd, i, "", true);
                    continue;
                }
                TerminalRecipes.Input in = sel.inputs().get(i);
                CraftPlanner.Line line = plan.lines().get(i);
                // Bei „beliebiges Item des Typs“ das Item zeigen, das tatsächlich verbraucht würde
                String item = line.firstUsed() >= 0 ? supply.stocks().get(line.firstUsed()).itemId() : in.displayItem();
                inputs[i].setItemStack(displayStack(item, 1));
                inputs[i].setItemUncraftable(!line.satisfied());
                inputs[i].setDescription(inputDescription(in, line, autocraft.contains(in.displayItem()), l));
                amount(cmd, i, TerminalTexts.formatAmount(line.have()) + "/" + TerminalTexts.formatAmount(line.need()),
                        line.satisfied());
            }
            summary = TerminalTexts.tr(l, max > 0 ? "ae2ui.craftterm.selected" : "ae2ui.craftterm.selectedMissing",
                    "item", TerminalTexts.itemName(l, sel.outputItem()),
                    "amount", Integer.toString(sel.outputQuantity()),
                    "bench", benchName(sel.benchId(), l),
                    "max", Integer.toString(max));
        }
        out.setActivatable(false);
        for (ItemGridSlot s : inputs) {
            s.setActivatable(false);
        }
        cmd.set("#AE2CraftOutput.Slots", new ItemGridSlot[]{out});
        cmd.set("#AE2CraftInputs.Slots", inputs);
        boolean error = status != null && statusError;
        cmd.set("#AE2CraftStatus.Text", error ? "" : status != null ? status : summary);
        cmd.set("#AE2CraftStatus.Visible", !error);
        cmd.set("#AE2CraftError.Text", error ? status : "");
        cmd.set("#AE2CraftError.Visible", error);
    }

    /** „vorhanden/benötigt“ über einer Zutat: weiß, wenn es reicht, sonst rot. */
    private static void amount(UICommandBuilder cmd, int index, String text, boolean enough) {
        cmd.set("#AE2CraftHave" + index + ".Text", enough ? text : "");
        cmd.set("#AE2CraftHave" + index + ".Visible", enough);
        cmd.set("#AE2CraftMiss" + index + ".Text", enough ? "" : text);
        cmd.set("#AE2CraftMiss" + index + ".Visible", !enough);
    }

    /** Beschreibung eines Rezepts: Item-Beschreibung, Werkbank, Zutaten; in der Liste dazu der Bedienhinweis. */
    private static String recipeDescription(TerminalRecipes.Entry e, String l, boolean selectHint) {
        StringBuilder sb = new StringBuilder(TerminalTexts.itemDescription(new ItemStack(e.outputItem(), 1), l));
        StringBuilder extra = new StringBuilder();
        TerminalTexts.line(extra, TerminalTexts.COLOR_INFO, TerminalTexts.tr(l, "ae2ui.craftterm.tooltip.bench",
                "bench", benchName(e.benchId(), l)));
        for (TerminalRecipes.Input in : e.inputs()) {
            TerminalTexts.line(extra, TerminalTexts.COLOR_MUTED, TerminalTexts.tr(l, "ae2ui.craftterm.tooltip.ingredient",
                    "amount", Integer.toString(in.quantity()), "item", inputName(in, l)));
        }
        if (selectHint) {
            TerminalTexts.line(extra, TerminalTexts.COLOR_MUTED, TerminalTexts.tr(l, "ae2ui.craftterm.tooltip.select"));
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        return sb.append(extra).toString();
    }

    private static String inputDescription(TerminalRecipes.Input in, CraftPlanner.Line line, boolean autocraft,
                                           String l) {
        StringBuilder sb = new StringBuilder();
        if (in.resourceTypeId() != null) {
            TerminalTexts.line(sb, TerminalTexts.COLOR_MUTED, inputName(in, l));
        }
        TerminalTexts.line(sb, TerminalTexts.COLOR_INFO, TerminalTexts.tr(l, "ae2ui.craftterm.tooltip.needed",
                "amount", TerminalTexts.grouped(line.need(), l)));
        TerminalTexts.line(sb, TerminalTexts.COLOR_INFO, TerminalTexts.tr(l, "ae2ui.craftterm.tooltip.network",
                "amount", TerminalTexts.grouped(line.network(), l)));
        TerminalTexts.line(sb, TerminalTexts.COLOR_INFO, TerminalTexts.tr(l, "ae2ui.craftterm.tooltip.inventory",
                "amount", TerminalTexts.grouped(line.inventory(), l)));
        if (!line.satisfied()) {
            TerminalTexts.line(sb, TerminalTexts.COLOR_BAD, TerminalTexts.tr(l, "ae2ui.craftterm.tooltip.missing",
                    "amount", TerminalTexts.grouped(line.missing(), l)));
            if (autocraft) {
                // Wie in AE2: fehlende Zutaten, die das Netz herstellen kann, sind eigens markiert
                TerminalTexts.line(sb, TerminalTexts.COLOR_INFO, TerminalTexts.tr(l, "ae2ui.craftterm.tooltip.autocraft"));
            }
        }
        return sb.toString();
    }

    /** Name einer Zutat: das Item, bei einem Ressourcentyp „Beliebig: Typ“. */
    private static String inputName(TerminalRecipes.Input in, String l) {
        if (in.resourceTypeId() == null) {
            return TerminalTexts.itemName(l, in.displayItem());
        }
        return TerminalTexts.tr(l, "ae2ui.craftterm.tooltip.any", "type", in.resourceTypeId().replace('_', ' '));
    }

    private static String benchName(String benchId, String l) {
        String key = "ae2ui.craftterm.bench." + benchId;
        String name = TerminalTexts.tr(l, key);
        return name.equals(key) ? benchId.replace('_', ' ') : name;
    }

    /** Items, die das Netz per Autocrafting herstellen kann. */
    private static Set<String> autocraftable(List<TerminalBackend.Entry> network) {
        Set<String> out = new HashSet<>();
        for (TerminalBackend.Entry e : network) {
            if (e.craftable()) {
                out.add(e.display().getItemId());
            }
        }
        return out;
    }

    private static ItemStack displayStack(String itemId, int quantity) {
        return new ItemStack(itemId, Math.max(1, Math.min(quantity, ItemKeys.maxStack(itemId))));
    }

    @Nullable
    private static World world(TerminalView.Viewer v) {
        try {
            return v.store().getExternalData().getWorld();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Vorräte

    /** Ein Inventarfeld des Spielers. */
    private record SlotRef(ItemContainer container, short slot) {
    }

    /**
     * Vorräte für die Planung.
     *
     * @param network Netz-Einträge; {@link CraftPlanner.Stock#ref()} eines Netz-Vorrats ist der Index hierin
     * @param slots   Inventarfelder; {@link CraftPlanner.Stock#ref()} eines Inventar-Vorrats ist der Index hierin
     */
    private record Supply(CraftPlanner.Stocks stocks, List<TerminalBackend.Entry> network, List<SlotRef> slots) {
    }

    private static Supply supply(List<TerminalBackend.Entry> network, TerminalView.Viewer v) {
        List<CraftPlanner.Stock> list = new ArrayList<>();
        for (int i = 0; i < network.size(); i++) {
            TerminalBackend.Entry e = network.get(i);
            if (e.amount() > 0) {
                list.add(new CraftPlanner.Stock(CraftPlanner.Source.NETWORK, i, e.display().getItemId(),
                        plain(e.display()), e.amount()));
            }
        }
        // Schnellleiste zuletzt: was der Spieler in der Hand hält, wird erst verbraucht, wenn sonst nichts da ist
        List<SlotRef> slots = new ArrayList<>();
        for (String grid : List.of(TerminalView.STORAGE_GRID, TerminalView.BACKPACK_GRID, TerminalView.HOTBAR_GRID)) {
            ItemContainer c = TerminalView.bagContainer(v, grid);
            if (c == null) {
                continue;
            }
            for (short s = 0; s < c.getCapacity(); s++) {
                ItemStack stack = c.getItemStack(s);
                if (ItemStack.isEmpty(stack)) {
                    continue;
                }
                list.add(new CraftPlanner.Stock(CraftPlanner.Source.INVENTORY, slots.size(), stack.getItemId(),
                        plain(stack), stack.getQuantity()));
                slots.add(new SlotRef(c, s));
            }
        }
        return new Supply(CraftPlanner.index(list), network, slots);
    }

    /** Ohne eigene Metadaten (Seelenbund, Zelleninhalt, Pattern …): nur solche Items werden verbraucht. */
    private static boolean plain(ItemStack stack) {
        try {
            BsonDocument meta = ItemKeys.metadataView(stack);
            return meta == null || meta.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ Ereignisse

    TerminalView.Result handle(TerminalView.Event e, TerminalBackend backend, TerminalView.Viewer v) {
        switch (e.action()) {
            case A_SEARCH -> {
                String s = e.craftSearch() == null ? "" : e.craftSearch();
                if (s.equals(search)) {
                    return TerminalView.Result.NONE;
                }
                search = s;
                page = 0;
                status = null;
                selectFirstMatch(backend, v);
                return TerminalView.Result.UPDATE;
            }
            case A_FILTER -> {
                onlyCraftable = !onlyCraftable;
                page = 0;
                status = null;
                return TerminalView.Result.UPDATE;
            }
            case A_PREV -> {
                page--;
                return TerminalView.Result.UPDATE;
            }
            case A_NEXT -> {
                page++;
                return TerminalView.Result.UPDATE;
            }
            case A_SELECT -> {
                if (e.slot() < 0 || e.slot() >= shown.size()) {
                    return TerminalView.Result.NONE;
                }
                selected = shown.get(e.slot()).id();
                status = null;
                return TerminalView.Result.UPDATE;
            }
            case A_X1, A_X10, A_STACK, A_MAX -> {
                craft(e.action(), backend, v);
                return TerminalView.Result.UPDATE;
            }
            default -> {
                return TerminalView.Result.NONE;
            }
        }
    }

    /** Nach dem Tippen in der Suche: den ersten Treffer wählen (dann genügt Suchen + „×1“). */
    private void selectFirstMatch(TerminalBackend backend, TerminalView.Viewer v) {
        String q = search.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || !backend.isOnline() || backend.accessDeniedText(v.locale()) != null) {
            return;
        }
        String l = v.locale();
        TerminalRecipes.Entry best = null;
        String bestName = null;
        for (TerminalRecipes.Entry e : TerminalRecipes.visible(TerminalRecipes.unlocks(v.player(), world(v)))) {
            String name = TerminalTexts.itemName(l, e.outputItem());
            if (!TerminalView.matches(q, e.outputItem(), name)) {
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (best == null || key.compareTo(bestName) < 0 || (key.equals(bestName) && e.id().compareTo(best.id()) < 0)) {
                best = e;
                bestName = key;
            }
        }
        if (best != null) {
            selected = best.id();
        }
    }

    // ------------------------------------------------------------------ Herstellen

    private void fail(String l, String key, Object... params) {
        status = TerminalTexts.tr(l, key, params);
        statusError = true;
    }

    private void craft(String action, TerminalBackend backend, TerminalView.Viewer v) {
        String l = v.locale();
        if (!backend.isOnline() || !v.ref().isValid()) {
            fail(l, "ae2ui.terminal.offline");
            return;
        }
        TerminalRecipes.Entry entry = TerminalRecipes.resolve(selected, TerminalRecipes.unlocks(v.player(), world(v)));
        if (entry == null) {
            fail(l, selected == null ? "ae2ui.craftterm.choose" : "ae2ui.craftterm.locked");
            return;
        }
        int perStack = Math.max(1, ItemKeys.maxStack(entry.outputItem()) / Math.max(1, entry.outputQuantity()));
        int wanted = switch (action) {
            case A_X10 -> 10;
            case A_STACK -> perStack;
            case A_MAX -> MAX_CRAFTS;
            default -> 1;
        };

        // 1. Planen – mit dem Stand von genau jetzt (nicht dem zuletzt gezeichneten)
        Supply supply = supply(backend.entries(), v);
        List<CraftPlanner.Ingredient> ingredients = entry.ingredients();
        int crafts = CraftPlanner.maxCrafts(ingredients, supply.stocks(), wanted);
        if (crafts <= 0) {
            fail(l, "ae2ui.craftterm.missing", "items", missingNames(entry, CraftPlanner.plan(ingredients,
                    supply.stocks(), 1), l));
            return;
        }
        CraftPlanner.Plan plan = CraftPlanner.plan(ingredients, supply.stocks(), crafts);
        if (!plan.complete()) {
            fail(l, "ae2ui.craftterm.failed");
            return;
        }

        // 2. Andere Plugins dürfen wie an der Werkbank ablehnen (Hytales CraftRecipeEvent.Pre)
        if (!allowedByEvent(v, entry, crafts)) {
            fail(l, "ae2ui.craftterm.denied");
            return;
        }

        // 3. Simulieren: Bestand und Energie für alle Netz-Entnahmen zusammen
        List<CraftTransaction.Step> steps = new ArrayList<>();
        Map<String, Long> fromNetwork = new LinkedHashMap<>();
        for (CraftPlanner.Take t : plan.takes()) {
            CraftPlanner.Stock s = supply.stocks().get(t.stock());
            if (s.source() == CraftPlanner.Source.NETWORK) {
                String key = supply.network().get(s.ref()).key();
                fromNetwork.merge(key, t.amount(), Long::sum);
                steps.add(new NetworkStep(backend, v, key, t.amount()));
            } else {
                steps.add(new InventoryStep(v, supply.slots().get(s.ref()), s.itemId(), t.amount()));
            }
        }
        if (!fromNetwork.isEmpty() && !backend.canExtract(fromNetwork)) {
            fail(l, "ae2ui.craftterm.noPower");
            return;
        }

        // 4. Entnehmen; bei einer Lücke geht alles zurück
        if (CraftTransaction.run(steps) != CraftTransaction.Outcome.DONE) {
            fail(l, "ae2ui.craftterm.failed");
            return;
        }

        // 5. Ergebnis: ins Inventar, was nicht passt ins Netz, als Letztes vor die Füße
        if (!outputTakenOver(v, entry, crafts)) {
            for (TerminalRecipes.Output o : entry.outputs()) {
                give(backend, v, o, (long) o.quantity() * crafts);
            }
        }
        status = TerminalTexts.tr(l, crafts < wanted && !A_MAX.equals(action) ? "ae2ui.craftterm.craftedPartial"
                        : "ae2ui.craftterm.crafted",
                "amount", TerminalTexts.grouped((long) entry.outputQuantity() * crafts, l),
                "item", TerminalTexts.itemName(l, entry.outputItem()));
        statusError = false;
    }

    private static String missingNames(TerminalRecipes.Entry entry, CraftPlanner.Plan plan, String l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entry.inputs().size(); i++) {
            CraftPlanner.Line line = plan.lines().get(i);
            if (line.satisfied()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(TerminalTexts.grouped(line.missing(), l)).append("× ").append(inputName(entry.inputs().get(i), l));
        }
        return sb.toString();
    }

    /** {@code CraftRecipeEvent.Pre}: false, wenn ein anderes Plugin die Herstellung abgelehnt hat. */
    private static boolean allowedByEvent(TerminalView.Viewer v, TerminalRecipes.Entry entry, int crafts) {
        try {
            CraftRecipeEvent.Pre pre = new CraftRecipeEvent.Pre(entry.recipe(), crafts);
            v.store().invoke(v.ref(), pre);
            return !pre.isCancelled();
        } catch (RuntimeException e) {
            return true; // Ereignis nicht zustellbar – wie ohne Zuhörer
        }
    }

    /**
     * {@code CraftRecipeEvent.Post} nach dem Bezahlen der Zutaten: Wer es abbricht, übernimmt wie an Hytales Werkbank
     * die Ausgabe selbst (dann gibt das Terminal nichts aus).
     */
    private static boolean outputTakenOver(TerminalView.Viewer v, TerminalRecipes.Entry entry, int crafts) {
        try {
            CraftRecipeEvent.Post post = new CraftRecipeEvent.Post(entry.recipe(), crafts);
            v.store().invoke(v.ref(), post);
            return post.isCancelled();
        } catch (RuntimeException e) {
            return false; // die Zutaten sind bezahlt: Ergebnis auf jeden Fall ausgeben
        }
    }

    /** Gibt {@code amount} Stück eines Ergebnisses in ganzen Stapeln aus. */
    private static void give(TerminalBackend backend, TerminalView.Viewer v, TerminalRecipes.Output o, long amount) {
        int max = ItemKeys.maxStack(o.itemId());
        long left = amount;
        while (left > 0) {
            int n = (int) Math.min(left, max);
            ItemStack stack = o.metadata() == null ? new ItemStack(o.itemId(), n)
                    : new ItemStack(o.itemId(), n, o.metadata());
            try {
                TerminalView.giveToInventory(backend, v, stack);
            } catch (RuntimeException e) {
                // Inventar nicht erreichbar: vor die Füße statt verlieren
                ItemUtils.dropItem(v.ref(), stack, v.store());
            }
            left -= n;
        }
    }

    /** Legt Items in ganzen Stapeln ins Inventar des Spielers, sonst vor seine Füße (Rückabwicklung). */
    private static void returnToPlayer(TerminalView.Viewer v, ItemStack stack) {
        int max = TerminalTexts.maxStack(stack);
        int left = stack.getQuantity();
        while (left > 0) {
            int n = Math.min(left, max);
            TerminalView.giveToPlayer(v.ref(), v.store(), stack.withQuantity(n));
            left -= n;
        }
    }

    /** Entnahme aus dem ME-Netz (kostet Energie wie im Terminal). */
    private static final class NetworkStep implements CraftTransaction.Step {
        private final TerminalBackend backend;
        private final TerminalView.Viewer viewer;
        private final String key;
        private final long wanted;
        private final List<ItemStack> taken = new ArrayList<>();

        NetworkStep(TerminalBackend backend, TerminalView.Viewer viewer, String key, long wanted) {
            this.backend = backend;
            this.viewer = viewer;
            this.key = key;
            this.wanted = wanted;
        }

        @Override
        public long wanted() {
            return wanted;
        }

        @Override
        public long simulate() {
            return backend.canExtract(Map.of(key, wanted)) ? wanted : 0;
        }

        @Override
        public long take() {
            long left = wanted;
            while (left > 0) {
                int n = (int) Math.min(left, Integer.MAX_VALUE);
                ItemStack got = backend.extract(key, n);
                if (ItemStack.isEmpty(got)) {
                    break;
                }
                taken.add(got);
                left -= got.getQuantity();
                if (got.getQuantity() < n) {
                    break;
                }
            }
            return wanted - left;
        }

        @Override
        public void giveBack() {
            for (ItemStack stack : taken) {
                long back;
                try {
                    back = backend.insert(stack);
                } catch (RuntimeException e) {
                    back = 0;
                }
                if (back < stack.getQuantity()) {
                    returnToPlayer(viewer, stack.withQuantity((int) (stack.getQuantity() - back)));
                }
            }
            taken.clear();
        }
    }

    /** Entnahme aus einem Inventarfeld des Spielers. */
    private static final class InventoryStep implements CraftTransaction.Step {
        private final TerminalView.Viewer viewer;
        private final SlotRef ref;
        private final String itemId;
        private final long wanted;
        @Nullable
        private ItemStack taken;

        InventoryStep(TerminalView.Viewer viewer, SlotRef ref, String itemId, long wanted) {
            this.viewer = viewer;
            this.ref = ref;
            this.itemId = itemId;
            this.wanted = wanted;
        }

        @Override
        public long wanted() {
            return wanted;
        }

        @Override
        public long simulate() {
            ItemStack stack = ref.container().getItemStack(ref.slot());
            return ItemStack.isEmpty(stack) || !itemId.equals(stack.getItemId()) || !plain(stack) ? 0
                    : stack.getQuantity();
        }

        @Override
        public long take() {
            ItemStack stack = ref.container().getItemStack(ref.slot());
            if (simulate() < wanted) {
                return 0;
            }
            ItemStackSlotTransaction tx = ref.container().removeItemStackFromSlot(ref.slot(), (int) wanted, true, false);
            if (!tx.succeeded()) {
                return 0;
            }
            taken = ItemStack.isEmpty(tx.getOutput()) ? stack.withQuantity((int) wanted) : tx.getOutput();
            return taken.getQuantity();
        }

        @Override
        public void giveBack() {
            if (ItemStack.isEmpty(taken)) {
                return;
            }
            ItemStack rest = ref.container().addItemStackToSlot(ref.slot(), taken).getRemainder();
            taken = null;
            if (!ItemStack.isEmpty(rest)) {
                returnToPlayer(viewer, rest);
            }
        }
    }
}
