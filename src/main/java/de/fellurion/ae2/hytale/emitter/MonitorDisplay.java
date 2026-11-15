/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.client.render.tesr.MonitorBlockEntityRenderer – Item und Menge auf dem Bildschirm des Monitors).
 */
package de.fellurion.ae2.hytale.emitter;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventItemMerging;
import com.hypixel.hytale.server.core.modules.entity.item.PreventPickup;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * Die Anzeige eines Monitors in der Welt: ein ruhendes Item mit Namensschild (Menge) vor dem Bildschirm.
 *
 * <p>Die Anzeige ist ein Entity, das nie gespeichert wird ({@code NonSerialized}) – der Block allein weiß, was er
 * zeigt, und legt sie beim Laden neu an. Sie steht innerhalb der Blockzelle des Monitors und gehört damit zur selben
 * Chunk-Sektion: Hört die Sektion auf zu ticken, entfernt die Engine Entity und Block-Entity gemeinsam, und der
 * Monitor meldet sich ab ({@link #remove}). Aufsammeln, Zusammenlegen und Treffen sind abgeschaltet; das Item ist
 * ein einzelnes Stück ohne Metadaten und hat mit dem Netzinhalt nichts zu tun.
 *
 * <p>Entities lassen sich nur zwischen zwei Durchläufen des Entity-Stores anlegen und entfernen, deshalb geschieht
 * beides über {@code world.execute}. Jedes {@link #remove} macht noch ausstehendes Anlegen ungültig.
 */
final class MonitorDisplay {

    /** Kennzeichnet die Anzeige-Entities (zum Zählen und Wiederfinden); wird nicht gespeichert. */
    static final class Marker implements Component<EntityStore> {
        static final Marker INSTANCE = new Marker();

        private Marker() {
        }

        @Override
        public Component<EntityStore> clone() {
            return INSTANCE;
        }
    }

    /** Größe des gezeigten Items (der Bildschirm ist 0,75 Blöcke breit). */
    static final float SCALE = 0.4f;
    /** Abstand der Anzeige von der Blockmitte in Blickrichtung: knapp vor der Vorderseite, noch in der Zelle. */
    static final double FRONT_OFFSET = 0.484;
    /** Bei seitlich zeigenden Monitoren sitzt das Item etwas unter der Mitte, das Schild darüber. */
    static final double DROP = 0.15;

    @Nullable
    private static ComponentType<EntityStore, Marker> markerType;

    static void setup(JavaPlugin plugin) {
        markerType = plugin.getEntityStoreRegistry().registerComponent(Marker.class, () -> Marker.INSTANCE);
    }

    /** Zahl der Anzeige-Entities in der Welt (Welt-Thread). */
    static int count(World world) {
        ComponentType<EntityStore, Marker> type = markerType;
        return type == null ? 0 : world.getEntityStore().getStore().getEntityCountFor(type);
    }

    @Nullable
    private Ref<EntityStore> ref;
    /** Anlegen ist bestellt, aber noch nicht geschehen. */
    private boolean creating;
    /** Zählt jedes Entfernen; bestelltes Anlegen einer älteren Generation verfällt. */
    private int generation;
    @Nullable
    private AEItemKey shownKey;
    @Nullable
    private String shownText;

    /** Gibt es die Anzeige (oder ist sie bestellt) und zeigt sie dieses Item? */
    boolean shows(AEItemKey key) {
        return (creating || (ref != null && ref.isValid())) && key.equals(shownKey);
    }

    /** Gibt es gerade ein Entity oder ist eines bestellt? */
    boolean present() {
        return creating || ref != null;
    }

    /** Lebt das Entity gerade? (Für Prüfbefehle.) */
    boolean alive() {
        return ref != null && ref.isValid();
    }

    /** Position des Entities als „x y z“ mit zwei Nachkommastellen, oder „-“, wenn es keines gibt. (Für Prüfbefehle.) */
    String position() {
        Ref<EntityStore> r = ref;
        TransformComponent transform = r == null || !r.isValid() ? null
                : r.getStore().getComponent(r, TransformComponent.getComponentType());
        if (transform == null) {
            return "-";
        }
        Vector3d p = transform.getPosition();
        return String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", p.x, p.y, p.z);
    }

    /**
     * Zeigt {@code key} mit dem Text {@code text} vor der Vorderseite des Blocks; ändert sich nur der Text, bleibt
     * das Entity dasselbe.
     */
    void show(World world, int x, int y, int z, Dir facing, AEItemKey key, String text) {
        if (ref != null && !ref.isValid()) {
            ref = null; // von der Engine oder einem Befehl entfernt
        }
        if (key.equals(shownKey)) {
            if (creating) {
                shownText = text; // das bestellte Entity bekommt den neuesten Text
                return;
            }
            if (ref != null) {
                if (!text.equals(shownText)) {
                    shownText = text;
                    Nameplate plate = ref.getStore().getComponent(ref, Nameplate.getComponentType());
                    if (plate != null) {
                        plate.setText(text);
                    }
                }
                return;
            }
        }
        remove(world);
        shownKey = key;
        shownText = text;
        creating = true;
        int expected = generation;
        world.execute(() -> {
            if (expected != generation || !creating) {
                return; // inzwischen entfernt (abgebaut, entladen, anderes Item)
            }
            creating = false;
            try {
                ref = spawn(world, x, y, z, facing, key, shownText == null ? "" : shownText);
            } catch (RuntimeException e) {
                // Item gibt es nicht (mehr) oder die Welt schließt gerade: dann ohne Anzeige
                ref = null;
                shownKey = null;
                shownText = null;
            }
        });
    }

    /** Entfernt die Anzeige (Block abgebaut oder entladen, kein Item, Monitor offline, Anzeige abgeschaltet). */
    void remove(World world) {
        generation++;
        creating = false;
        shownKey = null;
        shownText = null;
        Ref<EntityStore> old = ref;
        ref = null;
        if (old != null && old.isValid()) {
            world.execute(() -> {
                if (old.isValid()) {
                    old.getStore().removeEntity(old, RemoveReason.REMOVE);
                }
            });
        }
    }

    @Nullable
    private static Ref<EntityStore> spawn(World world, int x, int y, int z, Dir facing, AEItemKey key, String text) {
        ComponentType<EntityStore, Marker> marker = markerType;
        if (marker == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Vector3d pos = new Vector3d(x + 0.5 + facing.dx * FRONT_OFFSET,
                y + 0.5 + facing.dy * FRONT_OFFSET - (facing.dy == 0 ? DROP : 0),
                z + 0.5 + facing.dz * FRONT_OFFSET);
        // Blickrichtung eines Entities: (−sin(Gier)·cos(Nick), sin(Nick), −cos(Gier)·cos(Nick)); das Item schaut
        // vom Bildschirm weg
        float pitch = (float) (facing.dy * Math.PI / 2);
        float yaw = facing.dy != 0 ? 0f : (float) Math.atan2(-facing.dx, -facing.dz);
        Rotation3f rotation = new Rotation3f(pitch, yaw, 0f);

        // Ein einzelnes Stück ohne Metadaten und ohne Fall-Animation (wie die Vorschau der Entity-Werkzeuge)
        ItemStack stack = new ItemStack(key.itemId(), 1);
        stack.setOverrideDroppedItemAnimation(true);

        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
        holder.addComponent(ItemComponent.getComponentType(), new ItemComponent(stack));
        holder.addComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(SCALE));
        holder.addComponent(PreventPickup.getComponentType(), PreventPickup.INSTANCE);
        holder.addComponent(PreventItemMerging.getComponentType(), PreventItemMerging.INSTANCE);
        holder.ensureComponent(Intangible.getComponentType());
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(pos, rotation));
        holder.addComponent(HeadRotation.getComponentType(), new HeadRotation(rotation));
        holder.addComponent(Nameplate.getComponentType(), new Nameplate(text));
        holder.addComponent(marker, Marker.INSTANCE);
        return store.addEntity(holder, AddReason.SPAWN);
    }
}
