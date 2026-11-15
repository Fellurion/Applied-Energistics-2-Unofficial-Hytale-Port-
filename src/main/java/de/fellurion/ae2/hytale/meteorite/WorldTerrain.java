/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.meteorite;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.fellurion.ae2.core.worldgen.MeteoriteBlock;
import de.fellurion.ae2.core.worldgen.MeteoriteLocator;
import de.fellurion.ae2.core.worldgen.MeteoritePlacer;
import de.fellurion.ae2.hytale.BlockAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Die geladene Welt für den Meteoriten-Generator, mit Zwischenspeicher: Erst wird der ganze Einschlag „trocken“
 * berechnet – alle Schreibzugriffe landen in {@link #changes()} –, dann prüft der Bauschutz genau diese Änderungen,
 * und erst danach werden sie über mehrere Ticks verteilt in die Welt geschrieben. Nur auf dem Welt-Thread benutzen.
 */
final class WorldTerrain implements MeteoritePlacer.Terrain, MeteoriteLocator.Surface {

    private static final int HEIGHT = 320;

    /** Eine geplante Änderung mit dem, was vorher dort war. */
    record Change(int x, int y, int z, int block, int rotation, int fluid, byte level, int oldBlock) {
    }

    private record Section(@Nullable Ref<ChunkStore> ref, @Nullable BlockSection blocks, @Nullable FluidSection fluids) {
    }

    private final World world;
    private final ChunkStore chunkStore;
    private final Store<ChunkStore> store;
    private final int seaLevel;
    /** Sektionen rund um die Mitte, direkt adressiert (der Generator liest jede Zelle mehrfach). */
    private static final int SPAN = 8;
    private final Section[] sections = new Section[SPAN * SPAN * (HEIGHT >> 5)];
    private int baseChunkX = Integer.MIN_VALUE, baseChunkZ;
    private final Long2ObjectLinkedOpenHashMap<Change> changes = new Long2ObjectLinkedOpenHashMap<>();
    /** Spalten mit vorgemerkten Änderungen – nur dort lohnt der Blick in {@link #changes}. */
    private final boolean[] changedColumns = new boolean[SPAN * 32 * SPAN * 32];

    private boolean columnChanged(int x, int z) {
        int cx = x - (baseChunkX << 5), cz = z - (baseChunkZ << 5);
        return cx >= 0 && cz >= 0 && cx < SPAN * 32 && cz < SPAN * 32 && changedColumns[cx * SPAN * 32 + cz];
    }
    private boolean missing;

    WorldTerrain(World world, int seaLevel) {
        this.world = world;
        this.chunkStore = world.getChunkStore();
        this.store = chunkStore.getStore();
        this.seaLevel = seaLevel;
    }

    /** true, wenn ein benötigter Chunk nicht geladen war (dann ist das Ergebnis unbrauchbar). */
    boolean missingChunks() {
        return missing;
    }

    List<Change> changes() {
        return new ArrayList<>(changes.values());
    }

    // ------------------------------------------------------------------ Lesen

    private static final Section NONE = new Section(null, null, null);

    private Section section(int x, int y, int z) {
        if (baseChunkX == Integer.MIN_VALUE) {
            baseChunkX = (x >> 5) - SPAN / 2;
            baseChunkZ = (z >> 5) - SPAN / 2;
        }
        int cx = (x >> 5) - baseChunkX, cz = (z >> 5) - baseChunkZ;
        if (cx < 0 || cx >= SPAN || cz < 0 || cz >= SPAN) {
            missing = true; // außerhalb des erwarteten Bereichs: wie nicht geladen behandeln
            return NONE;
        }
        int index = (cx * SPAN + cz) * (HEIGHT >> 5) + (y >> 5);
        Section s = sections[index];
        if (s == null) {
            Ref<ChunkStore> ref = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
            if (ref == null || !ref.isValid()) {
                missing = true;
                s = NONE;
            } else {
                s = new Section(ref, store.getComponent(ref, BlockSection.getComponentType()),
                        store.getComponent(ref, FluidSection.getComponentType()));
            }
            sections[index] = s;
        }
        return s;
    }

    /** Block-Index in der Welt (ohne geplante Änderungen); -1 unterhalb der Welt. */
    int worldBlock(int x, int y, int z) {
        if (y < 0) {
            return -1;
        }
        if (y >= HEIGHT) {
            return 0;
        }
        BlockSection b = section(x, y, z).blocks();
        return b == null ? 0 : b.get(x, y, z);
    }

    private int worldFluid(int x, int y, int z) {
        if (y < 0 || y >= HEIGHT) {
            return 0;
        }
        FluidSection f = section(x, y, z).fluids();
        return f == null ? 0 : f.getFluidId(x, y, z);
    }

    private int block(int x, int y, int z) {
        Change c = columnChanged(x, z) ? changes.get(BlockAccess.pack(x, y, z)) : null;
        return c != null ? c.block() : worldBlock(x, y, z);
    }

    private int fluid(int x, int y, int z) {
        Change c = columnChanged(x, z) ? changes.get(BlockAccess.pack(x, y, z)) : null;
        return c != null ? c.fluid() : worldFluid(x, y, z);
    }

    @Override
    public int maxY() {
        return HEIGHT - 1;
    }

    @Override
    public int seaLevel() {
        return seaLevel;
    }

    @Override
    public int minY() {
        return 1;
    }

    @Override
    public int columnTop(int x, int z) {
        for (int sy = (HEIGHT >> 5) - 1; sy >= 0; sy--) {
            Section s = section(x, sy << 5, z);
            if ((s.blocks() == null || s.blocks().isSolidAir()) && s.fluids() == null) {
                continue;
            }
            for (int y = (sy << 5) + 31; y >= sy << 5; y--) {
                if (block(x, y, z) != 0 || fluid(x, y, z) != 0) {
                    return y;
                }
            }
        }
        return -1;
    }

    /** Oberster Boden-Block der Spalte (Erde, Stein, Sand …) – Bäume, Pflanzen und Wasser zählen nicht. */
    @Override
    public int groundHeight(int x, int z) {
        for (int y = columnTop(x, z); y > 0; y--) {
            int id = block(x, y, z);
            if (id != 0 && ground(id)) {
                return y;
            }
        }
        return 0;
    }

    /** Wie {@link #groundHeight}, aber eine Wasserfläche zählt als Oberfläche. */
    @Override
    public int surfaceHeight(int x, int z) {
        for (int y = columnTop(x, z); y > 0; y--) {
            int id = block(x, y, z);
            if (fluid(x, y, z) != 0 || (id != 0 && ground(id))) {
                return y;
            }
        }
        return 0;
    }

    private static final Map<Integer, Boolean> GROUND = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean ground(int blockId) {
        return GROUND.computeIfAbsent(blockId, id -> {
            BlockType t = BlockType.getAssetMap().getAsset(id);
            String key = t == null ? null : t.getId();
            return key != null && MeteoriteMaterials.solid(id)
                    && (key.startsWith("Soil_") || key.startsWith("Rock_") || key.startsWith("Ore_") || key.startsWith("AE2_Sky_Stone"));
        });
    }

    /** Block-Id (Asset-Key) des Bodens an dieser Stelle, für die Klima-Erkennung. */
    @Nullable
    String groundKey(int x, int z) {
        int y = groundHeight(x, z);
        BlockType t = y <= 0 ? null : BlockType.getAssetMap().getAsset(block(x, y, z));
        return t == null ? null : t.getId();
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        return block(x, y, z) == 0 && fluid(x, y, z) == 0;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        int id = block(x, y, z);
        return id == -1 || MeteoriteMaterials.solid(id);
    }

    @Override
    public boolean isReplaceable(int x, int y, int z) {
        int id = block(x, y, z);
        return id != -1 && MeteoriteMaterials.replaceable(id);
    }

    @Override
    public boolean hasFluid(int x, int y, int z) {
        return fluid(x, y, z) != 0;
    }

    @Override
    public boolean is(int x, int y, int z, MeteoriteBlock block) {
        MeteoriteMaterials.Material m = MeteoriteMaterials.of(block);
        if (m.fluid() != 0) {
            return fluid(x, y, z) == m.fluid();
        }
        return block(x, y, z) == m.block() && (m.block() != 0 || fluid(x, y, z) == 0);
    }

    // ------------------------------------------------------------------ Schreiben (nur vormerken)

    @Override
    public void set(int x, int y, int z, MeteoriteBlock block) {
        MeteoriteMaterials.Material m = MeteoriteMaterials.of(block);
        record(x, y, z, m.block(), 0, m.fluid(), m.level());
    }

    @Override
    public void copyFromAbove(int x, int y, int z) {
        Change above = columnChanged(x, z) ? changes.get(BlockAccess.pack(x, y + 1, z)) : null;
        if (above != null) {
            record(x, y, z, above.block(), above.rotation(), above.fluid(), above.level());
            return;
        }
        Section s = y + 1 < HEIGHT ? section(x, y + 1, z) : null;
        int id = s == null || s.blocks() == null ? 0 : s.blocks().get(x, y + 1, z);
        int rotation = s == null || s.blocks() == null ? 0 : s.blocks().getRotationIndex(x, y + 1, z);
        int fluid = s == null || s.fluids() == null ? 0 : s.fluids().getFluidId(x, y + 1, z);
        byte level = s == null || s.fluids() == null ? 0 : s.fluids().getFluidLevel(x, y + 1, z);
        record(x, y, z, id, rotation, fluid, level);
    }

    private void record(int x, int y, int z, int block, int rotation, int fluid, byte level) {
        if (y < 1 || y >= HEIGHT) {
            return;
        }
        int old = worldBlock(x, y, z);
        if (old < 0 || MeteoriteMaterials.indestructible(old)) {
            return;
        }
        changes.put(BlockAccess.pack(x, y, z), new Change(x, y, z, block, rotation, fluid, fluid == 0 ? 0 : level, old));
        int cx = x - (baseChunkX << 5), cz = z - (baseChunkZ << 5);
        if (cx >= 0 && cz >= 0 && cx < SPAN * 32 && cz < SPAN * 32) {
            changedColumns[cx * SPAN * 32 + cz] = true;
        }
    }

    // ------------------------------------------------------------------ Bauschutz

    /** Der Block, an dem die letzte Natur-Prüfung gescheitert ist („Block bei x y z“), für die Anzeige beim Admin. */
    @Nullable
    private String unnatural;

    @Nullable
    String unnaturalBlock() {
        return unnatural;
    }

    private boolean natural(int blockId, int x, int y, int z) {
        BuildProtection.Kind kind = BuildProtection.kind(blockId);
        if (kind == BuildProtection.Kind.NATURAL || (kind == BuildProtection.Kind.UNKNOWN && enclosed(x, y, z))) {
            return true;
        }
        BlockType t = BlockType.getAssetMap().getAsset(blockId);
        String key = t == null ? "#" + blockId : t.getId();
        int marker = key.indexOf("_State_Definitions_");
        unnatural = (marker > 1 && key.charAt(0) == '*' ? key.substring(1, marker) : key) + " @ " + x + " " + y + " " + z;
        unnaturalKind = kind;
        return false;
    }

    @Nullable
    private BuildProtection.Kind unnaturalKind;

    /** Ob der zuletzt gemeldete Block nur „unbekannt“ war (dann hilft eine Freigabe). */
    boolean unnaturalIsUnknown() {
        return unnaturalKind == BuildProtection.Kind.UNKNOWN;
    }

    /**
     * Ein Block, der rundum in festem Material steckt, ist kein Bauwerk (das braucht Luft zum Betreten), sondern
     * ein Erz oder Gestein einer anderen Mod.
     */
    private boolean enclosed(int x, int y, int z) {
        return MeteoriteMaterials.solid(worldBlock(x + 1, y, z)) && MeteoriteMaterials.solid(worldBlock(x - 1, y, z))
                && MeteoriteMaterials.solid(worldBlock(x, y + 1, z)) && (y <= 0 || MeteoriteMaterials.solid(worldBlock(x, y - 1, z)))
                && MeteoriteMaterials.solid(worldBlock(x, y, z + 1)) && MeteoriteMaterials.solid(worldBlock(x, y, z - 1));
    }

    /** Ob alles, was ersetzt würde, natürlich ist. */
    boolean changesOnlyNatural() {
        for (Change c : changes.values()) {
            if (!natural(c.oldBlock(), c.x(), c.y(), c.z())) {
                return false;
            }
        }
        return true;
    }

    /** Ob im Zylinder um {@code (x, z)} ab {@code minY} bis zur Oberfläche nur natürliche Blöcke stehen. */
    boolean cylinderIsNatural(int x, int z, int radius, int minY) {
        for (int i = x - radius; i <= x + radius; i++) {
            for (int k = z - radius; k <= z + radius; k++) {
                if ((i - x) * (i - x) + (k - z) * (k - z) > radius * radius) {
                    continue;
                }
                for (int sy = (HEIGHT >> 5) - 1; sy >= 0 && (sy << 5) + 31 >= minY; sy--) {
                    Section s = section(i, sy << 5, k);
                    if (s.blocks() == null || s.blocks().isSolidAir()) {
                        continue;
                    }
                    for (int y = (sy << 5) + 31; y >= Math.max(sy << 5, Math.max(minY, 0)); y--) {
                        if (!natural(s.blocks().get(i, y, k), i, y, k)) {
                            return false;
                        }
                    }
                }
            }
        }
        return !missing;
    }

    // ------------------------------------------------------------------ Änderungen anwenden

    /** Schreibt eine vorgemerkte Änderung in die Welt (legt Block-Entities automatisch an). */
    static void apply(World world, Change c) {
        ChunkStore cs = world.getChunkStore();
        Ref<ChunkStore> ref = cs.getChunkSectionReferenceAtBlock(c.x(), c.y(), c.z());
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<ChunkStore> store = cs.getStore();
        BlockSection blocks = store.getComponent(ref, BlockSection.getComponentType());
        if (blocks == null || MeteoriteMaterials.indestructible(blocks.get(c.x(), c.y(), c.z()))) {
            return;
        }
        BlockType type = BlockType.getAssetMap().getAsset(c.block());
        if (type != null) {
            BlockOperations.setBlock(cs, ref, c.x(), c.y(), c.z(), c.block(), type, c.rotation(), 0, BlockAccess.QUIET);
        }
        FluidSection fluids = store.getComponent(ref, FluidSection.getComponentType());
        if (c.fluid() != 0 && fluids == null) {
            fluids = store.ensureAndGetComponent(ref, FluidSection.getComponentType());
        }
        if (fluids != null && (c.fluid() != 0 || fluids.getFluidId(c.x(), c.y(), c.z()) != 0)) {
            fluids.setFluid(c.x(), c.y(), c.z(), c.fluid(), c.level());
            if (c.fluid() != 0) {
                blocks.setTicking(c.x(), c.y(), c.z(), true);
            }
        }
    }
}
