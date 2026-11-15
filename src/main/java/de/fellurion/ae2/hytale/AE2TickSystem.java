/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * AE2-Takt: 20× pro Sekunde je Welt (AE2 rechnet in Minecraft-Ticks à 50 ms).
 * Läuft im ChunkStore der Welt, also auf dem Welt-Thread.
 *
 * <p>Hytales Welten ticken nicht mit 20, sondern meist mit 30 Ticks je Sekunde. Die verstrichene Zeit wird deshalb
 * aufsummiert und der Rest in den nächsten Welt-Tick mitgenommen: bei 30 Welt-Ticks laufen in drei Welt-Ticks zwei
 * AE2-Ticks. (Hytales {@code DelayedSystem} verwirft den Rest – damit lief AE2 nur 15× pro Sekunde.)
 */
public final class AE2TickSystem extends TickingSystem<ChunkStore> {

    public static final float INTERVAL_SECONDS = 0.05f;
    /** Höchstens so viele AE2-Ticks je Welt-Tick; hängt der Server, wird der Rückstand nicht nachgeholt. */
    private static final int MAX_TICKS_PER_WORLD_TICK = 2;
    /** Toleranz für Rundungsfehler beim Aufsummieren (3 × 1/30 s soll genau 2 AE2-Ticks ergeben). */
    private static final float EPSILON = 1e-4f;

    private final HytaleLogger logger;

    public AE2TickSystem(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<ChunkStore> store) {
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        WorldNetworks nets = WorldNetworks.of(world);
        float pending = nets.pendingTickSeconds + Math.max(0f, dt);
        int due = ticksDue(pending);
        nets.pendingTickSeconds = carry(pending, due);
        for (int i = 0; i < due; i++) {
            tickOnce(world, nets);
        }
    }

    /** Wie viele AE2-Ticks bei so viel aufgelaufener Zeit fällig sind (höchstens {@link #MAX_TICKS_PER_WORLD_TICK}). */
    static int ticksDue(float pendingSeconds) {
        int due = 0;
        while (pendingSeconds >= INTERVAL_SECONDS - EPSILON && due < MAX_TICKS_PER_WORLD_TICK) {
            pendingSeconds -= INTERVAL_SECONDS;
            due++;
        }
        return due;
    }

    /** Die Zeit, die nach {@code due} AE2-Ticks in den nächsten Welt-Tick mitgeht (nie mehr als ein AE2-Tick). */
    static float carry(float pendingSeconds, int due) {
        return Math.max(0f, Math.min(pendingSeconds - due * INTERVAL_SECONDS, INTERVAL_SECONDS));
    }

    private void tickOnce(World world, WorldNetworks nets) {
        try {
            nets.tick();
            long start = System.nanoTime();
            de.fellurion.ae2.hytale.meteorite.MeteoriteImpacts.tick(world);
            nets.perf().addAfterTick(AE2Perf.Phase.METEORITES, System.nanoTime() - start);
        } catch (RuntimeException e) {
            logger.at(Level.SEVERE).withCause(e).log("AE2 tick in world %s failed", world.getName());
        }
    }
}
