/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.config;

import de.fellurion.ae2.core.config.Enums.ChannelMode;

/**
 * Gameplay configuration with the AE2 default values (common config of {@code appeng.core.AEConfig}).
 * <p>
 * Client-side and Minecraft-only options (recipe viewers, terminal style, FE ratio, tiny TNT, ...) are omitted. The
 * integration layer may replace the active config with {@link #set(AEConfig)} (e.g. after loading a config file); all
 * core classes read values via {@link #get()} at the time they need them.
 * <p>
 * The instance is mutable for convenience; treat it as "configure once at startup".
 */
public final class AEConfig {
    private static volatile AEConfig instance = new AEConfig();

    // general
    private ChannelMode channelMode = ChannelMode.DEFAULT;

    // craftingCPU
    private int craftingCalculationTimePerTick = 5;

    // crafting
    private int growthAcceleratorSpeed = 10;

    // spatialio
    private double spatialPowerMultiplier = 1250.0;
    private double spatialPowerExponent = 1.35;

    // battery
    private double chargerChargeRate = 1.0;
    private int wirelessTerminalBattery = 1_600_000;
    private int chargedStaffBattery = 8000;
    private int entropyManipulatorBattery = 200_000;
    private int portableCellBattery = 20_000;
    private int colorApplicatorBattery = 20_000;
    private int matterCannonBattery = 200_000;

    // wireless
    private double wirelessBaseCost = 8.0;
    private double wirelessCostMultiplier = 1.0;
    private double wirelessBaseRange = 16.0;
    private double wirelessBoosterRangeMultiplier = 1.0;
    private double wirelessBoosterExp = 1.5;
    private double wirelessHighWirelessCount = 64.0;
    private double wirelessTerminalDrainMultiplier = 1.0;

    // powerRatios
    private double powerUsageMultiplier = 1.0;
    private double gridEnergyStoragePerNode = 25.0;
    private double crystalResonanceGeneratorRate = 20.0;
    private double p2pTunnelEnergyTax = 0.025;
    private double p2pTunnelTransportTax = 0.025;

    // condenser
    private int condenserMatterBallsPower = 256;
    private int condenserSingularityPower = 256_000;

    // vibrationChamber
    private double vibrationChamberBaseEnergyPerFuelTick = 5.0;
    private int vibrationChamberMinEnergyPerGameTick = 4;
    private int vibrationChamberMaxEnergyPerGameTick = 40;

    public static AEConfig get() {
        return instance;
    }

    public static void set(AEConfig config) {
        instance = config;
    }

    /** Restores a fresh config with AE2 defaults (useful for tests). */
    public static void reset() {
        instance = new AEConfig();
    }

    public ChannelMode channelMode() {
        return channelMode;
    }

    public AEConfig channelMode(ChannelMode mode) {
        this.channelMode = mode;
        return this;
    }

    public int craftingCalculationTimePerTick() {
        return craftingCalculationTimePerTick;
    }

    public int growthAcceleratorSpeed() {
        return growthAcceleratorSpeed;
    }

    public AEConfig growthAcceleratorSpeed(int ticks) {
        this.growthAcceleratorSpeed = Math.max(1, Math.min(100, ticks));
        return this;
    }

    public double spatialPowerMultiplier() {
        return spatialPowerMultiplier;
    }

    public double spatialPowerExponent() {
        return spatialPowerExponent;
    }

    public double chargerChargeRate() {
        return chargerChargeRate;
    }

    public AEConfig chargerChargeRate(double rate) {
        this.chargerChargeRate = rate;
        return this;
    }

    public int wirelessTerminalBattery() {
        return wirelessTerminalBattery;
    }

    public int chargedStaffBattery() {
        return chargedStaffBattery;
    }

    public int entropyManipulatorBattery() {
        return entropyManipulatorBattery;
    }

    public int portableCellBattery() {
        return portableCellBattery;
    }

    public int colorApplicatorBattery() {
        return colorApplicatorBattery;
    }

    public int matterCannonBattery() {
        return matterCannonBattery;
    }

    public double powerUsageMultiplier() {
        return powerUsageMultiplier;
    }

    public AEConfig powerUsageMultiplier(double m) {
        this.powerUsageMultiplier = Math.max(0.01, m);
        return this;
    }

    public double gridEnergyStoragePerNode() {
        return gridEnergyStoragePerNode;
    }

    public double crystalResonanceGeneratorRate() {
        return crystalResonanceGeneratorRate;
    }

    public AEConfig crystalResonanceGeneratorRate(double rate) {
        this.crystalResonanceGeneratorRate = rate;
        return this;
    }

    public double p2pTunnelEnergyTax() {
        return p2pTunnelEnergyTax;
    }

    public double p2pTunnelTransportTax() {
        return p2pTunnelTransportTax;
    }

    public int condenserMatterBallsPower() {
        return condenserMatterBallsPower;
    }

    public int condenserSingularityPower() {
        return condenserSingularityPower;
    }

    public double vibrationChamberBaseEnergyPerFuelTick() {
        return vibrationChamberBaseEnergyPerFuelTick;
    }

    public int vibrationChamberMinEnergyPerGameTick() {
        return vibrationChamberMinEnergyPerGameTick;
    }

    public int vibrationChamberMaxEnergyPerGameTick() {
        return vibrationChamberMaxEnergyPerGameTick;
    }

    /** {@code AEConfig.wireless_getDrainRate}: AE/t drained by a wireless terminal at the given distance. */
    public double wirelessDrainRate(double range) {
        return wirelessTerminalDrainMultiplier * range;
    }

    /** {@code AEConfig.wireless_getMaxRange}: range in blocks of an access point with N boosters. */
    public double wirelessMaxRange(int boosters) {
        return wirelessBaseRange + wirelessBoosterRangeMultiplier * Math.pow(boosters, wirelessBoosterExp);
    }

    /** {@code AEConfig.wireless_getPowerDrain}: idle drain (AE/t) of an access point with N boosters. */
    public double wirelessPowerDrain(int boosters) {
        return wirelessBaseCost + wirelessCostMultiplier * Math.pow(boosters, 1 + boosters / wirelessHighWirelessCount);
    }
}
