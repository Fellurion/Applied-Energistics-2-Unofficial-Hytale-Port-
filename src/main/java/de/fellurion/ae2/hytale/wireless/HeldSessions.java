/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.AEBaseMenu#broadcastChanges – ruft je Tick ItemMenuHost.tick()).
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Die offenen Terminals in Items (je Spieler höchstens eines) und ihr Takt: etwa alle {@value #INTERVAL_SECONDS} s
 * prüft jede Sitzung auf dem Welt-Thread ihres Spielers Item, Ladung und Reichweite ({@link HeldItemSession#tick}).
 *
 * <p>Kosten: Solange niemand ein solches Terminal offen hat, endet der Takt je Spieler nach einem Blick in eine leere
 * Tabelle.
 */
final class HeldSessions {

    /** AE2 bucht den Leerlaufverbrauch alle 10 Ticks ({@code ItemMenuHost.BUFFER_ENERGY_TICKS}). */
    static final float INTERVAL_SECONDS = 0.5f;

    private static final Map<UUID, HeldItemSession> OPEN = new ConcurrentHashMap<>();

    private HeldSessions() {
    }

    static void setup(JavaPlugin plugin) {
        plugin.getEntityStoreRegistry().registerSystem(new Ticker());
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, e -> {
            HeldItemSession s = OPEN.remove(e.getPlayerRef().getUuid());
            if (s != null) {
                // Der Spieler ist weg: nichts mehr anfassen (der Zelleninhalt steht nach jeder Aktion im Item)
                s.abandon();
            }
        });
    }

    static void add(HeldItemSession session) {
        if (session.playerRef == null) {
            return;
        }
        HeldItemSession old = OPEN.put(session.playerRef.getUuid(), session);
        if (old != null && old != session) {
            old.close();
        }
    }

    static void remove(HeldItemSession session) {
        if (session.playerRef != null) {
            OPEN.remove(session.playerRef.getUuid(), session);
        }
    }

    private static final class Ticker extends DelayedEntitySystem<EntityStore> {

        private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerRef.getComponentType());

        Ticker() {
            super(INTERVAL_SECONDS);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                         @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            if (OPEN.isEmpty()) {
                return;
            }
            PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
            Player player = chunk.getComponent(index, Player.getComponentType());
            if (playerRef == null || player == null) {
                return;
            }
            HeldItemSession session = OPEN.get(playerRef.getUuid());
            if (session == null) {
                return;
            }
            if (session.playerRef != playerRef) {
                // neu verbunden: die alte Sitzung gehört zu einer Verbindung, die es nicht mehr gibt
                OPEN.remove(playerRef.getUuid(), session);
                session.abandon();
                return;
            }
            World world = store.getExternalData().getWorld();
            if (world == null) {
                return;
            }
            try {
                session.tick(chunk.getReferenceTo(index), store, player, world);
            } catch (RuntimeException e) {
                // Ein Fehler in einer Sitzung darf den Welt-Tick nie stören
                OPEN.remove(playerRef.getUuid(), session);
                session.abandon();
            }
        }
    }
}
