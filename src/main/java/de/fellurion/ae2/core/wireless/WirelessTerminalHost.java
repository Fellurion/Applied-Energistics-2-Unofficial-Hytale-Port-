/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.wireless;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.config.PowerMultiplier;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.IActionHost;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.MEStorage;
import de.fellurion.ae2.core.storage.NullStorage;
import de.fellurion.ae2.core.storage.StorageHelper;

import java.util.Objects;

/**
 * The terminal of a wireless terminal item held by a player. Port of {@code appeng.helpers.WirelessTerminalMenuHost}
 * together with {@code WirelessTerminalItem.getLinkedGrid / usePower}.
 * <ul>
 * <li>The item is linked to one access point; its grid is the terminal's storage.</li>
 * <li>The terminal works while an active access point of that grid is in range ({@link WirelessConnection}).</li>
 * <li>Idle power while open: {@code distance to the closest access point} AE/t
 * ({@code AEConfig.wireless_getDrainRate}) from the item's battery; moving items costs 1 AE per item from the same
 * battery.</li>
 * <li>Link status: out of power, connected, or the reason why the grid cannot be reached.</li>
 * </ul>
 * Deviations: the player is behind the {@link Holder} interface and the link behind {@link Link} (AE2 reads the
 * player's position and the item's link component directly). A {@link Target} may also report
 * {@link LinkStatus#OUT_OF_RANGE} without a grid, for a link into another world whose grid cannot be looked at from
 * the holder's world.
 */
public class WirelessTerminalHost extends PortableTerminalHost implements IActionHost {

    /** Where the player holding the terminal is. */
    public interface Holder {
        /** The world of the player, or null if unknown. */
        MEWorld getWorld();

        double getX();

        double getY();

        double getZ();
    }

    /** The grid the item is linked to (AE2 {@code WirelessTerminalItem.getLinkedGrid}). */
    @FunctionalInterface
    public interface Link {
        /** Looks the linked grid up again; called every tick while the terminal is open. */
        Target resolve();
    }

    /**
     * Result of resolving the link.
     *
     * @param grid  the linked grid, or null
     * @param error why there is no grid ({@link LinkStatus#NOT_LINKED} or {@link LinkStatus#NETWORK_NOT_FOUND})
     */
    public record Target(Grid grid, LinkStatus error) {
        public static Target of(Grid grid) {
            return grid == null ? notFound() : new Target(grid, null);
        }

        /** The item has no link (AE2: "Device is not linked."). */
        public static Target notLinked() {
            return new Target(null, LinkStatus.NOT_LINKED);
        }

        /** The linked access point or its grid is gone (AE2: "Linked network cannot be found"). */
        public static Target notFound() {
            return new Target(null, LinkStatus.NETWORK_NOT_FOUND);
        }
    }

    private final Holder holder;
    private final Link link;
    private IWirelessAccessPoint currentAccessPoint;
    /** The distance to the currently connected access point in blocks. */
    protected double currentDistanceFromGrid = Double.MAX_VALUE;
    /** How far away are we from losing signal. */
    protected double currentRemainingRange = Double.MIN_VALUE;
    private Target target = Target.notLinked();
    private LinkStatus linkStatus = LinkStatus.NOT_LINKED;

    public WirelessTerminalHost(Battery battery, Holder holder, Link link) {
        super(battery);
        this.holder = Objects.requireNonNull(holder, "holder");
        this.link = Objects.requireNonNull(link, "link");
        updateConnectedAccessPoint();
        updateLinkStatus();
    }

    @Override
    public LinkStatus getLinkStatus() {
        return linkStatus;
    }

    /** The linked grid as resolved in the last tick, or null. */
    public Grid getLinkedGrid() {
        return target.grid();
    }

    @Override
    public MEStorage getInventory() {
        var grid = target.grid();
        return grid == null ? NullStorage.of() : grid.getStorageService().getInventory();
    }

    @Override
    public double extractAEPower(double amt, Actionable mode, PowerMultiplier usePowerMultiplier) {
        final double extracted = Math.min(amt, battery().getAECurrentPower());

        if (mode == Actionable.SIMULATE) {
            return extracted;
        }

        return usePower(extracted) ? extracted : 0;
    }

    /** {@code WirelessTerminalItem.usePower}: true if the battery delivered the amount (0.5 AE tolerance). */
    private boolean usePower(double amount) {
        return battery().extractAEPower(amount, Actionable.MODULATE) >= amount - 0.5;
    }

    /** The node of the access point currently used, or null. */
    @Override
    public GridNode getActionableNode() {
        return currentAccessPoint == null ? null : currentAccessPoint.getActionableNode();
    }

    /** The access point currently used, or null. */
    public IWirelessAccessPoint getCurrentAccessPoint() {
        return currentAccessPoint;
    }

    /** Distance to the access point currently used, in blocks. */
    public double getCurrentDistance() {
        return currentDistanceFromGrid;
    }

    /** How far the holder is from losing the signal, in blocks. */
    public double getCurrentRemainingRange() {
        return currentRemainingRange;
    }

    protected void updateConnectedAccessPoint() {
        this.target = Objects.requireNonNull(link.resolve(), "link target");
        var connection = WirelessConnection.find(target.grid(), holder.getWorld(), holder.getX(), holder.getY(),
                holder.getZ());
        // If no access point is found this will work too
        this.currentAccessPoint = connection.accessPoint();
        this.currentDistanceFromGrid = connection.distance();
        this.currentRemainingRange = connection.remainingRange();
    }

    @Override
    public void tick(int ticks) {
        updateConnectedAccessPoint();
        for (int i = 0; i < ticks; i++) {
            consumeIdlePower(Actionable.MODULATE);
        }
        updateLinkStatus();
    }

    /** Recalculates the {@linkplain #getLinkStatus() link status}. */
    protected void updateLinkStatus() {
        // Update the link status after checking for range + power
        if (!consumeIdlePower(Actionable.SIMULATE)) {
            this.linkStatus = LinkStatus.OUT_OF_POWER;
        } else if (currentAccessPoint != null) {
            this.linkStatus = LinkStatus.CONNECTED;
        } else if (target.grid() == null) {
            this.linkStatus = target.error() == null ? LinkStatus.NETWORK_NOT_FOUND : target.error();
        } else {
            // If a grid exists, but no access point, we're out of range
            this.linkStatus = LinkStatus.OUT_OF_RANGE;
        }
    }

    @Override
    protected double getPowerDrainPerTick() {
        if (currentAccessPoint != null && currentDistanceFromGrid < Double.MAX_VALUE) {
            return AEConfig.get().wirelessDrainRate(currentDistanceFromGrid);
        } else {
            return 0.0;
        }
    }

    /**
     * Inserts into the linked grid, paying from the battery.
     *
     * @return the amount inserted (0 while not connected)
     */
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!linkStatus.connected()) {
            return 0;
        }
        return StorageHelper.poweredInsert(this, getInventory(), what, amount, source, mode);
    }

    /**
     * Extracts from the linked grid, paying from the battery.
     *
     * @return the amount extracted (0 while not connected)
     */
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!linkStatus.connected()) {
            return 0;
        }
        return StorageHelper.poweredExtraction(this, getInventory(), what, amount, source, mode);
    }
}
