/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Der AE2-Takt läuft unabhängig von der Tickrate der Welt mit 20 Ticks je Sekunde. */
class AE2TickSystemTest {

    private static int run(float dt, int worldTicks) {
        float pending = 0;
        int total = 0;
        for (int i = 0; i < worldTicks; i++) {
            pending += dt;
            int due = AE2TickSystem.ticksDue(pending);
            pending = AE2TickSystem.carry(pending, due);
            total += due;
        }
        return total;
    }

    @Test
    void thirtyWorldTicksGiveTwentyAe2Ticks() {
        assertEquals(200, run(1f / 30f, 300), 1);
    }

    @Test
    void twentyWorldTicksGiveTwentyAe2Ticks() {
        assertEquals(200, run(0.05f, 200), 1);
    }

    @Test
    void sixtyWorldTicksGiveTwentyAe2Ticks() {
        assertEquals(200, run(1f / 60f, 600), 1);
    }

    @Test
    void slowWorldCatchesUpWithTwoTicksAtMost() {
        // 10 Welt-Ticks je Sekunde: zwei AE2-Ticks je Welt-Tick
        assertEquals(200, run(0.1f, 100), 1);
        assertEquals(2, AE2TickSystem.ticksDue(5f));
    }

    @Test
    void backlogOfAHangingServerIsDropped() {
        float pending = AE2TickSystem.carry(5f, AE2TickSystem.ticksDue(5f));
        assertTrue(pending <= AE2TickSystem.INTERVAL_SECONDS + 1e-6f);
    }
}
