/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.automation.FluidPickupStrategy, FluidPlacementStrategy).
 */
package de.fellurion.ae2.hytale.fluid;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.fluid.FluidTicker;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.fellurion.ae2.core.automation.FluidPickupStrategy;
import de.fellurion.ae2.core.automation.FluidPlacementStrategy;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.hytale.plane.PlaneTarget;
import de.fellurion.ae2.hytale.plane.Planes;

import javax.annotation.Nullable;

/**
 * Die Stelle vor einer Plane, wie sie die Fluid-Strategien des Kerns brauchen ({@link FluidPickupStrategy} der
 * Annihilation Plane, {@link FluidPlacementStrategy} der Formation Plane).
 *
 * <ul>
 *   <li><b>Aufnehmen:</b> nur eine <b>Quelle</b> einer lagerbaren Flüssigkeit ({@link FluidTypes}) – ein Block =
 *   1000 mB. Fließende Flüssigkeit, Feuer und gesperrte Flüssigkeiten bleiben stehen (AE2: Sperrliste der Plane).
 *   Die Quelle verschwindet, die Nachbarn fließen nach.</li>
 *   <li><b>Setzen:</b> eine Quelle mit vollem Pegel, wenn dort nicht schon dieselbe Quelle steht und die Stelle
 *   frei ist: Luft oder ein Block ohne festen Körper (Gras, Blumen – er bleibt stehen). Fließende und andere
 *   Flüssigkeit an der Stelle wird wie beim Eimer des Spiels ersetzt.</li>
 * </ul>
 * Verändert wird nur, wo die Welt Platzieren erlaubt (das prüft auch der Eimer), die Zone Änderungen zulässt und der
 * Bauschutz der Plane nichts entgegenhält ({@link PlaneTarget#mayModify()}); nicht geladene Stellen bleiben
 * unberührt. Nach jeder Änderung werden die Planes vor dieser Stelle geweckt ({@link Planes#blockChanged}).
 */
final class PlaneFluids implements FluidPickupStrategy.Target, FluidPlacementStrategy.Target {

    /** Töne des Eimers im Spiel. */
    private static final String SOUND_PICKUP = "SFX_Water_MoveIn";
    private static final String SOUND_PLACE = "SFX_Water_MoveOut";

    private final PlaneTarget target;

    PlaneFluids(PlaneTarget target) {
        this.target = target;
    }

    /**
     * Die Sektion des Ziels, wenn die Plane dort jetzt Flüssigkeit verändern darf, sonst null. Läuft gerade ein
     * Durchlauf eines Stores (das Netz wird aus einem Ereignis heraus bedient), wird nichts verändert.
     */
    @Nullable
    private Ref<ChunkStore> writable() {
        if (!target.inWorld()) {
            return null;
        }
        World world = target.world();
        Store<ChunkStore> store = world.getChunkStore().getStore();
        if (!world.isInThread() || store.isProcessing() || world.getEntityStore().getStore().isProcessing()) {
            return null;
        }
        if (!world.getGameplayConfig().getWorldConfig().isBlockPlacementAllowed()) {
            return null;
        }
        Ref<ChunkStore> section = target.section();
        if (section == null || !target.environmentAllows(section, target.x(), target.y(), target.z())
                || !target.mayModify()) {
            return null;
        }
        return section;
    }

    /** Nummer der Flüssigkeit am Ziel (0 = keine oder nicht geladen). */
    private int fluidIndex() {
        Ref<ChunkStore> section = target.inWorld() ? target.section() : null;
        FluidSection fluids = section == null ? null
                : target.world().getChunkStore().getStore().getComponent(section, FluidSection.getComponentType());
        return fluids == null ? 0 : fluids.getFluidId(target.x(), target.y(), target.z());
    }

    // ------------------------------------------------------------------ Annihilation Plane

    @Override
    @Nullable
    public AEFluidKey getSourceFluid() {
        // erst das Billige: steht dort überhaupt eine lagerbare Quelle?
        FluidTypes.Info info = FluidTypes.byIndex(fluidIndex());
        return info == null || writable() == null ? null : info.key();
    }

    @Override
    @Nullable
    public GenericStack pickUpSource(AEFluidKey expected) {
        FluidTypes.Info info = FluidTypes.byIndex(fluidIndex());
        Ref<ChunkStore> section = info == null || !info.key().equals(expected) ? null : writable();
        if (section == null) {
            return null;
        }
        int x = target.x(), y = target.y(), z = target.z();
        if (!FluidWorld.set(target.world(), section, x, y, z, 0)) {
            return null;
        }
        Planes.blockChanged(target.nets(), x, y, z);
        return new GenericStack(expected, AEFluidKey.AMOUNT_BLOCK);
    }

    @Override
    public void playPickupEffect(AEFluidKey what) {
        FluidWorld.sound(target.world(), SOUND_PICKUP, target.x(), target.y(), target.z());
    }

    // ------------------------------------------------------------------ Formation Plane

    @Override
    public boolean canPlace(AEFluidKey what) {
        return placeable(what) != null;
    }

    /** Die Sektion des Ziels, wenn dort jetzt eine Quelle von {@code what} gesetzt werden kann, sonst null. */
    @Nullable
    private Ref<ChunkStore> placeable(AEFluidKey what) {
        FluidTypes.Info info = FluidTypes.get(what.fluidId());
        Fluid fluid = info == null ? null : Fluid.getAssetMap().getAsset(info.index());
        if (fluid == null || fluidIndex() == info.index()) {
            return null; // gibt es hier nicht (mehr) bzw. steht schon da
        }
        Ref<ChunkStore> section = writable();
        BlockSection blocks = section == null ? null : target.blocks(section);
        if (blocks == null) {
            return null;
        }
        // Frei ist Luft und alles ohne festen Körper (Gras, Blumen) – wie für die Blöcke der Formation Plane
        // (AE2: isAir bzw. canBeReplaced); in Truhen und andere Blöcke mit Körper läuft nichts hinein
        int blockId = blocks.get(target.x(), target.y(), target.z());
        if (blockId != 0) {
            BlockType type = BlockType.getAssetMap().getAsset(blockId);
            if (type == null || (type != BlockType.EMPTY && type.getMaterial() != BlockMaterial.Empty)
                    || FluidTicker.isSolid(type)) {
                return null;
            }
        }
        return section;
    }

    @Override
    public boolean placeSource(AEFluidKey what) {
        Ref<ChunkStore> section = placeable(what);
        FluidTypes.Info info = FluidTypes.get(what.fluidId());
        if (section == null || info == null) {
            return false;
        }
        int x = target.x(), y = target.y(), z = target.z();
        boolean placed;
        try {
            placed = FluidWorld.set(target.world(), section, x, y, z, info.index());
        } catch (RuntimeException e) {
            // zählt als gesetzt, wenn die Quelle jetzt dort steht – sonst bleibt die Flüssigkeit im Netz
            placed = fluidIndex() == info.index();
        }
        if (placed) {
            Planes.blockChanged(target.nets(), x, y, z);
        }
        return placed;
    }

    @Override
    public void playPlaceEffect(AEFluidKey what) {
        FluidWorld.sound(target.world(), SOUND_PLACE, target.x(), target.y(), target.z());
    }
}
