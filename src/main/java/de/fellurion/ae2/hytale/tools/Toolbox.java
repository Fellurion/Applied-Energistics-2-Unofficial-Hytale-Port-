/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.tools.NetworkToolItem#getInventory / #findNetworkToolInv / #getTooltipImage).
 */
package de.fellurion.ae2.hytale.tools;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.tools.ToolboxInventory;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.items.ItemTooltips;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Die Werkzeugkiste des Network Tools am Item: neun Plätze nur für Upgrade-Karten ({@link ToolboxInventory}), als
 * JSON in den Metadaten unter {@link #KEY}. Ein leeres Werkzeug trägt keine Metadaten.
 *
 * <p>{@link ItemStack} ist unveränderlich: {@link #write} liefert einen neuen Stack, den der Aufrufer mit
 * {@link Slot#replace} an genau die Stelle zurückschreibt, von der er den alten gelesen hat – liegt dort inzwischen
 * etwas anderes, schlägt das fehl und nichts ändert sich.
 */
public final class Toolbox {

    public static final String ITEM_ID = "AE2_Network_Tool";
    /** Metadaten-Schlüssel: Inhalt der Werkzeugkiste ({@link ToolboxInventory#toJson()}). */
    public static final String KEY = "AE2Toolbox";

    private Toolbox() {
    }

    /** Ein Network Tool an seinem Platz im Inventar. */
    public record Slot(ItemContainer container, short slot, ItemStack stack) {

        /**
         * Ersetzt das Werkzeug an seinem Platz durch {@code next} – nur, wenn dort noch ein Stack mit genau diesem
         * Inhalt liegt (Hytale vergleicht Item, Haltbarkeit und Metadaten).
         *
         * @return der Platz mit dem neuen Stack, oder null, wenn der Platz inzwischen etwas anderes enthält
         */
        @Nullable
        public Slot replace(ItemStack next) {
            if (slot < 0 || slot >= container.getCapacity()
                    || !container.replaceItemStackInSlot(slot, stack, next).succeeded()) {
                return null;
            }
            return new Slot(container, slot, next);
        }
    }

    public static boolean isTool(@Nullable ItemStack stack) {
        return !ItemStack.isEmpty(stack) && ITEM_ID.equals(stack.getItemId());
    }

    /** Der gespeicherte Inhalt als Text (null = leer) – zugleich das, woran die Seite „ihr“ Werkzeug wiedererkennt. */
    @Nullable
    public static String json(@Nullable ItemStack tool) {
        return isTool(tool) ? tool.getFromMetadataOrNull(KEY, Codec.STRING) : null;
    }

    /** Dasselbe Werkzeug mit demselben Inhalt? */
    static boolean sameTool(@Nullable ItemStack a, @Nullable ItemStack b) {
        return isTool(a) && isTool(b) && Objects.equals(json(a), json(b));
    }

    /** Inhalt der Werkzeugkiste (leer für alles, was kein Network Tool ist). */
    public static ToolboxInventory read(@Nullable ItemStack tool) {
        return ToolboxInventory.fromJson(json(tool), ItemKeys::maxStack);
    }

    /** Das Werkzeug mit diesem Inhalt und passendem Tooltip. */
    public static ItemStack write(ItemStack tool, ToolboxInventory content) {
        return ItemTooltips.refresh(tool.withMetadata(KEY, Codec.STRING, content.toJson()));
    }

    /** Ein Stapel aus dem Inventar, wie ihn die Werkzeugkiste führt. */
    @Nullable
    static SimpleStack simple(@Nullable ItemStack stack) {
        var key = ItemKeys.of(stack);
        return key == null ? null : SimpleStack.of(key, stack.getQuantity());
    }

    /** Ein Stapel der Werkzeugkiste als Hytale-Stack. */
    static ItemStack hytale(SimpleStack stack) {
        return ItemKeys.toStack(stack.key(), stack.count());
    }

    /** Die Abschnitte des Inventars, in denen ein Werkzeug bzw. Karten liegen können. */
    static List<ItemContainer> bags(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) {
        List<ItemContainer> out = new ArrayList<>();
        if (ref == null || !ref.isValid()) {
            return out;
        }
        InventoryComponent.Hotbar hotbar = accessor.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Storage storage = accessor.getComponent(ref, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Backpack backpack = accessor.getComponent(ref, InventoryComponent.Backpack.getComponentType());
        for (InventoryComponent c : new InventoryComponent[]{hotbar, storage, backpack}) {
            if (c != null && c.getInventory() != null) {
                out.add(c.getInventory());
            }
        }
        return out;
    }

    /** Alle Network Tools im Inventar des Spielers, in der Reihenfolge der Plätze (AE2: das erste zählt). */
    public static List<Slot> find(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) {
        List<Slot> out = new ArrayList<>();
        for (ItemContainer c : bags(accessor, ref)) {
            for (short s = 0; s < c.getCapacity(); s++) {
                ItemStack stack = c.getItemStack(s);
                if (isTool(stack)) {
                    out.add(new Slot(c, s, stack));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Tooltip

    /** Zusatzzeilen des Tooltips: die Karten der Werkzeugkiste je Art, die häufigste zuerst (AE2: Tooltip-Bild). */
    @Nullable
    static Message tooltip(ItemStack stack) {
        if (!isTool(stack)) {
            return null;
        }
        List<SimpleStack> cards = read(stack).summary();
        if (cards.isEmpty()) {
            return null;
        }
        Message out = Message.translation("ae2tools.toolbox.tooltip.header");
        for (SimpleStack card : cards) {
            Item item = Item.getAssetMap().getAsset(card.itemId());
            out.insert(Message.raw("\n")).insert(Message.translation("ae2tools.toolbox.tooltip.entry")
                    .param("count", Integer.toString(card.count()))
                    .param("item", item == null ? Message.raw(card.itemId()) : item.getTranslationMessage()));
        }
        return out;
    }
}
