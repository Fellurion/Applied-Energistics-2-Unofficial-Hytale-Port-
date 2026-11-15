/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.spatial;

import de.fellurion.ae2.core.spatial.SpatialPylons;
import de.fellurion.ae2.hytale.WorldNetworks;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Was Spatial IO je Welt braucht: die Pylone der Welt samt ihrer Linien und Netz-Regionen ({@link SpatialPylons})
 * und die Chunks, die Spatial Anchors geladen halten ({@link AnchorChunks}). Hängt am {@link WorldNetworks} der Welt
 * und verschwindet mit ihm.
 */
final class SpatialWorld {

    private static final Map<WorldNetworks, SpatialWorld> BY_NETS = new WeakHashMap<>();

    static SpatialWorld of(WorldNetworks nets) {
        synchronized (BY_NETS) {
            return BY_NETS.computeIfAbsent(nets, SpatialWorld::new);
        }
    }

    final SpatialPylons pylons = new SpatialPylons();
    final AnchorChunks anchors;
    /** Ein Transfer läuft gerade (oder ist für diesen Tick vorgemerkt): kein zweiter aus derselben Welt. */
    boolean transferring;

    private SpatialWorld(WorldNetworks nets) {
        this.anchors = new AnchorChunks(nets.world());
    }
}
