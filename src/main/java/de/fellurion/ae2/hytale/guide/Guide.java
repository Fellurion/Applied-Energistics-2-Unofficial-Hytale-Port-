/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.guide;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import de.fellurion.ae2.hytale.items.ItemUse;
import de.fellurion.ae2.hytale.items.ItemUseContext;

/**
 * Das Buch „AE2 Basics“ ({@value #BOOK}): Rechts- oder Linksklick mit dem Buch in der Hand schlägt es auf (die
 * Interaktion {@code AE2_Open_Book} spielt vorher die Geste und das Buchgeräusch). Der „Guide“ aus dem Mysterious
 * Cube öffnet dasselbe Buch.
 */
public final class Guide {

    public static final String BOOK = "AE2_Basics_Book";
    public static final String GUIDE = "AE2_Guide";

    private Guide() {
    }

    public static void setup(JavaPlugin plugin) {
        ItemUse.register(BOOK, Guide::open);
        ItemUse.register(GUIDE, Guide::open);
    }

    static void open(ItemUseContext ctx) {
        ctx.player().getPageManager().openCustomPage(ctx.playerEntity(), ctx.entityStore(), new GuideBookPage(ctx.playerRef()));
    }
}
