/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.fluid;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nullable;

/**
 * Flüssigkeit an einer Stelle der Welt lesen, als Quelle setzen und entfernen – so, wie es das Spiel mit dem Eimer
 * tut ({@code PlaceFluidInteraction}, {@code RefillContainerInteraction}): Die Flüssigkeit steht in der
 * {@link FluidSection} der Chunk-Sektion, die Nachbarn werden zum Nachfließen geweckt. Ein Ereignis gibt es dafür im
 * Spiel nicht; ob eine Stelle verändert werden darf, prüft der Aufrufer vorher.
 *
 * <p>Nur auf dem Thread der Welt und nie, während ein Store gerade durchlaufen wird. Geladen wird nichts.
 */
final class FluidWorld {

    private FluidWorld() {
    }

    /** Nummer der Flüssigkeit an der Stelle (0 = keine); -1, wenn die Stelle nicht geladen ist. */
    static int fluidIndex(World world, int x, int y, int z) {
        Ref<ChunkStore> section = section(world, x, y, z);
        if (section == null) {
            return -1;
        }
        FluidSection fluids = world.getChunkStore().getStore().getComponent(section, FluidSection.getComponentType());
        return fluids == null ? 0 : fluids.getFluidId(x, y, z);
    }

    /** Asset-Kennung der Flüssigkeit an der Stelle, oder null (keine, nicht geladen). */
    @Nullable
    static String fluidId(World world, int x, int y, int z) {
        int index = fluidIndex(world, x, y, z);
        Fluid fluid = index <= 0 ? null : Fluid.getAssetMap().getAsset(index);
        return fluid == null ? null : fluid.getId();
    }

    /** Pegel der Flüssigkeit an der Stelle (0 = keine oder nicht geladen). */
    static int fluidLevel(World world, int x, int y, int z) {
        Ref<ChunkStore> section = section(world, x, y, z);
        FluidSection fluids = section == null ? null
                : world.getChunkStore().getStore().getComponent(section, FluidSection.getComponentType());
        return fluids == null ? 0 : fluids.getFluidLevel(x, y, z);
    }

    @Nullable
    static Ref<ChunkStore> section(World world, int x, int y, int z) {
        if (y < 0 || y >= 320) {
            return null;
        }
        Ref<ChunkStore> section = world.getChunkStore().getChunkSectionReferenceAtBlock(x, y, z);
        return section == null || !section.isValid() ? null : section;
    }

    /**
     * Setzt die Flüssigkeit der Stelle mit vollem Pegel (eine Quelle bleibt so stehen) bzw. entfernt sie
     * ({@code fluidIndex == 0}).
     *
     * @param section die geladene Sektion der Stelle
     * @return true, wenn sich etwas geändert hat
     */
    static boolean set(World world, Ref<ChunkStore> section, int x, int y, int z, int fluidIndex) {
        ChunkStore chunks = world.getChunkStore();
        Store<ChunkStore> store = chunks.getStore();
        BlockSection blocks = store.getComponent(section, BlockSection.getComponentType());
        ChunkSection chunkSection = store.getComponent(section, ChunkSection.getComponentType());
        if (blocks == null || chunkSection == null) {
            return false;
        }
        FluidSection fluids = store.getComponent(section, FluidSection.getComponentType());
        boolean changed;
        if (fluidIndex <= 0) {
            changed = fluids != null && fluids.setFluid(x, y, z, 0, (byte) 0);
        } else {
            Fluid fluid = Fluid.getAssetMap().getAsset(fluidIndex);
            if (fluid == null) {
                return false;
            }
            if (fluids == null) {
                fluids = store.ensureAndGetComponent(section, FluidSection.getComponentType());
            }
            changed = fluids.setFluid(x, y, z, fluidIndex, (byte) fluid.getMaxFluidLevel());
            if (changed) {
                blocks.setTicking(x, y, z, true);
            }
        }
        if (changed) {
            chunkSection.markNeedsSaving();
            BlockOperations.updateBlockArea(chunks, chunkSection, blocks, x, y, z);
        }
        return changed;
    }

    /** Ton an der Stelle; fehlt er in dieser Spielversion, bleibt es still. */
    static void sound(World world, String soundEventId, int x, int y, int z) {
        try {
            int index = SoundEvent.getAssetMap().getIndexOrDefault(soundEventId, 0);
            if (index > 0) {
                SoundUtil.playSoundEvent3d(index, SoundCategory.SFX, x + 0.5, y + 0.5, z + 0.5,
                        world.getEntityStore().getStore());
            }
        } catch (RuntimeException e) {
            // Ton gibt es nicht
        }
    }
}
