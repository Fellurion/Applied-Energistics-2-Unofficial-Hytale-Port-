/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tiny TNT numbers and singularity frequencies (AE2: TinyTNTPrimedEntity, QuantumBridgeBlockEntity). */
class TinyTntAndSingularityTest {

    @Test
    void aChargeSetOffByAnExplosionHasAShortFuse() {
        var random = new Random(7);
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int i = 0; i < 10_000; i++) {
            int fuse = TinyTntLogic.chainFuse(random);
            min = Math.min(min, fuse);
            max = Math.max(max, fuse);
        }
        assertEquals(10, min, "fuse / 8");
        assertEquals(29, max, "fuse / 8 + fuse / 4 − 1");
        assertEquals(80, TinyTntLogic.FUSE_TICKS);
    }

    @Test
    void ae2sBlastReachesSoilFurtherThanStone() {
        float dirt = 0.5f, stone = 6.0f, obsidian = 1200f;
        assertTrue(TinyTntLogic.destroys(1.0, stone), "stone next to the charge");
        assertFalse(TinyTntLogic.destroys(2.0, stone), "stone diagonally");
        assertTrue(TinyTntLogic.destroys(2.0, dirt), "soil diagonally");
        assertFalse(TinyTntLogic.destroys(3.0, dirt), "nothing at the corners");
        assertFalse(TinyTntLogic.destroys(0.0, obsidian));
    }

    @Test
    void frequenciesAreNeverZeroAndDistinct() {
        var seen = new HashSet<Long>();
        for (int i = 0; i < 100; i++) {
            long f = SingularityFrequency.next(1_700_000_000_000L);
            assertNotEquals(0, f);
            assertTrue(seen.add(f), "100 pairs made in the same millisecond stay distinct");
            assertEquals(1_700_000_000_000L, f / 100, "AE2: time in ms times 100 plus a counter");
        }
        assertNotEquals(0, SingularityFrequency.next(0));
        assertNotEquals(0, SingularityFrequency.next());
    }
}
