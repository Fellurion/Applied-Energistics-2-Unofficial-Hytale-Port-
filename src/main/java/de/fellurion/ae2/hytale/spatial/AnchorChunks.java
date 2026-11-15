/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.server.services.ChunkLoadingService).
 */
package de.fellurion.ae2.hytale.spatial;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;

/**
 * Hält Chunks einer Welt im Auftrag von Spatial Anchors geladen und aktiv.
 *
 * <p>Hytale kennt dafür zwei Dinge: den Zähler {@code WorldChunk.addKeepLoaded()} (ein Chunk mit Zähler &gt; 0 wird
 * nicht entladen – derselbe Weg wie {@code /ae2 keeploaded}) und die „Aktiv“-Zeitgeber des Chunks und jeder seiner
 * 32³-Sektionen: ohne Spieler in der Nähe schaltet die Engine sie nach einigen Sekunden auf „nicht tickend“, die
 * Block-Entities der Sektion werden dann eingelagert und ein ME-Netz darin ginge aus. Deshalb frischt
 * {@link #keepAlive} einmal je Sekunde alle Zeitgeber auf und schaltet wieder ein, was doch stillgelegt wurde.
 *
 * <p>Jeder Anker zählt für sich; ein Chunk wird freigegeben, wenn ihn kein Anker mehr hält. Die Zähler der Engine
 * leben nur im Speicher: mit dem Stopp des Servers ist alles freigegeben. Anders als Minecrafts „forced chunks“
 * werden die Chunks nach einem Neustart nicht von selbst wieder geladen – der Anker arbeitet weiter, sobald sein
 * eigener Chunk geladen ist. Nur auf dem Welt-Thread benutzen.
 */
final class AnchorChunks {

    private final World world;
    /** Chunk → Zahl der Anker, die ihn halten. */
    private final Long2IntOpenHashMap holds = new Long2IntOpenHashMap();
    /** Chunk → das Chunk-Objekt, dessen Zähler erhöht wurde (nach einem Neuladen ist es ein anderes). */
    private final Long2ObjectOpenHashMap<WorldChunk> pinned = new Long2ObjectOpenHashMap<>();
    private long lastKeepAliveTick;
    private boolean keptAlive;

    AnchorChunks(World world) {
        this.world = world;
    }

    /** Ein Anker hält den Chunk ab jetzt. */
    boolean force(long chunk) {
        if (holds.addTo(chunk, 1) == 0) {
            pin(chunk);
        }
        return true;
    }

    /** Ein Anker gibt den Chunk frei; false, wenn niemand ihn hielt. */
    boolean release(long chunk) {
        int n = holds.get(chunk);
        if (n <= 0) {
            return false;
        }
        if (n > 1) {
            holds.put(chunk, n - 1);
            return true;
        }
        holds.remove(chunk);
        WorldChunk wc = pinned.remove(chunk);
        if (wc != null) {
            wc.removeKeepLoaded();
        }
        return true;
    }

    int heldChunks() {
        return holds.size();
    }

    boolean isHeld(long chunk) {
        return holds.get(chunk) > 0;
    }

    /** Höchstens einmal je Sekunde (20 AE2-Ticks): gehaltene Chunks geladen und aktiv halten. */
    void keepAlive(long tick) {
        if (holds.isEmpty()) {
            return;
        }
        if (keptAlive && tick >= lastKeepAliveTick && tick - lastKeepAliveTick < 20) {
            return;
        }
        keptAlive = true;
        lastKeepAliveTick = tick;
        LongIterator it = holds.keySet().iterator();
        while (it.hasNext()) {
            pin(it.nextLong());
        }
    }

    private void pin(long chunk) {
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> store = chunkStore.getStore();
        Ref<ChunkStore> ref = chunkStore.getChunkReference(chunk);
        WorldChunk wc = ref == null || !ref.isValid() ? null : store.getComponent(ref, WorldChunk.getComponentType());
        WorldChunk before = pinned.get(chunk);
        if (wc == null) {
            // Entladen, bevor der Zähler griff (oder nie geladen): der Anker lädt keine fremden Chunks nach
            if (before != null) {
                pinned.remove(chunk);
            }
            return;
        }
        if (wc != before) {
            wc.addKeepLoaded();
            pinned.put(chunk, wc);
        }
        wc.resetKeepAlive();
        wc.resetActiveTimer();
        wake(store, ref);
        // Jede 32³-Sektion hat ihren eigenen Aktiv-Zeitgeber – und in den Sektionen leben die Block-Entities
        int cx = ChunkUtil.xOfChunkIndex(chunk);
        int cz = ChunkUtil.zOfChunkIndex(chunk);
        int lowest = chunkStore.lowestLoadedSectionBelow(cx, cz, 0);
        int highest = chunkStore.highestLoadedSectionAbove(cx, cz, 0);
        for (int cy = lowest; cy <= highest; cy++) {
            Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReference(cx, cy, cz);
            if (sectionRef == null || !sectionRef.isValid()) {
                continue;
            }
            ChunkSection section = store.getComponent(sectionRef, ChunkSection.getComponentType());
            if (section != null) {
                section.resetKeepAlive();
                section.resetActiveTimer();
                wake(store, sectionRef);
            }
        }
    }

    /**
     * Schaltet einen Chunk oder eine Sektion wieder auf „tickend“, falls die Engine sie schon stillgelegt hat –
     * so, wie es {@code ChunkGrid} bei einer Anfrage mit {@code SET_TICKING} tut. Nicht aus dem Tick heraus (das
     * ändert den Store), deshalb als Aufgabe der Welt. Geladen oder erzeugt wird dabei nichts.
     */
    private void wake(Store<ChunkStore> store, Ref<ChunkStore> ref) {
        var nonTicking = ChunkStore.REGISTRY.getNonTickingComponentType();
        if (store.getArchetype(ref).contains(nonTicking)) {
            world.execute(() -> {
                if (ref.isValid()) {
                    store.tryRemoveComponent(ref, nonTicking);
                }
            });
        }
    }
}
