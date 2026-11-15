/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.signal;

import com.hypixel.hytale.server.core.universe.world.World;
import de.fellurion.ae2.hytale.BlockAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Das AE2-Signal einer Welt – der Ersatz für Redstone, das es in Hytale nicht gibt.
 *
 * <p>Ein sendender Block (Level Emitter, Redstone-P2P-Ausgang, Light Detector ...) meldet mit {@link #setEmitting},
 * ob er gerade ein Signal abgibt. Ein Block gilt als „angesteuert“ ({@link #isPowered}), wenn einer seiner sechs
 * Nachbarn sendet – so wie ein Redstone-Signal in Minecraft den Block neben dem Emitter erreicht. Empfänger sind die
 * Geräte, die in AE2 auf Redstone hören: Busse mit Redstone-Karte, Toggle Bus, IO Port, Redstone-P2P-Eingang ...
 *
 * <p>Nur auf dem Welt-Thread benutzen. Sender melden sich beim Abbauen/Entladen selbst ab
 * ({@code setEmitting(..., false)} in {@code Device.onRemoved}).
 */
public final class Signals {

    private static final Map<String, Signals> BY_WORLD = new ConcurrentHashMap<>();

    public static Signals of(World world) {
        return BY_WORLD.computeIfAbsent(world.getName(), n -> new Signals());
    }

    public static void forget(World world) {
        BY_WORLD.remove(world.getName());
    }

    private final LongOpenHashSet emitting = new LongOpenHashSet();
    private long version;

    private Signals() {
    }

    /** Meldet, ob der Block an dieser Stelle gerade ein Signal abgibt. */
    public void setEmitting(int x, int y, int z, boolean on) {
        long pos = BlockAccess.pack(x, y, z);
        if (on ? emitting.add(pos) : emitting.remove(pos)) {
            version++;
        }
    }

    /** Gibt der Block an dieser Stelle selbst ein Signal ab? */
    public boolean isEmitting(int x, int y, int z) {
        return emitting.contains(BlockAccess.pack(x, y, z));
    }

    /** Wird der Block an dieser Stelle von einem benachbarten Sender angesteuert? */
    public boolean isPowered(int x, int y, int z) {
        if (emitting.isEmpty()) {
            return false;
        }
        return emitting.contains(BlockAccess.pack(x + 1, y, z)) || emitting.contains(BlockAccess.pack(x - 1, y, z))
                || emitting.contains(BlockAccess.pack(x, y + 1, z)) || emitting.contains(BlockAccess.pack(x, y - 1, z))
                || emitting.contains(BlockAccess.pack(x, y, z + 1)) || emitting.contains(BlockAccess.pack(x, y, z - 1));
    }

    /** Zählt jede Änderung – wer {@link #isPowered} zwischenspeichert, fragt nur bei neuer Version neu. */
    public long version() {
        return version;
    }
}
