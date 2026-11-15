/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.tools.MemoryCardItem#restoreUpgrades – Spielerinventar und Network Tool als Kartenquelle).
 */
package de.fellurion.ae2.hytale.tools;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.tools.MemoryCardSettings.CardStock;
import de.fellurion.ae2.core.tools.ToolboxInventory;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.device.Drops;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Gegenstände eines Spielers für die Werkzeuge: geben, ohne dass etwas verloren geht, und Upgrade-Karten nehmen und
 * zurückgeben ({@link CardStock}) – wie in AE2 erst aus dem Inventar, dann aus der Werkzeugkiste eines Network Tools.
 */
public final class PlayerItems {

    private PlayerItems() {
    }

    /**
     * Gibt dem Spieler einen Stapel: ins Inventar, was nicht passt vor seine Füße – und sollte auch das nicht gehen,
     * an den Block {@code fallback} bzw. an die Stelle, an der der Spieler steht. Der Stapel landet in jedem Fall
     * genau einmal.
     */
    public static void give(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref, @Nullable ItemStack stack,
                            World world, @Nullable Vector3i fallback) {
        if (ItemStack.isEmpty(stack)) {
            return;
        }
        ItemStack rest = stack;
        if (ref != null && ref.isValid()) {
            ItemContainer inventory = InventoryComponent.getCombined(accessor, ref, InventoryComponent.HOTBAR_FIRST);
            rest = inventory.addItemStack(stack).getRemainder();
        }
        dropAtPlayer(accessor, ref, rest, world, fallback);
    }

    /** Lässt einen Stapel beim Spieler fallen, notfalls am Block {@code fallback} – nie ins Nichts. */
    static void dropAtPlayer(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref, @Nullable ItemStack stack,
                             World world, @Nullable Vector3i fallback) {
        if (ItemStack.isEmpty(stack)) {
            return;
        }
        Vector3i at = fallback;
        if (ref != null && ref.isValid()) {
            try {
                // ohne DropItemEvent: ein abgelehntes Ereignis ließe den Stapel verschwinden
                if (ItemUtils.throwItem(ref, accessor, stack, new Vector3d(0, 0, 0), 0f) != null) {
                    return;
                }
            } catch (RuntimeException e) {
                // der Store nimmt gerade keine neuen Wesen an (mitten in einem Durchlauf): unten verzögert fallen lassen
            }
            TransformComponent transform = accessor.getComponent(ref, TransformComponent.getComponentType());
            if (at == null && transform != null) {
                Vector3d p = transform.getPosition();
                at = new Vector3i((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
            }
        }
        if (at == null) {
            at = new Vector3i(0, 0, 0); // kein Ort bekannt (Spieler ohne Position): besser dort als gar nicht
        }
        Drops.at(world, at.x, at.y, at.z, List.of(stack));
    }

    /** Eine Karte ohne eigene Metadaten (umbenannte Karten bleiben, was sie sind, und werden nicht verbaut). */
    private static boolean plainCard(@Nullable ItemStack stack, UpgradeCard card) {
        if (ItemStack.isEmpty(stack) || !card.itemId.equals(stack.getItemId())) {
            return false;
        }
        AEItemKey key = ItemKeys.of(stack);
        return key != null && key.nbt() == null;
    }

    /**
     * Die Upgrade-Karten eines Spielers als Vorrat der Memory Card.
     *
     * @param fallback Block, an dem zurückgegebene Karten notfalls fallen (das Gerät)
     */
    static CardStock cards(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref, World world,
                           Vector3i fallback) {
        return new CardStock() {
            @Override
            public int take(UpgradeCard card, int amount) {
                int left = amount;
                for (ItemContainer c : Toolbox.bags(accessor, ref)) {
                    for (short s = 0; s < c.getCapacity() && left > 0; s++) {
                        ItemStack stack = c.getItemStack(s);
                        if (!plainCard(stack, card)) {
                            continue;
                        }
                        int n = Math.min(left, stack.getQuantity());
                        if (c.removeItemStackFromSlot(s, stack, n, true, false).succeeded()) {
                            left -= n;
                        }
                    }
                }
                for (Toolbox.Slot tool : Toolbox.find(accessor, ref)) {
                    if (left <= 0) {
                        break;
                    }
                    ToolboxInventory box = Toolbox.read(tool.stack());
                    int n = box.removeCards(card.itemId, left);
                    // erst wenn das Werkzeug mit dem verringerten Inhalt zurückgeschrieben ist, zählen die Karten
                    if (n > 0 && tool.replace(Toolbox.write(tool.stack(), box)) != null) {
                        left -= n;
                    }
                }
                return amount - left;
            }

            @Override
            public void give(UpgradeCard card, int amount) {
                if (amount <= 0) {
                    return;
                }
                int left = amount;
                if (ref != null && ref.isValid()) {
                    ItemContainer inventory = InventoryComponent.getCombined(accessor, ref, InventoryComponent.HOTBAR_FIRST);
                    ItemStack rest = inventory.addItemStack(new ItemStack(card.itemId, left)).getRemainder();
                    left = ItemStack.isEmpty(rest) ? 0 : rest.getQuantity();
                    for (Toolbox.Slot tool : Toolbox.find(accessor, ref)) {
                        if (left <= 0) {
                            break;
                        }
                        ToolboxInventory box = Toolbox.read(tool.stack());
                        int in = box.insert(SimpleStack.of(card.itemId, left));
                        if (in > 0 && tool.replace(Toolbox.write(tool.stack(), box)) != null) {
                            left -= in;
                        }
                    }
                }
                if (left > 0) {
                    // weder Inventar noch Werkzeugkiste: vor die Füße bzw. an den Block
                    dropAtPlayer(accessor, ref, new ItemStack(card.itemId, left), world, fallback);
                }
            }
        };
    }
}
