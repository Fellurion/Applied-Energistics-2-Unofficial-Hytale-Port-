/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.implementations.PatternAccessTermMenu / appeng.client.gui.me.patternaccess.PatternAccessTermScreen).
 */
package de.fellurion.ae2.hytale.crafting;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.crafting.IPatternDetails;
import de.fellurion.ae2.core.crafting.PatternDetailsHelper;
import de.fellurion.ae2.core.crafting.ProcessingPattern;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.CraftingDevices;
import de.fellurion.ae2.hytale.lang.AE2Language;
import de.fellurion.ae2.hytale.security.AE2Security;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pattern-Zugriffsterminal: alle Pattern Provider des ME-Netzes mit ihren Pattern-Feldern, aus der Ferne bedienbar.
 *
 * <p><b>Bedienung:</b> Klick auf ein Pattern in einem Provider nimmt es ins Inventar. Rechts stehen die kodierten
 * Patterns aus dem Inventar des Spielers; ein Klick wählt eines aus (ohne Auswahl gilt ein Pattern in der Hand).
 * Das gewählte Pattern legt ein Klick auf ein leeres Feld genau dort ab, „Einlegen“ ins erste freie Feld des
 * Providers; Rechtsklick auf ein Pattern im Inventar legt es in den ersten sichtbaren Provider mit freiem Feld
 * (AE2: Umschalt-Klick). Die Suche filtert wie in AE2 nach dem Namen des Providers und nach den Patterns
 * ({@link PatternSearch}); Patterns, die nicht passen, sind abgeblendet.
 *
 * <p><b>Regeln:</b> Eingelegt werden nur kodierte Patterns, je Feld eines. Jede Aktion prüft den Zugriff auf das Netz
 * neu und rechnet auf dem Server: Ein Pattern liegt zu jedem Zeitpunkt an genau einer Stelle, und was sich nicht
 * ablegen lässt, geht dorthin zurück, wo es herkam. Abweichung von AE2: Auch Provider ohne Strom/Kanal stehen in der
 * Liste (als „Offline“); das Terminal selbst muss online sein.
 */
public final class PatternAccessPage extends InteractiveCustomUIPage<PatternAccessPage.Payload>
        implements CraftingAccess.Refreshable {

    private static final String PAGE = "Pages/AE2/PatternAccess.ui";
    private static final String ROW = "Pages/AE2/PatternProviderRow.ui";
    /** Felder des Gitters mit den Patterns aus dem Inventar (6 × 6). */
    private static final int INVENTORY_SLOTS = 36;
    private static final int BUTTON_RIGHT = 3;

    /** Ein Pattern Provider des Netzes. */
    private record Provider(int x, int y, int z, String itemId, boolean online, ItemContainer container) {

        boolean at(Provider o) {
            return x == o.x && y == o.y && z == o.z;
        }
    }

    /** Ein kodiertes Pattern im Inventar des Spielers. */
    private record Held(ItemContainer container, short slot, AEItemKey key) {
    }

    private final WorldNetworks nets;
    private final int x, y, z;
    private String search = "";
    /** Vom Spieler rechts gewähltes Pattern; null = das Pattern in der Hand, falls er eines hält. */
    @Nullable
    private AEItemKey selected;
    @Nullable
    private String status;
    private boolean statusError;
    /** Provider in der Reihenfolge der gezeichneten Zeilen (Ereignisse nennen den Index). */
    private List<Provider> rows = List.of();
    private List<Held> inventory = List.of();
    private long fingerprint = Long.MIN_VALUE;
    private int ticks;
    private boolean open = true;

    PatternAccessPage(PlayerRef playerRef, WorldNetworks nets, int x, int y, int z) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Payload.CODEC);
        this.nets = nets;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    private String lang() {
        return AE2Language.of(playerRef);
    }

    // ------------------------------------------------------------------ Aufbau

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        String l = lang();
        cmd.append(PAGE);
        cmd.set("#PageTitle.Text", TerminalTexts.itemName(l, "AE2_Pattern_Access_Terminal"));
        cmd.set("#Hint.Text", TerminalTexts.tr(l, "ae2craft.access.hint"));
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput",
                EventData.of("@Search", "#SearchInput.Value"), false);
        evt.addEventBinding(CustomUIEventBindingType.SlotClicking, "#InvPatterns", EventData.of("Action", "Pick"), false);
        CraftingAccess.track(this);
        rows = List.of();
        fill(cmd, evt, store, ref, true);
    }

    /**
     * Zeichnet Provider, Inventar-Patterns und Zustand.
     *
     * @param rebuild true: Zeilen in jedem Fall neu anhängen (erster Aufbau)
     */
    private void fill(UICommandBuilder cmd, UIEventBuilder evt, Store<EntityStore> store, Ref<EntityStore> ref,
                      boolean rebuild) {
        String l = lang();
        String q = PatternSearch.normalize(search);
        boolean online = online();
        List<Provider> all = online ? providers() : List.of();
        List<Provider> visible = new ArrayList<>();
        int patterns = 0;
        for (Provider p : all) {
            List<String> texts = patternTexts(p, l);
            patterns += (int) texts.stream().filter(t -> !t.isEmpty()).count();
            if (PatternSearch.matchesProvider(q, providerText(p, l), texts)) {
                visible.add(p);
            }
        }

        // Zeilen nur neu anhängen, wenn sich die Liste geändert hat – sonst spränge die Liste beim Blättern nach oben
        boolean sameRows = !rebuild && visible.size() == rows.size();
        for (int i = 0; sameRows && i < visible.size(); i++) {
            sameRows = visible.get(i).at(rows.get(i));
        }
        if (!sameRows) {
            cmd.clear("#Providers");
        }
        rows = List.copyOf(visible);
        for (int i = 0; i < rows.size(); i++) {
            Provider p = rows.get(i);
            String row = "#Providers[" + i + "]";
            if (!sameRows) {
                cmd.append("#Providers", ROW);
                evt.addEventBinding(CustomUIEventBindingType.SlotClicking, row + " #Patterns",
                        new EventData().append("Action", "Slot").append("Provider", Integer.toString(i)), false);
                evt.addEventBinding(CustomUIEventBindingType.Activating, row + " #Insert",
                        new EventData().append("Action", "Insert").append("Provider", Integer.toString(i)), false);
            }
            cmd.set(row + " #Icon.ItemId", p.itemId());
            cmd.set(row + " #Name.Text", TerminalTexts.itemName(l, p.itemId()));
            cmd.set(row + " #Info.Text", position(p, l));
            cmd.set(row + " #Online.Text", p.online() ? TerminalTexts.tr(l, "ae2craft.access.online") : "");
            cmd.set(row + " #Online.Visible", p.online());
            cmd.set(row + " #Offline.Text", p.online() ? "" : TerminalTexts.tr(l, "ae2craft.access.offline"));
            cmd.set(row + " #Offline.Visible", !p.online());
            cmd.set(row + " #Insert.Text", TerminalTexts.tr(l, "ae2craft.access.insert"));
            // Passt der Name des Providers, gelten alle seine Patterns als Treffer
            boolean byName = q.isEmpty() || providerText(p, l).toLowerCase(Locale.ROOT).contains(q);
            int cap = p.container().getCapacity();
            ItemGridSlot[] slots = new ItemGridSlot[cap];
            for (short s = 0; s < cap; s++) {
                slots[s] = patternSlot(p.container().getItemStack(s), l, byName ? "" : q);
            }
            cmd.set(row + " #Patterns.Slots", slots);
        }

        cmd.set("#Summary.Text", !online ? "" : TerminalTexts.tr(l, "ae2craft.access.summary",
                "providers", Integer.toString(all.size()), "patterns", Integer.toString(patterns)));
        boolean empty = rows.isEmpty();
        cmd.set("#Empty.Visible", empty);
        cmd.set("#Empty.Text", !empty ? "" : TerminalTexts.tr(l, !online ? "ae2craft.access.networkOffline"
                : all.isEmpty() ? "ae2craft.access.noProviders" : "ae2craft.access.noMatch"));

        fillInventory(cmd, store, ref, l);
        fingerprint = fingerprint(all, store, ref);
    }

    /** Rechte Spalte: kodierte Patterns im Inventar, Auswahl und letzte Meldung. */
    private void fillInventory(UICommandBuilder cmd, Store<EntityStore> store, Ref<EntityStore> ref, String l) {
        inventory = held(store, ref);
        cmd.set("#InvHeader.Text", TerminalTexts.tr(l, "ae2craft.access.inventory", "count",
                Integer.toString(inventory.size())));
        ItemGridSlot[] slots = new ItemGridSlot[INVENTORY_SLOTS];
        for (int i = 0; i < INVENTORY_SLOTS; i++) {
            slots[i] = i < inventory.size() ? patternSlot(ItemKeys.toStack(inventory.get(i).key(), 1), l, "")
                    : emptySlot();
        }
        cmd.set("#InvPatterns.Slots", slots);

        AEItemKey chosen = selection(store, ref);
        ItemGridSlot icon = chosen == null ? emptySlot() : patternSlot(ItemKeys.toStack(chosen, 1), l, "");
        icon.setActivatable(false); // reine Anzeige
        cmd.set("#SelectedIcon.Slots", new ItemGridSlot[]{icon});
        String text;
        if (chosen == null) {
            text = TerminalTexts.tr(l, inventory.isEmpty() ? "ae2craft.access.noneInInventory"
                    : "ae2craft.access.nothingSelected");
        } else {
            text = TerminalTexts.tr(l, chosen.equals(selected) ? "ae2craft.access.selected" : "ae2craft.access.selectedHeld",
                    "pattern", patternName(chosen, l));
        }
        cmd.set("#SelectedText.Text", text);
        boolean error = status != null && statusError;
        cmd.set("#Status.Text", status == null || error ? "" : status);
        cmd.set("#Status.Visible", !error);
        cmd.set("#StatusError.Text", error ? status : "");
        cmd.set("#StatusError.Visible", error);
    }

    // ------------------------------------------------------------------ Netz

    private boolean online() {
        return CraftingAccess.grid(nets, x, y, z) != null && CraftingAccess.active(nets, x, y, z);
    }

    /** Alle Pattern Provider im Netz des Terminals, nach Name und Position geordnet. */
    private List<Provider> providers() {
        Grid grid = CraftingAccess.grid(nets, x, y, z);
        List<Provider> out = new ArrayList<>();
        if (grid == null) {
            return out;
        }
        for (WorldNetworks.Node n : nets.nodes()) {
            if (!(n.device() instanceof CraftingDevices.PatternProvider pp) || n.gridNode() == null
                    || n.gridNode().getGrid() != grid) {
                continue;
            }
            if (!pp.logic().isShownInAccessTerminal()) {
                continue; // AE2: Einstellung „im Pattern-Zugriffsterminal anzeigen“ des Providers
            }
            ItemContainer c = BlockAccess.container(nets.world(), n.x(), n.y(), n.z());
            if (c == null || c.getCapacity() <= 0) {
                continue;
            }
            String itemId = n.item() != null ? n.item().id() : "AE2_Pattern_Provider";
            out.add(new Provider(n.x(), n.y(), n.z(), itemId, n.gridNode().isActive(), c));
        }
        out.sort(Comparator.comparing(Provider::itemId).thenComparingInt(Provider::x).thenComparingInt(Provider::y)
                .thenComparingInt(Provider::z));
        return out;
    }

    /** Der Provider der Zeile {@code index}, wenn es ihn im Netz des Terminals noch gibt. */
    @Nullable
    private Provider provider(int index) {
        if (index < 0 || index >= rows.size() || !online()) {
            return null;
        }
        Provider row = rows.get(index);
        for (Provider p : providers()) {
            if (p.at(row)) {
                return p;
            }
        }
        return null;
    }

    private static String position(Provider p, String l) {
        return TerminalTexts.tr(l, "ae2craft.access.position", "x", Integer.toString(p.x()),
                "y", Integer.toString(p.y()), "z", Integer.toString(p.z()));
    }

    private static String providerText(Provider p, String l) {
        return TerminalTexts.itemName(l, p.itemId()) + " " + position(p, l);
    }

    private static List<String> patternTexts(Provider p, String l) {
        List<String> out = new ArrayList<>();
        for (short s = 0; s < p.container().getCapacity(); s++) {
            out.add(PatternSearch.text(decode(p.container().getItemStack(s)), id -> TerminalTexts.itemName(l, id)));
        }
        return out;
    }

    @Nullable
    private static IPatternDetails decode(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack) || !PatternDetailsHelper.isEncodedPattern(stack.getItemId())) {
            return null;
        }
        try {
            return PatternDetailsHelper.decodePattern(ItemKeys.of(stack));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Anzeige eines Patterns

    /**
     * Leeres Feld. Wie im Inventar sind alle Felder anklickbar ({@code IsActivatable}); die Seite bindet kein
     * Ablegen, ein herausgezogenes Symbol bewirkt also nichts – bewegt wird nur per Klick, gerechnet vom Server.
     */
    private static ItemGridSlot emptySlot() {
        ItemGridSlot slot = new ItemGridSlot();
        slot.setActivatable(true);
        return slot;
    }

    /**
     * Feld für ein Pattern: wie in AE2 zeigt es, was das Pattern herstellt; Art, Ergebnisse und Zutaten stehen in
     * der Beschreibung. {@code query} blendet Patterns ab, die nicht zur Suche passen.
     */
    private static ItemGridSlot patternSlot(@Nullable ItemStack stack, String l, String query) {
        ItemGridSlot slot = emptySlot();
        if (ItemStack.isEmpty(stack)) {
            return slot;
        }
        IPatternDetails details = decode(stack);
        GenericStack out = details == null ? null : details.getPrimaryOutput();
        if (out != null && out.what() instanceof AEItemKey key && Item.getAssetMap().getAsset(key.itemId()) != null) {
            int shownAmount = (int) Math.max(1, Math.min(out.amount(), ItemKeys.maxStack(key.itemId())));
            slot.setItemStack(new ItemStack(key.itemId(), shownAmount));
            slot.setDescription(description(stack, details, l));
            slot.setItemUncraftable(!PatternSearch.matchesPattern(query,
                    PatternSearch.text(details, id -> TerminalTexts.itemName(l, id))));
        } else {
            // Kein lesbares Pattern: das Item selbst zeigen (ohne Metadaten, die verträgt der Client hier nicht)
            slot.setItemStack(TerminalTexts.displayStack(stack, 1));
            slot.setDescription(TerminalTexts.tr(l, "ae2craft.access.pattern.invalid"));
            slot.setItemUncraftable(!query.isEmpty());
        }
        return slot;
    }

    private static String description(ItemStack stack, IPatternDetails details, String l) {
        StringBuilder sb = new StringBuilder();
        sb.append(TerminalTexts.tr(l, ProcessingPattern.ITEM_ID.equals(stack.getItemId())
                ? "ae2craft.access.pattern.processing" : "ae2craft.access.pattern.crafting"));
        sb.append('\n').append(TerminalTexts.tr(l, "ae2craft.access.pattern.outputs", "items",
                list(details.getOutputs(), l)));
        List<GenericStack> inputs = new ArrayList<>();
        for (IPatternDetails.IInput in : details.getInputs()) {
            GenericStack[] possible = in.getPossibleInputs();
            if (possible != null && possible.length > 0 && possible[0] != null) {
                inputs.add(new GenericStack(possible[0].what(), possible[0].amount() * Math.max(1, in.getMultiplier())));
            }
        }
        sb.append('\n').append(TerminalTexts.tr(l, "ae2craft.access.pattern.inputs", "items", list(inputs, l)));
        return sb.toString();
    }

    private static String list(List<GenericStack> stacks, String l) {
        StringBuilder sb = new StringBuilder();
        for (GenericStack gs : stacks) {
            if (gs == null || !(gs.what() instanceof AEItemKey key)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(TerminalTexts.formatAmount(gs.amount())).append("× ").append(TerminalTexts.itemName(l, key.itemId()));
        }
        return sb.toString();
    }

    /** Kurzname eines Patterns: was es herstellt. */
    private static String patternName(AEItemKey pattern, String l) {
        IPatternDetails details = PatternDetailsHelper.decodePattern(pattern);
        GenericStack out = details == null ? null : details.getPrimaryOutput();
        String name = out != null && out.what() instanceof AEItemKey key ? TerminalTexts.itemName(l, key.itemId())
                : TerminalTexts.itemName(l, pattern.itemId());
        return out == null ? name : TerminalTexts.formatAmount(out.amount()) + "× " + name;
    }

    // ------------------------------------------------------------------ Inventar des Spielers

    private static List<ItemContainer> bags(Store<EntityStore> store, Ref<EntityStore> ref) {
        List<ItemContainer> out = new ArrayList<>();
        InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Storage storage = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Backpack backpack = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());
        for (InventoryComponent c : new InventoryComponent[]{hotbar, storage, backpack}) {
            if (c != null && c.getInventory() != null) {
                out.add(c.getInventory());
            }
        }
        return out;
    }

    /** Alle kodierten Patterns im Inventar (Schnellleiste, Lager, Rucksack). */
    private static List<Held> held(Store<EntityStore> store, Ref<EntityStore> ref) {
        List<Held> out = new ArrayList<>();
        if (ref == null || !ref.isValid()) {
            return out;
        }
        for (ItemContainer c : bags(store, ref)) {
            for (short s = 0; s < c.getCapacity(); s++) {
                ItemStack stack = c.getItemStack(s);
                if (ItemStack.isEmpty(stack) || !PatternDetailsHelper.isEncodedPattern(stack.getItemId())) {
                    continue;
                }
                AEItemKey key = ItemKeys.of(stack);
                if (key != null) {
                    out.add(new Held(c, s, key));
                }
            }
        }
        return out;
    }

    /** Das Pattern, das eingelegt würde: die Auswahl, sonst ein Pattern in der Hand; null = keines. */
    @Nullable
    private AEItemKey selection(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (selected != null) {
            for (Held h : inventory) {
                if (h.key().equals(selected)) {
                    return selected;
                }
            }
            selected = null; // nicht mehr im Inventar
        }
        ItemStack inHand = InventoryComponent.getItemInHand(store, ref);
        if (!ItemStack.isEmpty(inHand) && PatternDetailsHelper.isEncodedPattern(inHand.getItemId())) {
            return ItemKeys.of(inHand);
        }
        return null;
    }

    // ------------------------------------------------------------------ Ereignisse

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Payload p) {
        if (!AE2Security.checkUse(nets, x, y, z, playerRef)) {
            close(); // Zugriff entzogen, während die Seite offen war
            return;
        }
        String l = lang();
        if (p.search != null && p.action == null) {
            if (p.search.equals(search)) {
                return;
            }
            search = p.search;
        } else if (p.action != null) {
            status = null;
            statusError = false;
            inventory = held(store, ref);
            int slot = toInt(p.slot, -1);
            int row = parse(p.provider);
            switch (p.action) {
                case "Pick" -> pick(store, ref, slot, toInt(p.button, 0) == BUTTON_RIGHT, l);
                case "Slot" -> slotClicked(store, ref, row, slot, l);
                case "Insert" -> insert(store, ref, row, -1, l);
                default -> {
                }
            }
        }
        refresh(store, ref);
    }

    private void fail(String l, String key, Object... params) {
        status = TerminalTexts.tr(l, key, params);
        statusError = true;
    }

    private void ok(String l, String key, Object... params) {
        status = TerminalTexts.tr(l, key, params);
        statusError = false;
    }

    /** Klick auf ein Pattern im Inventar: auswählen; Rechtsklick legt es in den ersten Provider mit freiem Feld. */
    private void pick(Store<EntityStore> store, Ref<EntityStore> ref, int index, boolean quick, String l) {
        if (index < 0 || index >= inventory.size()) {
            return;
        }
        AEItemKey key = inventory.get(index).key();
        if (!quick) {
            selected = key.equals(selected) ? null : key;
            return;
        }
        selected = key;
        if (!online()) {
            fail(l, "ae2craft.access.networkOffline");
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Provider p = provider(i);
            if (p != null && freeSlot(p) >= 0) {
                insert(store, ref, i, -1, l);
                return;
            }
        }
        fail(l, "ae2craft.access.noFreeSlot");
    }

    /** Klick auf ein Feld eines Providers: Pattern herausnehmen bzw. das gewählte in das leere Feld legen. */
    private void slotClicked(Store<EntityStore> store, Ref<EntityStore> ref, int row, int slot, String l) {
        Provider p = provider(row);
        if (p == null) {
            fail(l, online() ? "ae2craft.access.gone" : "ae2craft.access.networkOffline");
            return;
        }
        if (slot < 0 || slot >= p.container().getCapacity()) {
            return;
        }
        if (ItemStack.isEmpty(p.container().getItemStack((short) slot))) {
            insert(store, ref, row, slot, l);
        } else {
            take(store, ref, p, (short) slot, l);
        }
    }

    private static int freeSlot(Provider p) {
        for (short s = 0; s < p.container().getCapacity(); s++) {
            if (ItemStack.isEmpty(p.container().getItemStack(s))) {
                return s;
            }
        }
        return -1;
    }

    /** Nimmt das Pattern eines Feldes ins Inventar; passt es nicht hinein, bleibt es im Provider. */
    private void take(Store<EntityStore> store, Ref<EntityStore> ref, Provider p, short slot, String l) {
        ItemStack stack = p.container().getItemStack(slot);
        if (ItemStack.isEmpty(stack)) {
            return;
        }
        ItemContainer inv = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
        if (!inv.canAddItemStack(stack)) {
            fail(l, "ae2craft.access.inventoryFull");
            return;
        }
        ItemStackSlotTransaction tx = p.container().removeItemStackFromSlot(slot, stack.getQuantity(), true, false);
        if (!tx.succeeded()) {
            fail(l, "ae2craft.access.failed");
            return;
        }
        ItemStack taken = ItemStack.isEmpty(tx.getOutput()) ? stack : tx.getOutput();
        ItemStack rest = inv.addItemStack(taken).getRemainder();
        if (!ItemStack.isEmpty(rest)) {
            // Inventar hat sich doch als voll erwiesen: zurück ins Feld, notfalls vor die Füße
            ItemStack left = p.container().addItemStackToSlot(slot, rest, false, false).getRemainder();
            if (!ItemStack.isEmpty(left)) {
                ItemUtils.dropItem(ref, left, store);
            }
            fail(l, "ae2craft.access.inventoryFull");
            return;
        }
        ok(l, "ae2craft.access.taken", "pattern", patternName(ItemKeys.of(taken), l));
    }

    /**
     * Legt das gewählte Pattern in den Provider der Zeile {@code row}.
     *
     * @param slot Zielfeld; -1 = erstes freies Feld
     */
    private void insert(Store<EntityStore> store, Ref<EntityStore> ref, int row, int slot, String l) {
        Provider p = provider(row);
        if (p == null) {
            fail(l, online() ? "ae2craft.access.gone" : "ae2craft.access.networkOffline");
            return;
        }
        AEItemKey key = selection(store, ref);
        Held source = null;
        for (Held h : inventory) {
            if (h.key().equals(key)) {
                source = h;
                break;
            }
        }
        if (key == null || source == null) {
            fail(l, inventory.isEmpty() ? "ae2craft.access.noneInInventory" : "ae2craft.access.nothingSelected");
            return;
        }
        // Nur kodierte Patterns (leere Patterns und alles andere gehören nicht in einen Provider)
        if (!PatternDetailsHelper.isEncodedPattern(key.itemId())) {
            fail(l, "ae2craft.access.onlyPatterns");
            return;
        }
        int target = slot >= 0 ? slot : freeSlot(p);
        if (target < 0 || target >= p.container().getCapacity()
                || !ItemStack.isEmpty(p.container().getItemStack((short) target))) {
            fail(l, "ae2craft.access.providerFull");
            return;
        }
        // Erst aus dem Inventar nehmen, dann ablegen, Rest zurück: das Pattern liegt immer an genau einer Stelle
        ItemStack before = source.container().getItemStack(source.slot());
        ItemStackSlotTransaction tx = source.container().removeItemStackFromSlot(source.slot(), 1, true, false);
        if (ItemStack.isEmpty(before) || !tx.succeeded()) {
            fail(l, "ae2craft.access.failed");
            return;
        }
        ItemStack one = ItemStack.isEmpty(tx.getOutput()) ? before.withQuantity(1) : tx.getOutput();
        ItemStackSlotTransaction add = p.container().addItemStackToSlot((short) target, one, true, true);
        ItemStack rest = add.succeeded() ? add.getRemainder() : one;
        if (!ItemStack.isEmpty(rest)) {
            ItemStack left = source.container().addItemStackToSlot(source.slot(), rest, false, false).getRemainder();
            if (!ItemStack.isEmpty(left)) {
                ItemStack dropped = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST)
                        .addItemStack(left).getRemainder();
                if (!ItemStack.isEmpty(dropped)) {
                    ItemUtils.dropItem(ref, dropped, store);
                }
            }
            fail(l, "ae2craft.access.providerFull");
            return;
        }
        ok(l, "ae2craft.access.inserted", "pattern", patternName(key, l), "provider", TerminalTexts.itemName(l, p.itemId()));
    }

    // ------------------------------------------------------------------ Aktualisierung

    private void refresh(Store<EntityStore> store, Ref<EntityStore> ref) {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder evt = new UIEventBuilder();
        fill(cmd, evt, store, ref, false);
        sendUpdate(cmd, evt, false);
    }

    /** Stand von Providern (Position, Online, Feldinhalt) und Inventar-Patterns; ändert er sich, wird neu gezeichnet. */
    private long fingerprint(List<Provider> all, Store<EntityStore> store, Ref<EntityStore> ref) {
        long h = online() ? 17 : 5;
        for (Provider p : all) {
            h = h * 31 + p.x();
            h = h * 31 + p.y();
            h = h * 31 + p.z();
            h = h * 31 + (p.online() ? 1 : 0);
            for (short s = 0; s < p.container().getCapacity(); s++) {
                ItemStack stack = p.container().getItemStack(s);
                h = h * 31 + (ItemStack.isEmpty(stack) ? 0 : stack.hashCode());
            }
        }
        for (Held held : held(store, ref)) {
            h = h * 31 + held.key().hashCode();
            h = h * 31 + held.slot();
        }
        ItemStack inHand = ref != null && ref.isValid() ? InventoryComponent.getItemInHand(store, ref) : null;
        h = h * 31 + (ItemStack.isEmpty(inHand) ? 0 : inHand.hashCode());
        return h;
    }

    @Override
    public WorldNetworks networks() {
        return nets;
    }

    /** Alle 10 Netz-Ticks: neu zeichnen, wenn sich Provider, ihre Patterns oder die Patterns im Inventar geändert haben. */
    @Override
    public boolean refreshTick() {
        if (!open || !playerRef.isValid()) {
            return false;
        }
        if (++ticks % 10 != 0) {
            return true;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = ref.getStore();
        if (store.getExternalData().getWorld() != nets.world()) {
            return false; // Spieler hat die Welt gewechselt
        }
        if (fingerprint(online() ? providers() : List.of(), store, ref) != fingerprint) {
            refresh(store, ref);
        }
        return true;
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        open = false;
        CraftingAccess.untrack(this);
        super.onDismiss(ref, store);
    }

    private static int toInt(@Nullable Double d, int fallback) {
        return d == null || d.isNaN() || d.isInfinite() ? fallback : (int) Math.round(d);
    }

    private static int parse(@Nullable String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ Event-Daten

    public static final class Payload {
        String action;
        String provider;
        String search;
        Double slot;
        Double button;

        /** Feld und Maustaste schickt der Client als Zahl (wie im ME-Terminal). */
        public static final BuilderCodec<Payload> CODEC = BuilderCodec.builder(Payload.class, Payload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .append(new KeyedCodec<>("Provider", Codec.STRING), (o, v) -> o.provider = v, o -> o.provider).add()
                .append(new KeyedCodec<>("@Search", Codec.STRING), (o, v) -> o.search = v, o -> o.search).add()
                .append(new KeyedCodec<>("SlotIndex", Codec.DOUBLE), (o, v) -> o.slot = v, o -> o.slot).add()
                .append(new KeyedCodec<>("PressedMouseButton", Codec.DOUBLE), (o, v) -> o.button = v, o -> o.button).add()
                .build();
    }
}
