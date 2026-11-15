/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.worldgen.meteorite.MeteoriteStructure, MeteoriteStructurePiece).
 */
package de.fellurion.ae2.hytale.meteorite;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.worldgen.IWorldGen;
import com.hypixel.hytale.server.worldgen.biome.Biome;
import com.hypixel.hytale.server.worldgen.chunk.ChunkGenerator;
import com.hypixel.hytale.server.worldgen.chunk.ZoneBiomeResult;
import com.hypixel.hytale.server.worldgen.container.WaterContainer;
import de.fellurion.ae2.core.worldgen.Climate;
import de.fellurion.ae2.core.worldgen.FalloutMode;
import de.fellurion.ae2.core.worldgen.MeteoriteBlock;
import de.fellurion.ae2.core.worldgen.MeteoriteLocator;
import de.fellurion.ae2.core.worldgen.MeteoritePlacer;
import de.fellurion.ae2.core.worldgen.MeteoriteSite;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Meteoriten bei der Weltgenerierung, wie in Minecraft: Jeder frisch generierte Chunk bekommt seinen Teil der
 * Meteoriten in der Nähe. Wo ein Meteorit liegt und wie er aussieht, hängt nur vom Seed der Welt ab; die Höhe kommt
 * aus dem Gelände-Generator, damit alle Chunks eines Meteoriten dieselbe Mitte benutzen.
 *
 * <p>Läuft auf Hintergrund-Threads, mehrere Chunks gleichzeitig: angefasst wird nur der Chunk des Ereignisses.
 * In Welten mit einem anderen Generator gibt es keine Höhenabfrage vorab; dort wird ein Meteorit nur vorgemerkt und
 * später im geladenen Gelände gebaut ({@link MeteoriteImpacts}), und nur wenn die Welt ausdrücklich eingeschaltet ist.
 */
final class MeteoriteWorldgen {

    /** Welthöhe in Blöcken (10 Sektionen zu 32). */
    private static final int HEIGHT = 320;
    /** Wasserstand des Standard-Geländes, falls der Generator keinen nennt. */
    private static final int DEFAULT_WATER_TOP = 114;

    /** Geplante Meteoriten je Welt und Region (leer = Region ohne Meteorit). */
    private static final Map<String, Map<Long, Optional<MeteoriteSite>>> PLANS = new ConcurrentHashMap<>();
    @Nullable
    private static volatile BiConsumer<String, Throwable> warn;

    private MeteoriteWorldgen() {
    }

    static void setup(BiConsumer<String, Throwable> warning) {
        warn = warning;
    }

    static void onChunkPreLoad(ChunkPreLoadProcessEvent event) {
        if (!event.isNewlyGenerated()) {
            return;
        }
        try {
            generate(event);
        } catch (RuntimeException | LinkageError e) {
            BiConsumer<String, Throwable> w = warn;
            if (w != null) {
                w.accept("AE2: meteorite generation failed for a chunk", e);
            }
        }
    }

    private static void generate(ChunkPreLoadProcessEvent event) {
        World world = event.getWorld();
        WorldChunk chunk = event.getChunk();
        Holder<ChunkStore> holder = event.getHolder();
        if (world == null || chunk == null || holder == null) {
            return;
        }
        // Merken, dass dieser Chunk mit dem Plugin entstanden ist (Bauschutz-Stufe "strict")
        ChunkInfo.markGenerated(holder);

        MeteoriteConfig.Settings settings = MeteoriteConfig.of(world.getName());
        if (!settings.worldgen()) {
            return;
        }
        long seed = world.getWorldConfig().getSeed();
        int minX = chunk.getX() << 5, minZ = chunk.getZ() << 5;
        int maxX = minX + 31, maxZ = minZ + 31;
        IWorldGen generator = world.getChunkStore().getGenerator();
        if (!(generator instanceof ChunkGenerator terrain)) {
            // Anderer Generator: nur vormerken, wenn die Welt ausdrücklich eingeschaltet wurde
            if (settings.worldgenExplicit()) {
                MeteoriteLocator.Candidate c = MeteoriteLocator.candidate(seed, MeteoriteLocator.region(minX), MeteoriteLocator.region(minZ));
                if (c != null && c.x() >= minX && c.x() <= maxX && c.z() >= minZ && c.z() <= maxZ) {
                    MeteoriteRegistry.of(world.getName()).addPending(new MeteoriteRegistry.Pending(c.x(), c.z(), c.radius(), c.seed()));
                }
            }
            return;
        }
        ChunkColumn column = holder.getComponent(ChunkColumn.getComponentType());
        Holder<ChunkStore>[] sections = column == null ? null : column.getSectionHolders();
        if (sections == null) {
            return;
        }
        int reach = MeteoriteSite.REACH;
        for (int rx = MeteoriteLocator.region(minX - reach); rx <= MeteoriteLocator.region(maxX + reach); rx++) {
            for (int rz = MeteoriteLocator.region(minZ - reach); rz <= MeteoriteLocator.region(maxZ + reach); rz++) {
                MeteoriteSite site = plan(world.getName(), terrain, seed, rx, rz);
                if (site == null || site.x() + reach < minX || site.x() - reach > maxX
                        || site.z() + reach < minZ || site.z() - reach > maxZ) {
                    continue;
                }
                PreloadTerrain target = new PreloadTerrain(sections, seaLevel(terrain, seed, site.x(), site.z()));
                MeteoritePlacer.place(target, site, minX, minZ, maxX, maxZ);
                if (site.x() >= minX && site.x() <= maxX && site.z() >= minZ && site.z() <= maxZ) {
                    MeteoriteRegistry.of(world.getName()).add(new MeteoriteRegistry.Entry(site.x(), site.y(), site.z(),
                            site.radius(), MeteoriteRegistry.Source.WORLDGEN, true));
                }
            }
        }
    }

    // ------------------------------------------------------------------ Planung (nur aus Seed und Generator)

    /** Der Meteorit einer Region, wie ihn die Weltgenerierung baut; null, wenn die Region keinen hat. */
    @Nullable
    static MeteoriteSite plan(String worldName, ChunkGenerator terrain, long seed, int regionX, int regionZ) {
        Map<Long, Optional<MeteoriteSite>> plans = PLANS.computeIfAbsent(worldName.toLowerCase(Locale.ROOT), w -> new ConcurrentHashMap<>());
        long key = ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
        Optional<MeteoriteSite> cached = plans.get(key);
        if (cached == null) {
            if (plans.size() > 4096) {
                plans.clear();
            }
            cached = plans.computeIfAbsent(key, k -> {
                MeteoriteLocator.Candidate c = MeteoriteLocator.candidate(seed, regionX, regionZ);
                return Optional.ofNullable(c == null ? null : plan(terrain, seed, c));
            });
        }
        return cached.orElse(null);
    }

    @Nullable
    private static MeteoriteSite plan(ChunkGenerator terrain, long seed, MeteoriteLocator.Candidate c) {
        int s = (int) seed;
        ZoneBiomeResult at = terrain.getZoneBiomeResultAt(s, c.x(), c.z());
        String zone = at == null || at.getZoneResult() == null || at.getZoneResult().getZone() == null
                ? "" : at.getZoneResult().getZone().name();
        int waterTop = waterTop(at == null ? null : at.getBiome(), s, c.x(), c.z());
        int ground = terrain.getHeight(s, c.x(), c.z());
        Climate climate = climate(zone, ground < waterTop - 2);
        MeteoriteLocator.Surface surface = new MeteoriteLocator.Surface() {
            @Override
            public int surfaceHeight(int x, int z) {
                return Math.max(terrain.getHeight(s, x, z), waterTop);
            }

            @Override
            public int groundHeight(int x, int z) {
                return terrain.getHeight(s, x, z);
            }

            @Override
            public int seaLevel() {
                return waterTop + 1;
            }

            @Override
            public int minY() {
                return 1;
            }
        };
        MeteoriteSite site = MeteoriteLocator.plan(c, climate, surface);
        return site.y() + 16 >= HEIGHT ? null : site;
    }

    private static int seaLevel(ChunkGenerator terrain, long seed, int x, int z) {
        ZoneBiomeResult at = terrain.getZoneBiomeResultAt((int) seed, x, z);
        return waterTop(at == null ? null : at.getBiome(), (int) seed, x, z) + 1;
    }

    /** Y des obersten Wasserblocks an dieser Stelle laut Generator. */
    private static int waterTop(@Nullable Biome biome, int seed, int x, int z) {
        if (biome != null && biome.getWaterContainer() != null && biome.getWaterContainer().hasEntries()) {
            int top = biome.getWaterContainer().getMaxHeight(seed, x, z);
            if (WaterContainer.isValidWaterHeight(top)) {
                return top;
            }
        }
        return DEFAULT_WATER_TOP;
    }

    /**
     * Klima aus dem Namen der Zone: Zone 1 gemäßigt, Zone 2 Wüste, Zone 3 Schnee, Zone 4 verwüstet/vulkanisch;
     * „Shore“ = Strand, „Ocean“ = Meer.
     */
    static Climate climate(String zoneName, boolean underwater) {
        String z = zoneName.toLowerCase(Locale.ROOT);
        boolean ocean = underwater || z.contains("ocean");
        boolean shore = z.contains("shore");
        if (z.startsWith("zone2")) {
            return new Climate(ocean, Climate.Temperature.HOT, false, false, FalloutMode.SAND, MeteoriteBlock.SAND);
        }
        if (z.startsWith("zone3")) {
            return new Climate(ocean, Climate.Temperature.COLD, true, true, FalloutMode.ICE_SNOW, MeteoriteBlock.SNOW_BLOCK);
        }
        if (z.startsWith("zone4")) {
            return new Climate(ocean, Climate.Temperature.HOT, false, false, FalloutMode.DEFAULT, MeteoriteBlock.STONE);
        }
        return new Climate(ocean, Climate.Temperature.TEMPERATE, true, false,
                shore ? FalloutMode.SAND : FalloutMode.DEFAULT, shore ? MeteoriteBlock.SAND : MeteoriteBlock.COBBLESTONE);
    }

    // ------------------------------------------------------------------ Blockzugriff im noch nicht geladenen Chunk

    /** Schreibt direkt in die Sektionen des frisch generierten Chunks (er ist noch nicht in der Welt). */
    private static final class PreloadTerrain implements MeteoritePlacer.Terrain {
        private final Holder<ChunkStore>[] sections;
        private final int seaLevel;

        PreloadTerrain(Holder<ChunkStore>[] sections, int seaLevel) {
            this.sections = sections;
            this.seaLevel = seaLevel;
        }

        /** Sektionen je Höhe, einmal nachgeschlagen (der Generator liest jede Zelle mehrfach). */
        private final BlockSection[] blockSections = new BlockSection[HEIGHT >> 5];
        private final FluidSection[] fluidSections = new FluidSection[HEIGHT >> 5];
        private final boolean[] resolved = new boolean[HEIGHT >> 5];

        private void resolve(int sy) {
            Holder<ChunkStore> s = sy < sections.length ? sections[sy] : null;
            blockSections[sy] = s == null ? null : s.getComponent(BlockSection.getComponentType());
            fluidSections[sy] = s == null ? null : s.getComponent(FluidSection.getComponentType());
            resolved[sy] = true;
        }

        @Nullable
        private BlockSection blocks(int y) {
            if (y < 0 || y >= HEIGHT) {
                return null;
            }
            if (!resolved[y >> 5]) {
                resolve(y >> 5);
            }
            return blockSections[y >> 5];
        }

        @Nullable
        private FluidSection fluids(int y) {
            if (y < 0 || y >= HEIGHT) {
                return null;
            }
            if (!resolved[y >> 5]) {
                resolve(y >> 5);
            }
            return fluidSections[y >> 5];
        }

        private int block(int x, int y, int z) {
            BlockSection b = blocks(y);
            return b == null ? (y < 0 ? -1 : 0) : b.get(x, y, z);
        }

        private int fluid(int x, int y, int z) {
            FluidSection f = fluids(y);
            return f == null ? 0 : f.getFluidId(x, y, z);
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
        public int columnTop(int x, int z) {
            for (int sy = (HEIGHT >> 5) - 1; sy >= 0; sy--) {
                BlockSection b = blocks(sy << 5);
                FluidSection f = fluids(sy << 5);
                if ((b == null || b.isSolidAir()) && f == null) {
                    continue;
                }
                for (int y = (sy << 5) + 31; y >= sy << 5; y--) {
                    if ((b != null && b.get(x, y, z) != 0) || (f != null && f.getFluidId(x, y, z) != 0)) {
                        return y;
                    }
                }
            }
            return -1;
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

        @Override
        public void set(int x, int y, int z, MeteoriteBlock block) {
            MeteoriteMaterials.Material m = MeteoriteMaterials.of(block);
            write(x, y, z, m.block(), 0, m.fluid(), m.level());
        }

        @Override
        public void copyFromAbove(int x, int y, int z) {
            BlockSection above = blocks(y + 1);
            FluidSection aboveFluid = fluids(y + 1);
            int id = above == null ? 0 : above.get(x, y + 1, z);
            int rotation = above == null ? 0 : above.getRotationIndex(x, y + 1, z);
            int fluid = aboveFluid == null ? 0 : aboveFluid.getFluidId(x, y + 1, z);
            byte level = aboveFluid == null ? 0 : aboveFluid.getFluidLevel(x, y + 1, z);
            write(x, y, z, id, rotation, fluid, level);
        }

        private void write(int x, int y, int z, int id, int rotation, int fluid, byte level) {
            if (y < 1 || y >= HEIGHT) {
                return;
            }
            Holder<ChunkStore> s = sections[y >> 5];
            if (s == null) {
                return;
            }
            BlockSection b = blocks(y);
            if (b == null) {
                b = s.ensureAndGetComponent(BlockSection.getComponentType());
                blockSections[y >> 5] = b;
            }
            if (MeteoriteMaterials.indestructible(b.get(x, y, z))) {
                return;
            }
            ChunkSection section = s.getComponent(ChunkSection.getComponentType());
            if (section != null) {
                BlockOperations.setBlockPreLoad(section, b, x, y, z, id, rotation, 0);
            } else {
                b.set(x, y, z, id, rotation, 0);
            }
            FluidSection f = fluids(y);
            if (f == null && fluid != 0) {
                f = s.ensureAndGetComponent(FluidSection.getComponentType());
                fluidSections[y >> 5] = f;
            }
            if (f != null && (fluid != 0 || f.getFluidId(x, y, z) != 0)) {
                f.setFluid(x, y, z, fluid, fluid == 0 ? (byte) 0 : level);
                if (fluid != 0) {
                    b.setTicking(x, y, z, true); // die Flüssigkeit soll sich nach dem Laden setzen
                }
            }
        }
    }
}
