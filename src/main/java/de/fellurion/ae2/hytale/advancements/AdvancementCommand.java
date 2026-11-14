/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.advancements;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * {@code /ae2 erfolge} (Alias {@code advancements}) öffnet die Erfolgsübersicht – für alle Spieler.
 * {@code /ae2 erfolge reset} setzt die eigenen Erfolge zurück (Recht {@code …command.ae2.erfolge.reset}).
 */
final class AdvancementCommand extends AbstractPlayerCommand {

    AdvancementCommand() {
        super("erfolge", "Zeigt deine AE2-Erfolge");
        addAliases("advancements");
        requireNoPermission();
        addSubCommand(new Reset());
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        // Items, die schon vor dem Plugin im Inventar lagen, sofort berücksichtigen
        AE2Advancements.scanInventory(ref, store);
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store, new AdvancementsPage(playerRef));
        }
    }

    /** Setzt die eigenen AE2-Erfolge zurück (zum Testen). */
    private static final class Reset extends AbstractPlayerCommand {
        Reset() {
            super("reset", "Setzt deine AE2-Erfolge zurück");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            AE2Advancements.reset(playerRef.getUuid());
            context.sendMessage(Message.translation("ae2adv.command.reset"));
        }
    }
}
