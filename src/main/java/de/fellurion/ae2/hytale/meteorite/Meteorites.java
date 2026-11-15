/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.meteorite;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;

import javax.annotation.Nullable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Einstieg für alles rund um Meteoriten: Weltgenerierung ({@link MeteoriteWorldgen}), Einschlag-Ereignis
 * ({@link MeteoriteImpacts}), Bauschutz ({@link BuildProtection}), Verzeichnis ({@link MeteoriteRegistry}) und
 * Einstellungen ({@link MeteoriteConfig}).
 */
public final class Meteorites {

    @Nullable
    private static ScheduledFuture<?> saver;

    private Meteorites() {
    }

    public static void setup(JavaPlugin plugin) {
        BiConsumer<String, Throwable> warn = (message, error) ->
                plugin.getLogger().at(Level.WARNING).withCause(error).log("%s", message);
        MeteoriteConfig.load(plugin.getDataDirectory(), warn);
        MeteoriteRegistry.setup(plugin.getDataDirectory(), warn);
        MeteoriteWorldgen.setup(warn);
        MeteoriteImpacts.setup(warn, message -> plugin.getLogger().at(Level.INFO).log("%s", message));
        BuildProtection.setup(plugin);
        plugin.getEventRegistry().registerGlobal(ChunkPreLoadProcessEvent.class, MeteoriteWorldgen::onChunkPreLoad);
        plugin.getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent.class,
                e -> MeteoriteCompass.forget(e.getPlayerRef().getUuid()));
        saver = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(MeteoriteRegistry::saveAll, 30, 30, TimeUnit.SECONDS);
        MeteoriteConfig.Settings d = MeteoriteConfig.of("");
        plugin.getLogger().at(Level.INFO).log("AE2: meteorites – world generation %s, impact event %s by default",
                d.worldgen() ? "on" : "off", d.impacts() ? "on" : "off");
    }

    public static void shutdown() {
        if (saver != null) {
            saver.cancel(false);
            saver = null;
        }
        MeteoriteRegistry.saveAll();
    }
}
