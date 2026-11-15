/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.meteorite;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.world.World;
import de.fellurion.ae2.hytale.device.Drops;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mysterious Cube im Creative-Modus: Hytale lässt dort beim Abbauen grundsätzlich nichts fallen. Damit man in
 * Creative-Welten trotzdem an die Pressen eines Meteoriten kommt, lässt AE2 den Inhalt des Würfels selbst fallen –
 * aber erst, wenn der Block wirklich entfernt wurde (ein anderes Plugin kann den Abbau noch verhindern).
 */
public final class CubeDrops {

    static final String BLOCK = "AE2_Mysterious_Cube";
    private static final String DROP_LIST = "AE2_Mysterious_Cube";
    /** So lange darf zwischen Abbau-Ereignis und Entfernen des Blocks höchstens liegen. */
    private static final long WINDOW_MILLIS = 5000;

    /** Gerade im Creative-Modus angeschlagene Würfel: Welt|x|y|z → Zeitpunkt. */
    private static final Map<String, Long> PENDING = new ConcurrentHashMap<>();

    private CubeDrops() {
    }

    private static String key(World world, int x, int y, int z) {
        return world.getName() + '|' + x + '|' + y + '|' + z;
    }

    /** Ein Spieler, der nicht im Abenteuer-Modus ist, baut den Würfel ab (das Ereignis kommt vor dem Entfernen). */
    static void brokenWithoutDrops(World world, int x, int y, int z) {
        long now = System.currentTimeMillis();
        PENDING.values().removeIf(t -> now - t > WINDOW_MILLIS);
        PENDING.put(key(world, x, y, z), now);
    }

    /** Der Würfel an dieser Stelle wurde entfernt: fallen lassen, falls Hytale es wegen des Spielmodus nicht tut. */
    public static void onRemoved(World world, int x, int y, int z) {
        Long since = PENDING.remove(key(world, x, y, z));
        if (since == null || System.currentTimeMillis() - since > WINDOW_MILLIS) {
            return;
        }
        ItemModule items = ItemModule.get();
        if (items == null || !items.isEnabled()) {
            return;
        }
        List<ItemStack> drops = items.getRandomItemDrops(DROP_LIST);
        if (drops != null && !drops.isEmpty()) {
            Drops.at(world, x, y, z, drops);
        }
    }
}
