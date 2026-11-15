/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.AEBaseBlockEntity#addAdditionalDrops / appeng.parts.AEBasePart#addAdditionalDrops).
 */
package de.fellurion.ae2.hytale.device;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Lässt Gegenstände an einem Block fallen, die nicht in dessen Hytale-Container liegen (eingebaute Upgrade-Karten).
 * Den Container selbst leert Hytale beim Abbauen von sich aus.
 */
public final class Drops {

    private Drops() {
    }

    /** Lässt {@code stacks} in der Mitte des Blocks fallen (wie Hytale den Inhalt abgebauter Container). */
    public static void at(World world, int x, int y, int z, List<ItemStack> stacks) {
        List<ItemStack> valid = new ArrayList<>();
        for (ItemStack s : stacks) {
            if (!ItemStack.isEmpty(s)) {
                valid.add(s);
            }
        }
        if (valid.isEmpty()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Holder<EntityStore>[] holders = ItemComponent.generateItemDrops(store, valid, new Vector3d(x + 0.5, y, z + 0.5),
                Rotation3f.IDENTITY);
        if (holders.length > 0) {
            // Nicht mitten in einem laufenden Store-Durchlauf einfügen (Block-Entity wird gerade entfernt)
            world.execute(() -> store.addEntities(holders, AddReason.SPAWN));
        }
    }

    /** Nimmt alle Karten aus {@code upgrades} und lässt sie am Block fallen (Block abgebaut). */
    public static void upgrades(World world, int x, int y, int z, UpgradeInventory upgrades) {
        List<ItemStack> cards = new ArrayList<>();
        for (int i = 0; i < upgrades.size(); i++) {
            UpgradeCard card = upgrades.get(i);
            if (card != null) {
                cards.add(new ItemStack(card.itemId, 1));
            }
        }
        if (cards.isEmpty()) {
            return;
        }
        upgrades.clear(); // erst herausnehmen, dann fallen lassen: jede Karte gibt es genau einmal
        at(world, x, y, z, cards);
    }
}
