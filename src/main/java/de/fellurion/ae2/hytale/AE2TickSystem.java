/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.DelayedSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * AE2-Takt: 20× pro Sekunde je Welt (AE2 rechnet in Minecraft-Ticks à 50 ms).
 * Läuft im ChunkStore der Welt, also auf dem Welt-Thread.
 */
public final class AE2TickSystem extends DelayedSystem<ChunkStore> {

    public static final float INTERVAL_SECONDS = 0.05f;

    private final HytaleLogger logger;

    public AE2TickSystem(HytaleLogger logger) {
        super(INTERVAL_SECONDS);
        this.logger = logger;
    }

    @Override
    public void delayedTick(float dt, int systemIndex, @Nonnull Store<ChunkStore> store) {
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        try {
            WorldNetworks.of(world).tick();
        } catch (RuntimeException e) {
            logger.at(Level.SEVERE).withCause(e).log("AE2-Tick in Welt %s fehlgeschlagen", world.getName());
        }
    }
}
