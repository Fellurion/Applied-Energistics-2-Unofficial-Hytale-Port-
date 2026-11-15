/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.gadgets;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.iterator.BlockIterator;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * Blickstrahl durch die Welt mit Hytales {@link BlockIterator}: liefert die erste Zelle, die der Test annimmt, samt
 * der Zelle davor (daraus ergibt sich die getroffene Seite) und der Entfernung. Nicht geladene Chunks beenden den
 * Strahl ohne Treffer – geladen wird nichts.
 */
final class WorldRay {

    /** Entscheidet je Zelle, ob der Strahl hier endet. */
    @FunctionalInterface
    interface CellTest {
        boolean stop(int blockId, int fluidId);
    }

    /**
     * Ein Treffer.
     *
     * @param hasPrev  false, wenn schon die Startzelle getroffen wurde (dann ist prev = Treffer)
     * @param distance Entfernung vom Start bis zum Eintritt in die Zelle
     */
    record Hit(int x, int y, int z, int prevX, int prevY, int prevZ, boolean hasPrev, double distance, int blockId,
               int fluidId) {
    }

    private WorldRay() {
    }

    /** Zustand eines Laufs (der Iterator ruft je Zelle zurück). */
    private static final class Walk {
        final ChunkStore chunks;
        final Store<ChunkStore> store;
        final CellTest test;
        final double ox, oy, oz;
        @Nullable
        BlockSection blocks;
        @Nullable
        FluidSection fluids;
        int secX, secY, secZ;
        boolean hasSection;
        int prevX, prevY, prevZ;
        boolean hasPrev;
        @Nullable
        Hit hit;

        Walk(World world, CellTest test, Vector3d origin) {
            this.chunks = world.getChunkStore();
            this.store = chunks.getStore();
            this.test = test;
            this.ox = origin.x;
            this.oy = origin.y;
            this.oz = origin.z;
        }

        boolean visit(int x, int y, int z, double px, double py, double pz) {
            if (y < 0 || y >= 320) {
                return false;
            }
            if (!hasSection || !ChunkUtil.isSameChunkSection(x, y, z, secX, secY, secZ)) {
                Ref<ChunkStore> ref = chunks.getChunkSectionReferenceAtBlock(x, y, z);
                if (ref == null || !ref.isValid()) {
                    return false; // nicht geladen
                }
                blocks = store.getComponent(ref, BlockSection.getComponentType());
                fluids = store.getComponent(ref, FluidSection.getComponentType());
                secX = x;
                secY = y;
                secZ = z;
                hasSection = true;
            }
            if (blocks == null) {
                return false;
            }
            int block = blocks.get(x, y, z);
            int fluid = fluids == null ? 0 : fluids.getFluidId(x, y, z);
            if (test.stop(block, fluid)) {
                double dx = x + px - ox, dy = y + py - oy, dz = z + pz - oz;
                hit = new Hit(x, y, z, hasPrev ? prevX : x, hasPrev ? prevY : y, hasPrev ? prevZ : z, hasPrev,
                        Math.sqrt(dx * dx + dy * dy + dz * dz), block, fluid);
                return false;
            }
            prevX = x;
            prevY = y;
            prevZ = z;
            hasPrev = true;
            return true;
        }
    }

    /**
     * Verfolgt den Strahl bis {@code maxDistance}.
     *
     * @return der erste Treffer, oder null (nichts getroffen, Chunk nicht geladen, Richtung unbrauchbar)
     */
    @Nullable
    static Hit cast(World world, Vector3d origin, Vector3d direction, double maxDistance, CellTest test) {
        double length = direction.length();
        if (!(length > 1e-6) || !Double.isFinite(length) || !origin.isFinite() || maxDistance <= 0) {
            return null;
        }
        Walk walk = new Walk(world, test, origin);
        try {
            BlockIterator.iterate(origin.x, origin.y, origin.z, direction.x / length, direction.y / length,
                    direction.z / length, maxDistance, (x, y, z, px, py, pz, qx, qy, qz) -> walk.visit(x, y, z, px, py, pz));
        } catch (RuntimeException e) {
            return null; // der Iterator lehnt den Strahl ab
        }
        return walk.hit;
    }
}
