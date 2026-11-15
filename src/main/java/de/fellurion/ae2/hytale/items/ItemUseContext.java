/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.items;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.AE2BlockComponent;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction.UseContext;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import org.joml.Vector3i;

import javax.annotation.Nullable;

/**
 * Ein Klick mit einem AE2-Item in der Hand.
 *
 * @param type         {@code Primary} (Linksklick) oder {@code Secondary} (Rechtsklick)
 * @param held         das Item in der Hand beim Klick (unveränderlich; Ersatz mit {@link #setHeld})
 * @param targetBlock  anvisierter Block in Reichweite, oder null (Klick in die Luft / zu weit weg)
 * @param targetEntity anvisiertes Wesen, oder null
 */
public record ItemUseContext(World world, CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> playerEntity,
                             Player player, PlayerRef playerRef, InteractionType type, InteractionContext interaction,
                             ItemStack held, @Nullable Vector3i targetBlock, @Nullable Ref<EntityStore> targetEntity) {

    public Store<EntityStore> entityStore() {
        return playerEntity.getStore();
    }

    /** Linksklick? (sonst Rechtsklick) */
    public boolean primary() {
        return type == InteractionType.Primary;
    }

    /** Duckt sich der Spieler gerade? (AE2: Umschalt-Klick) */
    public boolean crouching() {
        try {
            MovementStatesComponent states = commandBuffer.getComponent(playerEntity, MovementStatesComponent.getComponentType());
            return states != null && states.getMovementStates() != null && states.getMovementStates().crouching;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Ersetzt das Item in der Hand (geänderte Metadaten, Ladung, verbraucht = null). Schlägt fehl, wenn dort
     * inzwischen etwas anderes liegt – dann wird nichts angefasst.
     *
     * @return true, wenn das Item ersetzt wurde
     */
    public boolean setHeld(@Nullable ItemStack stack) {
        ItemContainer container = interaction.getHeldItemContainer();
        short slot = interaction.getHeldItemSlot();
        if (container == null || slot < 0 || slot >= container.getCapacity()) {
            return false;
        }
        ItemStack current = container.getItemStack(slot);
        if (ItemStack.isEmpty(current) || !current.getItemId().equals(held.getItemId())
                || current.getQuantity() != held.getQuantity()) {
            return false;
        }
        boolean ok = ItemStack.isEmpty(stack)
                ? container.removeItemStackFromSlot(slot).succeeded()
                : container.setItemStackForSlot(slot, stack).succeeded();
        if (ok) {
            interaction.setHeldItem(ItemStack.isEmpty(stack) ? null : stack);
        }
        return ok;
    }

    /** Der AE2-Block an der anvisierten Stelle, sobald er im Netz angemeldet ist; sonst null. */
    @Nullable
    public WorldNetworks.Node targetNode() {
        return targetBlock == null ? null
                : WorldNetworks.of(world).node(targetBlock.x, targetBlock.y, targetBlock.z);
    }

    /**
     * Der Klick als „Benutzen“ des anvisierten AE2-Blocks – für alles, was einen {@link UseContext} erwartet
     * (Zugriffsprüfung {@code AE2Security.checkUse}, Seiten öffnen). Null, wenn dort kein AE2-Block steht.
     */
    @Nullable
    public UseContext asBlockUse() {
        if (targetBlock == null) {
            return null;
        }
        AE2BlockComponent block = BlockAccess.component(AE2BlockComponent.getComponentType(), world,
                targetBlock.x, targetBlock.y, targetBlock.z);
        return block == null ? null
                : new UseContext(world, commandBuffer, playerEntity, player, playerRef, new Vector3i(targetBlock), block, held);
    }
}
