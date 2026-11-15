/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.worldgen.meteorite.MeteoritePlacer, CraterType, fallout.*).
 */
package de.fellurion.ae2.hytale.meteorite;

import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import de.fellurion.ae2.core.worldgen.MeteoriteBlock;

import java.util.EnumMap;
import java.util.Map;

/**
 * Welche Hytale-Blöcke der Meteoriten-Generator setzt. Flüssigkeiten sind in Hytale keine Blöcke, sondern liegen
 * in einer eigenen Schicht; ein Material ist deshalb entweder ein Block oder Luft plus Flüssigkeit.
 */
final class MeteoriteMaterials {

    /**
     * @param block    Block-Index (0 = Luft)
     * @param fluid    Flüssigkeits-Index (0 = keine)
     * @param level    Füllstand der Flüssigkeit
     */
    record Material(int block, int fluid, byte level) {
    }

    private static volatile Map<MeteoriteBlock, Material> materials;
    private static volatile int bedrock = Integer.MIN_VALUE;

    private MeteoriteMaterials() {
    }

    static Material of(MeteoriteBlock block) {
        Map<MeteoriteBlock, Material> m = materials;
        if (m == null) {
            m = load();
            materials = m;
        }
        return m.get(block);
    }

    private static Map<MeteoriteBlock, Material> load() {
        Map<MeteoriteBlock, Material> m = new EnumMap<>(MeteoriteBlock.class);
        int stone = block("Rock_Stone");
        m.put(MeteoriteBlock.AIR, new Material(0, 0, (byte) 0));
        m.put(MeteoriteBlock.SKY_STONE, solid(stone, "AE2_Sky_Stone_Block"));
        m.put(MeteoriteBlock.MYSTERIOUS_CUBE, solid(stone, "AE2_Mysterious_Cube"));
        m.put(MeteoriteBlock.QUARTZ_BLOCK, solid(stone, "AE2_Quartz_Block"));
        m.put(MeteoriteBlock.DAMAGED_BUDDING, solid(stone, "AE2_Damaged_Budding_Quartz"));
        m.put(MeteoriteBlock.CHIPPED_BUDDING, solid(stone, "AE2_Chipped_Budding_Quartz"));
        m.put(MeteoriteBlock.FLAWED_BUDDING, solid(stone, "AE2_Flawed_Budding_Quartz"));
        m.put(MeteoriteBlock.FLAWLESS_BUDDING, solid(stone, "AE2_Flawless_Budding_Quartz"));
        // Knospen zeigen unrotiert nach oben
        m.put(MeteoriteBlock.SMALL_BUD, solid(0, "AE2_Small_Quartz_Bud"));
        m.put(MeteoriteBlock.MEDIUM_BUD, solid(0, "AE2_Medium_Quartz_Bud"));
        m.put(MeteoriteBlock.LARGE_BUD, solid(0, "AE2_Large_Quartz_Bud"));
        m.put(MeteoriteBlock.LAVA, fluid("Lava_Source", stone));
        m.put(MeteoriteBlock.WATER, fluid("Water_Source", 0));
        // Hytale hat kein Obsidian: erkaltetes Magma (wie in ae2data/entropy.json)
        m.put(MeteoriteBlock.OBSIDIAN, solid(stone, "Rock_Magma_Cooled", "Rock_Basalt"));
        m.put(MeteoriteBlock.MAGMA, solid(stone, "Rock_Volcanic", "Rock_Magma_Cooled"));
        m.put(MeteoriteBlock.SNOW_BLOCK, solid(stone, "Soil_Snow"));
        m.put(MeteoriteBlock.SNOW_LAYER, solid(stone, "Soil_Snow_Half", "Soil_Snow"));
        m.put(MeteoriteBlock.ICE, solid(stone, "Rock_Ice"));
        // Kein natürliches Glas in Hytale: vom Einschlag verbackener Sand
        m.put(MeteoriteBlock.GLASS, solid(stone, "Rock_Sandstone", "Soil_Sand"));
        m.put(MeteoriteBlock.TERRACOTTA, solid(stone, "Soil_Clay"));
        m.put(MeteoriteBlock.SAND, solid(stone, "Soil_Sand"));
        m.put(MeteoriteBlock.DIRT, solid(stone, "Soil_Dirt"));
        m.put(MeteoriteBlock.GRASS, solid(stone, "Soil_Grass"));
        m.put(MeteoriteBlock.COBBLESTONE, solid(stone, "Rock_Stone_Cobble"));
        m.put(MeteoriteBlock.STONE, new Material(stone, 0, (byte) 0));
        m.put(MeteoriteBlock.GRAVEL, solid(stone, "Soil_Gravel"));
        bedrock = block("Rock_Bedrock");
        return m;
    }

    /** Erster vorhandener Block der Liste, sonst {@code fallback}. */
    private static Material solid(int fallback, String... keys) {
        for (String key : keys) {
            int id = block(key);
            if (id > 0) {
                return new Material(id, 0, (byte) 0);
            }
        }
        return new Material(Math.max(fallback, 0), 0, (byte) 0);
    }

    private static Material fluid(String key, int fallbackBlock) {
        int id = Fluid.getAssetMap().getIndexOrDefault(key, Integer.MIN_VALUE);
        Fluid f = id <= 0 ? null : Fluid.getAssetMap().getAsset(id);
        if (f == null) {
            return new Material(Math.max(fallbackBlock, 0), 0, (byte) 0);
        }
        return new Material(0, id, (byte) f.getMaxFluidLevel());
    }

    /** Block-Index oder {@code Integer.MIN_VALUE}, wenn es den Block nicht gibt. */
    static int block(String key) {
        return BlockType.getAssetMap().getIndexOrDefault(key, Integer.MIN_VALUE);
    }

    /** Unzerstörbar: wird nie ersetzt (AE2: Bedrock). */
    static boolean indestructible(int blockId) {
        if (materials == null) {
            of(MeteoriteBlock.AIR);
        }
        return blockId == bedrock;
    }

    /** Fester Block (kein Luft-, Pflanzen- oder Deko-Block ohne Kollision). */
    static boolean solid(int blockId) {
        if (blockId == 0) {
            return false;
        }
        BlockType t = BlockType.getAssetMap().getAsset(blockId);
        return t != null && t.getMaterial() == BlockMaterial.Solid;
    }

    /** Darf einfach überschrieben werden: Luft (auch mit Flüssigkeit) und Pflanzen. */
    static boolean replaceable(int blockId) {
        return !solid(blockId);
    }
}
