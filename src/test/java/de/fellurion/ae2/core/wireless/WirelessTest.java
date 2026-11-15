/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.wireless;

import de.fellurion.ae2.core.cells.CellItemData;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.energy.CreativeEnergyCellLogic;
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.storage.IActionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wireless access point (range, power), the access point a terminal connects to, the idle power of handheld
 * terminals and their link status (ports of AE2 {@code WirelessAccessPointBlockEntity},
 * {@code WirelessTerminalMenuHost}, {@code PortableCellMenuHost}, {@code ItemMenuHost}).
 */
class WirelessTest {
    private MEWorld world;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        world = new MEWorld();
    }

    static long p(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    /** A simple battery of an item. */
    static final class TestBattery implements PortableTerminalHost.Battery {
        double stored;
        int extractions;

        TestBattery(double stored) {
            this.stored = stored;
        }

        @Override
        public double getAECurrentPower() {
            return stored;
        }

        @Override
        public double extractAEPower(double amount, Actionable mode) {
            double out = Math.min(stored, amount);
            if (mode == Actionable.MODULATE) {
                stored -= out;
                extractions++;
            }
            return out;
        }
    }

    /** A player standing somewhere. */
    static final class TestHolder implements WirelessTerminalHost.Holder {
        MEWorld world;
        double x, y, z;

        TestHolder(MEWorld world, double x, double y, double z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public MEWorld getWorld() {
            return world;
        }

        @Override
        public double getX() {
            return x;
        }

        @Override
        public double getY() {
            return y;
        }

        @Override
        public double getZ() {
            return z;
        }
    }

    /** controller(0,0,0) + creative energy cell below + access point on top (connects downwards). */
    private WirelessAccessPointLogic poweredAccessPoint() {
        world.addNode(p(0, 0, 0), NodeType.CONTROLLER, null, null, null);
        world.addNode(p(0, -1, 0), NodeType.CREATIVE_ENERGY_CELL, null, null, new CreativeEnergyCellLogic());
        var wap = new WirelessAccessPointLogic();
        world.addNode(p(0, 1, 0), NodeType.WIRELESS_ACCESS_POINT, Direction.UP, null, wap);
        world.tick(40);
        assertTrue(wap.isActive());
        return wap;
    }

    // ------------------------------------------------------------------ access point

    @Test
    void rangeFollowsAE2Formula() {
        var wap = new WirelessAccessPointLogic();
        assertEquals(16.0, wap.getRange(), 1e-9);
        wap.setBoosters(1);
        assertEquals(17.0, wap.getRange(), 1e-9);
        wap.setBoosters(4);
        assertEquals(24.0, wap.getRange(), 1e-9);
        wap.setBoosters(16);
        assertEquals(80.0, wap.getRange(), 1e-9);
        wap.setBoosters(64);
        assertEquals(16 + 512.0, wap.getRange(), 1e-9);
    }

    @Test
    void powerDrainFollowsAE2Formula() {
        var wap = new WirelessAccessPointLogic();
        assertEquals(8.0, wap.getIdlePowerUsage(), 1e-9);
        wap.setBoosters(1);
        assertEquals(8 + Math.pow(1, 1 + 1 / 64.0), wap.getIdlePowerUsage(), 1e-9);
        wap.setBoosters(8);
        assertEquals(8 + Math.pow(8, 1.125), wap.getIdlePowerUsage(), 1e-9);
        wap.setBoosters(64);
        assertEquals(8 + 64 * 64.0, wap.getIdlePowerUsage(), 1e-9);
        wap.setBoosters(-3);
        assertEquals(0, wap.getBoosters());
    }

    @Test
    void boostersChangeTheNodesIdlePower() {
        var wap = poweredAccessPoint();
        GridNode node = wap.getActionableNode();
        assertEquals(8.0, node.getIdlePowerUsage(), 1e-9);
        double before = node.getGrid().getEnergyService().getIdlePowerUsage();
        wap.setBoosters(8);
        assertEquals(8 + Math.pow(8, 1.125), node.getIdlePowerUsage(), 1e-9);
        assertEquals(before + Math.pow(8, 1.125), node.getGrid().getEnergyService().getIdlePowerUsage(), 1e-9);
        wap.setBoosters(0);
        assertEquals(before, node.getGrid().getEnergyService().getIdlePowerUsage(), 1e-9);
    }

    @Test
    void accessPointIsActiveOnlyWithPowerAndChannel() {
        var wap = new WirelessAccessPointLogic();
        assertFalse(wap.isActive());
        assertNull(wap.getGrid());
        var cell = new EnergyCellLogic(100_000, 800, 200);
        world.addNode(p(0, 0, 0), NodeType.ENERGY_CELL, null, null, cell);
        world.addNode(p(0, 1, 0), NodeType.WIRELESS_ACCESS_POINT, Direction.UP, null, wap);
        world.tick(5);
        assertNotNull(wap.getGrid());
        assertFalse(wap.isActive());
        cell.setAECurrentPower(50_000);
        world.tick(40);
        assertTrue(wap.isActive());
        world.removeNode(p(0, 1, 0));
        assertFalse(wap.isActive());
        assertNull(wap.getGrid());
    }

    // ------------------------------------------------------------------ range decision

    @Test
    void signalNeedsSameWorldRangeAndActivity() {
        var wap = poweredAccessPoint();
        // distance is measured from the block position to the holder position
        var s = WirelessConnection.signal(wap, world, 3, 1, 4);
        assertTrue(s.usable());
        assertEquals(25.0, s.distanceSquared(), 1e-9);
        assertEquals(16 * 16 - 25.0, s.remainingRangeSquared(), 1e-9);
        // exactly at the range limit is out of range (AE2: r < rangeLimit)
        assertFalse(WirelessConnection.signal(wap, world, 16, 1, 0).usable());
        assertTrue(WirelessConnection.signal(wap, world, 15.99, 1, 0).usable());
        // other world
        assertFalse(WirelessConnection.signal(wap, new MEWorld(), 3, 1, 4).usable());
        assertFalse(WirelessConnection.signal(wap, null, 3, 1, 4).usable());
        // boosters extend the range
        wap.setBoosters(4);
        assertTrue(WirelessConnection.signal(wap, world, 23, 1, 0).usable());
        // inactive access point
        world.removeNode(p(0, -1, 0));
        world.tick(2);
        assertFalse(wap.isActive());
        assertFalse(WirelessConnection.signal(wap, world, 3, 1, 4).usable());
    }

    @Test
    void closestUsableAccessPointWins() {
        var near = poweredAccessPoint();
        world.addNode(p(1, 0, 0), NodeType.cable(de.fellurion.ae2.core.grid.CableType.GLASS), null, null, null);
        for (int x = 2; x <= 30; x++) {
            world.addNode(p(x, 0, 0), NodeType.cable(de.fellurion.ae2.core.grid.CableType.GLASS), null, null, null);
        }
        var far = new WirelessAccessPointLogic();
        far.setBoosters(16); // range 80
        world.addNode(p(30, 1, 0), NodeType.WIRELESS_ACCESS_POINT, Direction.UP, null, far);
        world.tick(40);
        assertTrue(far.isActive());
        assertSame(near.getGrid(), far.getGrid());

        var c = WirelessConnection.find(near.getGrid(), world, 2, 1, 0);
        assertTrue(c.connected());
        assertSame(near, c.accessPoint());
        assertEquals(2.0, c.distance(), 1e-9);
        // the remaining range is the largest one of all usable access points
        assertEquals(Math.sqrt(80 * 80 - 28 * 28), c.remainingRange(), 1e-9);

        // out of range of the near one, still in range of the boosted one
        c = WirelessConnection.find(near.getGrid(), world, 20, 1, 0);
        assertSame(far, c.accessPoint());
        assertEquals(10.0, c.distance(), 1e-9);

        // nobody in range
        c = WirelessConnection.find(near.getGrid(), world, 0, 1, 500);
        assertFalse(c.connected());
        assertNull(c.accessPoint());

        assertFalse(WirelessConnection.find((de.fellurion.ae2.core.grid.Grid) null, world, 0, 0, 0).connected());
    }

    // ------------------------------------------------------------------ wireless terminal

    @Test
    void terminalDrainDependsOnDistanceAndIsBuffered() {
        var wap = poweredAccessPoint();
        var battery = new TestBattery(1000);
        var holder = new TestHolder(world, 5, 1, 0);
        var host = new WirelessTerminalHost(battery, holder, () -> WirelessTerminalHost.Target.of(wap.getGrid()));
        assertEquals(LinkStatus.CONNECTED, host.getLinkStatus());
        assertEquals(5.0, host.getCurrentDistance(), 1e-9);
        assertSame(wap.getActionableNode(), host.getActionableNode());

        // first tick takes the power for 10 ticks at once: 10 ticks * 5 AE/t
        host.tick();
        assertEquals(950.0, battery.stored, 1e-9);
        assertEquals(1, battery.extractions);
        for (int i = 0; i < 10; i++) {
            host.tick();
        }
        assertEquals(950.0, battery.stored, 1e-9);
        // farther away costs more
        holder.x = 10;
        host.tick();
        assertEquals(850.0, battery.stored, 1e-9);
        assertEquals(2, battery.extractions);
        assertEquals(LinkStatus.CONNECTED, host.getLinkStatus());
    }

    @Test
    void severalTicksAtOnceCostTheSame() {
        var wap = poweredAccessPoint();
        var single = new TestBattery(1000);
        var batch = new TestBattery(1000);
        var a = new WirelessTerminalHost(single, new TestHolder(world, 5, 1, 0),
                () -> WirelessTerminalHost.Target.of(wap.getGrid()));
        var b = new WirelessTerminalHost(batch, new TestHolder(world, 5, 1, 0),
                () -> WirelessTerminalHost.Target.of(wap.getGrid()));
        for (int i = 0; i < 40; i++) {
            a.tick();
        }
        for (int i = 0; i < 4; i++) {
            b.tick(10);
        }
        assertEquals(single.stored, batch.stored, 1e-9);
        assertEquals(a.getRemainingEnergyTicks(), b.getRemainingEnergyTicks());
        assertEquals(a.getLinkStatus(), b.getLinkStatus());

        var cellA = new TestBattery(7);
        var cellB = new TestBattery(7);
        var pa = new PortableCellHost(cellA, () -> null);
        var pb = new PortableCellHost(cellB, () -> null);
        for (int i = 0; i < 30; i++) {
            pa.tick();
        }
        pb.tick(30);
        assertEquals(cellA.stored, cellB.stored, 1e-9);
        assertEquals(pa.getLinkStatus(), pb.getLinkStatus());
    }

    @Test
    void terminalRunsOutOfPower() {
        var wap = poweredAccessPoint();
        var battery = new TestBattery(120);
        var host = new WirelessTerminalHost(battery, new TestHolder(world, 8, 1, 0),
                () -> WirelessTerminalHost.Target.of(wap.getGrid()));
        int ticks = 0;
        while (host.getLinkStatus().connected() && ticks < 1000) {
            host.tick();
            ticks++;
        }
        // 80 AE pay 10 ticks, the remaining 40 AE pay 5 more; the tick in which a buffer is taken is not counted
        assertEquals(LinkStatus.OUT_OF_POWER, host.getLinkStatus());
        assertEquals(0.0, battery.stored, 1e-9);
        assertEquals(17, ticks);
    }

    @Test
    void creativeHolderDoesNotDrainIdlePower() {
        var wap = poweredAccessPoint();
        var battery = new TestBattery(10);
        var host = new WirelessTerminalHost(battery, new TestHolder(world, 8, 1, 0),
                () -> WirelessTerminalHost.Target.of(wap.getGrid()));
        host.setCreative(true);
        for (int i = 0; i < 100; i++) {
            host.tick();
        }
        assertEquals(10.0, battery.stored, 1e-9);
        assertEquals(LinkStatus.CONNECTED, host.getLinkStatus());
    }

    @Test
    void linkStatusReasons() {
        var wap = poweredAccessPoint();
        var holder = new TestHolder(world, 3, 1, 0);
        var target = new WirelessTerminalHost.Target[]{WirelessTerminalHost.Target.notLinked()};
        var host = new WirelessTerminalHost(new TestBattery(1000), holder, () -> target[0]);
        assertEquals(LinkStatus.NOT_LINKED, host.getLinkStatus());
        assertNull(host.getActionableNode());

        target[0] = WirelessTerminalHost.Target.notFound();
        host.tick();
        assertEquals(LinkStatus.NETWORK_NOT_FOUND, host.getLinkStatus());
        target[0] = WirelessTerminalHost.Target.of(null);
        host.tick();
        assertEquals(LinkStatus.NETWORK_NOT_FOUND, host.getLinkStatus());

        target[0] = WirelessTerminalHost.Target.of(wap.getGrid());
        host.tick();
        assertEquals(LinkStatus.CONNECTED, host.getLinkStatus());

        holder.x = 100;
        host.tick();
        assertEquals(LinkStatus.OUT_OF_RANGE, host.getLinkStatus());
        assertNull(host.getCurrentAccessPoint());

        holder.x = 3;
        holder.world = new MEWorld();
        host.tick();
        assertEquals(LinkStatus.OUT_OF_RANGE, host.getLinkStatus());

        // out of range does not drain: no power is needed while there is no connection
        var empty = new WirelessTerminalHost(new TestBattery(0), new TestHolder(world, 100, 1, 0),
                () -> WirelessTerminalHost.Target.of(wap.getGrid()));
        assertEquals(LinkStatus.OUT_OF_RANGE, empty.getLinkStatus());
        var emptyNear = new WirelessTerminalHost(new TestBattery(0), new TestHolder(world, 3, 1, 0),
                () -> WirelessTerminalHost.Target.of(wap.getGrid()));
        assertEquals(LinkStatus.OUT_OF_POWER, emptyNear.getLinkStatus());
    }

    @Test
    void terminalPaysItemTransfersFromItsBattery() {
        var wap = poweredAccessPoint();
        var drive = CellHostLogic.drive();
        world.addNode(p(1, 0, 0), NodeType.DRIVE, Direction.NORTH, null, drive);
        drive.setCell(0, StorageCells.getCellInventory("AE2_Item_Storage_Cell_1k", new CellItemData(), null));
        world.tick(5);

        var battery = new TestBattery(100);
        var host = new WirelessTerminalHost(battery, new TestHolder(world, 0, 1, 0),
                () -> WirelessTerminalHost.Target.of(wap.getGrid()));
        // standing on the access point: no idle drain
        host.tick();
        assertEquals(100.0, battery.stored, 1e-9);
        var stone = AEItemKey.of("Rock_Stone");
        assertEquals(64, host.insert(stone, 64, Actionable.MODULATE, IActionSource.empty()));
        assertEquals(36.0, battery.stored, 1e-9);
        // only 36 AE left: 36 items
        assertEquals(36, host.extract(stone, 64, Actionable.MODULATE, IActionSource.empty()));
        assertEquals(0.0, battery.stored, 1e-9);
        assertEquals(0, host.extract(stone, 64, Actionable.MODULATE, IActionSource.empty()));
        assertEquals(28, wap.getGrid().getStorageService().getInventory().getAvailableStacks().get(stone));
        // the usage multiplier is ignored by the wireless terminal (as in AE2)
        battery.stored = 10;
        AEConfig.get().powerUsageMultiplier(4);
        assertEquals(10.0, host.extractAEPower(10, Actionable.SIMULATE, PowerMultiplier.CONFIG), 1e-9);
    }

    // ------------------------------------------------------------------ portable cell

    @Test
    void portableCellDrainsHalfAnAEPerTick() {
        var data = new CellItemData();
        var cell = StorageCells.getCellInventory("AE2_Portable_Item_Cell_1k", data, null);
        var battery = new TestBattery(20);
        var host = new PortableCellHost(battery, () -> cell);
        assertEquals(LinkStatus.CONNECTED, host.getLinkStatus());
        host.tick();
        // 10 ticks * 0.5 AE/t at once
        assertEquals(15.0, battery.stored, 1e-9);
        for (int i = 0; i < 10; i++) {
            host.tick();
        }
        assertEquals(15.0, battery.stored, 1e-9);
        host.tick();
        assertEquals(10.0, battery.stored, 1e-9);
        int ticks = 12;
        while (host.getLinkStatus().connected() && ticks < 1000) {
            host.tick();
            ticks++;
        }
        assertEquals(LinkStatus.OUT_OF_POWER, host.getLinkStatus());
        assertEquals(0.0, battery.stored, 1e-9);
        // 20 AE / 0.5 AE/t = 40 ticks, plus the ticks in which a new buffer was taken
        assertEquals(44, ticks);
    }

    @Test
    void portableCellPaysTransfersWithTheUsageMultiplier() {
        var data = new CellItemData();
        var cell = StorageCells.getCellInventory("AE2_Portable_Item_Cell_1k", data, null);
        var battery = new TestBattery(1000);
        var host = new PortableCellHost(battery, () -> cell);
        host.setCreative(true);
        var stone = AEItemKey.of("Rock_Stone");
        assertEquals(100, host.insert(stone, 100, Actionable.MODULATE, IActionSource.empty()));
        assertEquals(900.0, battery.stored, 1e-9);
        assertEquals(100, cell.getAvailableStacks().get(stone));
        assertEquals(100L, data.getContents().get(stone));

        AEConfig.get().powerUsageMultiplier(2);
        assertEquals(50, host.extract(stone, 50, Actionable.MODULATE, IActionSource.empty()));
        assertEquals(800.0, battery.stored, 1e-9);
        assertEquals(400.0, host.extractAEPower(1000, Actionable.SIMULATE, PowerMultiplier.CONFIG), 1e-9);

        // an empty battery moves nothing
        battery.stored = 0;
        host.setCreative(false);
        host.tick();
        assertEquals(LinkStatus.OUT_OF_POWER, host.getLinkStatus());
        assertEquals(0, host.extract(stone, 50, Actionable.MODULATE, IActionSource.empty()));
        assertEquals(0, host.insert(stone, 50, Actionable.MODULATE, IActionSource.empty()));
        assertEquals(50, cell.getAvailableStacks().get(stone));
    }

    @Test
    void portableCellWithoutItemHasNoStorage() {
        var host = new PortableCellHost(new TestBattery(100), () -> null);
        assertEquals(0, host.insert(AEItemKey.of("Rock_Stone"), 5, Actionable.MODULATE, IActionSource.empty()));
        assertTrue(host.getInventory().getAvailableStacks().isEmpty());
    }
}
