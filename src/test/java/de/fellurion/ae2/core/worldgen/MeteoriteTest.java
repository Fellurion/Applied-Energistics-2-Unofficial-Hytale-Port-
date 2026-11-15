/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.core.worldgen;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeteoriteTest {

    /** Flache Testwelt: Stein bis {@code ground}, darüber Luft; optional Wasser bis {@code sea}. */
    private static final class FlatWorld implements MeteoritePlacer.Terrain, MeteoriteLocator.Surface {
        final int ground;
        final int sea;
        final Map<Long, MeteoriteBlock> changed = new HashMap<>();

        FlatWorld(int ground, int sea) {
            this.ground = ground;
            this.sea = sea;
        }

        static long key(int x, int y, int z) {
            return ((long) (x + 30000) << 40) | ((long) (z + 30000) << 16) | (y + 1000);
        }

        MeteoriteBlock at(int x, int y, int z) {
            MeteoriteBlock b = changed.get(key(x, y, z));
            if (b != null) {
                return b;
            }
            if (y <= ground) {
                return MeteoriteBlock.STONE;
            }
            return y <= sea ? MeteoriteBlock.WATER : MeteoriteBlock.AIR;
        }

        @Override public int maxY() { return 255; }
        @Override public int seaLevel() { return sea + 1; }
        @Override public int minY() { return 0; }
        @Override public int surfaceHeight(int x, int z) { return Math.max(ground, sea); }
        @Override public int groundHeight(int x, int z) { return ground; }

        @Override
        public int columnTop(int x, int z) {
            for (int y = 255; y >= 0; y--) {
                if (at(x, y, z) != MeteoriteBlock.AIR) {
                    return y;
                }
            }
            return -1;
        }

        @Override public boolean isAir(int x, int y, int z) { return at(x, y, z) == MeteoriteBlock.AIR; }
        @Override public boolean hasFluid(int x, int y, int z) {
            MeteoriteBlock b = at(x, y, z);
            return b == MeteoriteBlock.WATER || b == MeteoriteBlock.LAVA;
        }
        @Override public boolean isReplaceable(int x, int y, int z) { return isAir(x, y, z) || hasFluid(x, y, z); }
        @Override public boolean isSolid(int x, int y, int z) { return !isReplaceable(x, y, z); }
        @Override public boolean is(int x, int y, int z, MeteoriteBlock block) { return at(x, y, z) == block; }
        @Override public void set(int x, int y, int z, MeteoriteBlock block) { changed.put(key(x, y, z), block); }
        @Override public void copyFromAbove(int x, int y, int z) { changed.put(key(x, y, z), at(x, y + 1, z)); }
    }

    private static MeteoriteSite site(float radius, CraterType crater, FalloutMode fallout, boolean pure, boolean lake) {
        return new MeteoriteSite(100, 60 - ((int) Math.ceil(radius) + 1), -40, radius, crater, fallout,
                MeteoriteBlock.SAND, pure, lake, 987654321L);
    }

    @Test
    void halfOfTheRegionsHaveAMeteoriteAndCandidatesKeepTheirDistance() {
        int found = 0;
        for (int rx = -20; rx < 20; rx++) {
            for (int rz = -20; rz < 20; rz++) {
                MeteoriteLocator.Candidate c = MeteoriteLocator.candidate(42L, rx, rz);
                if (c == null) {
                    continue;
                }
                found++;
                assertEquals(rx, MeteoriteLocator.region(c.x()));
                assertEquals(rz, MeteoriteLocator.region(c.z()));
                assertTrue(c.radius() >= 2 && c.radius() < 8);
                // Kandidaten liegen im vorderen Teil der Region -> Abstand zur Nachbarregion >= SEPARATION
                assertTrue(Math.floorMod(c.x(), MeteoriteLocator.REGION) < MeteoriteLocator.REGION - MeteoriteLocator.SEPARATION);
                assertTrue(Math.floorMod(c.z(), MeteoriteLocator.REGION) < MeteoriteLocator.REGION - MeteoriteLocator.SEPARATION);
                assertEquals(c, MeteoriteLocator.candidate(42L, rx, rz), "gleicher Seed, gleicher Meteorit");
            }
        }
        assertTrue(found > 700 && found < 900, "etwa die Hälfte von 1600 Regionen, war " + found);
    }

    @Test
    void centreSitsBelowTheSurface() {
        FlatWorld world = new FlatWorld(60, -1);
        MeteoriteLocator.Candidate c = new MeteoriteLocator.Candidate(0, 0, 10, 10, 4.5f, 7L);
        MeteoriteSite s = MeteoriteLocator.plan(c, Climate.temperate(), world);
        assertEquals(60 - 6, s.y(), "Mitte liegt ceil(r)+1 unter der Oberfläche");
        assertFalse(s.lake(), "kein Wasser in der Nähe");
        assertNotNull(s.crater());
    }

    @Test
    void oceanMeteoriteIsBuriedWithoutCrater() {
        FlatWorld world = new FlatWorld(40, 62);
        Climate ocean = new Climate(true, Climate.Temperature.TEMPERATE, true, false, FalloutMode.DEFAULT, MeteoriteBlock.SAND);
        MeteoriteSite s = MeteoriteLocator.plan(new MeteoriteLocator.Candidate(0, 0, 0, 0, 5f, 3L), ocean, world);
        assertEquals(CraterType.NONE, s.crater());
        assertEquals(40 - 6, s.y(), "vom Meeresboden gemessen");
        MeteoritePlacer.place(world, s);
        assertEquals(MeteoriteBlock.MYSTERIOUS_CUBE, world.at(s.x(), s.y(), s.z()));
        assertTrue(world.changed.values().stream().noneMatch(b -> b == MeteoriteBlock.AIR), "kein Krater im Ozean");
    }

    @Test
    void meteoriteHasCubeQuartzFloorAndSkyStone() {
        FlatWorld world = new FlatWorld(60, -1);
        MeteoriteSite s = site(5f, CraterType.NORMAL, FalloutMode.DEFAULT, false, false);
        MeteoritePlacer.place(world, s);
        assertEquals(MeteoriteBlock.MYSTERIOUS_CUBE, world.at(s.x(), s.y(), s.z()));
        int budding = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                MeteoriteBlock floor = world.at(s.x() + dx, s.y() - 1, s.z() + dz);
                assertTrue(floor == MeteoriteBlock.QUARTZ_BLOCK || floor.name().endsWith("_BUDDING"), "Boden ist Certus: " + floor);
                if (floor.name().endsWith("_BUDDING")) {
                    budding++;
                }
                MeteoriteBlock above = world.at(s.x() + dx, s.y(), s.z() + dz);
                if (above.name().endsWith("_BUD")) {
                    assertTrue(floor.name().endsWith("_BUDDING"), "Knospen nur auf Budding-Quarz");
                }
            }
        }
        assertTrue(budding >= 1, "mindestens ein Budding-Block bei diesem Seed");
        long skyStone = world.changed.values().stream().filter(b -> b == MeteoriteBlock.SKY_STONE).count();
        assertTrue(skyStone > 300, "Himmelsstein-Körper, war " + skyStone);
        // Krater: direkt über der früheren Oberfläche und in der Mulde ist Luft
        assertEquals(MeteoriteBlock.AIR, world.at(s.x() + 10, 60, s.z()));
    }

    @Test
    void piecewisePlacementGivesTheSameResult() {
        for (CraterType crater : new CraterType[]{CraterType.NORMAL, CraterType.LAVA, CraterType.WATER, CraterType.NONE}) {
            for (FalloutMode fallout : FalloutMode.values()) {
                MeteoriteSite s = site(7.3f, crater, fallout, crater == CraterType.LAVA, crater == CraterType.WATER);
                FlatWorld whole = new FlatWorld(60, -1);
                MeteoritePlacer.place(whole, s);
                FlatWorld pieces = new FlatWorld(60, -1);
                // 32er-Kacheln wie Hytale-Chunks, in "falscher" Reihenfolge
                for (int tx = 3; tx >= -3; tx--) {
                    for (int tz = -3; tz <= 3; tz++) {
                        int minX = Math.floorDiv(s.x(), 32) * 32 + tx * 32;
                        int minZ = Math.floorDiv(s.z(), 32) * 32 + tz * 32;
                        MeteoritePlacer.place(pieces, s, minX, minZ, minX + 31, minZ + 31);
                    }
                }
                assertEquals(whole.changed, pieces.changed, crater + "/" + fallout);
                assertFalse(whole.changed.isEmpty());
            }
        }
    }

    @Test
    void nothingChangesBeyondTheReach() {
        FlatWorld world = new FlatWorld(60, -1);
        MeteoriteSite s = site(7.9f, CraterType.NORMAL, FalloutMode.DEFAULT, false, false);
        MeteoritePlacer.place(world, s);
        for (long key : world.changed.keySet()) {
            int x = (int) (key >> 40) - 30000;
            int z = (int) ((key >> 16) & 0xFFFFFF) - 30000;
            assertTrue(Math.abs(x - s.x()) <= MeteoriteSite.REACH && Math.abs(z - s.z()) <= MeteoriteSite.REACH);
        }
    }
}
