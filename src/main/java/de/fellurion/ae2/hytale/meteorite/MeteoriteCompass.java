/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.misc.MeteoriteCompassItem, appeng.server.services.compass.ServerCompassService).
 */
package de.fellurion.ae2.hytale.meteorite;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import org.joml.Vector3d;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Meteoriten-Kompass: Wer ihn in der Hand hält, bekommt den nächsten Meteoriten, in dem noch ein Mysterious Cube
 * liegt, als Marker in Hytales Kompassleiste und auf der Karte (Reichweite wie in AE2: rund 2768 Blöcke). Ist der
 * Cube abgebaut, zeigt der Kompass auf den nächsten Meteoriten.
 *
 * <p>Hytale hat keine drehbare Kompassnadel am Item; die Kompassleiste des Spiels übernimmt die Richtungsanzeige.
 * Die Marker liefert ein Karten-Thread, deshalb liegen die Ziele in einer nebenläufigen Tabelle.
 */
final class MeteoriteCompass {

    static final String ITEM = "AE2_Meteorite_Compass";
    /** AE2: 173 Chunks zu 16 Blöcken. */
    private static final double RANGE = 2768;
    private static final String PROVIDER = "ae2_meteorite_compass";

    private record Target(String world, int x, int y, int z) {
    }

    /** Ziel je Spieler-Objekt (der Karten-Thread bekommt den Spieler, nicht seine UUID). */
    private static final Map<Player, Target> TARGETS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** Spieler, denen schon gesagt wurde, dass nichts in Reichweite ist (bis sie den Kompass weglegen). */
    private static final Set<UUID> TOLD_NONE = ConcurrentHashMap.newKeySet();
    private static final Set<String> WORLDS_WITH_PROVIDER = ConcurrentHashMap.newKeySet();

    private MeteoriteCompass() {
    }

    /** Einmal je Sekunde und Welt (Welt-Thread): Ziele der Spieler mit Kompass in der Hand aktualisieren. */
    static void tick(World world) {
        if (WORLDS_WITH_PROVIDER.add(world.getName())) {
            world.getWorldMapManager().addMarkerProvider(PROVIDER, (w, player, collector) -> {
                Target t = TARGETS.get(player);
                if (t != null && t.world().equals(w.getName())) {
                    collector.addIgnoreViewDistance(new MapMarkerBuilder("ae2_meteorite", "AE2_Meteorite.png",
                            new Transform(t.x() + 0.5, t.y(), t.z() + 0.5))
                            .withName(Message.translation("ae2meteor.compass.marker"))
                            .withCompassImage("AE2_MeteoriteCompass.png").build());
                }
            });
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        MeteoriteRegistry registry = MeteoriteRegistry.of(world.getName());
        for (PlayerRef player : world.getPlayerRefs()) {
            UUID id = player.getUuid();
            Ref<EntityStore> ref = player.getReference();
            ItemStack held = ref != null && ref.isValid() ? InventoryComponent.getItemInHand(store, ref) : null;
            Player entity = ref != null && ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
            if (entity == null) {
                continue;
            }
            if (held == null || held.isEmpty() || !ITEM.equals(held.getItemId()) || player.getTransform() == null) {
                TARGETS.remove(entity);
                TOLD_NONE.remove(id);
                continue;
            }
            Vector3d p = player.getTransform().getPosition();
            MeteoriteRegistry.Entry nearest = registry.nearest(p.x, p.z, true, RANGE);
            if (nearest == null) {
                TARGETS.remove(entity);
                if (TOLD_NONE.add(id)) {
                    NotificationUtil.sendNotification(player.getPacketHandler(), Message.translation("ae2meteor.compass.none"),
                            NotificationStyle.Default);
                }
            } else {
                TOLD_NONE.remove(id);
                TARGETS.put(entity, new Target(world.getName(), nearest.x(), nearest.y(), nearest.z()));
            }
        }
    }

    static void forget(UUID player) {
        TOLD_NONE.remove(player);
    }
}
