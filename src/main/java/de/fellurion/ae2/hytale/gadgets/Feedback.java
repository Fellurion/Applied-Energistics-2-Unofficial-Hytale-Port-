/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.gadgets;

import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rückmeldungen der Werkzeuge: Hinweis an den Spieler, Partikel und Töne aus den Hytale-Assets. Nichts davon darf
 * eine Aktion stören – fehlt ein Asset in dieser Spielversion, bleibt es still.
 */
final class Feedback {

    /** Text-Schlüssel: der Akku reicht nicht ({@code {needed}} = benötigte AE). */
    static final String NO_POWER = "ae2gadget.noPower";

    private static final long QUIET_MS = 10_000L;
    private static final Map<UUID, Long> LAST_NO_POWER = new ConcurrentHashMap<>();

    private Feedback() {
    }

    /** Kurzer Hinweis am Bildschirmrand. */
    static void tell(PlayerRef player, Message message, NotificationStyle style) {
        try {
            com.hypixel.hytale.server.core.util.NotificationUtil.sendNotification(player.getPacketHandler(), message, style);
        } catch (RuntimeException e) {
            // Verbindung weg – dann eben ohne
        }
    }

    static void tell(PlayerRef player, String key) {
        tell(player, Message.translation(key), NotificationStyle.Default);
    }

    /** „Nicht genug Energie“ mit dem Bedarf der Aktion. */
    static void noPower(PlayerRef player, double needed) {
        tell(player, Message.translation(NO_POWER).param("needed", Long.toString((long) needed)), NotificationStyle.Warning);
    }

    /**
     * „Nicht genug Energie“ für Dinge, die bei jedem Schlag auffallen würden: höchstens alle {@value #QUIET_MS} ms
     * je Spieler.
     */
    static void noPowerOnceInAWhile(PlayerRef player, double needed) {
        long now = System.currentTimeMillis();
        Long last = LAST_NO_POWER.get(player.getUuid());
        if (last != null && now - last < QUIET_MS) {
            return;
        }
        if (LAST_NO_POWER.size() > 512) {
            LAST_NO_POWER.clear(); // Spieler von früher: der Speicher bleibt klein
        }
        LAST_NO_POWER.put(player.getUuid(), now);
        noPower(player, needed);
    }

    /** Partikel für alle Spieler in Sichtweite. */
    static void particle(World world, String systemId, double x, double y, double z) {
        try {
            ParticleUtil.spawnParticleEffect(systemId, new Vector3d(x, y, z), world.getEntityStore().getStore());
        } catch (RuntimeException e) {
            // Partikel gibt es nicht
        }
    }

    /** Ton an einer Stelle der Welt. */
    static void sound(World world, String soundEventId, double x, double y, double z) {
        try {
            int index = SoundEvent.getAssetMap().getIndexOrDefault(soundEventId, 0);
            if (index > 0) {
                SoundUtil.playSoundEvent3d(index, SoundCategory.SFX, x, y, z, world.getEntityStore().getStore());
            }
        } catch (RuntimeException e) {
            // Ton gibt es nicht
        }
    }
}
