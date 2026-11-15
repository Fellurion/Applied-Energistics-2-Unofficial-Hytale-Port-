/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.spatial.SpatialIOPortBlockEntity#triggerTransition / transition).
 */
package de.fellurion.ae2.hytale.spatial;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.World;
import de.fellurion.ae2.core.spatial.SpatialIOPortLogic;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Führt einen Raumtransfer aus. Ein Transfer läuft nie aus einem Tick-System heraus: er wird an die Welt übergeben
 * ({@code World.execute}, wie AE2s {@code TickHandler.addCallable}) und dort zwischen zwei Ticks in einem Stück
 * erledigt. Gemessen ({@code /ae2 spatial bench}, Gelände mit Wasser, ausgelasteter Rechner): 16³ etwa 6–15 ms, 128³
 * (2,1 Mio. Stellen, davon 1,2–1,8 Mio. mit Block oder Flüssigkeit) etwa 130–320 ms je Richtung. Das Verteilen über
 * mehrere Ticks lohnt dafür nicht – so gibt es keinen Zwischenzustand, den ein Server-Stopp treffen könnte, und keine
 * Sperre.
 */
final class SpatialTransfers {

    /** Ergebnis eines Versuchs samt Messwerten. */
    record Outcome(SpatialIOPortLogic.Result result, @Nullable RegionSwap.Refusal refusal, RegionSwap.Stats stats) {

        /** Meldung für den Spieler oder die Konsole ({@code ae2spatial.result.*} / {@code ae2spatial.refuse.*}). */
        Message message() {
            if (result == SpatialIOPortLogic.Result.REFUSED && refusal != null) {
                return refusalMessage(refusal);
            }
            return Message.translation("ae2spatial.result." + result.name());
        }
    }

    private SpatialTransfers() {
    }

    static Message refusalMessage(RegionSwap.Refusal refusal) {
        Message m = Message.translation("ae2spatial.refuse." + refusal.key());
        Object[] params = refusal.params();
        for (int i = 0; i + 1 < params.length; i += 2) {
            m.param(String.valueOf(params[i]), String.valueOf(params[i + 1]));
        }
        return m;
    }

    /**
     * Merkt einen Transfer am Port bei {@code x,y,z} vor; er läuft vor dem nächsten Tick der Welt.
     *
     * @param feedback bekommt das Ergebnis (Spieler, der ausgelöst hat), oder null (Signal)
     */
    static void request(WorldNetworks nets, int x, int y, int z, @Nullable Consumer<Outcome> feedback) {
        World world = nets.world();
        world.execute(() -> {
            Outcome outcome;
            try {
                outcome = run(nets, x, y, z);
            } catch (RuntimeException e) {
                Spatial.logger().at(Level.SEVERE).withCause(e).log("AE2: spatial transfer at %d %d %d in %s failed",
                        x, y, z, world.getName());
                outcome = new Outcome(SpatialIOPortLogic.Result.REFUSED, new RegionSwap.Refusal("failed"),
                        new RegionSwap.Stats());
            }
            if (feedback != null) {
                feedback.accept(outcome);
            }
        });
    }

    /** Führt den Transfer jetzt aus. Nur auf dem Welt-Thread und nicht aus einem System heraus. */
    static Outcome run(WorldNetworks nets, int x, int y, int z) {
        // Alle Welten teilen sich die Plots: immer nur ein Transfer zugleich
        synchronized (Spatial.store()) {
            return runLocked(nets, x, y, z);
        }
    }

    private static Outcome runLocked(WorldNetworks nets, int x, int y, int z) {
        RegionSwap.Stats stats = new RegionSwap.Stats();
        WorldNetworks.Node node = nets.node(x, y, z);
        if (node == null || !(node.device() instanceof IOPortDevice port)) {
            return new Outcome(SpatialIOPortLogic.Result.REFUSED, new RegionSwap.Refusal("noPort"), stats);
        }
        SpatialWorld spatial = SpatialWorld.of(nets);
        if (spatial.transferring) {
            return new Outcome(SpatialIOPortLogic.Result.REFUSED, new RegionSwap.Refusal("busy"), stats);
        }
        SpatialStore store = Spatial.store();
        World world = nets.world();
        RegionSwap.Refusal[] refusal = {null};
        int[] region = new int[6];
        spatial.transferring = true;
        SpatialIOPortLogic.Result result;
        try {
            result = port.logic().transition(port.host(nets, node), (plot, fresh, bx, by, bz, sx, sy, sz) -> {
                region[0] = bx;
                region[1] = by;
                region[2] = bz;
                region[3] = sx;
                region[4] = sy;
                region[5] = sz;
                if (x >= bx && y >= by && z >= bz && x < bx + sx && y < by + sy && z < bz + sz) {
                    // Der Port würde samt Zelle in den Plot seiner eigenen Zelle wandern
                    refusal[0] = new RegionSwap.Refusal("portInside");
                } else {
                    refusal[0] = RegionSwap.swap(store, Spatial.rules(), world, plot, fresh, bx, by, bz, sx, sy, sz,
                            x, z, stats);
                }
                return refusal[0] == null ? null : refusal[0].key();
            }, System.currentTimeMillis());
        } finally {
            spatial.transferring = false;
        }
        if (result == SpatialIOPortLogic.Result.OK) {
            Spatial.logger().at(Level.INFO).log("AE2: spatial transfer in %s at %d %d %d, %dx%dx%d: %d blocks and %d "
                            + "block entities stored, %d blocks and %d block entities placed, %d positions skipped, "
                            + "%.1f ms", world.getName(), region[0], region[1], region[2], region[3], region[4],
                    region[5], stats.blocksOut, stats.entitiesOut, stats.blocksIn, stats.entitiesIn, stats.skipped,
                    stats.totalNanos / 1e6);
        }
        return new Outcome(result, refusal[0], stats);
    }
}
