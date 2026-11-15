/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.networktool.NetworkStatusMenu#broadcastChanges).
 */
package de.fellurion.ae2.hytale.tools;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.DelayedSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Offene Seiten der Werkzeuge, die sich von selbst auffrischen (Netzwerkstatus). Ein eigener, langsamer Takt je Welt
 * ruft sie auf dem Welt-Thread; ohne offene Seite kostet er nichts.
 */
final class ToolPages {

    /** AE2 schickt den Netzwerkstatus alle 16 Ticks neu. */
    static final float INTERVAL_SECONDS = 0.8f;

    /** Eine Seite, die sich auffrischt. */
    interface Refreshable {
        /** Welt der Seite: gerufen wird nur vom Takt (und Thread) dieser Welt. */
        World world();

        /** @return false, wenn die Seite zu ist und nicht mehr gerufen werden will */
        boolean refreshTick();
    }

    private static final Set<Refreshable> OPEN = ConcurrentHashMap.newKeySet();

    private ToolPages() {
    }

    static void track(Refreshable page) {
        OPEN.add(page);
    }

    static void untrack(Refreshable page) {
        OPEN.remove(page);
    }

    /** Takt der offenen Seiten; läuft im ChunkStore der Welt, also auf dem Welt-Thread. */
    static final class Refresher extends DelayedSystem<ChunkStore> {

        Refresher() {
            super(INTERVAL_SECONDS);
        }

        @Override
        public void delayedTick(float dt, int systemIndex, @Nonnull Store<ChunkStore> store) {
            if (OPEN.isEmpty()) {
                return;
            }
            World world = store.getExternalData().getWorld();
            for (Refreshable page : OPEN) {
                if (page.world() != world) {
                    continue;
                }
                boolean keep;
                try {
                    keep = page.refreshTick();
                } catch (RuntimeException e) {
                    keep = false; // eine kaputte Seite darf den Takt nicht stören
                }
                if (!keep) {
                    OPEN.remove(page);
                }
            }
        }
    }
}
