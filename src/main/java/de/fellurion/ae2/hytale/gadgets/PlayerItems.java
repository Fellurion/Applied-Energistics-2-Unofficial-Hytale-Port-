/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.gadgets;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.ItemKeys;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Das Inventar des Spielers für Werkzeuge, die sich daraus laden (Munition, Farbe). */
final class PlayerItems {

    /** Wie viele Stück eines Stapels ein Werkzeug haben will (0 = keine). */
    @FunctionalInterface
    interface Wanted {
        int of(ItemStack stack);
    }

    private PlayerItems() {
    }

    /** Leiste, Rucksack-Fächer und Rucksack des Spielers, in dieser Reihenfolge. */
    static List<ItemContainer> containers(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> player) {
        List<ItemContainer> out = new ArrayList<>(3);
        add(out, accessor.getComponent(player, InventoryComponent.Hotbar.getComponentType()));
        add(out, accessor.getComponent(player, InventoryComponent.Storage.getComponentType()));
        add(out, accessor.getComponent(player, InventoryComponent.Backpack.getComponentType()));
        return out;
    }

    private static void add(List<ItemContainer> out, @Nullable InventoryComponent component) {
        ItemContainer c = component == null ? null : component.getInventory();
        if (c != null) {
            out.add(c);
        }
    }

    /**
     * Nimmt Items aus dem Inventar. Der Slot des Werkzeugs selbst wird übersprungen; Items mit eigenen Metadaten
     * (umbenannt, mit Inhalt) bleiben liegen, denn im Werkzeug bliebe davon nur die Anzahl übrig.
     *
     * @param wanted wird je Stapel gefragt und sieht dabei, was schon genommen wurde, über seinen eigenen Zustand
     * @return die genommenen Items (je Stapel ein Eintrag mit der genommenen Menge)
     */
    static List<ItemStack> take(List<ItemContainer> containers, @Nullable ItemContainer heldContainer, short heldSlot, Wanted wanted) {
        List<ItemStack> taken = new ArrayList<>();
        for (ItemContainer c : containers) {
            for (short slot = 0; slot < c.getCapacity(); slot++) {
                if (c == heldContainer && slot == heldSlot) {
                    continue;
                }
                ItemStack stack = c.getItemStack(slot);
                if (ItemStack.isEmpty(stack) || (stack.getMetadata() != null && !stack.getMetadata().isEmpty())) {
                    continue;
                }
                int n = Math.min(stack.getQuantity(), wanted.of(stack));
                if (n > 0 && c.removeItemStackFromSlot(slot, stack, n).succeeded()) {
                    taken.add(stack.withQuantity(n));
                }
            }
        }
        return taken;
    }

    /** Gibt dem Spieler Items zurück (in Stapeln); was nicht passt, fällt vor ihm zu Boden. */
    static void give(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> player, String itemId, int count) {
        int max = Math.max(1, ItemKeys.maxStack(itemId));
        ItemContainer inventory = InventoryComponent.getCombined(accessor, player, InventoryComponent.HOTBAR_FIRST);
        int remaining = count;
        while (remaining > 0) {
            int n = Math.min(max, remaining);
            remaining -= n;
            SimpleItemContainer.addOrDropItemStack(accessor, player, inventory, new ItemStack(itemId, n));
        }
    }
}
