/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.gadgets;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.util.TargetUtil;
import de.fellurion.ae2.hytale.items.ItemUseContext;
import org.joml.Vector3i;

import javax.annotation.Nullable;

/** Wohin ein Spieler mit dem Werkzeug zielt: Hytales Blick ({@link TargetUtil#getLook}) und die Reichweite des Items. */
final class Aim {

    private Aim() {
    }

    /** Augenposition und Blickrichtung des Spielers, oder null, wenn sie sich nicht ermitteln lassen. */
    @Nullable
    static Transform look(ItemUseContext ctx) {
        try {
            return TargetUtil.getLook(ctx.playerEntity(), ctx.commandBuffer());
        } catch (RuntimeException | AssertionError e) {
            return null;
        }
    }

    /** Wie weit der Spieler mit dem Item in der Hand Blöcke erreicht (wie Hytales eigene Prüfung). */
    static double reach(ItemUseContext ctx) {
        Item item = ctx.held().getItem();
        return item == null ? 5.0 : item.getInteractionConfig().getUseDistance(ctx.player().getGameMode());
    }

    /**
     * Die erste nicht leere Zelle im Blick des Spielers, in Reichweite.
     *
     * @param fluids true: auch eine Flüssigkeit hält den Blick auf (AE2: {@code ClipContext.Fluid.ANY})
     */
    @Nullable
    static WorldRay.Hit firstCell(ItemUseContext ctx, boolean fluids) {
        Transform look = look(ctx);
        if (look == null) {
            return null;
        }
        return WorldRay.cast(ctx.world(), look.getPosition(), look.getDirection(), reach(ctx),
                (block, fluid) -> block > 1 || (fluids && fluid != 0)); // 0 = Luft, 1 = unbekannt
    }

    /** Der anvisierte Block: der vom Client gemeldete, sonst der erste im Blick, den der Server findet; oder null. */
    @Nullable
    static Vector3i targetBlock(ItemUseContext ctx) {
        if (ctx.targetBlock() != null) {
            return ctx.targetBlock();
        }
        WorldRay.Hit hit = firstCell(ctx, false);
        return hit == null ? null : new Vector3i(hit.x(), hit.y(), hit.z());
    }
}
