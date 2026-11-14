/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.security;

import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Besitzer eines ME-Netzes ({@link OwnerRanking}), je Netz zwischengespeichert. Neu berechnet wird nur, wenn sich
 * die Blöcke der Welt geändert haben ({@link WorldNetworks#topologyVersion()}) oder das Netz gewachsen/geschrumpft
 * ist ({@link Grid#size()}): Zusammenwachsen zweier Netze passiert erst im Takt nach dem Platzieren.
 *
 * <p>Blöcke ohne Netzanschluss (oder noch nicht angemeldet) haben ihren eigenen Besitzer.
 */
final class NetworkOwners {

    private record Cached(long version, int size, @Nullable Owner owner) {
    }

    /** Schwach gehalten: aufgelöste Netze verschwinden von selbst. Zugriff von mehreren Welt-Threads. */
    private static final Map<Grid, Cached> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private NetworkOwners() {
    }

    /** Besitzer des Netzes, an dem {@code node} hängt (sonst des Blocks selbst); null = niemand. */
    @Nullable
    static Owner of(WorldNetworks nets, WorldNetworks.Node node) {
        GridNode g = node.gridNode();
        Grid grid = g == null ? null : g.getGrid();
        if (grid == null) {
            BlockOwners.Info info = BlockOwners.of(nets, node);
            return info == null ? null : owner(info.owner(), info.name());
        }
        long version = nets.topologyVersion();
        int size = grid.size();
        Cached c = CACHE.get(grid);
        if (c != null && c.version() == version && c.size() == size) {
            return c.owner();
        }
        Owner o = compute(nets, grid);
        CACHE.put(grid, new Cached(version, size, o));
        return o;
    }

    /** true, wenn der Block an einem Netz hängt (Meldung „dieses ME-Netz“ statt „dieser Block“). */
    static boolean networked(WorldNetworks.Node node) {
        GridNode g = node.gridNode();
        return g != null && g.getGrid() != null;
    }

    /** Vergisst alles (z. B. nach Änderungen an Besitzern per Befehl). */
    static void invalidate() {
        CACHE.clear();
    }

    @Nullable
    private static Owner compute(WorldNetworks nets, Grid grid) {
        List<OwnerRanking.Candidate> candidates = new ArrayList<>();
        for (GridNode gn : grid.getNodes()) {
            long p = gn.getPos();
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            WorldNetworks.Node n = nets.node(x, y, z);
            if (n == null) {
                continue;
            }
            BlockOwners.Info info = BlockOwners.of(nets, n);
            if (info != null) {
                candidates.add(new OwnerRanking.Candidate(info.owner(), info.name(), info.since(), gn.isController(),
                        x, y, z));
            }
        }
        OwnerRanking.Candidate best = OwnerRanking.pick(candidates);
        return best == null ? null : owner(best.owner(), best.name());
    }

    static Owner owner(java.util.UUID uuid, @Nullable String storedName) {
        String known = AE2Security.knownName(uuid);
        return new Owner(uuid, Owner.displayName(uuid, known != null ? known : storedName));
    }
}
