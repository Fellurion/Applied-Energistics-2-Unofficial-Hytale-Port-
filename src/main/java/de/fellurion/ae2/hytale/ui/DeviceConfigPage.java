/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.implementations.IOBusMenu / StorageBusMenu / InterfaceMenu / UpgradeableMenu /
 * SetStockAmountMenu / PriorityMenu und die Screens dazu).
 */
package de.fellurion.ae2.hytale.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.automation.ConfigInventory;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.cells.Upgrades;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction.UseContext;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.fluid.FluidContainers;
import de.fellurion.ae2.hytale.fluid.FluidTypes;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Watchers;
import de.fellurion.ae2.hytale.lang.AE2Language;
import de.fellurion.ae2.hytale.security.AE2Security;
import de.fellurion.ae2.hytale.ui.ConfigHost.GhostSlots;
import de.fellurion.ae2.hytale.ui.ConfigHost.ItemSection;
import de.fellurion.ae2.hytale.ui.ConfigHost.NumberField;
import de.fellurion.ae2.hytale.ui.ConfigHost.Setting;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Konfiguration eines AE2-Geräts – Busse, ME-Interface, Maschinen …: jedes Gerät, das {@link ConfigHost} ist. Die
 * Seite kennt keine Gerätearten; sie zeigt die Abschnitte, die das Gerät anbietet, und blendet die übrigen aus.
 *
 * <p><b>Filter / Vorrat:</b> Geisterplätze – sie merken sich nur, welches Item gemeint ist (beim Interface mit
 * Menge), es wird nichts verbraucht. Von den 63 Filterplätzen eines Bus sind wie in AE2 {@code 18 + 9 ·
 * Kapazitätskarten} nutzbar. <b>Upgrade-Karten</b> sind echte Items: Einbauen nimmt eine Karte aus dem Inventar,
 * Herausnehmen legt sie zurück; es passen nur die Karten, die AE2 für das Gerät vorsieht. <b>Einstellungen</b>
 * erscheinen wie in AE2 mit der passenden Karte (Fuzzy-Modus, Signal, „nur herstellen“), dazu je nach Gerät ein
 * Zahlenwert (Schwelle) und die Priorität.
 *
 * <p><b>Zwei Layouts:</b> {@code DeviceConfig.ui} für Geräte mit Filter (oder ganz ohne Geisterplätze) und
 * {@code InterfaceConfig.ui} für Geräte, deren Plätze Mengen tragen ({@link GhostSlots#amounts}) – dort gibt es die
 * Mengenknöpfe und die echten Vorratsplätze ({@link ConfigHost#itemSection}).
 *
 * <p><b>Bedienung</b> (eine eigene Seite hat keinen Mauszeiger mit Item): rechts steht das Inventar; ein Klick wählt
 * ein Item (ohne Auswahl gilt das Item in der Hand), ein Klick auf einen leeren Platz legt es dort ab bzw. baut die
 * Karte ein. Rechtsklick im Inventar fügt direkt hinzu (AE2: Umschalt-Klick). Klick auf einen belegten Filter- oder
 * Kartenplatz leert ihn; beim Interface wählt der Klick den Platz für die Mengenknöpfe, Rechtsklick leert ihn.
 *
 * <p><b>Flüssigkeiten im Filter</b> (AE2: Rechtsklick mit einem gefüllten Behälter setzt dessen Flüssigkeit statt
 * des Items): Ist ein Behälter-Item (Eimer) gewählt, setzt ein Rechtsklick auf einen leeren Filterplatz die
 * Flüssigkeit darin bzw. – bei leerem Behälter – die erste lagerbare; jeder weitere Rechtsklick schaltet den Platz
 * zur nächsten Flüssigkeit weiter ({@link ConfigEdits#fluidForSlot}). So lässt sich jede Flüssigkeit wählen, auch
 * die, für die es im Spiel keinen Behälter gibt.
 *
 * <p><b>Regeln:</b> Jede Aktion prüft den Zugriff auf das Netz neu und rechnet auf dem Server; eine Karte bzw. ein
 * Stapel liegt zu jedem Zeitpunkt an genau einer Stelle. Alles wird sofort im Gerät gespeichert.
 */
public final class DeviceConfigPage extends InteractiveCustomUIPage<DeviceConfigPage.Payload> {

    private static final String PAGE_FILTER = "Pages/AE2/DeviceConfig.ui";
    private static final String PAGE_STOCK = "Pages/AE2/InterfaceConfig.ui";
    /** Felder des Gitters mit dem Inventar des Spielers (6 × 8). */
    private static final int INVENTORY_SLOTS = 48;
    private static final int BUTTON_RIGHT = 3;
    private static final int[] PRIORITY_STEPS = {-100, -10, -1, 1, 10, 100};
    private static final String[] PRIORITY_BUTTONS = {"PrioM100", "PrioM10", "PrioM1", "PrioP1", "PrioP10", "PrioP100"};
    private static final int[] AMOUNT_STEPS = {-64, -10, -1, 1, 10, 64};
    private static final String[] AMOUNT_BUTTONS = {"AmtM64", "AmtM10", "AmtM1", "AmtP1", "AmtP10", "AmtP64"};
    /** Knöpfe des Zahlenfelds: −groß, −mittel, −klein, +groß, +mittel, +klein (wie bei der Priorität). */
    private static final String[] NUMBER_BUTTONS = {"NumM2", "NumM1", "NumM0", "NumP2", "NumP1", "NumP0"};
    /** Zu {@link #NUMBER_BUTTONS}: Index der Schrittweite ({@link NumberField#steps}) und Vorzeichen. */
    private static final int[] NUMBER_STEP = {2, 1, 0, 2, 1, 0};
    private static final int[] NUMBER_SIGN = {-1, -1, -1, 1, 1, 1};
    /** Filtergitter in DeviceConfig.ui: 9 Felder je Zeile zu 50 Punkten, 7 Zeilen; der Rest von #FilterBox. */
    private static final int FILTER_COLUMNS = 9;
    private static final int FILTER_ROWS = 7;
    private static final int SLOT_PITCH = 50;
    private static final int FILTER_BOX_REST = 91;

    /** Das Gerät hinter der Seite mit dem, was es bei jeder Abfrage mitbekommt. */
    private record Host(ConfigHost device, ConfigHost.Context ctx) {

        WorldNetworks.Node node() {
            return ctx.node();
        }

        @Nullable
        GhostSlots slots() {
            return device.ghostSlots(ctx);
        }

        @Nullable
        UpgradeInventory upgrades() {
            return device.upgradeCards();
        }

        List<Setting> settings() {
            List<Setting> all = device.settings(ctx);
            return all.size() > ConfigHost.MAX_SETTINGS ? all.subList(0, ConfigHost.MAX_SETTINGS) : all;
        }
    }

    /** Ein Stapel im Inventar des Spielers. */
    private record Held(ItemContainer container, short slot, AEItemKey key, ItemStack stack) {
    }

    private final WorldNetworks nets;
    private final int x, y, z;
    /** Art des Geräts beim Öffnen; steht dort später etwas anderes, schließt sich die Seite. */
    private final String kind;
    /** true: Layout mit Mengen und Vorratsplätzen (InterfaceConfig.ui); steht beim Öffnen fest. */
    private final boolean stockLayout;
    /** Vom Spieler rechts gewähltes Item; null = das Item in der Hand, falls er eines hält. */
    @Nullable
    private AEItemKey selected;
    /** Vorrats-Layout: Platz, auf den die Mengenknöpfe wirken; -1 = keiner. */
    private int activeSlot = -1;
    @Nullable
    private String status;
    private boolean statusError;
    private List<Held> inventory = List.of();
    private List<Setting> settings = List.of();
    private long fingerprint = Long.MIN_VALUE;
    private boolean open = true;
    private final BooleanSupplier watcher = this::refreshTick;

    private DeviceConfigPage(PlayerRef playerRef, WorldNetworks nets, int x, int y, int z, String kind,
                             boolean stockLayout) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Payload.CODEC);
        this.nets = nets;
        this.x = x;
        this.y = y;
        this.z = z;
        this.kind = kind;
        this.stockLayout = stockLayout;
    }

    /**
     * Öffnet die Konfiguration des benutzten Blocks.
     *
     * @return false, wenn der Block (noch) kein konfigurierbares Gerät ist – etwa im Tick des Platzierens
     */
    public static boolean open(UseContext ctx) {
        WorldNetworks nets = WorldNetworks.of(ctx.world());
        WorldNetworks.Node node = nets.node(ctx.pos().x, ctx.pos().y, ctx.pos().z);
        if (node == null || !(node.device() instanceof ConfigHost device)) {
            return false;
        }
        GhostSlots slots = device.ghostSlots(new ConfigHost.Context(nets, node, AE2Language.of(ctx.playerRef())));
        ctx.player().getPageManager().openCustomPage(ctx.playerEntity(), ctx.entityStore(),
                new DeviceConfigPage(ctx.playerRef(), nets, ctx.pos().x, ctx.pos().y, ctx.pos().z, node.kind(),
                        slots != null && slots.amounts()));
        return true;
    }

    /**
     * Für Blöcke, deren Benutzen etwas anderes öffnet (ihr Slot-Fenster): öffnet die Konfiguration, wenn das Gerät
     * das wünscht ({@link ConfigHost#opensOnCrouch}). Der Aufrufer hat geprüft, dass der Spieler sich duckt.
     *
     * @return true, wenn die Seite geöffnet wurde
     */
    public static boolean openOnCrouch(UseContext ctx) {
        WorldNetworks.Node node = WorldNetworks.of(ctx.world()).node(ctx.pos().x, ctx.pos().y, ctx.pos().z);
        return node != null && node.device() instanceof ConfigHost device && device.opensOnCrouch() && open(ctx);
    }

    private String lang() {
        return AE2Language.of(playerRef);
    }

    private static String tr(String l, String key, Object... params) {
        return TerminalTexts.tr(l, key, params);
    }

    /** Das Gerät, solange es an dieser Stelle noch dasselbe ist. */
    @Nullable
    private Host host() {
        WorldNetworks.Node n = nets.node(x, y, z);
        if (n == null || !kind.equals(n.kind()) || !(n.device() instanceof ConfigHost device)) {
            return null;
        }
        return new Host(device, new ConfigHost.Context(nets, n, lang()));
    }

    /** Tragen die Plätze Mengen? Nur im Vorrats-Layout – das andere hat dafür keine Knöpfe. */
    private boolean stocking(@Nullable GhostSlots slots) {
        return stockLayout && slots != null && slots.amounts();
    }

    /** Nutzbare Geisterplätze, nie mehr als es gibt. */
    private static int usable(GhostSlots slots) {
        return Math.max(0, Math.min(slots.usable(), slots.inventory().size()));
    }

    // ------------------------------------------------------------------ Aufbau

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        String l = lang();
        Host h = host();
        cmd.append(stockLayout ? PAGE_STOCK : PAGE_FILTER);
        cmd.set("#ClearFilter.Text", tr(l, stockLayout ? "ae2bus.filter.clearStock" : "ae2bus.filter.clear"));
        cmd.set("#SettingsHeader.Text", tr(l, "ae2bus.settings.header"));
        cmd.set("#NoSettings.Text", tr(l, "ae2bus.settings.none"));
        cmd.set("#PrioSet.Text", tr(l, "ae2bus.priority.set"));
        cmd.set("#NumSet.Text", tr(l, "ae2bus.priority.set"));
        cmd.set("#InvHeader.Text", tr(l, "ae2bus.inventory.header"));

        evt.addEventBinding(CustomUIEventBindingType.SlotClicking, "#Filter", EventData.of("Action", "Filter"), false);
        evt.addEventBinding(CustomUIEventBindingType.SlotClicking, "#Upgrades", EventData.of("Action", "Card"), false);
        evt.addEventBinding(CustomUIEventBindingType.SlotClicking, "#Inventory", EventData.of("Action", "Pick"), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#ClearFilter", EventData.of("Action", "ClearAll"), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#Action", EventData.of("Action", "Act"), false);
        for (int i = 0; i < ConfigHost.MAX_SETTINGS; i++) {
            evt.addEventBinding(CustomUIEventBindingType.Activating, "#Setting" + i,
                    new EventData().append("Action", "Setting").append("Index", Integer.toString(i)), false);
        }
        for (int i = 0; i < NUMBER_BUTTONS.length; i++) {
            evt.addEventBinding(CustomUIEventBindingType.Activating, "#" + NUMBER_BUTTONS[i],
                    new EventData().append("Action", "Number").append("Index", Integer.toString(i)), false);
        }
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#NumSet",
                new EventData().append("Action", "NumberSet").append("@Value", "#NumValue.Value"), false);
        for (int i = 0; i < PRIORITY_BUTTONS.length; i++) {
            evt.addEventBinding(CustomUIEventBindingType.Activating, "#" + PRIORITY_BUTTONS[i],
                    new EventData().append("Action", "Priority").append("Index", Integer.toString(i)), false);
        }
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#PrioSet",
                new EventData().append("Action", "PrioritySet").append("@Value", "#PrioValue.Value"), false);
        if (stockLayout) {
            evt.addEventBinding(CustomUIEventBindingType.SlotClicking, "#Storage", EventData.of("Action", "Storage"), false);
            for (int i = 0; i < AMOUNT_BUTTONS.length; i++) {
                evt.addEventBinding(CustomUIEventBindingType.Activating, "#" + AMOUNT_BUTTONS[i],
                        new EventData().append("Action", "Amount").append("Index", Integer.toString(i)), false);
            }
        }
        if (h != null) {
            Watchers watchers = h.device().watchers();
            if (watchers != null) {
                watchers.remove(watcher); // bei erneutem Aufbau nicht doppelt anmelden
                watchers.add(watcher);
            }
            if (h.device().hasPriority()) {
                cmd.set("#PrioValue.Value", (double) h.device().priority());
            }
            NumberField number = h.device().numberField(h.ctx());
            if (number != null) {
                cmd.set("#NumValue.Value", (double) number.value());
            }
            GhostSlots slots = h.slots();
            if (!stockLayout && slots != null) {
                shrinkFilter(cmd, slots.inventory().size());
            }
        }
        fill(cmd, store, ref);
    }

    /**
     * Filter-Layout: Hat das Gerät weniger Geisterplätze, als das Gitter Zeilen hat (Level Emitter: einen), rücken
     * die Abschnitte darunter nach oben. Bei den 63 Plätzen eines Bus bleibt alles, wie es in der Datei steht.
     */
    private static void shrinkFilter(UICommandBuilder cmd, int slots) {
        int rows = Math.max(1, (slots + FILTER_COLUMNS - 1) / FILTER_COLUMNS);
        if (rows >= FILTER_ROWS) {
            return;
        }
        Anchor grid = new Anchor();
        grid.setWidth(Value.of(FILTER_COLUMNS * SLOT_PITCH));
        grid.setHeight(Value.of(rows * SLOT_PITCH));
        cmd.setObject("#Filter.Anchor", grid);
        Anchor box = new Anchor();
        box.setHeight(Value.of(rows * SLOT_PITCH + FILTER_BOX_REST));
        cmd.setObject("#FilterBox.Anchor", box);
    }

    /** Schreibt alles, was sich ändern kann. */
    private void fill(UICommandBuilder cmd, Store<EntityStore> store, Ref<EntityStore> ref) {
        String l = lang();
        Host h = host();
        inventory = held(store, ref);
        if (h == null) {
            return; // Gerät weg: die Seite schließt sich beim nächsten Takt bzw. Klick
        }
        ConfigHost device = h.device();
        cmd.set("#PageTitle.Text", title(h, l));
        cmd.set("#Info.Text", info(h, l));

        GhostSlots slots = h.slots();
        boolean stocking = stocking(slots);
        cmd.set("#FilterBox.Visible", slots != null);
        if (slots != null) {
            ConfigInventory config = slots.inventory();
            int visible = usable(slots);
            cmd.set("#FilterHeader.Text", tr(l, slots.headerKey(), "used", Integer.toString(ConfigEdits.used(config, visible)),
                    "slots", Integer.toString(visible)));
            String tip = tr(l, slots.tipKey());
            ItemGridSlot[] filter = new ItemGridSlot[visible];
            for (int i = 0; i < visible; i++) {
                GenericStack gs = config.getStack(i);
                filter[i] = gs != null && gs.what() instanceof AEItemKey key
                        ? shown(key, stocking ? gs.amount() : 1, tip, l)
                        : gs != null && gs.what() instanceof AEFluidKey fluid ? shownFluid(fluid, tip, l) : emptySlot();
            }
            cmd.set("#Filter.Slots", filter);
        }
        ConfigHost.Action action = slots == null ? null : device.action(h.ctx());
        cmd.set("#Action.Visible", action != null);
        cmd.set("#Action.Text", action == null ? "" : action.text());

        UpgradeInventory upgrades = h.upgrades();
        cmd.set("#UpgradeBox.Visible", upgrades != null);
        if (upgrades != null) {
            int installed = 0;
            ItemGridSlot[] cards = new ItemGridSlot[upgrades.size()];
            for (int i = 0; i < cards.length; i++) {
                UpgradeCard card = upgrades.get(i);
                cards[i] = card == null ? emptySlot() : shown(AEItemKey.of(card.itemId), 1, tr(l, "ae2bus.tip.card"), l);
                installed += card == null ? 0 : 1;
            }
            cmd.set("#Upgrades.Slots", cards);
            cmd.set("#UpgradeHeader.Text", tr(l, "ae2bus.upgrades.header", "count", Integer.toString(installed),
                    "max", Integer.toString(upgrades.size())));
            cmd.set("#UpgradeInfo.Text", tr(l, "ae2bus.upgrades.fits", "cards", supportedCards(upgrades.getHostItemId(), l)));
        }

        settings = h.settings();
        for (int i = 0; i < ConfigHost.MAX_SETTINGS; i++) {
            boolean used = i < settings.size();
            cmd.set("#Setting" + i + ".Visible", used);
            cmd.set("#Setting" + i + ".Text", used ? settings.get(i).text() : "");
        }
        // „Weitere Einstellungen erscheinen mit der passenden Karte“ – nur, wenn das Gerät überhaupt Karten nimmt
        cmd.set("#NoSettings.Visible", settings.isEmpty() && upgrades != null);
        cmd.set("#SettingsHeader.Visible", !settings.isEmpty() || upgrades != null);

        NumberField number = device.numberField(h.ctx());
        cmd.set("#NumberBox.Visible", number != null);
        if (number != null) {
            cmd.set("#NumberLabel.Text", tr(l, number.labelKey(), "value", TerminalTexts.grouped(number.value(), l)));
            for (int i = 0; i < NUMBER_BUTTONS.length; i++) {
                cmd.set("#" + NUMBER_BUTTONS[i] + ".Text", (NUMBER_SIGN[i] < 0 ? "-" : "+")
                        + TerminalTexts.formatAmount(step(number, i)));
            }
        }

        cmd.set("#PriorityBox.Visible", device.hasPriority());
        cmd.set("#PriorityLabel.Text", device.hasPriority()
                ? tr(l, "ae2bus.priority.value", "value", Integer.toString(device.priority())) : "");

        if (stockLayout) {
            fillStock(cmd, h, slots, l);
        }
        fillInventory(cmd, store, ref, h, slots, l);
        fingerprint = fingerprint(h, store, ref);
    }

    /** Vorrats-Layout: Menge des gewählten Platzes und die echten Plätze des Geräts. */
    private void fillStock(UICommandBuilder cmd, Host h, @Nullable GhostSlots slots, String l) {
        ConfigInventory config = slots == null ? null : slots.inventory();
        GenericStack active = config != null && activeSlot >= 0 && activeSlot < config.size() ? config.getStack(activeSlot)
                : null;
        if (active == null) {
            activeSlot = -1;
        }
        cmd.set("#AmountLabel.Text", active == null || !(active.what() instanceof AEItemKey key) ? tr(l, "ae2bus.amount.none")
                : tr(l, "ae2bus.amount.slot", "slot", Integer.toString(activeSlot + 1), "amount", Long.toString(active.amount()),
                "item", TerminalTexts.itemName(l, key.itemId())));
        ItemSection section = h.device().itemSection();
        cmd.set("#StorageBox.Visible", section != null);
        if (section == null) {
            return;
        }
        cmd.set("#StorageHeader.Text", tr(l, section.headerKey()));
        ItemContainer storage = section.container();
        int n = storage == null ? 0 : Math.min(section.slots(), storage.getCapacity());
        ItemGridSlot[] grid = new ItemGridSlot[Math.max(0, section.slots())];
        for (int i = 0; i < grid.length; i++) {
            ItemStack stack = i < n ? storage.getItemStack((short) i) : null;
            grid[i] = emptySlot();
            if (!ItemStack.isEmpty(stack)) {
                grid[i].setItemStack(TerminalTexts.displayStack(stack, stack.getQuantity()));
                grid[i].setDescription(withTip(TerminalTexts.slotDescription(stack, l), tr(l, section.tipKey())));
            }
        }
        cmd.set("#Storage.Slots", grid);
    }

    /** Rechte Spalte: Inventar, Auswahl, letzte Meldung, Hinweise. */
    private void fillInventory(UICommandBuilder cmd, Store<EntityStore> store, Ref<EntityStore> ref, Host h,
                               @Nullable GhostSlots slots, String l) {
        ItemGridSlot[] grid = new ItemGridSlot[INVENTORY_SLOTS];
        for (int i = 0; i < INVENTORY_SLOTS; i++) {
            grid[i] = emptySlot();
            if (i < inventory.size()) {
                ItemStack stack = inventory.get(i).stack();
                grid[i].setItemStack(TerminalTexts.displayStack(stack, stack.getQuantity()));
                grid[i].setDescription(TerminalTexts.slotDescription(stack, l));
            }
        }
        cmd.set("#Inventory.Slots", grid);

        AEItemKey chosen = selection(store, ref);
        ItemGridSlot icon = chosen == null ? emptySlot() : shown(chosen, 1, "", l);
        icon.setActivatable(false); // reine Anzeige
        cmd.set("#SelectedIcon.Slots", new ItemGridSlot[]{icon});
        cmd.set("#SelectedText.Text", chosen == null ? tr(l, "ae2bus.selected.none")
                : tr(l, chosen.equals(selected) ? "ae2bus.selected.item" : "ae2bus.selected.held", "item",
                TerminalTexts.itemName(l, chosen.itemId())));

        boolean error = status != null && statusError;
        cmd.set("#Status.Text", status == null || error ? "" : status);
        cmd.set("#Status.Visible", !error);
        cmd.set("#StatusError.Text", error ? status : "");
        cmd.set("#StatusError.Visible", error);

        String hint = slots != null ? tr(l, slots.hintKey())
                : tr(l, h.upgrades() != null ? "ae2bus.hint.cards" : "ae2bus.hint.settings");
        if (fluidFilter(slots)) {
            hint += "\n" + tr(l, "ae2fluid.config.hint");
        }
        String note = h.device().note(h.ctx());
        if (note != null && !note.isEmpty()) {
            hint += "\n" + note;
        }
        cmd.set("#Hint.Text", hint);
    }

    // ------------------------------------------------------------------ Texte

    /** Titel: Schlüssel des Geräts, sonst der Name seines Items (des Kartenfachs bzw. des Blocks). */
    private static String title(Host h, String l) {
        String key = h.device().titleKey();
        if (key != null) {
            return tr(l, key);
        }
        UpgradeInventory upgrades = h.upgrades();
        if (upgrades != null) {
            return TerminalTexts.itemName(l, upgrades.getHostItemId());
        }
        return h.node().item() != null ? TerminalTexts.itemName(l, h.node().item().id()) : h.node().kind();
    }

    private static String info(Host h, String l) {
        String info = h.device().info(h.ctx());
        return info != null ? info : tr(l, h.device().online(h.node()) ? "ae2bus.info.online" : "ae2bus.info.offline");
    }

    /** „Kapazitätskarte ×5, Fuzzy-Karte, …“ – die Karten, die AE2 für dieses Gerät vorsieht. */
    private static String supportedCards(String hostItemId, String l) {
        Map<UpgradeCard, Integer> supported = Upgrades.getSupportedUpgrades(hostItemId);
        StringBuilder sb = new StringBuilder();
        for (UpgradeCard card : UpgradeCard.values()) {
            int max = supported.getOrDefault(card, 0);
            if (max <= 0) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            String name = TerminalTexts.itemName(l, card.itemId);
            sb.append(max > 1 ? tr(l, "ae2bus.upgrades.card", "card", name, "max", Integer.toString(max)) : name);
        }
        return sb.toString();
    }

    /** Schrittweite des Zahlenknopfs {@code button} (ohne Vorzeichen, mindestens 1). */
    private static long step(NumberField number, int button) {
        long[] steps = number.steps();
        int i = Math.min(NUMBER_STEP[button], steps.length - 1);
        return i < 0 ? 1 : Math.max(1, steps[i]);
    }

    // ------------------------------------------------------------------ Anzeige eines Felds

    /** Leeres Feld; wie im Inventar sind alle Felder anklickbar. */
    private static ItemGridSlot emptySlot() {
        ItemGridSlot slot = new ItemGridSlot();
        slot.setActivatable(true);
        return slot;
    }

    /** Feld, das {@code key} zeigt – ohne Metadaten, die verträgt der Client in einer eigenen Seite nicht. */
    private static ItemGridSlot shown(AEItemKey key, long amount, String tip, String l) {
        ItemGridSlot slot = emptySlot();
        if (Item.getAssetMap().getAsset(key.itemId()) == null) {
            return slot; // Item gibt es nicht mehr (Mod entfernt)
        }
        int quantity = (int) Math.max(1, Math.min(amount, ItemKeys.maxStack(key.itemId())));
        ItemStack display;
        try {
            display = TerminalTexts.displayStack(ItemKeys.toStack(key, quantity), quantity);
        } catch (RuntimeException e) {
            display = new ItemStack(key.itemId(), quantity);
        }
        slot.setItemStack(display);
        slot.setDescription(withTip(TerminalTexts.itemDescription(display, l), tip));
        return slot;
    }

    /** Feld, das eine Flüssigkeit zeigt: ihr Anzeige-Item, im Tooltip ihr Name. */
    private static ItemGridSlot shownFluid(AEFluidKey key, String tip, String l) {
        ItemGridSlot slot = emptySlot();
        try {
            slot.setItemStack(TerminalTexts.displayStack(FluidTypes.displayStack(key.fluidId()), 1));
        } catch (RuntimeException e) {
            return slot; // kein Item zum Anzeigen
        }
        StringBuilder sb = new StringBuilder();
        TerminalTexts.line(sb, TerminalTexts.COLOR_INFO, tr(l, "ae2fluid.config.tip.fluid", "fluid",
                FluidTypes.name(l, key.fluidId())));
        slot.setDescription(withTip(sb.toString(), tip));
        return slot;
    }

    /** Nimmt dieser Filter Flüssigkeiten an? (Vorratsplätze des Interface nicht: es lagert hier nur Items.) */
    private boolean fluidFilter(@Nullable GhostSlots slots) {
        return slots != null && !stocking(slots) && !FluidTypes.storable().isEmpty()
                && slots.inventory().isSupportedType(FluidTypes.storable().getFirst().key());
    }

    private static String withTip(String description, String tip) {
        if (tip.isEmpty()) {
            return description;
        }
        StringBuilder sb = new StringBuilder(description);
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        TerminalTexts.line(sb, TerminalTexts.COLOR_MUTED, tip);
        return sb.toString();
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

    /** Alle Stapel im Inventar (Schnellleiste, Lager, Rucksack) in der Reihenfolge des Gitters. */
    private static List<Held> held(Store<EntityStore> store, Ref<EntityStore> ref) {
        List<Held> out = new ArrayList<>();
        if (ref == null || !ref.isValid()) {
            return out;
        }
        for (ItemContainer c : bags(store, ref)) {
            for (short s = 0; s < c.getCapacity(); s++) {
                ItemStack stack = c.getItemStack(s);
                AEItemKey key = ItemKeys.of(stack);
                if (key != null) {
                    out.add(new Held(c, s, key, stack));
                }
            }
        }
        return out;
    }

    /** Das Item, das abgelegt würde: die Auswahl, sonst das Item in der Hand; null = keines. */
    @Nullable
    private AEItemKey selection(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (selected != null) {
            if (find(selected) != null) {
                return selected;
            }
            selected = null; // nicht mehr im Inventar
        }
        AEItemKey inHand = ItemKeys.of(InventoryComponent.getItemInHand(store, ref));
        return inHand != null && find(inHand) != null ? inHand : null;
    }

    /** Erster Stapel dieses Items im Inventar. */
    @Nullable
    private Held find(@Nullable AEItemKey key) {
        if (key == null) {
            return null;
        }
        for (Held h : inventory) {
            if (h.key().equals(key)) {
                return h;
            }
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
        Host h = host();
        if (h == null) {
            close(); // Block abgebaut oder ersetzt
            return;
        }
        if (p.action == null) {
            return;
        }
        String l = lang();
        ConfigHost device = h.device();
        status = null;
        statusError = false;
        inventory = held(store, ref);
        int slot = toInt(p.slot, -1);
        int index = parse(p.index);
        boolean right = toInt(p.button, 0) == BUTTON_RIGHT;
        boolean priorityChanged = false;
        boolean numberChanged = false;
        switch (p.action) {
            case "Pick" -> pick(h, slot, right, l);
            case "Filter" -> filterClicked(store, ref, h, slot, right, l);
            case "Card" -> cardClicked(store, ref, h, slot, l);
            case "Storage" -> storageClicked(store, ref, h, slot, l);
            case "ClearAll" -> {
                GhostSlots slots = h.slots();
                if (slots != null) {
                    slots.inventory().clear();
                    activeSlot = -1;
                    ok(l, "ae2bus.msg.clearedAll");
                }
            }
            case "Act" -> {
                ConfigHost.Action action = h.slots() == null ? null : device.action(h.ctx());
                ConfigHost.Feedback feedback = action == null ? null : action.run().get();
                if (feedback != null) {
                    status = feedback.text();
                    statusError = feedback.error();
                }
            }
            case "Setting" -> {
                settings = h.settings();
                if (index >= 0 && index < settings.size()) {
                    settings.get(index).next().run();
                }
            }
            case "Amount" -> {
                GhostSlots slots = h.slots();
                if (stocking(slots) && index >= 0 && index < AMOUNT_STEPS.length && activeSlot >= 0) {
                    ConfigEdits.changeAmount(slots.inventory(), activeSlot, AMOUNT_STEPS[index]);
                }
            }
            case "Number" -> {
                NumberField number = device.numberField(h.ctx());
                if (number != null && index >= 0 && index < NUMBER_BUTTONS.length) {
                    number.setter().accept(ConfigEdits.addNumber(number.value(), NUMBER_SIGN[index] * step(number, index),
                            number.min(), number.max()));
                    numberChanged = true;
                }
            }
            case "NumberSet" -> {
                NumberField number = device.numberField(h.ctx());
                if (number != null) {
                    number.setter().accept(ConfigEdits.toNumber(p.value, number.value(), number.min(), number.max()));
                    numberChanged = true;
                }
            }
            case "Priority" -> {
                if (device.hasPriority() && index >= 0 && index < PRIORITY_STEPS.length) {
                    device.setPriority(h.node(), ConfigEdits.addPriority(device.priority(), PRIORITY_STEPS[index]));
                    priorityChanged = true;
                }
            }
            case "PrioritySet" -> {
                if (device.hasPriority()) {
                    device.setPriority(h.node(), ConfigEdits.toPriority(p.value, device.priority()));
                    priorityChanged = true;
                }
            }
            default -> {
            }
        }
        if (!"Pick".equals(p.action) || right) {
            device.configChanged(h.node()); // das Gerät speichert, was sich geändert hat (Auswählen ändert nichts)
        }
        UICommandBuilder cmd = new UICommandBuilder();
        if (priorityChanged) {
            cmd.set("#PrioValue.Value", (double) device.priority());
        }
        NumberField number = numberChanged ? device.numberField(h.ctx()) : null;
        if (number != null) {
            cmd.set("#NumValue.Value", (double) number.value());
        }
        fill(cmd, store, ref);
        sendUpdate(cmd, false);
    }

    private void fail(String l, String key, Object... params) {
        status = tr(l, key, params);
        statusError = true;
    }

    private void ok(String l, String key, Object... params) {
        status = tr(l, key, params);
        statusError = false;
    }

    /** Klick auf ein Item im Inventar: auswählen; Rechtsklick fügt es direkt hinzu (AE2: Umschalt-Klick). */
    private void pick(Host h, int index, boolean quick, String l) {
        if (index < 0 || index >= inventory.size()) {
            return;
        }
        Held held = inventory.get(index);
        if (!quick) {
            selected = held.key().equals(selected) ? null : held.key();
            return;
        }
        selected = held.key();
        UpgradeInventory upgrades = h.upgrades();
        UpgradeCard card = UpgradeCard.byItemId(held.key().itemId());
        if (upgrades != null && card != null && upgrades.canInsert(card)) {
            // wie in AE2 wandern Karten zuerst in die Kartenplätze
            for (int i = 0; i < upgrades.size(); i++) {
                if (upgrades.get(i) == null) {
                    insertCard(upgrades, held, card, i, l);
                    return;
                }
            }
        }
        if (h.slots() != null) {
            put(h, -1, held, l);
        } else if (upgrades != null && card != null) {
            insertCard(upgrades, held, card, -1, l); // sagt, warum die Karte nicht passt
        } else if (upgrades != null) {
            fail(l, "ae2bus.msg.notACard");
        }
    }

    /** Klick auf einen Filter- bzw. Vorratsplatz. */
    private void filterClicked(Store<EntityStore> store, Ref<EntityStore> ref, Host h, int slot, boolean right, String l) {
        GhostSlots slots = h.slots();
        if (slots == null || slot < 0 || slot >= usable(slots)) {
            return;
        }
        ConfigInventory config = slots.inventory();
        if (right && fluidClicked(store, ref, slots, slot, l)) {
            return;
        }
        if (config.getKey(slot) == null) {
            Held held = find(selection(store, ref));
            if (held == null) {
                fail(l, "ae2bus.msg.nothing");
                return;
            }
            put(h, slot, held, l);
        } else if (stocking(slots) && !right) {
            activeSlot = slot; // die Mengenknöpfe gelten jetzt für diesen Platz
        } else {
            ConfigEdits.clear(config, usable(slots), slot);
            if (activeSlot == slot) {
                activeSlot = -1;
            }
            ok(l, "ae2bus.msg.cleared");
        }
    }

    /**
     * Rechtsklick auf einen Filterplatz, während ein Behälter-Item gewählt ist: setzt eine Flüssigkeit bzw. schaltet
     * zur nächsten weiter (siehe {@link ConfigEdits#fluidForSlot}). Verbraucht nichts.
     *
     * @return true, wenn der Klick damit erledigt ist; false = gewöhnlicher Klick (kein Behälter gewählt, Item im
     * Platz, Filter ohne Flüssigkeiten)
     */
    private boolean fluidClicked(Store<EntityStore> store, Ref<EntityStore> ref, GhostSlots slots, int slot, String l) {
        if (!fluidFilter(slots)) {
            return false;
        }
        Held held = find(selection(store, ref));
        if (held == null || !FluidContainers.isContainerItem(held.key().itemId())) {
            return false;
        }
        ConfigInventory config = slots.inventory();
        AEKey current = config.getKey(slot);
        if (current != null && !(current instanceof AEFluidKey)) {
            return false;
        }
        int visible = usable(slots);
        List<String> storable = new ArrayList<>();
        for (FluidTypes.Info info : FluidTypes.storable()) {
            storable.add(info.id());
        }
        FluidContainers.Content content = FluidContainers.contentOf(held.stack());
        AEFluidKey fluid = ConfigEdits.fluidForSlot(config, visible, slot, content == null ? null : content.fluid(), storable);
        if (fluid == null) {
            fail(l, "ae2fluid.config.noOther");
            return true;
        }
        String name = FluidTypes.name(l, fluid.fluidId());
        switch (ConfigEdits.put(config, visible, slot, fluid, 1, true)) {
            case DONE -> ok(l, "ae2bus.msg.set", "item", name);
            case DUPLICATE -> fail(l, "ae2bus.msg.duplicate", "item", name);
            default -> fail(l, "ae2bus.msg.failed");
        }
        return true;
    }

    /** Legt das Item von {@code held} als Geisteritem in einen Platz; verbraucht nichts. */
    private void put(Host h, int slot, Held held, String l) {
        GhostSlots slots = h.slots();
        if (slots == null) {
            return;
        }
        String name = TerminalTexts.itemName(l, held.key().itemId());
        ConfigInventory config = slots.inventory();
        boolean stocking = stocking(slots);
        int visible = usable(slots);
        int target = slot >= 0 ? slot : ConfigEdits.firstFree(config, visible);
        // Vorrat: so viel, wie der gewählte Stapel hat (AE2: Stapel auf dem Mauszeiger)
        ConfigEdits.Result result = ConfigEdits.put(config, visible, slot, held.key(),
                stocking ? held.stack().getQuantity() : 1, !stocking);
        switch (result) {
            case DONE -> {
                if (stocking) {
                    activeSlot = target;
                }
                ok(l, "ae2bus.msg.set", "item", name);
            }
            case DUPLICATE -> fail(l, "ae2bus.msg.duplicate", "item", name);
            case FULL -> {
                UpgradeInventory upgrades = h.upgrades();
                boolean capacityCards = upgrades != null && upgrades.getMaxInstalled(UpgradeCard.CAPACITY) > 0;
                fail(l, stocking ? "ae2bus.msg.fullStock" : capacityCards ? "ae2bus.msg.full" : "ae2bus.msg.fullPlain");
            }
            case NOTHING -> fail(l, "ae2bus.msg.nothing");
            default -> fail(l, "ae2bus.msg.failed");
        }
    }

    /** Klick auf einen Kartenplatz: Karte herausnehmen bzw. die gewählte Karte einbauen. */
    private void cardClicked(Store<EntityStore> store, Ref<EntityStore> ref, Host h, int slot, String l) {
        UpgradeInventory upgrades = h.upgrades();
        if (upgrades == null || slot < 0 || slot >= upgrades.size()) {
            return;
        }
        UpgradeCard installed = upgrades.get(slot);
        if (installed != null) {
            removeCard(store, ref, h, upgrades, slot, installed, l);
            return;
        }
        Held held = find(selection(store, ref));
        UpgradeCard card = held == null ? null : UpgradeCard.byItemId(held.key().itemId());
        if (card == null) {
            fail(l, "ae2bus.msg.notACard");
            return;
        }
        insertCard(upgrades, held, card, slot, l);
    }

    /** Baut eine Karte aus dem Inventar in den freien Platz {@code slot} ein: erst prüfen, dann nehmen, dann einbauen. */
    private void insertCard(UpgradeInventory upgrades, Held held, UpgradeCard card, int slot, String l) {
        String name = TerminalTexts.itemName(l, card.itemId);
        int max = upgrades.getMaxInstalled(card);
        if (max <= 0) {
            fail(l, "ae2bus.msg.cardUnsupported", "card", name);
            return;
        }
        if (upgrades.getInstalledUpgrades(card) >= max) {
            fail(l, "ae2bus.msg.cardMax", "card", name, "max", Integer.toString(max));
            return;
        }
        if (slot < 0 || slot >= upgrades.size() || upgrades.get(slot) != null || !upgrades.canInsert(card)) {
            fail(l, "ae2bus.msg.cardNoSlot");
            return;
        }
        ItemStack before = held.container().getItemStack(held.slot());
        if (ItemStack.isEmpty(before) || !card.itemId.equals(before.getItemId())) {
            fail(l, "ae2bus.msg.failed"); // Inventar hat sich inzwischen geändert
            return;
        }
        ItemStackSlotTransaction tx = held.container().removeItemStackFromSlot(held.slot(), 1, true, false);
        if (!tx.succeeded()) {
            fail(l, "ae2bus.msg.failed");
            return;
        }
        upgrades.set(slot, card);
        ok(l, "ae2bus.msg.cardInserted", "card", name);
    }

    /** Nimmt die Karte aus {@code slot} ins Inventar; passt sie nicht hinein, bleibt sie eingebaut. */
    private void removeCard(Store<EntityStore> store, Ref<EntityStore> ref, Host h, UpgradeInventory upgrades, int slot,
                            UpgradeCard card, String l) {
        String name = TerminalTexts.itemName(l, card.itemId);
        ItemStack stack = new ItemStack(card.itemId, 1);
        ItemContainer inv = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
        if (!inv.canAddItemStack(stack)) {
            fail(l, "ae2bus.msg.inventoryFull");
            return;
        }
        if (upgrades.remove(slot) != card) {
            fail(l, "ae2bus.msg.failed");
            return;
        }
        ItemStack rest = inv.addItemStack(stack).getRemainder();
        if (!ItemStack.isEmpty(rest)) {
            ItemUtils.dropItem(ref, rest, store); // Inventar doch voll: vor die Füße statt ins Nichts
        }
        // AE2 leert Filterplätze, die ohne die Kapazitätskarte nicht mehr freigeschaltet sind
        GhostSlots slots = h.slots();
        int hidden = slots == null || slots.amounts() ? 0 : ConfigEdits.clearHidden(slots.inventory(), usable(slots));
        if (hidden > 0) {
            ok(l, "ae2bus.msg.cardHidden", "card", name, "count", Integer.toString(hidden));
        } else {
            ok(l, "ae2bus.msg.cardRemoved", "card", name);
        }
    }

    /**
     * Vorrats-Layout, Klick auf einen echten Platz: belegt → Stapel ins Inventar; leer → den gewählten Stapel
     * hineinlegen. Beides verschiebt Hytale als ein Vorgang zwischen den Containern (was nicht passt, bleibt, wo es
     * war).
     */
    private void storageClicked(Store<EntityStore> store, Ref<EntityStore> ref, Host h, int slot, String l) {
        ItemSection section = stockLayout ? h.device().itemSection() : null;
        ItemContainer storage = section == null ? null : section.container();
        if (storage == null || slot < 0 || slot >= Math.min(section.slots(), storage.getCapacity())) {
            return;
        }
        ItemStack inside = storage.getItemStack((short) slot);
        if (!ItemStack.isEmpty(inside)) {
            ItemContainer inv = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
            storage.moveItemStackFromSlot((short) slot, inv);
            ItemStack after = storage.getItemStack((short) slot);
            int moved = inside.getQuantity() - (ItemStack.isEmpty(after) ? 0 : after.getQuantity());
            if (moved <= 0) {
                fail(l, "ae2bus.msg.inventoryFull");
            } else {
                ok(l, "ae2bus.msg.taken", "amount", Integer.toString(moved), "item", TerminalTexts.itemName(l, inside.getItemId()));
            }
            return;
        }
        Held held = find(selection(store, ref));
        if (held == null) {
            fail(l, "ae2bus.msg.nothing");
            return;
        }
        ItemStack before = held.container().getItemStack(held.slot());
        if (ItemStack.isEmpty(before) || !held.key().equals(ItemKeys.of(before))) {
            fail(l, "ae2bus.msg.failed");
            return;
        }
        held.container().moveItemStackFromSlotToSlot(held.slot(), before.getQuantity(), storage, (short) slot, true);
        ItemStack left = held.container().getItemStack(held.slot());
        int moved = before.getQuantity() - (ItemStack.isEmpty(left) ? 0 : left.getQuantity());
        if (moved <= 0) {
            fail(l, "ae2bus.msg.slotRejects");
        } else {
            ok(l, "ae2bus.msg.stored", "amount", Integer.toString(moved), "item", TerminalTexts.itemName(l, before.getItemId()));
        }
    }

    // ------------------------------------------------------------------ Aktualisierung

    /** Stand von Gerät und Inventar; ändert er sich (auch durch andere Spieler oder das Netz), wird neu gezeichnet. */
    private long fingerprint(Host h, Store<EntityStore> store, Ref<EntityStore> ref) {
        ConfigHost device = h.device();
        long f = device.online(h.node()) ? 17 : 5;
        GhostSlots slots = h.slots();
        if (slots != null) {
            ConfigInventory config = slots.inventory();
            int visible = usable(slots);
            f = f * 31 + visible;
            for (int i = 0; i < visible; i++) {
                GenericStack gs = config.getStack(i);
                f = f * 31 + (gs == null ? 0 : gs.hashCode());
            }
            f = f * 31 + slots.headerKey().hashCode();
        }
        UpgradeInventory upgrades = h.upgrades();
        if (upgrades != null) {
            for (int i = 0; i < upgrades.size(); i++) {
                UpgradeCard card = upgrades.get(i);
                f = f * 31 + (card == null ? 0 : card.ordinal() + 1);
            }
        }
        for (Setting s : h.settings()) {
            f = f * 31 + s.text().hashCode();
        }
        f = f * 31 + device.priority();
        NumberField number = device.numberField(h.ctx());
        f = f * 31 + (number == null ? 0 : Long.hashCode(number.value()) + 1);
        String info = device.info(h.ctx()); // z. B. das Ziel vor einem Bus
        f = f * 31 + (info == null ? 0 : info.hashCode());
        ItemSection section = stockLayout ? device.itemSection() : null;
        ItemContainer storage = section == null ? null : section.container();
        if (storage != null) {
            for (short s = 0; s < storage.getCapacity(); s++) {
                ItemStack stack = storage.getItemStack(s);
                f = f * 31 + (ItemStack.isEmpty(stack) ? 0 : stack.hashCode());
            }
        }
        for (Held held : held(store, ref)) {
            f = f * 31 + held.key().hashCode();
            f = f * 31 + held.slot();
            f = f * 31 + held.stack().getQuantity();
        }
        ItemStack inHand = ref != null && ref.isValid() ? InventoryComponent.getItemInHand(store, ref) : null;
        f = f * 31 + (ItemStack.isEmpty(inHand) ? 0 : inHand.hashCode());
        return f;
    }

    /**
     * Vom Gerät alle {@link Watchers#INTERVAL_TICKS} Ticks und beim Entfernen gerufen (Welt-Thread): zeichnet neu,
     * wenn sich etwas geändert hat, und schließt die Seite, wenn es das Gerät nicht mehr gibt.
     *
     * @return false, wenn die Seite nicht weiter beobachten will
     */
    private boolean refreshTick() {
        if (!open || !playerRef.isValid()) {
            return false;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = ref.getStore();
        if (store.getExternalData().getWorld() != nets.world()) {
            return false; // Spieler hat die Welt gewechselt
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || player.getPageManager().getCustomPage() != this) {
            open = false;
            return false; // inzwischen ist eine andere Seite offen: dorthin darf kein Update dieser Seite gehen
        }
        Host h = host();
        if (h == null) {
            open = false;
            close();
            return false;
        }
        if (fingerprint(h, store, ref) != fingerprint) {
            UICommandBuilder cmd = new UICommandBuilder();
            fill(cmd, store, ref);
            sendUpdate(cmd, false);
        }
        return true;
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        open = false;
        Host h = host();
        Watchers watchers = h == null ? null : h.device().watchers();
        if (watchers != null) {
            watchers.remove(watcher);
        }
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
        String index;
        Double value;
        Double slot;
        Double button;

        /** Feld, Maustaste und Zahlenfeld schickt der Client als Zahl (wie im ME-Terminal). */
        public static final BuilderCodec<Payload> CODEC = BuilderCodec.builder(Payload.class, Payload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .append(new KeyedCodec<>("Index", Codec.STRING), (o, v) -> o.index = v, o -> o.index).add()
                .append(new KeyedCodec<>("@Value", Codec.DOUBLE), (o, v) -> o.value = v, o -> o.value).add()
                .append(new KeyedCodec<>("SlotIndex", Codec.DOUBLE), (o, v) -> o.slot = v, o -> o.slot).add()
                .append(new KeyedCodec<>("PressedMouseButton", Codec.DOUBLE), (o, v) -> o.button = v, o -> o.button).add()
                .build();
    }
}
