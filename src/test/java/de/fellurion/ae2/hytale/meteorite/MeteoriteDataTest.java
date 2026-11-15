/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.meteorite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeteoriteDataTest {

    @Test
    void defaultsAreLikeMinecraftAndWorldsCanBeSwitched(@TempDir Path dir) throws Exception {
        MeteoriteConfig.load(dir, (message, error) -> { throw new AssertionError(message, error); });
        assertTrue(Files.isRegularFile(dir.resolve("meteorites.json")), "Datei wird beim ersten Start angelegt");

        MeteoriteConfig.Settings fresh = MeteoriteConfig.of("Irgendeine");
        assertTrue(fresh.worldgen(), "Weltgenerierung ist Standard");
        assertFalse(fresh.worldgenExplicit());
        assertFalse(fresh.impacts(), "Einschlag-Ereignis ist standardmäßig aus");
        assertEquals(MeteoriteConfig.Protection.NORMAL, fresh.protection());

        MeteoriteConfig.setImpacts("Default", true);
        MeteoriteConfig.setInterval("default", 15);
        MeteoriteConfig.setProtection("DEFAULT", MeteoriteConfig.Protection.STRICT);
        MeteoriteConfig.setWorldgen("plots", false);

        // neu laden: alles bleibt erhalten, Weltnamen ohne Groß-/Kleinschreibung
        MeteoriteConfig.load(dir, (message, error) -> { throw new AssertionError(message, error); });
        MeteoriteConfig.Settings world = MeteoriteConfig.of("default");
        assertTrue(world.impacts());
        assertEquals(15, world.intervalMinutes());
        assertEquals(MeteoriteConfig.Protection.STRICT, world.protection());
        assertTrue(world.worldgen());
        assertFalse(MeteoriteConfig.of("Plots").worldgen());
        assertTrue(MeteoriteConfig.of("Plots").worldgenExplicit());
        assertFalse(MeteoriteConfig.of("andere").impacts(), "andere Welten bleiben beim Standard");
    }

    @Test
    void registryRemembersMeteoritesTouchedChunksAndPending(@TempDir Path dir) {
        MeteoriteRegistry.setup(dir, (message, error) -> { throw new AssertionError(message, error); });
        MeteoriteRegistry r = MeteoriteRegistry.of("RegistryTest");
        r.add(new MeteoriteRegistry.Entry(100, 60, 100, 4f, MeteoriteRegistry.Source.WORLDGEN, true));
        r.add(new MeteoriteRegistry.Entry(-400, 70, 20, 6f, MeteoriteRegistry.Source.IMPACT, true));
        r.touch(3, -2);
        r.addPending(new MeteoriteRegistry.Pending(900, 900, 5f, 42L));

        assertEquals(100, r.nearest(90, 90, true, 500).x());
        assertNull(r.nearest(5000, 5000, true, 500), "außer Reichweite");

        // Cube abgebaut: der Kompass (nur mit Cube) nimmt den nächsten
        r.cubeRemoved(100, 60, 100);
        assertEquals(-400, r.nearest(90, 90, true, 2000).x());
        assertEquals(100, r.nearest(90, 90, false, 2000).x(), "ohne Cube-Filter bleibt er bekannt");

        assertTrue(r.isTouched(3, -2));
        assertFalse(r.isTouched(-2, 3));

        MeteoriteRegistry.saveAll();
        assertTrue(Files.isRegularFile(dir.resolve("meteorites").resolve("registrytest.json")));
        assertNotNull(r.pending());
        assertEquals(1, r.pending().size());
        // ein fertig gebauter Meteorit ist nicht mehr vorgemerkt
        r.add(new MeteoriteRegistry.Entry(900, 64, 900, 5f, MeteoriteRegistry.Source.WORLDGEN, true));
        assertTrue(r.pending().isEmpty());
    }

    @Test
    void builtNamesAreRecognisedByWholeWords() {
        // Gebautes – auch von Bau-Mods, die ihre Blöcke als "Rock"/"Soil" markieren
        for (String built : new String[]{"Rock_Stone_Brick", "Rock_Stone_Cobble_Half", "Wood_Softwood_Planks", "Soil_Pathway",
                "Soil_Dirt_Tilled", "Soil_Road_Roman", "Mcw_Paths_Rock_Aqua_Brick_Diamond", "Rock_Stone_Cobble_Arch",
                "Furniture_Crude_Torch", "Build_Lightsource_White", "Endgame_Void_Brick_Light"}) {
            assertTrue(BuildProtection.builtName(built), built);
        }
        // Natur – auch wenn ein Teilwort zufällig passt ("GreyLight", "Chestnut", "Larch")
        for (String natural : new String[]{"Soil_Grass", "Rock_Stone", "Soil_Clay_GreyLight", "Wood_Chestnut_Trunk", "Wood_Larch_Trunk",
                "Plant_Crop_Wheat_Block", "Ore_Iron_Stone", "Arkanum_Taint_Soil", "Soil_Snow_Half", "Plant_Leaves_Broad",
                "Wood_Oak_Branch_Corner", "Plant_Leaves_Autumn_Floor"}) {
            assertFalse(BuildProtection.builtName(natural), natural);
        }
    }

    @Test
    void allowListSupportsExactIdsAndPrefixes() {
        java.util.Set<String> allowed = java.util.Set.of("Arkanum_Crystal_Flux", "Arkanum_Taint_*");
        assertTrue(BuildProtection.matches(allowed, "Arkanum_Crystal_Flux"));
        assertTrue(BuildProtection.matches(allowed, "Arkanum_Taint_Soil"));
        assertFalse(BuildProtection.matches(allowed, "Arkanum_Crystal_Flux_Large"));
        assertFalse(BuildProtection.matches(allowed, "Rock_Stone"));
        assertFalse(BuildProtection.matches(java.util.Set.of(), "Rock_Stone"));
    }

    @Test
    void allowListIsStoredInTheConfig(@TempDir Path dir) throws Exception {
        MeteoriteConfig.load(dir, (message, error) -> { throw new AssertionError(message, error); });
        assertTrue(MeteoriteConfig.global().naturalBlocks().isEmpty());
        assertTrue(MeteoriteConfig.allowNatural("Arkanum_Taint_*"));
        assertFalse(MeteoriteConfig.allowNatural("Arkanum_Taint_*"), "schon vorhanden");
        MeteoriteConfig.load(dir, (message, error) -> { throw new AssertionError(message, error); });
        assertTrue(MeteoriteConfig.global().naturalBlocks().contains("Arkanum_Taint_*"));
    }
}
