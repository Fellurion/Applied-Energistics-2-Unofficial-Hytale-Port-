/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.automation.AnnihilationPlanePart#onEntityCollision, ItemPickupStrategy#storeEntityItem).
 */
package de.fellurion.ae2.hytale.plane;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventPickup;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.automation.ItemPickupStrategy;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.hytale.ItemKeys;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Herumliegende Items (Item-Entities) in einem Quader. Hytale führt sie in einem eigenen räumlichen Index, der
 * einmal je Tick neu aufgebaut wird; frisch erzeugte Items erscheinen dort also einen Tick später.
 *
 * <p>Nicht eingesammelt wird, was Hytale selbst vom Aufheben ausnimmt ({@code PreventPickup}) oder was der Spieler
 * erst benutzen muss ({@code Interactable}).
 */
final class PlaneItems {

    /** Rand um den Blockraum: ein Item, das genau auf der Kante liegt, zählt dazu. */
    private static final double EDGE = 0.05;

    private PlaneItems() {
    }

    /** Füllt {@code out} mit den Items im Quader ({@code min} bis {@code max}, Weltkoordinaten). */
    static void collect(World world, Vector3d min, Vector3d max, List<Ref<EntityStore>> out) {
        out.clear();
        Store<EntityStore> store = world.getEntityStore().getStore();
        SpatialResource<Ref<EntityStore>, EntityStore> items = store.getResource(EntityModule.get().getItemSpatialResourceType());
        if (items == null) {
            return;
        }
        items.getSpatialStructure().collectBox(min, max, out);
        for (int i = out.size() - 1; i >= 0; i--) {
            if (stackOf(store, out.get(i)) == null) {
                out.remove(i);
            }
        }
    }

    /** Die Items im Blockraum des Ziels (leer, wenn dort nichts geladen ist). */
    static void collectInFront(PlaneTarget target, Vector3d min, Vector3d max, List<Ref<EntityStore>> out) {
        out.clear();
        if (target.section() == null) {
            return;
        }
        min.set(target.x() - EDGE, target.y() - EDGE, target.z() - EDGE);
        max.set(target.x() + 1 + EDGE, target.y() + 1 + EDGE, target.z() + 1 + EDGE);
        collect(target.world(), min, max, out);
    }

    /** Die Items vor der Plane als Objekte für den Kern ({@code ItemPickupStrategy}). */
    static List<WorldItem> inFront(PlaneTarget target, Vector3d min, Vector3d max, List<Ref<EntityStore>> scratch) {
        collectInFront(target, min, max, scratch);
        if (scratch.isEmpty()) {
            return List.of();
        }
        Store<EntityStore> store = target.world().getEntityStore().getStore();
        List<WorldItem> out = new ArrayList<>(scratch.size());
        for (Ref<EntityStore> ref : scratch) {
            out.add(new WorldItem(store, ref));
        }
        return out;
    }

    /** Stapel eines einsammelbaren Items; null, wenn es das Entity nicht mehr gibt oder es nicht eingesammelt wird. */
    @Nullable
    static ItemStack stackOf(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        ItemComponent item = store.getComponent(ref, ItemComponent.getComponentType());
        ItemStack stack = item == null ? null : item.getItemStack();
        if (ItemStack.isEmpty(stack)) {
            return null;
        }
        Archetype<EntityStore> archetype = store.getArchetype(ref);
        if (archetype.contains(PreventPickup.getComponentType()) || archetype.contains(Interactable.getComponentType())) {
            return null;
        }
        return stack;
    }

    /** Ein herumliegendes Item, wie es der Kern sieht (AE2: {@code ItemEntity}). */
    static final class WorldItem implements ItemPickupStrategy.ItemEntity {
        private final Store<EntityStore> store;
        private final Ref<EntityStore> ref;

        WorldItem(Store<EntityStore> store, Ref<EntityStore> ref) {
            this.store = store;
            this.ref = ref;
        }

        @Nullable
        private ItemStack stack() {
            // Während eines Store-Durchlaufs dürfen keine Entities entfernt werden: dann gilt das Item als nicht da
            return store.isProcessing() ? null : stackOf(store, ref);
        }

        @Override
        public boolean isAlive() {
            return stack() != null;
        }

        @Override
        public AEItemKey getItem() {
            ItemStack stack = stack();
            AEItemKey key = stack == null ? null : ItemKeys.of(stack);
            return key == null ? AEItemKey.of("Empty") : key;
        }

        @Override
        public int getCount() {
            ItemStack stack = stack();
            return stack == null ? 0 : stack.getQuantity();
        }

        @Override
        public void discard() {
            if (stack() == null) {
                return;
            }
            ItemComponent item = store.getComponent(ref, ItemComponent.getComponentType());
            if (item != null) {
                item.setItemStack(null); // falls das Entity in diesem Tick noch einmal angesehen wird
            }
            store.removeEntity(ref, RemoveReason.REMOVE);
        }

        @Override
        public void setCount(int count) {
            ItemStack stack = stack();
            ItemComponent item = stack == null ? null : store.getComponent(ref, ItemComponent.getComponentType());
            if (item != null && count > 0) {
                item.setItemStack(stack.withQuantity(count));
            }
        }
    }
}
