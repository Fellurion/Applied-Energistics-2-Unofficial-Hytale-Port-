/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.spatial.SpatialStorageHelper#swapRegions und appeng.spatial.CachedPlane).
 */
package de.fellurion.ae2.hytale.spatial;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.blocktype.component.BlockPhysics;
import com.hypixel.hytale.server.core.entity.entities.player.windows.WindowManager;
import com.hypixel.hytale.server.core.modules.block.BlockEntity;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.blockanimation.BlockAnimationModule;
import com.hypixel.hytale.server.core.modules.blockhealth.BlockHealthModule;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.PrefabBuffer;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.PrefabBufferBlockEntry;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import de.fellurion.ae2.core.spatial.SpatialStoragePlot;
import de.fellurion.ae2.hytale.meteorite.BuildProtection;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Tauscht die Blöcke eines Quaders der Welt mit dem Inhalt eines Plots.
 *
 * <p>AE2 tauscht zwei gleich große Gebiete zweier Welten ({@code CachedPlane.swap}); hier ist die Gegenseite die
 * Plot-Datei. Ablauf – alles in einem Stück auf dem Welt-Thread, außerhalb der Store-Verarbeitung:
 * <ol>
 *   <li><b>Prüfen</b> (ändert nichts): Bauschutz, alle betroffenen Sektionen geladen (ein Gebiet, das über die Welt
 *       hinausragt, ist es nie), alter Plot lesbar, kein Spieler würde eingemauert;</li>
 *   <li><b>Lesen</b>: Block, Rotation, Füllteil, Stützwert und Flüssigkeit jeder Stelle; Stellen mit gesperrten
 *       Blöcken ({@link MoveRules}) und mit Mehrfachblöcken, die über den Rand ragen, werden übersprungen;</li>
 *   <li><b>Entnehmen</b> der Block-Entities so, wie die Engine sie beim Entladen eines Chunks entnimmt
 *       ({@code BlockEntity.takeBlockEntity}, Grund {@code UNLOAD}): nichts fällt heraus, kein „abgebaut“; AE2-Geräte
 *       schreiben dabei ihren Zustand in ihre Komponente und verlassen ihr Netz;</li>
 *   <li><b>Sichern</b>: der neue Plot-Inhalt wird als Temp-Datei geschrieben, auf die Platte gezwungen und über die
 *       alte Datei umbenannt. Scheitert das, kommen die entnommenen Block-Entities zurück und nichts ist passiert;</li>
 *   <li><b>Setzen</b> des alten Plot-Inhalts in die Welt, Block-Entities mit dem Grund {@code LOAD} wie beim Laden
 *       eines Chunks – AE2-Blöcke melden sich im nächsten Tick wieder an.</li>
 * </ol>
 * Zwischen zwei Ticks gilt damit immer: jeder Block steht entweder in der Welt oder in der Plot-Datei. Geschrieben
 * wird direkt in die Sektionen (wie Hytales {@code BlockSelection.place}), ohne Partikel, Töne, Drops oder
 * Nachbar-Aktualisierung.
 *
 * <p>Abweichungen von AE2: Spieler und andere Entities im Gebiet werden nicht mitversetzt (es gibt keine
 * Speicher-Dimension, in die sie könnten) – würde ein Spieler nach dem Tausch in einem festen Block stecken, wird der
 * Transfer abgelehnt. Geplante Block-Ticks wandern nicht mit; tickende Blöcke und Flüssigkeiten werden nach dem Setzen
 * neu angestoßen.
 * Nachbarn am Rand bekommen keine Block-Aktualisierung.
 */
final class RegionSwap {

    /** Größter Füllteil-Abstand, den Hytale speichern kann (5 Bit mit Vorzeichen je Achse). */
    private static final int MAX_FILLER_REACH = 16;

    /** Warum ein Tausch nicht stattfindet: Schlüssel {@code ae2spatial.refuse.<key>} und Platzhalter. */
    record Refusal(String key, Object... params) {
    }

    /** Messwerte eines Tauschs. */
    static final class Stats {
        int volume;
        int blocksOut;
        int blocksIn;
        int entitiesOut;
        int entitiesIn;
        int skipped;
        long fileBytes;
        long checkNanos;
        long scanNanos;
        long takeNanos;
        long saveNanos;
        long placeNanos;
        long totalNanos;
    }

    /** Eine geladene 32³-Sektion mit allem, was beim Schreiben gebraucht wird. */
    private static final class Sec {
        Ref<ChunkStore> ref;
        int cx, cy, cz;
        BlockSection blocks;
        ChunkSection section;
        @Nullable
        BlockComponentSection components;
        @Nullable
        FluidSection fluids;
        @Nullable
        BlockPhysics physics;
        @Nullable
        HeightmapColumn heightmap;
        @Nullable
        WorldChunk chunk;
        boolean readBlocks;
        boolean readFluids;
        boolean hadTicking;
        boolean hasHealth;
        boolean hasAnimation;
        boolean changed;
    }

    private final SpatialStore store;
    private final World world;
    private final ChunkStore chunkStore;
    private final Store<ChunkStore> st;
    private final MoveRules rules;
    private final int x0, y0, z0, sx, sy, sz;
    private final int scx0, scy0, scz0, nx, ny, nz;
    private Sec[] secs;

    private RegionSwap(SpatialStore store, MoveRules rules, World world, int x0, int y0, int z0, int sx, int sy, int sz) {
        this.store = store;
        this.rules = rules;
        this.world = world;
        this.chunkStore = world.getChunkStore();
        this.st = chunkStore.getStore();
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.scx0 = ChunkUtil.chunkCoordinate(x0);
        this.scy0 = ChunkUtil.chunkCoordinate(y0);
        this.scz0 = ChunkUtil.chunkCoordinate(z0);
        this.nx = ChunkUtil.chunkCoordinate(x0 + sx - 1) - scx0 + 1;
        this.ny = ChunkUtil.chunkCoordinate(y0 + sy - 1) - scy0 + 1;
        this.nz = ChunkUtil.chunkCoordinate(z0 + sz - 1) - scz0 + 1;
    }

    /**
     * Tauscht das Gebiet {@code x0..x0+sx-1} (ebenso y, z) mit dem Inhalt von {@code plot}.
     *
     * @param fresh  der Plot ist neu und hat noch keinen Inhalt
     * @param portX  Standort des auslösenden Geräts (für den Bauschutz)
     * @param stats  nimmt Messwerte auf, oder null
     * @return null bei Erfolg, sonst der Grund – dann ist nichts verändert
     */
    @Nullable
    static Refusal swap(SpatialStore store, MoveRules rules, World world, SpatialStoragePlot plot, boolean fresh,
                        int x0, int y0, int z0, int sx, int sy, int sz, int portX, int portZ, @Nullable Stats stats) {
        long start = System.nanoTime();
        Stats s = stats == null ? new Stats() : stats;
        RegionSwap swap = new RegionSwap(store, rules, world, x0, y0, z0, sx, sy, sz);
        Refusal refusal = swap.run(plot, fresh, portX, portZ, s);
        s.totalNanos = System.nanoTime() - start;
        return refusal;
    }

    // ------------------------------------------------------------------ Ablauf

    @Nullable
    private Refusal run(SpatialStoragePlot plot, boolean fresh, int portX, int portZ, Stats stats) {
        long t = System.nanoTime();
        // nur auf dem Welt-Thread und nicht aus einem System heraus: es werden Block-Entities entnommen und eingesetzt
        st.assertThread();
        st.assertWriteProcessing();
        long volumeLong = (long) sx * sy * sz;
        if (sx < 1 || sy < 1 || sz < 1 || volumeLong > 128L * 128L * 128L) {
            return new Refusal("size");
        }
        int volume = (int) volumeLong;
        stats.volume = volume;
        if (!BuildProtection.deviceMayModify(world, portX, portZ, x0, z0, x0 + sx - 1, z0 + sz - 1)) {
            return new Refusal("protected");
        }
        if (!loadSections()) {
            return new Refusal("notLoaded");
        }

        // --- alter Inhalt des Plots (kommt in die Welt)
        int[] inBlock = null;
        int[] inMeta = null;
        int[] inFluid = null;
        Int2ObjectOpenHashMap<Holder<ChunkStore>> inEntities = new Int2ObjectOpenHashMap<>();
        Path file = store.plotFile(plot.getId());
        if (!fresh && Files.isRegularFile(file)) {
            PrefabBuffer old;
            try {
                old = PlotFile.read(file, plot);
            } catch (IOException | RuntimeException e) {
                store.warn("AE2: spatial plot " + plot.getId() + " cannot be read from " + file, e);
                return new Refusal("plotUnreadable", "id", plot.getId());
            }
            int[][] dense = new int[3][];
            dense[0] = new int[volume];
            dense[1] = new int[volume];
            boolean[] bad = {false};
            old.newAccess().forEachRaw((cx, cz, blocks, o) -> true,
                    (IPrefabBuffer.RawBlockConsumer<Object>) (px, py, pz, mask, blockId, chance, holder, support,
                                                               rotation, filler, o) -> {
                        if (px < 0 || py < 0 || pz < 0 || px >= sx || py >= sy || pz >= sz) {
                            bad[0] = true;
                            return;
                        }
                        int i = index(px, py, pz);
                        dense[0][i] = blockId;
                        dense[1][i] = meta(rotation, filler, support);
                        if (holder != null) {
                            inEntities.put(i, holder);
                        }
                    },
                    (IPrefabBuffer.FluidConsumer<Object>) (px, py, pz, fluidId, level, o) -> {
                        if (px < 0 || py < 0 || pz < 0 || px >= sx || py >= sy || pz >= sz) {
                            bad[0] = true;
                            return;
                        }
                        if (fluidId != 0 && (level & 15) != 0) {
                            if (dense[2] == null) {
                                dense[2] = new int[volume];
                            }
                            dense[2][index(px, py, pz)] = fluid(fluidId, level);
                        }
                    }, null, null);
            if (bad[0]) {
                store.warn("AE2: spatial plot file " + file + " holds blocks outside of its size", null);
                return new Refusal("plotUnreadable", "id", plot.getId());
            }
            inBlock = dense[0];
            inMeta = dense[1];
            inFluid = dense[2];
        } else if (!fresh) {
            store.warn("AE2: spatial plot " + plot.getId() + " has no file " + file + " - treating it as empty", null);
        }
        stats.checkNanos = System.nanoTime() - t;
        t = System.nanoTime();

        // --- Welt lesen
        int[] outBlock = new int[volume];
        int[] outMeta = new int[volume];
        int[][] outFluidRef = new int[1][];
        BitSet skip = new BitSet(volume);
        IntArrayList entityCells = new IntArrayList();
        scan(inBlock, outBlock, outMeta, outFluidRef, skip, entityCells);
        int[] outFluid = outFluidRef[0];
        stats.skipped = skip.cardinality();
        stats.scanNanos = System.nanoTime() - t;

        Refusal buried = playerWouldBeBuried(inBlock, skip);
        if (buried != null) {
            return buried;
        }

        // --- Block-Entities entnehmen (ab hier wird verändert)
        t = System.nanoTime();
        Int2ObjectOpenHashMap<Holder<ChunkStore>> outEntities = new Int2ObjectOpenHashMap<>();
        try {
            for (int n = 0; n < entityCells.size(); n++) {
                int i = entityCells.getInt(n);
                if (skip.get(i)) {
                    continue;
                }
                Holder<ChunkStore> holder = take(i);
                if (holder != null) {
                    outEntities.put(i, holder);
                }
            }
        } catch (RuntimeException e) {
            store.warn("AE2: spatial transfer of plot " + plot.getId() + " failed while taking block entities", e);
            putBack(outEntities);
            return new Refusal("failed");
        }
        stats.takeNanos = System.nanoTime() - t;

        // --- neuen Plot-Inhalt sichern
        t = System.nanoTime();
        Path tmp = store.tempFile(plot.getId());
        try {
            PrefabBuffer content = buildContent(outBlock, outMeta, outFluid, outEntities, inBlock, inMeta, inFluid,
                    inEntities, skip, stats);
            stats.fileBytes = PlotFile.write(tmp, plot, content);
            SpatialStore.move(tmp, file);
        } catch (IOException | RuntimeException e) {
            store.warn("AE2: spatial plot " + plot.getId() + " could not be saved - nothing was transferred", e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // bleibt liegen und wird beim nächsten Start entfernt
            }
            putBack(outEntities);
            return new Refusal("saveFailed");
        }
        stats.entitiesOut = outEntities.size();
        stats.saveNanos = System.nanoTime() - t;

        // --- alten Plot-Inhalt in die Welt setzen: darf nicht mehr abbrechen
        t = System.nanoTime();
        place(outBlock, outMeta, outFluid, inBlock, inMeta, inFluid, inEntities, skip, stats);
        finish();
        stats.placeNanos = System.nanoTime() - t;
        return null;
    }

    // ------------------------------------------------------------------ Hilfen

    /** Position im Plot → Index der dichten Felder (Spalte für Spalte, y zuletzt). */
    private int index(int dx, int dy, int dz) {
        return (dx * sz + dz) * sy + dy;
    }

    private static int meta(int rotation, int filler, int support) {
        return (rotation & 0xFF) | (filler & 0xFFFF) << 8 | (support & 0xF) << 24;
    }

    private static int rotation(int meta) {
        return meta & 0xFF;
    }

    private static int filler(int meta) {
        return meta >>> 8 & 0xFFFF;
    }

    private static int support(int meta) {
        return meta >>> 24 & 0xF;
    }

    private static int fluid(int fluidId, int level) {
        return fluidId << 4 | (level & 15);
    }

    private Sec sec(int x, int y, int z) {
        return secs[((ChunkUtil.chunkCoordinate(x) - scx0) * nz + (ChunkUtil.chunkCoordinate(z) - scz0)) * ny
                + (ChunkUtil.chunkCoordinate(y) - scy0)];
    }

    private boolean inside(int x, int y, int z) {
        return x >= x0 && y >= y0 && z >= z0 && x < x0 + sx && y < y0 + sy && z < z0 + sz;
    }

    /** Sammelt alle Sektionen des Gebiets; false, wenn eine nicht geladen ist (geladen wird nie). */
    private boolean loadSections() {
        Sec[] list = new Sec[nx * ny * nz];
        var healthModule = BlockHealthModule.get();
        var animationModule = BlockAnimationModule.get();
        for (int a = 0; a < nx; a++) {
            for (int c = 0; c < nz; c++) {
                for (int b = 0; b < ny; b++) {
                    Ref<ChunkStore> ref = chunkStore.getChunkSectionReference(scx0 + a, scy0 + b, scz0 + c);
                    if (ref == null || !ref.isValid()) {
                        return false;
                    }
                    Sec s = new Sec();
                    s.ref = ref;
                    s.cx = scx0 + a;
                    s.cy = scy0 + b;
                    s.cz = scz0 + c;
                    s.blocks = st.getComponent(ref, BlockSection.getComponentType());
                    s.section = st.getComponent(ref, ChunkSection.getComponentType());
                    if (s.blocks == null || s.section == null) {
                        return false;
                    }
                    Ref<ChunkStore> column = s.section.getChunkColumnReference();
                    if (column == null || !column.isValid()) {
                        return false;
                    }
                    s.heightmap = st.getComponent(column, HeightmapColumn.getComponentType());
                    s.chunk = st.getComponent(column, WorldChunk.getComponentType());
                    s.components = st.getComponent(ref, BlockComponentSection.getComponentType());
                    s.fluids = st.getComponent(ref, FluidSection.getComponentType());
                    s.physics = st.getComponent(ref, BlockPhysics.getComponentType());
                    s.readBlocks = !s.blocks.isSolidAir();
                    s.readFluids = s.fluids != null && !s.fluids.isEmpty();
                    s.hadTicking = s.blocks.hasTicking();
                    s.hasHealth = healthModule != null
                            && st.getComponent(ref, healthModule.getBlockHealthSectionComponentType()) != null;
                    s.hasAnimation = animationModule != null
                            && st.getComponent(ref, animationModule.getBlockAnimationSectionComponentType()) != null;
                    list[(a * nz + c) * ny + b] = s;
                }
            }
        }
        this.secs = list;
        return true;
    }

    /** Liest die Welt in dichte Felder und bestimmt, welche Stellen übersprungen werden. Ändert nichts. */
    private void scan(@Nullable int[] inBlock, int[] outBlock, int[] outMeta, int[][] outFluidRef, BitSet skip,
                      IntArrayList entityCells) {
        final int x1 = x0 + sx - 1, y1 = y0 + sy - 1, z1 = z0 + sz - 1;
        for (int dx = 0; dx < sx; dx++) {
            int x = x0 + dx;
            for (int dz = 0; dz < sz; dz++) {
                int z = z0 + dz;
                int i = index(dx, 0, dz);
                for (int dy = 0; dy < sy; dy++, i++) {
                    int y = y0 + dy;
                    Sec s = sec(x, y, z);
                    boolean sk = false;
                    if (s.readBlocks) {
                        int id = s.blocks.get(x, y, z);
                        if (id != 0) {
                            int rot = s.blocks.getRotationIndex(x, y, z);
                            int fill = s.blocks.getFiller(x, y, z);
                            int support = s.physics == null ? 0 : s.physics.get(x, y, z);
                            if (id == rules.matrixFrame) {
                                // AE2: der Matrix-Rahmen wird beim Tausch zu Luft
                                id = 0;
                                rot = 0;
                                fill = 0;
                                support = 0;
                            } else if (rules.immovable(id)) {
                                sk = true;
                            } else if (fill != 0) {
                                // Füllteil: sein Hauptblock muss im Gebiet liegen, sonst bleibt der ganze Block stehen
                                sk = !inside(x - FillerBlockUtil.unpackX(fill), y - FillerBlockUtil.unpackY(fill),
                                        z - FillerBlockUtil.unpackZ(fill));
                            } else if (rules.multiCell(id)) {
                                sk = straddles(id, rot, x, y, z, x1, y1, z1, skip);
                            }
                            if (!sk && s.components != null && s.components.hasBlockComponents(ChunkUtil.indexBlock(x, y, z))) {
                                if (rules.hasImmovableComponent(entityArchetype(s, x, y, z))) {
                                    sk = true;
                                } else {
                                    entityCells.add(i);
                                }
                            }
                            outBlock[i] = id;
                            outMeta[i] = meta(rot, fill, support);
                        }
                    }
                    if (s.readFluids) {
                        int fluidId = s.fluids.getFluidId(x, y, z);
                        if (fluidId != 0) {
                            int level = s.fluids.getFluidLevel(x, y, z);
                            if ((level & 15) != 0) {
                                if (outFluidRef[0] == null) {
                                    outFluidRef[0] = new int[outBlock.length];
                                }
                                outFluidRef[0][i] = fluid(fluidId, level);
                            }
                        }
                    }
                    // AE2 überspringt eine Stelle auch, wenn der Block der Gegenseite gesperrt ist
                    if (sk || (inBlock != null && inBlock[i] != 0 && rules.immovable(inBlock[i]))) {
                        skip.set(i);
                    }
                }
            }
        }
    }

    /**
     * Ragt der Mehrfachblock mit Hauptblock an {@code x,y,z} über das Gebiet hinaus? Dann bleiben er und alle seine
     * Teile stehen (sonst würde er zerrissen).
     */
    private boolean straddles(int id, int rot, int x, int y, int z, int x1, int y1, int z1, BitSet skip) {
        BlockBoundingBoxes.RotatedVariantBoxes boxes = FillerBlockUtil.multiCellFootprint(id, rot);
        if (boxes == null) {
            return false;
        }
        int min = FillerBlockUtil.packedMinCell(boxes);
        int max = FillerBlockUtil.packedMaxCell(boxes);
        int ax = x + FillerBlockUtil.unpackX(min), ay = y + FillerBlockUtil.unpackY(min), az = z + FillerBlockUtil.unpackZ(min);
        int bx = x + FillerBlockUtil.unpackX(max), by = y + FillerBlockUtil.unpackY(max), bz = z + FillerBlockUtil.unpackZ(max);
        if (ax >= x0 && ay >= y0 && az >= z0 && bx <= x1 && by <= y1 && bz <= z1) {
            return false;
        }
        if (bx - ax > 2 * MAX_FILLER_REACH || by - ay > 2 * MAX_FILLER_REACH || bz - az > 2 * MAX_FILLER_REACH) {
            return true;
        }
        for (int px = Math.max(ax, x0); px <= Math.min(bx, x1); px++) {
            for (int pz = Math.max(az, z0); pz <= Math.min(bz, z1); pz++) {
                for (int py = Math.max(ay, y0); py <= Math.min(by, y1); py++) {
                    skip.set(index(px - x0, py - y0, pz - z0));
                }
            }
        }
        return true;
    }

    @Nullable
    private com.hypixel.hytale.component.Archetype<ChunkStore> entityArchetype(Sec s, int x, int y, int z) {
        int index = ChunkUtil.indexBlock(x, y, z);
        Ref<ChunkStore> ref = s.components.getBlockReference(index);
        if (ref != null && ref.isValid()) {
            return st.getArchetype(ref);
        }
        Holder<ChunkStore> holder = s.components.getBlockHolder(index);
        return holder == null ? null : holder.getArchetype();
    }

    /** Ein Spieler, der nach dem Tausch in einem festen Block stünde, verhindert den Transfer. */
    @Nullable
    private Refusal playerWouldBeBuried(@Nullable int[] inBlock, BitSet skip) {
        if (inBlock == null) {
            return null;
        }
        for (PlayerRef player : world.getPlayerRefs()) {
            Transform transform;
            try {
                transform = player.getTransform();
            } catch (RuntimeException e) {
                continue;
            }
            if (transform == null) {
                continue;
            }
            Vector3d p = transform.getPosition();
            int ax = (int) Math.floor(p.x - 0.4), bx = (int) Math.floor(p.x + 0.4);
            int ay = (int) Math.floor(p.y + 0.05), by = (int) Math.floor(p.y + 1.85);
            int az = (int) Math.floor(p.z - 0.4), bz = (int) Math.floor(p.z + 0.4);
            for (int x = ax; x <= bx; x++) {
                for (int z = az; z <= bz; z++) {
                    for (int y = ay; y <= by; y++) {
                        if (!inside(x, y, z)) {
                            continue;
                        }
                        int i = index(x - x0, y - y0, z - z0);
                        if (!skip.get(i) && rules.solid(inBlock[i])) {
                            return new Refusal("player", "player", player.getUsername());
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Entnimmt die Block-Entity einer Stelle ohne „Abbauen“ (wie beim Entladen); offene Fenster werden geschlossen. */
    @Nullable
    private Holder<ChunkStore> take(int i) {
        int dy = i % sy;
        int rest = i / sy;
        int dz = rest % sz;
        int dx = rest / sz;
        int x = x0 + dx, y = y0 + dy, z = z0 + dz;
        Sec s = sec(x, y, z);
        if (s.components == null) {
            return null;
        }
        int index = ChunkUtil.indexBlock(x, y, z);
        ItemContainerBlock container = null;
        Ref<ChunkStore> ref = s.components.getBlockReference(index);
        if (ref != null && ref.isValid()) {
            container = st.getComponent(ref, ItemContainerBlock.getComponentType());
        } else {
            Holder<ChunkStore> stored = s.components.getBlockHolder(index);
            if (stored != null) {
                container = stored.getComponent(ItemContainerBlock.getComponentType());
            }
        }
        if (container != null && !container.getWindows().isEmpty()) {
            // sonst könnte ein Spieler aus dem schon gespeicherten Behälter noch etwas herausnehmen
            WindowManager.closeAndRemoveAll(container.getWindows());
        }
        Holder<ChunkStore> holder = BlockEntity.takeBlockEntity(st, s.components, x, y, z);
        if (holder == null || !holder.hasSerializableComponents(ChunkStore.REGISTRY.getData())) {
            return null;
        }
        return holder;
    }

    /** Gibt entnommene Block-Entities an ihre Stelle zurück (Transfer abgebrochen). */
    private void putBack(Int2ObjectOpenHashMap<Holder<ChunkStore>> taken) {
        for (Int2ObjectMap.Entry<Holder<ChunkStore>> e : taken.int2ObjectEntrySet()) {
            int i = e.getIntKey();
            int dy = i % sy;
            int rest = i / sy;
            try {
                addEntity(x0 + rest / sz, y0 + dy, z0 + rest % sz, e.getValue());
            } catch (RuntimeException ex) {
                store.warn("AE2: could not restore a block entity after a failed spatial transfer", ex);
            }
        }
    }

    /** Setzt eine Block-Entity so ein, wie die Engine es beim Laden eines Chunks tut. */
    private void addEntity(int x, int y, int z, Holder<ChunkStore> holder) {
        Sec s = sec(x, y, z);
        int index = ChunkUtil.indexBlock(x, y, z);
        if (s.components == null) {
            s.components = st.ensureAndGetComponent(s.ref, BlockComponentSection.getComponentType());
        }
        if (s.components.hasBlockComponents(index)) {
            // Kann nur bei einem Fehler vorkommen: die Stelle wurde vorher geleert
            BlockEntity.takeBlockEntity(st, s.components, x, y, z);
        }
        holder.putComponent(BlockModule.BlockStateInfo.getComponentType(), new BlockModule.BlockStateInfo(index, s.ref));
        if (st.getArchetype(s.ref).contains(ChunkStore.REGISTRY.getNonTickingComponentType())) {
            s.components.addBlockHolder(index, holder);
        } else {
            st.addEntity(holder, AddReason.LOAD);
            // LOAD merkt die Stelle nicht zum Speichern vor (beim echten Laden steht sie ja schon auf der Platte)
            s.components.markBlockNeedsSaving(index);
        }
    }

    /**
     * Der neue Inhalt des Plots: was aus der Welt kommt; an übersprungenen Stellen bleibt, was der Plot dort schon
     * gespeichert hatte.
     */
    private PrefabBuffer buildContent(int[] outBlock, int[] outMeta, @Nullable int[] outFluid,
                                      Int2ObjectOpenHashMap<Holder<ChunkStore>> outEntities, @Nullable int[] inBlock,
                                      @Nullable int[] inMeta, @Nullable int[] inFluid,
                                      Int2ObjectOpenHashMap<Holder<ChunkStore>> inEntities, BitSet skip, Stats stats) {
        PrefabBuffer.Builder builder = PrefabBuffer.newBuilder();
        List<PrefabBufferBlockEntry> column = new ArrayList<>(sy);
        String[] keys = new String[0];
        int count = 0;
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                column.clear();
                int i = index(dx, 0, dz);
                for (int dy = 0; dy < sy; dy++, i++) {
                    int id, m, f;
                    Holder<ChunkStore> holder;
                    if (skip.get(i)) {
                        id = inBlock == null ? 0 : inBlock[i];
                        m = inMeta == null ? 0 : inMeta[i];
                        f = inFluid == null ? 0 : inFluid[i];
                        holder = inEntities.get(i);
                    } else {
                        id = outBlock[i];
                        m = outMeta[i];
                        f = outFluid == null ? 0 : outFluid[i];
                        holder = outEntities.get(i);
                    }
                    if (id == 0 && f == 0 && holder == null) {
                        continue;
                    }
                    if (id >= keys.length) {
                        keys = java.util.Arrays.copyOf(keys, Math.max(id + 1, BlockType.getAssetMap().getNextIndex()));
                    }
                    String key = keys[id];
                    if (key == null) {
                        BlockType type = BlockType.getAssetMap().getAsset(id);
                        key = type == null ? BlockType.UNKNOWN.getId() : type.getId();
                        keys[id] = key;
                    }
                    column.add(new PrefabBufferBlockEntry(dy, id, key, 1.0F, holder, f >>> 4, (byte) (f & 15),
                            (byte) support(m), rotation(m), filler(m)));
                    count++;
                }
                if (!column.isEmpty()) {
                    builder.addColumn(dx, dz, column.toArray(PrefabBufferBlockEntry.EMPTY_ARRAY), null);
                }
            }
        }
        stats.blocksOut = count;
        return builder.build();
    }

    /** Schreibt den alten Plot-Inhalt in die Welt. Fehler an einer Stelle werden gemeldet, der Rest läuft weiter. */
    private void place(int[] outBlock, int[] outMeta, @Nullable int[] outFluid, @Nullable int[] inBlock,
                       @Nullable int[] inMeta, @Nullable int[] inFluid,
                       Int2ObjectOpenHashMap<Holder<ChunkStore>> inEntities, BitSet skip, Stats stats) {
        BlockType[] types = new BlockType[0];
        int errors = 0;
        int placed = 0;
        for (int dx = 0; dx < sx; dx++) {
            int x = x0 + dx;
            for (int dz = 0; dz < sz; dz++) {
                int z = z0 + dz;
                int i = index(dx, 0, dz);
                for (int dy = 0; dy < sy; dy++, i++) {
                    int oldId = outBlock[i];
                    int newId = inBlock == null ? 0 : inBlock[i];
                    int oldFluid = outFluid == null ? 0 : outFluid[i];
                    int newFluid = inFluid == null ? 0 : inFluid[i];
                    if ((oldId | newId | oldFluid | newFluid) == 0 || skip.get(i)) {
                        continue;
                    }
                    int y = y0 + dy;
                    try {
                        Sec s = sec(x, y, z);
                        int oldMeta = outMeta[i];
                        int newMeta = inMeta == null ? 0 : inMeta[i];
                        if (oldId != newId || oldMeta != newMeta) {
                            if (newId >= types.length) {
                                types = java.util.Arrays.copyOf(types, Math.max(newId + 1, BlockType.getAssetMap().getNextIndex()));
                            }
                            BlockType type = types[newId];
                            if (type == null) {
                                type = BlockType.getAssetMap().getAsset(newId);
                                if (type == null) {
                                    type = BlockType.EMPTY;
                                }
                                types[newId] = type;
                            }
                            if (s.blocks.set(x, y, z, newId, rotation(newMeta), filler(newMeta))) {
                                s.changed = true;
                                if (s.heightmap != null) {
                                    s.heightmap.onBlockChanged(chunkStore, s.chunk, s.cy, s.blocks, x, y, z, type);
                                }
                                int blockIndex = ChunkUtil.indexBlock(x, y, z);
                                if (s.hasAnimation) {
                                    BlockAnimationModule.onBlockReplaced(st, s.ref, blockIndex, oldId, newId);
                                }
                                if (s.hasHealth) {
                                    BlockHealthModule.onBlockReplaced(st, s.ref, blockIndex);
                                }
                            }
                            int support = support(newMeta);
                            if (support != support(oldMeta)) {
                                if (s.physics == null) {
                                    s.physics = st.ensureAndGetComponent(s.ref, BlockPhysics.getComponentType());
                                }
                                s.physics.set(x, y, z, support);
                                s.changed = true;
                            }
                            if (newId != 0) {
                                placed++;
                            }
                        }
                        if (oldFluid != newFluid) {
                            if (s.fluids == null) {
                                s.fluids = st.ensureAndGetComponent(s.ref, FluidSection.getComponentType());
                            }
                            s.fluids.setFluid(x, y, z, newFluid >>> 4, (byte) (newFluid & 15));
                            s.changed = true;
                        }
                        // Tickende Blöcke und Flüssigkeiten neu anstoßen (wie Hytales setBlock bzw. /fluid set),
                        // Tick-Marken des alten Inhalts löschen
                        boolean tick = rules.ticking(newId) || newFluid != 0;
                        if (tick || s.hadTicking) {
                            s.blocks.setTicking(x, y, z, tick);
                        }
                    } catch (RuntimeException e) {
                        if (errors++ < 5) {
                            store.warn("AE2: spatial transfer could not set the block at " + x + " " + y + " " + z, e);
                        }
                    }
                }
            }
        }
        stats.blocksIn = placed;

        // Block-Entities: die gespeicherten, sonst die Vorgabe des Blocktyps (wie Hytale beim Platzieren)
        int entities = 0;
        if (inBlock != null) {
            for (int dx = 0; dx < sx; dx++) {
                for (int dz = 0; dz < sz; dz++) {
                    int i = index(dx, 0, dz);
                    for (int dy = 0; dy < sy; dy++, i++) {
                        int id = inBlock[i];
                        if (id == 0 || skip.get(i)) {
                            continue;
                        }
                        Holder<ChunkStore> holder = inEntities.get(i);
                        if (holder == null && rules.declaresEntity(id) && filler(inMeta[i]) == 0) {
                            BlockType type = BlockType.getAssetMap().getAsset(id);
                            Holder<ChunkStore> template = type == null ? null : type.getBlockEntity();
                            holder = template == null ? null : template.clone();
                        }
                        if (holder == null) {
                            continue;
                        }
                        try {
                            addEntity(x0 + dx, y0 + dy, z0 + dz, holder);
                            entities++;
                        } catch (RuntimeException e) {
                            if (errors++ < 5) {
                                store.warn("AE2: spatial transfer could not restore the block entity at " + (x0 + dx)
                                        + " " + (y0 + dy) + " " + (z0 + dz), e);
                            }
                        }
                    }
                }
            }
        }
        stats.entitiesIn = entities;
        if (errors > 5) {
            store.warn("AE2: spatial transfer had " + errors + " errors in total", null);
        }
    }

    /** Geänderte Sektionen zum Speichern vormerken, Licht neu berechnen lassen. */
    private void finish() {
        var lighting = world.getChunkLighting();
        for (Sec s : secs) {
            if (!s.changed) {
                continue;
            }
            try {
                s.blocks.invalidate();
                s.section.markNeedsSaving();
                if (s.chunk != null) {
                    s.chunk.markNeedsSaving();
                }
                lighting.invalidateLightInChunkSection(chunkStore, s.cx, s.cz, s.cy);
            } catch (RuntimeException e) {
                store.warn("AE2: spatial transfer could not finish section " + s.cx + " " + s.cy + " " + s.cz, e);
            }
        }
    }
}
