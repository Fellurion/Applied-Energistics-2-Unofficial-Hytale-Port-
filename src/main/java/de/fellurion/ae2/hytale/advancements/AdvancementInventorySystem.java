/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: minecraft:inventory_changed).
 */
package de.fellurion.ae2.hytale.advancements;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Item-Erfolge: Hytale feuert {@link InventoryChangeEvent} für jeden geänderten Spieler-Container (Hotbar,
 * Inventar, Rucksack …) – egal ob aufgehoben, hergestellt, aus einem Terminal genommen oder im Kreativmodus
 * geholt. Geprüft wird nur der geänderte Container (wenige Slots, Index-Lookup je Item).
 */
final class AdvancementInventorySystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    AdvancementInventorySystem() {
        super(InventoryChangeEvent.class);
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InventoryChangeEvent event) {
        PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
        if (player == null || event.getItemContainer() == null) {
            return;
        }
        try {
            AE2Advancements.onContainerChanged(player, event.getItemContainer());
        } catch (RuntimeException ignored) {
            // Erfolge dürfen die Inventarverarbeitung nie stören
        }
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), PlayerRef.getComponentType());
    }
}
