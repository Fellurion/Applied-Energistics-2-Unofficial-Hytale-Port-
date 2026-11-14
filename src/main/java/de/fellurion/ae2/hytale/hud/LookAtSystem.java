/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.hud;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import de.fellurion.ae2.hytale.WorldNetworks;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verfolgt je Spieler, welchen AE2-Block er ansieht, und hält die Blockinfo ({@link LookAtHud}) aktuell.
 *
 * <p>Kosten: Das System läuft nur alle {@value #INTERVAL_SECONDS} s (≈ 4–5 Welt-Ticks). In einer Welt ohne
 * AE2-Blöcke passiert nichts weiter; sonst ein Strahl über höchstens {@value #MAX_DISTANCE} Blöcke
 * ({@link TargetUtil#getTargetBlock}, liest nur Blocksektionen) und ein Positions-Lookup in {@link WorldNetworks}.
 * Der Inhalt wird beim Zielwechsel und danach alle {@value #REFRESH_RUNS} Durchläufe (≈ 0,3 s) aus
 * zwischengespeicherten Kernwerten gebaut; gesendet wird nur, was sich geändert hat.
 */
final class LookAtSystem extends DelayedEntitySystem<EntityStore> {

    static final float INTERVAL_SECONDS = 0.15f;
    static final double MAX_DISTANCE = 8.0;
    static final int REFRESH_RUNS = 2;

    private static final Map<UUID, Viewer> VIEWERS = new ConcurrentHashMap<>();

    private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerRef.getComponentType());

    LookAtSystem() {
        super(INTERVAL_SECONDS);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    /** Spieler hat den Server verlassen. */
    static void forget(UUID playerId) {
        VIEWERS.remove(playerId);
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
        Player player = chunk.getComponent(index, Player.getComponentType());
        if (playerRef == null || player == null) {
            return;
        }
        Viewer viewer = VIEWERS.get(playerRef.getUuid());
        World world = store.getExternalData().getWorld();
        WorldNetworks nets = world == null ? null : WorldNetworks.of(world);
        // Keine AE2-Blöcke in der Welt oder eine Seite offen (Terminal, Schale …): kein Strahl, Anzeige weg
        WorldNetworks.Node node = nets == null || nets.size() == 0 || player.getPageManager().getCustomPage() != null
                ? null : target(nets, chunk.getReferenceTo(index), store);
        if (node == null) {
            if (viewer != null) {
                viewer.lookAway(player);
            }
            return;
        }
        if (viewer == null || viewer.playerRef != playerRef) {
            // erster Blick auf AE2 oder neu verbunden (neue Verbindung = neues PlayerRef und neue HUDs)
            viewer = new Viewer(playerRef);
            VIEWERS.put(playerRef.getUuid(), viewer);
        }
        try {
            viewer.look(player, nets, node);
        } catch (RuntimeException e) {
            // Die Anzeige darf den Welt-Tick nie stören
            viewer.lookAway(player);
        }
    }

    /** AE2-Block im Blick (erster nicht-leerer Block auf dem Strahl), sonst null. */
    @Nullable
    private static WorldNetworks.Node target(WorldNetworks nets, Ref<EntityStore> ref, Store<EntityStore> store) {
        try {
            Vector3i hit = TargetUtil.getTargetBlock(ref, MAX_DISTANCE, store);
            return hit == null ? null : nets.node(hit.x, hit.y, hit.z);
        } catch (RuntimeException e) {
            // Spieler ohne Transform/Kopfdrehung (gerade beim Weltwechsel) oder Chunk nicht geladen
            return null;
        }
    }

    /** Zustand eines Spielers: sein HUD, das aktuelle Ziel, Sprache und Zwischenspeicher. */
    private static final class Viewer {
        private final PlayerRef playerRef;
        private final HudContext context = new HudContext();
        @Nullable
        private LookAtHud hud;
        @Nullable
        private WorldNetworks.Node target;
        private int countdown;
        /** Das aktuelle Ziel bekommt keine Anzeige (Deko-Block). */
        private boolean suppressed;

        Viewer(PlayerRef playerRef) {
            this.playerRef = playerRef;
        }

        void look(Player player, WorldNetworks nets, WorldNetworks.Node node) {
            LookAtHud h = ensureHud(player);
            if (node == target && (suppressed || h.isVisible() && --countdown > 0)) {
                return;
            }
            countdown = REFRESH_RUNS;
            if (node != target) {
                target = node;
                context.reset(AE2Hud.localeOf(playerRef), nets);
            }
            LookInfo info;
            if (!"quartz_glass".equals(node.kind())
                    && !de.fellurion.ae2.hytale.security.AE2Security.canUse(nets, node, playerRef)) {
                var owner = de.fellurion.ae2.hytale.security.AE2Security.ownerOf(nets, node);
                info = InfoProviders.locked(context, node, owner == null ? null : owner.name());
            } else {
                info = InfoProviders.describe(context, node);
            }
            suppressed = info == null;
            if (info == null) {
                h.hide();
            } else {
                h.show(info);
            }
        }

        void lookAway(Player player) {
            LookAtHud h = hud;
            if (target == null && (h == null || !h.isVisible())) {
                return; // schon ausgeblendet: der Normalfall, kostet nichts
            }
            target = null;
            suppressed = false;
            context.forgetCaches();
            if (h != null && player.getHudManager().getCustomHud(AE2Hud.HUD_KEY) == h) {
                h.hide();
            }
        }

        /** Legt das HUD an bzw. neu an, wenn der Server die HUDs zurückgesetzt hat (Weltwechsel, Respawn). */
        private LookAtHud ensureHud(Player player) {
            HudManager huds = player.getHudManager();
            CustomUIHud current = huds.getCustomHud(AE2Hud.HUD_KEY);
            LookAtHud h = hud;
            if (h == null || current != h) {
                h = new LookAtHud(playerRef);
                hud = h;
                target = null;
                suppressed = false;
                huds.addCustomHud(playerRef, h);
            }
            return h;
        }
    }
}
