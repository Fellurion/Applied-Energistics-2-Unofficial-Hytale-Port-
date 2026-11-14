/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;

/**
 * Meldet AE2-Blöcke beim Platzieren/Laden an und beim Abbauen/Entladen ab
 * (entspricht in AE2 dem Erzeugen/Zerstören der GridNodes in AEBaseBlockEntity.onReady/setRemoved).
 */
public final class AE2BlockLifecycle extends RefSystem<ChunkStore> {

    /** Diagnose: {@code -Dae2.debug=true} protokolliert An-/Abmeldungen. */
    static final boolean DEBUG = Boolean.getBoolean("ae2.debug");

    private final ComponentType<ChunkStore, AE2BlockComponent> type;
    private final Query<ChunkStore> query;

    public AE2BlockLifecycle(ComponentType<ChunkStore, AE2BlockComponent> type) {
        this.type = type;
        this.query = Query.and(type, BlockModule.BlockStateInfo.getComponentType());
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return query;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull AddReason reason, @Nonnull Store<ChunkStore> store,
                              @Nonnull CommandBuffer<ChunkStore> cb) {
        AE2BlockComponent comp = cb.getComponent(ref, type);
        BlockModule.BlockStateInfo info = cb.getComponent(ref, BlockModule.BlockStateInfo.getComponentType());
        if (comp == null || info == null) {
            return;
        }
        Vector3i pos = new Vector3i();
        if (!info.fillWorldPos(store, pos)) {
            return;
        }
        World world = store.getExternalData().getWorld();
        if (DEBUG) {
            System.out.println("[AE2-DEBUG] add " + comp.kind() + " @" + pos.x + "," + pos.y + "," + pos.z + " reason=" + reason + " world=" + world.getName());
        }
        WorldNetworks.of(world).nodeAdded(BlockAccess.pack(pos.x, pos.y, pos.z), ref, comp, info, reason == AddReason.SPAWN);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<ChunkStore> store,
                               @Nonnull CommandBuffer<ChunkStore> cb) {
        BlockModule.BlockStateInfo info = cb.getComponent(ref, BlockModule.BlockStateInfo.getComponentType());
        if (info == null) {
            return;
        }
        Vector3i pos = new Vector3i();
        if (!info.fillWorldPos(store, pos)) {
            WorldNetworks.of(store.getExternalData().getWorld()).nodeRemovedByRef(ref);
            return;
        }
        World world = store.getExternalData().getWorld();
        if (DEBUG) {
            System.out.println("[AE2-DEBUG] remove @" + pos.x + "," + pos.y + "," + pos.z + " reason=" + reason + " world=" + world.getName());
        }
        WorldNetworks.of(world).nodeRemoved(BlockAccess.pack(pos.x, pos.y, pos.z), ref, reason == RemoveReason.REMOVE);
    }
}
