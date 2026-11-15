/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.networktool.NetworkToolMenu, appeng.items.contents.NetworkToolMenuHost,
 * appeng.items.tools.NetworkToolItem).
 */
package de.fellurion.ae2.hytale.tools;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.tools.ToolboxInventory;
import de.fellurion.ae2.hytale.items.ItemUseContext;
import de.fellurion.ae2.hytale.lang.AE2Language;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Werkzeugkiste des Network Tools: neun Plätze, in die nur Upgrade-Karten passen und die im Werkzeug bleiben (AE2:
 * NetworkToolMenu). Links die Kiste, rechts das Inventar; ein Klick auf eine Karte im Inventar legt den Stapel in die
 * Kiste, ein Klick auf einen Stapel in der Kiste holt ihn ins Inventar zurück (eine eigene Seite hat keinen
 * Mauszeiger mit Item).
 *
 * <p><b>Nichts wird verdoppelt, nichts geht verloren:</b> Der Inhalt steht in den Metadaten des Werkzeugs. Vor jeder
 * Aktion sucht die Seite ihr Werkzeug neu – am gemerkten Platz, sonst (verschoben) im übrigen Inventar – und erkennt
 * es am Inhalt. Geschrieben wird nur mit Vergleich ({@link Toolbox.Slot#replace}): Liegt am Platz nicht mehr genau
 * der gelesene Stack, ändert sich nichts. Die Reihenfolge ist immer „erst herausnehmen, dann hineinlegen“; was beim
 * Hineinlegen nicht ankommt, geht zurück an die Quelle und notfalls vor die Füße des Spielers. Ist das Werkzeug
 * nicht mehr im Inventar, schließt sich die Seite.
 */
final class ToolboxPage extends InteractiveCustomUIPage<ToolboxPage.Payload> {

    private static final String PAGE = "Pages/AE2/Toolbox.ui";
    /** Felder des Gitters mit dem Inventar des Spielers (6 × 8, wie in der Gerätekonfiguration). */
    private static final int INVENTORY_SLOTS = 48;

    /** Ein Stapel im Inventar des Spielers. */
    private record Held(ItemContainer container, short slot, ItemStack stack) {
    }

    private final World world;
    /** Platz, an dem das Werkzeug zuletzt lag. */
    private ItemContainer toolContainer;
    private short toolSlot;
    /** Inhalt des Werkzeugs, wie ihn die Seite zuletzt gelesen bzw. geschrieben hat – daran erkennt sie es wieder. */
    @Nullable
    private String known;
    private List<Held> inventory = List.of();
    @Nullable
    private String status;
    private boolean statusError;

    private ToolboxPage(PlayerRef playerRef, World world, ItemContainer toolContainer, short toolSlot,
                        @Nullable String known) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Payload.CODEC);
        this.world = world;
        this.toolContainer = toolContainer;
        this.toolSlot = toolSlot;
        this.known = known;
    }

    /** Öffnet die Werkzeugkiste des Network Tools in der Hand. */
    static void open(ItemUseContext ctx) {
        ItemContainer container = ctx.interaction().getHeldItemContainer();
        short slot = ctx.interaction().getHeldItemSlot();
        if (container == null || slot < 0 || slot >= container.getCapacity()
                || !Toolbox.isTool(container.getItemStack(slot))) {
            return;
        }
        ctx.player().getPageManager().openCustomPage(ctx.playerEntity(), ctx.entityStore(),
                new ToolboxPage(ctx.playerRef(), ctx.world(), container, slot, Toolbox.json(container.getItemStack(slot))));
    }

    private String lang() {
        return AE2Language.of(playerRef);
    }

    // ------------------------------------------------------------------ Werkzeug wiederfinden

    /**
     * Das Werkzeug dieser Seite: am gemerkten Platz, wenn dort noch ein Network Tool mit dem bekannten Inhalt liegt,
     * sonst das erste im Inventar mit diesem Inhalt (der Spieler hat es verschoben). Null = nicht mehr da.
     */
    @Nullable
    private Toolbox.Slot locate(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (toolSlot >= 0 && toolSlot < toolContainer.getCapacity()) {
            ItemStack here = toolContainer.getItemStack(toolSlot);
            if (Toolbox.isTool(here) && Objects.equals(Toolbox.json(here), known)) {
                return new Toolbox.Slot(toolContainer, toolSlot, here);
            }
        }
        for (Toolbox.Slot candidate : Toolbox.find(store, ref)) {
            if (Objects.equals(Toolbox.json(candidate.stack()), known)) {
                remember(candidate);
                return candidate;
            }
        }
        return null;
    }

    private void remember(Toolbox.Slot tool) {
        toolContainer = tool.container();
        toolSlot = tool.slot();
        known = Toolbox.json(tool.stack());
    }

    /**
     * Schreibt den neuen Inhalt ins Werkzeug.
     *
     * @return der Platz danach, oder null, wenn das Werkzeug nicht mehr dort liegt
     */
    @Nullable
    private Toolbox.Slot store(Toolbox.Slot tool, ToolboxInventory content) {
        Toolbox.Slot written = tool.replace(Toolbox.write(tool.stack(), content));
        if (written != null) {
            remember(written);
        }
        return written;
    }

    // ------------------------------------------------------------------ Aufbau

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        String l = lang();
        cmd.append(PAGE);
        cmd.set("#PageTitle.Text", TerminalTexts.tr(l, "ae2tools.toolbox.title"));
        cmd.set("#InvHeader.Text", TerminalTexts.tr(l, "ae2tools.toolbox.inventory"));
        cmd.set("#Hint.Text", TerminalTexts.tr(l, "ae2tools.toolbox.hint"));
        evt.addEventBinding(CustomUIEventBindingType.SlotClicking, "#Box", EventData.of("Action", "Take"), false);
        evt.addEventBinding(CustomUIEventBindingType.SlotClicking, "#Inventory", EventData.of("Action", "Store"), false);
        fill(cmd, store, ref, locate(store, ref));
    }

    /** Leeres Feld; wie im Inventar sind alle Felder anklickbar. */
    private static ItemGridSlot emptySlot() {
        ItemGridSlot slot = new ItemGridSlot();
        slot.setActivatable(true);
        return slot;
    }

    /** Feld mit einem Stapel – ohne Metadaten, die verträgt der Client in einer eigenen Seite nicht. */
    private static ItemGridSlot shown(ItemStack stack, String tip, String l) {
        ItemGridSlot slot = emptySlot();
        slot.setItemStack(TerminalTexts.displayStack(stack, stack.getQuantity()));
        String description = TerminalTexts.slotDescription(stack, l);
        slot.setDescription(tip.isEmpty() ? description : description.isEmpty() ? tip : description + "\n\n" + tip);
        return slot;
    }

    private void fill(UICommandBuilder cmd, Store<EntityStore> store, Ref<EntityStore> ref, @Nullable Toolbox.Slot tool) {
        String l = lang();
        ToolboxInventory content = Toolbox.read(tool == null ? null : tool.stack());
        ItemGridSlot[] box = new ItemGridSlot[ToolboxInventory.SLOTS];
        int used = 0;
        String takeTip = TerminalTexts.tr(l, "ae2tools.toolbox.tip.take");
        for (int i = 0; i < box.length; i++) {
            SimpleStack s = content.get(i);
            box[i] = emptySlot();
            if (s != null) {
                used++;
                try {
                    box[i] = shown(Toolbox.hytale(s), takeTip, l);
                } catch (RuntimeException e) {
                    // Karte, die sich nicht anzeigen lässt (Item entfernt): der Platz bleibt belegt, zeigt aber nichts
                }
            }
        }
        cmd.set("#Box.Slots", box);
        cmd.set("#BoxHeader.Text", TerminalTexts.tr(l, "ae2tools.toolbox.header", "used", Integer.toString(used),
                "slots", Integer.toString(ToolboxInventory.SLOTS)));

        inventory = held(store, ref);
        String storeTip = TerminalTexts.tr(l, "ae2tools.toolbox.tip.store");
        ItemGridSlot[] grid = new ItemGridSlot[INVENTORY_SLOTS];
        for (int i = 0; i < INVENTORY_SLOTS; i++) {
            grid[i] = emptySlot();
            if (i < inventory.size()) {
                ItemStack stack = inventory.get(i).stack();
                grid[i] = shown(stack, ToolboxInventory.isUpgradeCard(stack.getItemId()) ? storeTip : "", l);
            }
        }
        cmd.set("#Inventory.Slots", grid);

        boolean error = status != null && statusError;
        cmd.set("#Status.Text", status == null || error ? "" : status);
        cmd.set("#Status.Visible", !error);
        cmd.set("#StatusError.Text", error ? status : "");
        cmd.set("#StatusError.Visible", error);
    }

    /** Alle Stapel im Inventar (Schnellleiste, Lager, Rucksack) in der Reihenfolge des Gitters. */
    private static List<Held> held(Store<EntityStore> store, Ref<EntityStore> ref) {
        List<Held> out = new ArrayList<>();
        for (ItemContainer c : Toolbox.bags(store, ref)) {
            for (short s = 0; s < c.getCapacity(); s++) {
                ItemStack stack = c.getItemStack(s);
                if (!ItemStack.isEmpty(stack)) {
                    out.add(new Held(c, s, stack));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Ereignisse

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Payload p) {
        Toolbox.Slot tool = locate(store, ref);
        if (tool == null) {
            close(); // das Werkzeug ist nicht mehr im Inventar
            return;
        }
        if (p.action == null) {
            return;
        }
        String l = lang();
        status = null;
        statusError = false;
        int slot = p.slot == null || p.slot.isNaN() || p.slot.isInfinite() ? -1 : (int) Math.round(p.slot);
        switch (p.action) {
            case "Take" -> take(store, ref, tool, slot, l);
            case "Store" -> store(store, ref, tool, slot, l);
            default -> {
            }
        }
        Toolbox.Slot now = locate(store, ref);
        if (now == null) {
            close();
            return;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        fill(cmd, store, ref, now);
        sendUpdate(cmd, false);
    }

    private void fail(String l, String key) {
        status = TerminalTexts.tr(l, key);
        statusError = true;
    }

    private void ok(String l, String key, int amount, String itemId) {
        status = TerminalTexts.tr(l, key, "amount", Integer.toString(amount), "item", TerminalTexts.itemName(l, itemId));
        statusError = false;
    }

    /**
     * Klick auf einen Platz der Kiste: den Stapel ins Inventar holen. Erst kommt er aus dem Werkzeug (mit Vergleich
     * geschrieben), dann ins Inventar; was dort nicht passt, geht zurück in denselben Platz.
     */
    private void take(Store<EntityStore> store, Ref<EntityStore> ref, Toolbox.Slot tool, int slot, String l) {
        ToolboxInventory content = Toolbox.read(tool.stack());
        SimpleStack inside = content.get(slot);
        if (inside == null) {
            return;
        }
        ItemStack out;
        try {
            out = Toolbox.hytale(inside);
        } catch (RuntimeException e) {
            fail(l, "ae2tools.toolbox.msg.failed"); // Item gibt es nicht mehr: es bleibt in der Kiste
            return;
        }
        SimpleStack taken = content.extract(slot, inside.count());
        Toolbox.Slot written = taken == null ? null : store(tool, content);
        if (written == null) {
            fail(l, "ae2tools.toolbox.msg.failed"); // nichts geändert
            return;
        }
        // Ab hier gibt es die Karten nur noch in dieser Methode: ins Inventar, der Rest zurück in die Kiste
        ItemContainer inv = InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
        ItemStack rest = inv.addItemStack(out).getRemainder();
        int left = ItemStack.isEmpty(rest) ? 0 : rest.getQuantity();
        if (left > 0) {
            // Das Werkzeug liegt jetzt evtl. neben neuen Karten – sein Platz ist derselbe geblieben
            int lost = content.putBack(slot, taken.withCount(left));
            Toolbox.Slot back = store(written, content);
            if (back == null) {
                lost = left; // Werkzeug nicht mehr beschreibbar: alles, was übrig ist, fällt
            }
            if (lost > 0) {
                PlayerItems.dropAtPlayer(store, ref, out.withQuantity(lost), world, null);
            }
        }
        int moved = inside.count() - left;
        if (moved <= 0) {
            fail(l, "ae2tools.toolbox.msg.inventoryFull");
        } else {
            ok(l, "ae2tools.toolbox.msg.taken", moved, inside.itemId());
        }
    }

    /**
     * Klick auf einen Stapel im Inventar: so viel davon in die Kiste legen, wie hineinpasst. Erst kommt die Menge
     * aus dem Inventar (mit Vergleich entnommen), dann ins Werkzeug; scheitert das Schreiben, geht sie zurück.
     */
    private void store(Store<EntityStore> store, Ref<EntityStore> ref, Toolbox.Slot tool, int index, String l) {
        if (index < 0 || index >= inventory.size()) {
            return;
        }
        Held held = inventory.get(index);
        ItemStack live = held.container().getItemStack(held.slot());
        if (ItemStack.isEmpty(live) || live != held.stack() && !live.equals(held.stack())) {
            return; // das Inventar hat sich seit dem Zeichnen geändert: nur neu zeichnen
        }
        SimpleStack card = ToolboxInventory.isUpgradeCard(live.getItemId()) ? Toolbox.simple(live) : null;
        if (card == null) {
            fail(l, "ae2tools.toolbox.msg.onlyCards");
            return;
        }
        ToolboxInventory content = Toolbox.read(tool.stack());
        int fits = content.simulateInsert(card);
        if (fits <= 0) {
            fail(l, "ae2tools.toolbox.msg.full");
            return;
        }
        if (!held.container().removeItemStackFromSlot(held.slot(), live, fits, true, false).succeeded()) {
            fail(l, "ae2tools.toolbox.msg.failed"); // nichts geändert
            return;
        }
        // Ab hier gibt es die Karten nur noch in dieser Methode
        int in = content.insert(card.withCount(fits));
        Toolbox.Slot written = store(tool, content);
        int back = written == null ? fits : fits - in;
        if (back > 0) {
            // nicht (alles) angekommen: zurück ins Inventar, notfalls vor die Füße
            PlayerItems.give(store, ref, live.withQuantity(back), world, null);
        }
        if (written == null) {
            fail(l, "ae2tools.toolbox.msg.failed");
        } else {
            ok(l, "ae2tools.toolbox.msg.stored", in, card.itemId());
        }
    }

    // ------------------------------------------------------------------ Event-Daten

    public static final class Payload {
        String action;
        Double slot;
        Double button;

        /** Feld und Maustaste schickt der Client als Zahl (wie in der Gerätekonfiguration). */
        public static final BuilderCodec<Payload> CODEC = BuilderCodec.builder(Payload.class, Payload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .append(new KeyedCodec<>("SlotIndex", Codec.DOUBLE), (o, v) -> o.slot = v, o -> o.slot).add()
                .append(new KeyedCodec<>("PressedMouseButton", Codec.DOUBLE), (o, v) -> o.button = v, o -> o.button).add()
                .build();
    }
}
