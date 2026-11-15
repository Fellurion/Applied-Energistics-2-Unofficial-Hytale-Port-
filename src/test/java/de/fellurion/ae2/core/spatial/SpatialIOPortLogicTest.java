/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.spatial;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.energy.IEnergySource;
import de.fellurion.ae2.core.spatial.SpatialIOPortLogic.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plots, cells and the Spatial IO Port (ports of {@code SpatialStoragePlotManager}, {@code SpatialStorageCellItem}
 * and {@code SpatialIOPortBlockEntity}) against a small in-memory world.
 */
class SpatialIOPortLogicTest {

    /** A world of named blocks plus the stored content of the plots; swaps like the integration layer does. */
    private static final class FakeWorld implements SpatialIOPortLogic.RegionSwapper {
        final Map<String, String> blocks = new HashMap<>();
        final Map<Integer, Map<String, String>> plotContent = new HashMap<>();
        String refuse;
        int swaps;
        boolean lastFresh;

        static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        @Override
        public String swap(SpatialStoragePlot plot, boolean fresh, int x, int y, int z, int sizeX, int sizeY,
                int sizeZ) {
            if (refuse != null) {
                return refuse;
            }
            swaps++;
            lastFresh = fresh;
            Map<String, String> stored = fresh ? new HashMap<>() : plotContent.getOrDefault(plot.getId(), Map.of());
            Map<String, String> captured = new HashMap<>();
            for (int dx = 0; dx < sizeX; dx++) {
                for (int dy = 0; dy < sizeY; dy++) {
                    for (int dz = 0; dz < sizeZ; dz++) {
                        String rel = key(dx, dy, dz);
                        String abs = key(x + dx, y + dy, z + dz);
                        String old = blocks.remove(abs);
                        if (old != null) {
                            captured.put(rel, old);
                        }
                        String in = stored.get(rel);
                        if (in != null) {
                            blocks.put(abs, in);
                        }
                    }
                }
            }
            plotContent.put(plot.getId(), captured);
            return null;
        }
    }

    private static final class Battery implements IEnergySource {
        double stored;

        @Override
        public double extractAEPower(double amt, Actionable mode, PowerMultiplier multiplier) {
            double out = Math.min(stored, multiplier.multiply(amt));
            if (mode == Actionable.MODULATE) {
                stored -= out;
            }
            return multiplier.divide(out);
        }
    }

    private static final class Port implements SpatialIOPortLogic.Host {
        String cellItem;
        SpatialPlotInfo cellInfo;
        String output;
        SpatialPlotInfo outputInfo;
        boolean active = true;
        SpatialPylonService spatial = new SpatialPylonService();
        Battery battery = new Battery();
        double returned;

        @Override
        public SpatialIOPortLogic.Cell inputCell() {
            if (cellItem == null) {
                return null;
            }
            return new SpatialIOPortLogic.Cell() {
                @Override
                public String itemId() {
                    return cellItem;
                }

                @Override
                public SpatialPlotInfo plotInfo() {
                    return cellInfo;
                }

                @Override
                public void setPlotInfo(SpatialPlotInfo info) {
                    cellInfo = info;
                }
            };
        }

        @Override
        public boolean isOutputEmpty() {
            return output == null;
        }

        @Override
        public void moveCellToOutput() {
            output = cellItem;
            outputInfo = cellInfo;
            cellItem = null;
            cellInfo = null;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public SpatialPylonService spatial() {
            return spatial;
        }

        @Override
        public IEnergySource energy() {
            return battery;
        }

        @Override
        public void returnPower(double ae) {
            battery.stored += ae;
            returned += ae;
        }

        @Override
        public String owner() {
            return "owner-1";
        }

        @Override
        public String worldId() {
            return "default";
        }

        /** Takes the cell out of the output slot and puts it back into the input. */
        void reinsert() {
            cellItem = output;
            cellInfo = outputInfo;
            output = null;
            outputInfo = null;
        }
    }

    private SpatialStoragePlotManager plots;
    private SpatialIOPortLogic logic;
    private FakeWorld world;
    private Port port;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        plots = new SpatialStoragePlotManager();
        logic = new SpatialIOPortLogic(plots);
        world = new FakeWorld();
        port = new Port();
        port.cellItem = SpatialStorageCell.CELL_16;
        port.battery.stored = 1e9;
        frame(port.spatial, 0, 0, 0, 3);
    }

    /** Pylon box from {@code o} to {@code o + extent} on every axis, one line per axis. */
    private static void frame(SpatialPylonService s, int ox, int oy, int oz, int extent) {
        List<SpatialPylonCluster> list = new ArrayList<>();
        list.add(new SpatialPylonCluster(ox + 1, oy, oz, ox + extent, oy, oz));
        list.add(new SpatialPylonCluster(ox, oy + 1, oz, ox, oy + extent, oz));
        list.add(new SpatialPylonCluster(ox, oy, oz + 1, ox, oy, oz + extent));
        s.reset(list);
        assertTrue(s.isValidRegion());
    }

    // --- cells and plots ---

    @Test
    void cellTiers() {
        assertEquals(2, SpatialStorageCell.getMaxStoredDim("AE2_Spatial_Storage_Cell_2"));
        assertEquals(16, SpatialStorageCell.getMaxStoredDim("AE2_Spatial_Storage_Cell_16"));
        assertEquals(128, SpatialStorageCell.getMaxStoredDim("AE2_Spatial_Storage_Cell_128"));
        assertEquals(0, SpatialStorageCell.getMaxStoredDim("AE2_Item_Storage_Cell_1k"));
        assertFalse(SpatialStorageCell.isSpatialCell(null));
    }

    @Test
    void plotIdsCountUpFromOne() {
        assertNull(plots.getPlot(-1));
        assertEquals(1, plots.allocatePlot(2, 2, 2, null).getId());
        assertEquals(2, plots.allocatePlot(16, 3, 5, "a").getId());
        assertEquals(3, plots.allocatePlot(1, 1, 1, "a").getId());
        plots.freePlot(2);
        assertEquals(4, plots.allocatePlot(2, 2, 2, null).getId());
        assertNull(plots.getPlot(2));
        assertEquals(3, plots.getPlots().size());
        // an id is never handed out twice, also not the one of a freed highest plot
        plots.freePlot(4);
        assertEquals(5, plots.allocatePlot(2, 2, 2, null).getId());
        SpatialStoragePlotManager copy = new SpatialStoragePlotManager();
        plots.freePlot(5);
        copy.load(plots.toJson());
        assertEquals(6, copy.allocatePlot(2, 2, 2, null).getId());
    }

    @Test
    void reservedIdsAreNotHandedOut() {
        plots.reserveIdsUpTo(7);
        assertEquals(8, plots.allocatePlot(2, 2, 2, null).getId());
    }

    @Test
    void plotSizeLimits() {
        assertThrows(IllegalArgumentException.class, () -> plots.allocatePlot(0, 2, 2, null));
        assertThrows(IllegalArgumentException.class, () -> plots.allocatePlot(2, 129, 2, null));
        assertEquals(128, plots.allocatePlot(128, 128, 128, null).getSizeZ());
    }

    @Test
    void plotListSurvivesJson() {
        SpatialStoragePlot a = plots.allocatePlot(2, 3, 4, "owner \"x\"");
        plots.allocatePlot(16, 16, 16, null);
        plots.setLastTransition(a.getId(), new TransitionInfo("world", -5, 60, 7, 0, 66, 12, 1234567890123L));
        int[] saves = {0};
        plots.setSaveListener(() -> saves[0]++);

        SpatialStoragePlotManager copy = new SpatialStoragePlotManager();
        copy.load(plots.toJson());
        assertEquals(2, copy.getPlots().size());
        SpatialStoragePlot b = copy.getPlot(1);
        assertTrue(b.hasSize(2, 3, 4));
        assertEquals("owner \"x\"", b.getOwner());
        assertEquals(new TransitionInfo("world", -5, 60, 7, 0, 66, 12, 1234567890123L), b.getLastTransition());
        assertNull(copy.getPlot(2).getOwner());
        assertNull(copy.getPlot(2).getLastTransition());
        assertEquals(3, copy.allocatePlot(1, 1, 1, null).getId());

        assertTrue(copy.restorePlot(new SpatialStoragePlot(9, 2, 2, 2, null)));
        assertFalse(copy.restorePlot(new SpatialStoragePlot(9, 4, 4, 4, null)));
        assertTrue(copy.getPlot(9).hasSize(2, 2, 2));
        assertEquals(0, saves[0]);
        plots.freePlot(1);
        assertEquals(1, saves[0]);
    }

    @Test
    void plotInfoOnTheCell() {
        SpatialPlotInfo info = new SpatialPlotInfo(12, 16, 3, 5);
        assertEquals("SP-0012", info.serialNumber());
        assertEquals(info, SpatialPlotInfo.fromJson(info.toJson()));
        assertNull(SpatialPlotInfo.fromJson(null));
        assertNull(SpatialPlotInfo.fromJson(""));
        assertNull(SpatialPlotInfo.fromJson("{\"id\":3}"));
        assertNull(SpatialPlotInfo.fromJson("nonsense"));
    }

    // --- port conditions ---

    @Test
    void conditionsInAe2Order() {
        assertEquals(Result.OK, logic.status(port));

        port.cellItem = null;
        assertEquals(Result.NO_CELL, logic.status(port));
        port.cellItem = "AE2_Item_Storage_Cell_1k";
        assertEquals(Result.NO_CELL, logic.status(port));
        port.cellItem = SpatialStorageCell.CELL_16;

        port.output = "something";
        assertEquals(Result.OUTPUT_OCCUPIED, logic.status(port));
        port.output = null;

        port.active = false;
        assertEquals(Result.NOT_ACTIVE, logic.status(port));
        port.active = true;

        port.spatial = new SpatialPylonService();
        port.spatial.reset(List.of());
        assertEquals(Result.NO_REGION, logic.status(port));

        port.spatial.reset(List.of(new SpatialPylonCluster(0, 0, 0, 3, 0, 0), new SpatialPylonCluster(0, 0, 2, 3, 0, 2)));
        assertEquals(Result.INVALID_REGION, logic.status(port));

        frame(port.spatial, 0, 0, 0, 3);
        port.battery.stored = port.spatial.requiredPower() - 100;
        assertEquals(Result.NOT_ENOUGH_POWER, logic.status(port));
        // AE2 accepts a shortfall below 0.1 %
        port.battery.stored = port.spatial.requiredPower() * 0.9995;
        assertEquals(Result.OK, logic.status(port));
    }

    @Test
    void cellTooSmall() {
        port.cellItem = SpatialStorageCell.CELL_2;
        assertEquals(Result.OK, logic.status(port));
        frame(port.spatial, 0, 0, 0, 4);
        assertEquals(Result.CELL_TOO_SMALL, logic.transition(port, world, 0));
        assertEquals(0, world.swaps);
        assertTrue(plots.getPlots().isEmpty());
        port.cellItem = SpatialStorageCell.CELL_16;
        frame(port.spatial, 0, 0, 0, 17);
        assertEquals(Result.OK, logic.status(port));
        frame(port.spatial, 0, 0, 0, 18);
        assertEquals(Result.CELL_TOO_SMALL, logic.status(port));
    }

    // --- transfers ---

    @Test
    void transferOutAndBackIn() {
        world.blocks.put("1,1,1", "stone");
        world.blocks.put("2,2,1", "chest");
        world.blocks.put("3,1,1", "pylon"); // on the box, not inside
        double before = port.battery.stored;
        long req = port.spatial.requiredPower();

        assertEquals(Result.OK, logic.transition(port, world, 42L));
        assertTrue(world.lastFresh);
        assertEquals(1, world.swaps);
        assertEquals(Map.of("3,1,1", "pylon"), world.blocks);
        assertEquals(before - req, port.battery.stored, 1e-6);

        // the cell is bound to a new plot and lies in the output slot
        assertNull(port.cellItem);
        assertEquals(SpatialStorageCell.CELL_16, port.output);
        assertEquals(new SpatialPlotInfo(1, 2, 2, 2), port.outputInfo);
        SpatialStoragePlot plot = plots.getPlot(1);
        assertTrue(plot.hasSize(2, 2, 2));
        assertEquals("owner-1", plot.getOwner());
        assertEquals(new TransitionInfo("default", 0, 0, 0, 3, 3, 3, 42L), plot.getLastTransition());

        // nothing happens while the cell is in the output slot
        assertEquals(Result.NO_CELL, logic.transition(port, world, 43L));

        // back in, at another frame of the same size
        port.reinsert();
        frame(port.spatial, 10, 20, 30, 3);
        assertEquals(Result.OK, logic.transition(port, world, 44L));
        assertFalse(world.lastFresh);
        assertEquals(Map.of("3,1,1", "pylon", "11,21,31", "stone", "12,22,31", "chest"), world.blocks);
        assertEquals(1, plots.getPlots().size());
        assertEquals(new SpatialPlotInfo(1, 2, 2, 2), port.outputInfo);
        assertEquals(10, plot.getLastTransition().minX());
        assertTrue(world.plotContent.get(1).isEmpty());
    }

    @Test
    void repeatedSwapsNeitherLoseNorDuplicate() {
        world.blocks.put("1,1,1", "a");
        world.blocks.put("2,1,2", "b");
        for (int i = 0; i < 6; i++) {
            assertEquals(Result.OK, logic.transition(port, world, i));
            int inWorld = world.blocks.size();
            int inPlot = world.plotContent.get(1).size();
            assertEquals(2, inWorld + inPlot);
            assertEquals(i % 2 == 0 ? 0 : 2, inWorld);
            port.reinsert();
        }
        assertEquals(1, plots.getPlots().size());
    }

    @Test
    void usedCellNeedsTheSameSize() {
        assertEquals(Result.OK, logic.transition(port, world, 0));
        port.reinsert();
        frame(port.spatial, 0, 0, 0, 4);
        assertEquals(Result.SIZE_MISMATCH, logic.transition(port, world, 1));
        assertEquals(1, world.swaps);
        assertEquals(SpatialStorageCell.CELL_16, port.cellItem);
        frame(port.spatial, 5, 5, 5, 3);
        assertEquals(Result.OK, logic.transition(port, world, 2));
    }

    @Test
    void cellWhosePlotIsGoneStartsOver() {
        assertEquals(Result.OK, logic.transition(port, world, 0));
        port.reinsert();
        plots.freePlot(1);
        frame(port.spatial, 0, 0, 0, 5);
        assertEquals(Result.OK, logic.transition(port, world, 1));
        assertTrue(world.lastFresh);
        assertEquals(new SpatialPlotInfo(2, 4, 4, 4), port.outputInfo);
    }

    @Test
    void refusedTransferChangesNothing() {
        world.blocks.put("1,1,1", "stone");
        world.refuse = "not loaded";
        double before = port.battery.stored;
        assertEquals(Result.REFUSED, logic.transition(port, world, 0));
        assertEquals("not loaded", logic.lastRefusal());
        // the power taken for the attempt came back
        assertEquals(before, port.battery.stored);
        assertEquals(port.spatial.requiredPower(), port.returned, 1e-6);
        assertEquals(SpatialStorageCell.CELL_16, port.cellItem);
        assertNull(port.cellInfo);
        assertNull(port.output);
        assertTrue(plots.getPlots().isEmpty());
        assertEquals(Map.of("1,1,1", "stone"), world.blocks);

        // a used cell keeps its plot when a later transfer is refused
        world.refuse = null;
        assertEquals(Result.OK, logic.transition(port, world, 1));
        port.reinsert();
        world.refuse = "player inside";
        assertEquals(Result.REFUSED, logic.transition(port, world, 2));
        // the plot freed after the first refusal had id 1; ids are not reused
        assertNotNull(plots.getPlot(2));
        assertEquals(new SpatialPlotInfo(2, 2, 2, 2), port.cellInfo);
        assertEquals(1, world.plotContent.get(2).size());
    }

    @Test
    void notEnoughPowerDoesNothing() {
        port.battery.stored = 10;
        assertEquals(Result.NOT_ENOUGH_POWER, logic.transition(port, world, 0));
        assertEquals(0, world.swaps);
        assertEquals(10, port.battery.stored);
        assertTrue(plots.getPlots().isEmpty());
    }

    // --- redstone ---

    @Test
    void triggersOnRisingEdgeOnly() {
        assertNull(logic.getLastRedstoneState());
        assertFalse(logic.updateRedstoneState(false));
        assertTrue(logic.updateRedstoneState(true));
        assertFalse(logic.updateRedstoneState(true));
        assertFalse(logic.updateRedstoneState(false));
        assertTrue(logic.updateRedstoneState(true));
        // a restored state does not trigger again
        SpatialIOPortLogic loaded = new SpatialIOPortLogic(plots);
        loaded.setLastRedstoneState(Boolean.TRUE);
        assertFalse(loaded.updateRedstoneState(true));
        // undecided and already powered: AE2 triggers on the first look
        assertTrue(new SpatialIOPortLogic(plots).updateRedstoneState(true));
    }
}
