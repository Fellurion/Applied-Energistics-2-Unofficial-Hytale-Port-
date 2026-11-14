/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.me.service.PathingService#achievementPost, appeng.parts.storagebus.StorageBusPart#checkStorageBusOnInterface).
 */
package de.fellurion.ae2.hytale.advancements;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Netz-Erfolge. AE2 vergibt sie an die Besitzer der Netzknoten; Hytale-Blöcke haben (noch) keinen Besitzer,
 * daher pragmatisch: Spieler in der Nähe des Netzes.
 * <ul>
 *   <li>Kanäle (8/128/2048): Spieler im Umkreis von {@value #CHANNEL_RADIUS} Blöcken eines Knotens eines Netzes,
 *       das so viele Kanäle belegt.</li>
 *   <li>Rekursives Netzwerken: Speicherbus, dessen Vorderseite an einer ME-Schnittstelle (Block oder Kabelvariante)
 *       liegt; Spieler im Umkreis von {@value #RECURSIVE_RADIUS} Blöcken des Busses.</li>
 * </ul>
 */
final class NetworkAdvancementTriggers {

    /** Prüfabstand in AE2-Ticks (2 s). */
    static final int CHECK_INTERVAL_TICKS = 40;
    static final double CHANNEL_RADIUS = 32;
    static final double RECURSIVE_RADIUS = 16;

    private NetworkAdvancementTriggers() {
    }

    static void tick(WorldNetworks nets) {
        if (nets.tickCount() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        Collection<PlayerRef> players = nets.world().getPlayerRefs();
        if (players.isEmpty()) {
            return;
        }
        AdvancementRegistry registry = AdvancementRegistry.get();
        List<Advancement.Criterion> channelCriteria = registry.criteria(Trigger.NETWORK_CHANNELS);
        List<Advancement.Criterion> recursiveCriteria = registry.criteria(Trigger.RECURSIVE_NETWORKING);

        List<PlayerRef> needChannels = new ArrayList<>();
        List<PlayerRef> needRecursive = new ArrayList<>();
        int minChannels = Integer.MAX_VALUE;
        for (PlayerRef p : players) {
            PlayerAdvancements progress = AE2Advancements.progress(p.getUuid());
            for (Advancement.Criterion c : channelCriteria) {
                if (!progress.isDone(c.advancementId())) {
                    minChannels = Math.min(minChannels, c.channels());
                    if (needChannels.isEmpty() || needChannels.getLast() != p) {
                        needChannels.add(p);
                    }
                }
            }
            for (Advancement.Criterion c : recursiveCriteria) {
                if (!progress.isDone(c.advancementId())) {
                    needRecursive.add(p);
                    break;
                }
            }
        }

        if (!needChannels.isEmpty()) {
            for (Grid grid : nets.me().getGrids()) {
                int used = grid.getPathingService().getUsedChannels();
                if (used < minChannels) {
                    continue;
                }
                for (PlayerRef p : needChannels) {
                    if (nearGrid(p.getTransform().getPosition(), grid)) {
                        AE2Advancements.onNetworkChannels(p.getUuid(), used);
                    }
                }
            }
        }

        if (!needRecursive.isEmpty()) {
            for (WorldNetworks.Node bus : nets.nodes()) {
                if (!"storage_bus".equals(bus.kind()) || bus.facing() == null) {
                    continue;
                }
                BlockAccess.Dir f = bus.facing();
                WorldNetworks.Node target = nets.node(bus.x() + f.dx, bus.y() + f.dy, bus.z() + f.dz);
                if (target == null || !"interface".equals(target.kind())) {
                    continue;
                }
                for (PlayerRef p : needRecursive) {
                    if (dist2(p.getTransform().getPosition(), bus.x(), bus.y(), bus.z()) <= RECURSIVE_RADIUS * RECURSIVE_RADIUS) {
                        AE2Advancements.trigger(p.getUuid(), Trigger.RECURSIVE_NETWORKING);
                    }
                }
            }
        }
    }

    private static boolean nearGrid(Vector3d pos, Grid grid) {
        double r2 = CHANNEL_RADIUS * CHANNEL_RADIUS;
        for (GridNode n : grid.getNodes()) {
            long p = n.getPos();
            if (dist2(pos, BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p)) <= r2) {
                return true;
            }
        }
        return false;
    }

    private static double dist2(Vector3d pos, int x, int y, int z) {
        double dx = x + 0.5 - pos.x;
        double dy = y + 0.5 - pos.y;
        double dz = z + 0.5 - pos.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
