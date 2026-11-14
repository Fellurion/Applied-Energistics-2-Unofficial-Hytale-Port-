/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * Interaktions-Typ {@code AE2UseBlock}: wird über die RootInteraction {@code AE2_Use_Block} beim Benutzen
 * jedes AE2-Blocks ausgelöst und an {@link UseHandlers} nach {@code Kind} weitergereicht.
 */
public final class AE2UseBlockInteraction extends SimpleBlockInteraction {

    public static final String TYPE_ID = "AE2UseBlock";

    public static final BuilderCodec<AE2UseBlockInteraction> CODEC = BuilderCodec
            .builder(AE2UseBlockInteraction.class, AE2UseBlockInteraction::new, SimpleBlockInteraction.CODEC)
            .build();

    public AE2UseBlockInteraction() {
    }

    /** Alles, was ein Handler über den Benutzungs-Vorgang wissen muss. */
    public record UseContext(World world, CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> playerEntity,
                             Player player, PlayerRef playerRef, Vector3i pos, AE2BlockComponent block,
                             @Nullable ItemStack itemInHand) {

        public Store<EntityStore> entityStore() {
            return playerEntity.getStore();
        }
    }

    @Override
    protected void interactWithBlock(@Nonnull World world, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                     @Nonnull InteractionType type, @Nonnull InteractionContext context,
                                     @Nullable ItemStack itemInHand, @Nonnull Vector3i pos,
                                     @Nonnull CooldownHandler cooldownHandler) {
        Ref<EntityStore> entity = context.getEntity();
        Player player = commandBuffer.getComponent(entity, Player.getComponentType());
        PlayerRef playerRef = commandBuffer.getComponent(entity, PlayerRef.getComponentType());
        if (player == null || playerRef == null) {
            return;
        }
        AE2BlockComponent block = BlockAccess.component(AE2BlockComponent.getComponentType(), world, pos.x, pos.y, pos.z);
        if (block == null) {
            return;
        }
        UseHandlers.handle(new UseContext(world, commandBuffer, entity, player, playerRef, new Vector3i(pos), block, itemInHand));
    }

    @Override
    protected void simulateInteractWithBlock(@Nonnull InteractionType type, @Nonnull InteractionContext context,
                                             @Nullable ItemStack itemInHand, @Nonnull World world,
                                             @Nonnull Vector3i targetBlock) {
    }

    /**
     * Öffnet das Slot-Fenster des Blocks (Hytales Container-Ansicht neben dem Spielerinventar),
     * analog zu Hytales {@code OpenContainerInteraction}.
     */
    public static boolean openContainerWindow(UseContext ctx) {
        int x = ctx.pos().x, y = ctx.pos().y, z = ctx.pos().z;
        ItemContainerBlock icb = BlockAccess.component(ItemContainerBlock.getComponentType(), ctx.world(), x, y, z);
        BlockType bt = BlockAccess.blockType(ctx.world(), x, y, z);
        if (icb == null || bt == null) {
            return false;
        }
        int rot = BlockAccess.rotationIndex(ctx.world(), x, y, z);
        ContainerBlockWindow window = new ContainerBlockWindow(x, y, z, rot, bt, icb.getItemContainer());
        UUIDComponent uuidComponent = ctx.commandBuffer().getComponent(ctx.playerEntity(), UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return false;
        }
        UUID uuid = uuidComponent.getUuid();
        Map<UUID, ContainerBlockWindow> windows = icb.getWindows();
        if (windows.putIfAbsent(uuid, window) != null) {
            return false;
        }
        if (ctx.player().getPageManager().setPageWithWindows(ctx.playerEntity(), ctx.entityStore(), Page.Bench, true, window)) {
            window.registerCloseEvent(e -> windows.remove(uuid, window));
            return true;
        }
        windows.remove(uuid, window);
        return false;
    }
}
