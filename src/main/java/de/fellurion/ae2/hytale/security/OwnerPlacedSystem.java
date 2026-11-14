/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.security;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.components.PlacedByInteractionComponent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.fellurion.ae2.hytale.AE2BlockComponent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Trägt beim Platzieren den Besitzer in den AE2-Block ein.
 *
 * <p>Hytale ({@code BlockPlaceUtils.tryPlaceBlock}) hängt nach dem Platzieren durch einen Spieler eine
 * {@link PlacedByInteractionComponent} mit dessen UUID an die neue Block-Entity; dieses System reagiert darauf
 * (wie Hytales {@code CreateWarpWhenTeleporterPlacedSystem}) und schreibt UUID, Name und Zeitpunkt in
 * {@code AE2_Block.Config} (siehe {@link BlockOwners}).
 */
final class OwnerPlacedSystem extends RefChangeSystem<ChunkStore, PlacedByInteractionComponent> {

    private final ComponentType<ChunkStore, PlacedByInteractionComponent> placedByType;
    private final ComponentType<ChunkStore, AE2BlockComponent> ae2Type;
    private final Query<ChunkStore> query;

    OwnerPlacedSystem(ComponentType<ChunkStore, AE2BlockComponent> ae2Type) {
        this.placedByType = PlacedByInteractionComponent.getComponentType();
        this.ae2Type = ae2Type;
        this.query = Query.and(placedByType, ae2Type);
    }

    @Override
    @Nonnull
    public ComponentType<ChunkStore, PlacedByInteractionComponent> componentType() {
        return placedByType;
    }

    @Override
    @Nonnull
    public Query<ChunkStore> getQuery() {
        return query;
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull PlacedByInteractionComponent placedBy,
                                 @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> cb) {
        record(ref, placedBy, cb);
    }

    @Override
    public void onComponentSet(@Nonnull Ref<ChunkStore> ref, @Nullable PlacedByInteractionComponent old,
                               @Nonnull PlacedByInteractionComponent placedBy, @Nonnull Store<ChunkStore> store,
                               @Nonnull CommandBuffer<ChunkStore> cb) {
        record(ref, placedBy, cb);
    }

    // Laden eines Chunks löst hier nichts aus (Store.addEntity ruft nur RefSystem.onEntityAdded): der Zeitstempel
    // bleibt der vom Platzieren.

    @Override
    public void onComponentRemoved(@Nonnull Ref<ChunkStore> ref, @Nonnull PlacedByInteractionComponent placedBy,
                                   @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> cb) {
        // Besitz bleibt im AE2-Block gespeichert
    }

    private void record(Ref<ChunkStore> ref, PlacedByInteractionComponent placedBy, CommandBuffer<ChunkStore> cb) {
        try {
            UUID who = placedBy.getWhoPlacedUuid();
            AE2BlockComponent comp = cb.getComponent(ref, ae2Type);
            if (who == null || comp == null) {
                return;
            }
            // Derselbe Besitzer schon eingetragen (z. B. erneut gesetzte Komponente): Platzierzeit nie verjüngen,
            // sonst verschöbe sich, wem ein Netz gehört
            BlockOwners.Info known = BlockOwners.read(comp.config());
            if (known != null && known.owner().equals(who)) {
                return;
            }
            BlockOwners.write(comp.config(), who, AE2Security.knownName(who), System.currentTimeMillis());
            BlockModule.BlockStateInfo info = cb.getComponent(ref, BlockModule.BlockStateInfo.getComponentType());
            if (info != null) {
                info.markNeedsSaving();
            }
        } catch (RuntimeException ignored) {
            // Platzieren darf nie am Besitzer-Eintrag scheitern; dann gilt der Block als Altbestand
        }
    }
}
