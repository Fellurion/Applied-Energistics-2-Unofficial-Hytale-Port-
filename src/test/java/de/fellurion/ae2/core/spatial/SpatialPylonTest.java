/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.grid.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pylon lines ({@code SpatialPylonCalculator}) and the region, validity, efficiency and power rules of
 * {@code SpatialPylonService}.
 */
class SpatialPylonTest {
    private final Set<Long> pylons = new HashSet<>();

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        pylons.clear();
    }

    private void line(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    pylons.add(BlockPos.asLong(x, y, z));
                }
            }
        }
    }

    private SpatialPylonCluster at(int x, int y, int z) {
        return SpatialPylonCalculator.calculate((a, b, c) -> pylons.contains(BlockPos.asLong(a, b, c)), x, y, z);
    }

    /** The clusters of all pylons, as the integration layer hands them to the service. */
    private List<SpatialPylonCluster> allClusters() {
        List<SpatialPylonCluster> list = new ArrayList<>();
        for (long p : pylons) {
            SpatialPylonCluster c = at(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
            if (c != null) {
                list.add(c);
            }
        }
        return list;
    }

    // --- clusters ---

    @Test
    void singlePylonFormsNothing() {
        line(0, 0, 0, 0, 0, 0);
        assertNull(at(0, 0, 0));
    }

    @Test
    void lineFormsOneClusterForAllItsPylons() {
        line(5, 64, -3, 9, 64, -3);
        SpatialPylonCluster c = at(7, 64, -3);
        assertNotNull(c);
        assertEquals(SpatialPylonCluster.Axis.X, c.axis());
        assertEquals(5, c.size());
        assertEquals(5, c.minX());
        assertEquals(9, c.maxX());
        assertEquals(c, at(5, 64, -3));
        assertEquals(c, at(9, 64, -3));
        assertTrue(c.isStart(5, 64, -3));
        assertTrue(c.isEnd(9, 64, -3));
        assertFalse(c.isEnd(8, 64, -3));
    }

    @Test
    void axisFollowsTheLine() {
        line(0, 0, 0, 0, 3, 0);
        assertEquals(SpatialPylonCluster.Axis.Y, at(0, 1, 0).axis());
        pylons.clear();
        line(0, 0, 0, 0, 0, 1);
        assertEquals(SpatialPylonCluster.Axis.Z, at(0, 0, 0).axis());
        assertEquals(2, at(0, 0, 1).size());
    }

    @Test
    void linesMeetingInACornerDoNotForm() {
        line(0, 0, 0, 3, 0, 0);
        line(0, 1, 0, 0, 3, 0);
        for (long p : pylons) {
            assertNull(at(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p)), BlockPos.toString(p));
        }
    }

    @Test
    void pylonTouchingTheSideBreaksTheLine() {
        line(0, 0, 0, 0, 0, 3);
        assertNotNull(at(0, 0, 0));
        line(1, 0, 2, 1, 0, 2);
        assertNull(at(0, 0, 0));
        assertNull(at(0, 0, 2));
        assertNull(at(1, 0, 2));
    }

    @Test
    void parallelLinesSideBySideDoNotForm() {
        line(0, 0, 0, 3, 0, 0);
        line(0, 0, 1, 3, 0, 1);
        assertNull(at(0, 0, 0));
        assertNull(at(2, 0, 1));
    }

    @Test
    void linesWithAGapFormSeparately() {
        line(0, 0, 0, 2, 0, 0);
        line(4, 0, 0, 6, 0, 0);
        assertEquals(3, at(1, 0, 0).size());
        assertEquals(3, at(5, 0, 0).size());
        assertNotEquals(at(1, 0, 0), at(5, 0, 0));
    }

    // --- service ---

    /** Three lines along the edges that leave the corner (0,0,0) free; the box is 0..3 on every axis. */
    private void smallFrame() {
        line(1, 0, 0, 3, 0, 0);
        line(0, 1, 0, 0, 3, 0);
        line(0, 0, 1, 0, 0, 3);
    }

    @Test
    void noPylonsNoRegion() {
        SpatialPylonService s = new SpatialPylonService();
        s.reset(List.of());
        assertFalse(s.hasRegion());
        assertFalse(s.isValidRegion());
        assertEquals(0, s.requiredPower());
        assertEquals(0f, s.currentEfficiency());
        assertEquals(0, s.sizeX());
    }

    @Test
    void frameGivesRegionEfficiencyAndPower() {
        smallFrame();
        SpatialPylonService s = new SpatialPylonService();
        s.reset(allClusters());
        assertTrue(s.hasRegion());
        assertTrue(s.isValidRegion());
        assertEquals(0, s.getMinX());
        assertEquals(3, s.getMaxY());
        // the transferred blocks are the ones strictly inside the pylon box
        assertEquals(1, s.innerMinX());
        assertEquals(1, s.innerMinY());
        assertEquals(1, s.innerMinZ());
        assertEquals(2, s.sizeX());
        assertEquals(2, s.sizeY());
        assertEquals(2, s.sizeZ());
        assertEquals(9, s.pylonCount());
        assertEquals(3, s.clusterCount());
        // 9 pylons of max(6, 27 * 3 / 8) = 10 required
        assertEquals(90f, s.currentEfficiency(), 0.001f);
        // (3*3*3 * 1250) ^ (1 + 0.35 * 0.1)
        assertEquals(48614, s.requiredPower());
        assertTrue(s.isValid(at(1, 0, 0)));
    }

    @Test
    void efficiencyIsCappedAndPowerIsLinearAtFullEfficiency() {
        // all twelve edges of the 0..3 box without the corners: 12 lines of 2 pylons
        for (int a = 0; a <= 3; a += 3) {
            for (int b = 0; b <= 3; b += 3) {
                line(1, a, b, 2, a, b);
                line(a, 1, b, a, 2, b);
                line(a, b, 1, a, b, 2);
            }
        }
        SpatialPylonService s = new SpatialPylonService();
        s.reset(allClusters());
        assertTrue(s.isValidRegion());
        assertEquals(24, s.pylonCount());
        assertEquals(100f, s.currentEfficiency());
        assertEquals(33750, s.requiredPower());
    }

    @Test
    void fewerPylonsCostMorePower() {
        // two pylons per line: 6 of 10
        line(2, 0, 0, 3, 0, 0);
        line(0, 2, 0, 0, 3, 0);
        line(0, 0, 2, 0, 0, 3);
        SpatialPylonService s = new SpatialPylonService();
        s.reset(allClusters());
        assertTrue(s.isValidRegion());
        assertEquals(3, s.getMaxY());
        assertEquals(60f, s.currentEfficiency(), 0.001f);
        assertEquals(145288, s.requiredPower());
    }

    @Test
    void configChangesThePower() {
        smallFrame();
        SpatialPylonService s = new SpatialPylonService();
        s.reset(allClusters());
        long normal = s.requiredPower();
        assertEquals((long) Math.pow(27 * AEConfig.get().spatialPowerMultiplier(),
                1 + (AEConfig.get().spatialPowerExponent() - 1) * 0.1), normal, 1);
    }

    @Test
    void flatBoxIsInvalid() {
        // two lines in one plane: the box is only one block thick on Y
        line(1, 0, 0, 3, 0, 0);
        line(0, 0, 1, 0, 0, 3);
        SpatialPylonService s = new SpatialPylonService();
        s.reset(allClusters());
        assertTrue(s.hasRegion());
        assertFalse(s.isValidRegion());
        assertFalse(s.isValid(at(1, 0, 0)));
    }

    @Test
    void boxMustBeMoreThanOneBlockThick() {
        // extents of 1 on every axis: nothing between the pylons
        line(0, 0, 0, 1, 0, 0);
        line(0, 1, 2, 0, 1, 3);
        SpatialPylonService s = new SpatialPylonService();
        s.reset(allClusters());
        assertFalse(s.isValidRegion());
    }

    @Test
    void lineInsideTheBoxInvalidatesTheRegion() {
        // box 0..5; one extra line runs through the inside, on none of the faces
        line(1, 0, 0, 5, 0, 0);
        line(0, 1, 0, 0, 5, 0);
        line(0, 0, 1, 0, 0, 5);
        SpatialPylonService s = new SpatialPylonService();
        s.reset(allClusters());
        assertTrue(s.isValidRegion());

        line(2, 2, 2, 3, 2, 2);
        s.reset(allClusters());
        assertTrue(s.hasRegion());
        assertFalse(s.isValidRegion());
    }

    @Test
    void lineOnAFaceIsFine() {
        line(1, 0, 0, 5, 0, 0);
        line(0, 1, 0, 0, 5, 0);
        line(0, 0, 1, 0, 0, 5);
        // lies in the bottom face (y = min), not on an edge
        line(2, 0, 3, 4, 0, 3);
        SpatialPylonService s = new SpatialPylonService();
        s.reset(allClusters());
        assertTrue(s.isValidRegion());
        assertEquals(18, s.pylonCount());
    }

    @Test
    void largestRegionNumbers() {
        // 129 block box (128 cube inside) with a single line per axis
        line(1, 0, 0, 4, 0, 0);
        line(0, 126, 0, 0, 129, 0);
        line(0, 0, 126, 0, 0, 129);
        line(126, 0, 0, 129, 0, 0);
        SpatialPylonService s = new SpatialPylonService();
        s.reset(allClusters());
        assertTrue(s.isValidRegion());
        assertEquals(128, s.sizeX());
        assertEquals(128, s.sizeY());
        assertEquals(128, s.sizeZ());
        assertEquals(16, s.pylonCount());
        // required pylons: (129*129*3) * 3 / 8 = 18721
        assertEquals(16f / 18721f * 100f, s.currentEfficiency(), 0.0001f);
        assertTrue(s.requiredPower() > 5_000_000_000_000L);
    }
}
