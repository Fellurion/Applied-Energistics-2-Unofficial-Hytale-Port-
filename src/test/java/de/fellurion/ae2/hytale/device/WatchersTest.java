/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.device;

import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offene Seiten eines Geräts: Takt, Abmelden, kaputte Beobachter. */
class WatchersTest {

    @Test
    void laeuftNurImTaktUndNurMitBeobachtern() {
        Watchers watchers = new Watchers();
        int[] calls = {0};
        watchers.tick(0); // ohne Beobachter passiert nichts
        watchers.add(() -> {
            calls[0]++;
            return true;
        });
        for (long t = 1; t <= 3L * Watchers.INTERVAL_TICKS; t++) {
            watchers.tick(t);
        }
        assertEquals(3, calls[0]);
        watchers.run();
        assertEquals(4, calls[0], "run() ruft sofort");
    }

    @Test
    void werFalschLiefertOderScheitertFliegtRaus() {
        Watchers watchers = new Watchers();
        int[] calls = {0, 0, 0};
        BooleanSupplier keeps = () -> {
            calls[0]++;
            return true;
        };
        watchers.add(keeps);
        watchers.add(() -> {
            calls[1]++;
            return false;
        });
        watchers.add(() -> {
            calls[2]++;
            throw new IllegalStateException("kaputte Seite");
        });
        watchers.run();
        watchers.run();
        assertEquals(2, calls[0]);
        assertEquals(1, calls[1], "abgemeldet nach false");
        assertEquals(1, calls[2], "abgemeldet nach Fehler");
        assertFalse(watchers.isEmpty());
        watchers.remove(keeps);
        assertTrue(watchers.isEmpty());
    }
}
