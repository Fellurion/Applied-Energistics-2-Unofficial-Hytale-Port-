/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.items;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.util.InteractionValidation;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;

/**
 * Interaktions-Typ {@code AE2UseItem}: wird über die RootInteraction {@code AE2_Use_Item} ausgelöst, wenn ein Spieler
 * mit einem AE2-Item in der Hand klickt (im Item-JSON {@code "Interactions": {"Secondary": "AE2_Use_Item"}}, für
 * Linksklick {@code "Primary"}), und an den bei {@link ItemUse} angemeldeten Handler des Items weitergereicht.
 */
public final class AE2UseItemInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "AE2UseItem";

    public static final BuilderCodec<AE2UseItemInteraction> CODEC = BuilderCodec
            .builder(AE2UseItemInteraction.class, AE2UseItemInteraction::new, SimpleInstantInteraction.CODEC)
            .build();

    public AE2UseItemInteraction() {
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context,
                            @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        ItemStack held = context.getHeldItem();
        if (commandBuffer == null || ItemStack.isEmpty(held)) {
            return;
        }
        ItemUse.Handler handler = ItemUse.handler(held.getItemId());
        if (handler == null) {
            return;
        }
        Ref<EntityStore> entity = context.getEntity();
        Player player = commandBuffer.getComponent(entity, Player.getComponentType());
        PlayerRef playerRef = commandBuffer.getComponent(entity, PlayerRef.getComponentType());
        if (player == null || playerRef == null) {
            return;
        }
        World world = commandBuffer.getExternalData().getWorld();
        // Der anvisierte Block kommt vom Client: nur gelten lassen, wenn der Spieler ihn wirklich erreichen kann
        Vector3i target = null;
        BlockPosition block = context.getTargetBlock();
        if (block != null) {
            BlockPosition raw = context.getTargetBlockRaw() != null ? context.getTargetBlockRaw() : block;
            if (InteractionValidation.canEntityInteractWithBlock(entity, commandBuffer, held, block, raw)) {
                target = new Vector3i(block.x, block.y, block.z);
            }
        }
        handler.use(new ItemUseContext(world, commandBuffer, entity, player, playerRef, type, context, held, target,
                context.getTargetEntity()));
    }

    @Override
    protected void simulateFirstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context,
                                    @Nonnull CooldownHandler cooldownHandler) {
        // nur auf dem Server
    }
}
