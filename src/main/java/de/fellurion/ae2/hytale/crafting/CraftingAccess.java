/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.crafting;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Einstieg ins Autocrafting für Terminals und Blöcke (Craft-Anfrage, Status, Pattern-Kodierung und -Zugriff). */
public final class CraftingAccess {

    private CraftingAccess() {
    }

    /** Seiten, die sich regelmäßig aktualisieren (CPU-Status, laufende Berechnung). */
    interface Refreshable {
        boolean refreshTick();

        /** Netz-Registry der Seite: getickt wird nur vom Takt (und Thread) dieser Welt. */
        WorldNetworks networks();
    }

    private static final Set<Refreshable> OPEN = ConcurrentHashMap.newKeySet();

    static void track(Refreshable page) {
        OPEN.add(page);
    }

    static void untrack(Refreshable page) {
        OPEN.remove(page);
    }

    /** Vom Netz-Tick aufgerufen (Welt-Thread). */
    public static void refreshOpen(WorldNetworks nets) {
        for (Refreshable p : OPEN) {
            if (p.networks() != nets) {
                continue;
            }
            try {
                if (!p.refreshTick()) {
                    OPEN.remove(p);
                }
            } catch (RuntimeException e) {
                OPEN.remove(p);
            }
        }
    }

    @Nullable
    static Grid grid(WorldNetworks nets, int x, int y, int z) {
        WorldNetworks.Node n = nets.node(x, y, z);
        GridNode g = n == null ? null : n.gridNode();
        return g == null ? null : g.getGrid();
    }

    static boolean active(WorldNetworks nets, int x, int y, int z) {
        WorldNetworks.Node n = nets.node(x, y, z);
        return n != null && n.gridNode() != null && n.gridNode().isActive();
    }

    /** Items, die das Netz des Blocks bei x/y/z herstellen kann (Pattern vorhanden). */
    public static Set<AEItemKey> craftables(WorldNetworks nets, int x, int y, int z) {
        Grid grid = grid(nets, x, y, z);
        Set<AEItemKey> out = new HashSet<>();
        if (grid == null) {
            return out;
        }
        for (AEKey k : grid.getCraftingService().getCraftables(k -> k instanceof AEItemKey)) {
            if (k instanceof AEItemKey ik) {
                out.add(ik);
            }
        }
        return out;
    }

    /** Öffnet die Craft-Anfrage (Menge wählen, berechnen, starten). */
    public static void openCraftRequest(Player player, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                        WorldNetworks nets, int x, int y, int z, AEItemKey what) {
        player.getPageManager().openCustomPage(ref, store, new CraftRequestPage(playerRef, nets, x, y, z, what));
    }

    /** Öffnet den Crafting-Status (CPUs, Aufträge, Abbrechen). */
    public static void openStatus(Player player, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                  WorldNetworks nets, int x, int y, int z) {
        player.getPageManager().openCustomPage(ref, store, new CraftingStatusPage(playerRef, nets, x, y, z));
    }

    /** Öffnet das Pattern-Kodierterminal. */
    public static void openPatternEncoding(Player player, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                           WorldNetworks nets, int x, int y, int z) {
        player.getPageManager().openCustomPage(ref, store, new PatternEncodingPage(playerRef, nets, x, y, z));
    }

    /** Öffnet das Pattern-Zugriffsterminal (alle Pattern Provider des Netzes aus der Ferne bedienen). */
    public static void openPatternAccess(Player player, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                                         WorldNetworks nets, int x, int y, int z) {
        player.getPageManager().openCustomPage(ref, store, new PatternAccessPage(playerRef, nets, x, y, z));
    }
}
