/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.items;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Was ein Klick mit einem AE2-Item in der Hand tut – je Item-Id.
 *
 * <p>Anmelden beim Plugin-Start: {@code ItemUse.register("AE2_Certus_Quartz_Wrench", ctx -> ...)}. Damit Hytale den
 * Klick überhaupt meldet, braucht das Item im JSON {@code "Interactions": {"Secondary": "AE2_Use_Item"}} (Rechtsklick)
 * und/oder {@code "Primary": "AE2_Use_Item"} (Linksklick) – als Patch unter {@code tools/item_patches}. „Benutzen“
 * (F) eines Blocks läuft dagegen über den Block ({@code UseHandlers}), nicht über das Item.
 *
 * <p>Der Handler läuft auf dem Welt-Thread des Spielers. Er prüft selbst, ob der Spieler darf, was er tut
 * (ME-Netze: {@link ItemUseContext#asBlockUse()} und {@code AE2Security.checkUse}).
 */
public final class ItemUse {

    /** Ein Klick mit dem Item in der Hand. */
    @FunctionalInterface
    public interface Handler {
        void use(ItemUseContext ctx);
    }

    private static final Map<String, Handler> HANDLERS = new ConcurrentHashMap<>();

    private ItemUse() {
    }

    public static void register(String itemId, Handler handler) {
        if (HANDLERS.putIfAbsent(itemId, handler) != null) {
            throw new IllegalStateException("AE2: item use for '" + itemId + "' registered twice");
        }
    }

    @Nullable
    static Handler handler(String itemId) {
        return HANDLERS.get(itemId);
    }
}
