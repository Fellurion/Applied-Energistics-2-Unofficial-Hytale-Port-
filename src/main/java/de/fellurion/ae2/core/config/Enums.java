/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.config;

/**
 * Holder for the small setting enums of AE2 ({@code appeng.api.config.*}). They are nested here only to keep the
 * file count down; use them via static import or {@code Enums.FuzzyMode} etc.
 */
public final class Enums {
    private Enums() {
    }

    /** Port of {@code appeng.api.config.IncludeExclude}. */
    public enum IncludeExclude {
        WHITELIST,
        BLACKLIST
    }

    /** Port of {@code appeng.api.config.AccessRestriction}. */
    public enum AccessRestriction {
        NO_ACCESS(0),
        READ(1),
        WRITE(2),
        READ_WRITE(3);

        private final int permissionBit;

        AccessRestriction(int v) {
            this.permissionBit = v;
        }

        public boolean hasPermission(AccessRestriction ar) {
            return (this.permissionBit & ar.permissionBit) == ar.permissionBit;
        }

        public boolean isAllowExtraction() {
            return (permissionBit & 1) != 0;
        }

        public boolean isAllowInsertion() {
            return (permissionBit & 2) != 0;
        }
    }

    /** Port of {@code appeng.api.config.RedstoneMode}. */
    public enum RedstoneMode {
        IGNORE,
        LOW_SIGNAL,
        HIGH_SIGNAL,
        SIGNAL_PULSE
    }

    /** Port of {@code appeng.api.config.SchedulingMode} (export bus slot order). */
    public enum SchedulingMode {
        DEFAULT,
        ROUNDROBIN,
        RANDOM
    }

    /** Port of {@code appeng.api.config.StorageFilter} (storage bus). */
    public enum StorageFilter {
        NONE,
        EXTRACTABLE_ONLY
    }

    /** Port of {@code appeng.api.config.YesNo}. */
    public enum YesNo {
        YES,
        NO,
        UNDECIDED
    }

    /**
     * Port of {@code appeng.api.config.LockCraftingMode}: the circumstances under which a pattern provider will lock
     * further crafting.
     */
    public enum LockCraftingMode {
        /** Crafting is never locked. */
        NONE,
        /** After pushing a pattern, no further crafts are accepted until a redstone pulse is received. */
        LOCK_UNTIL_PULSE,
        /** Crafting is locked while the pattern provider is receiving a redstone signal. */
        LOCK_WHILE_HIGH,
        /** Crafting is locked while the pattern provider is not receiving a redstone signal. */
        LOCK_WHILE_LOW,
        /**
         * After pushing a pattern, no further crafts are accepted until the primary pattern result is returned to the
         * network through the pattern provider.
         */
        LOCK_UNTIL_RESULT
    }

    /**
     * Port of {@code appeng.api.config.CondenserOutput}. The required power is taken from {@link AEConfig}
     * (matter balls 256, singularity 256000 by default).
     */
    public enum CondenserOutput {
        TRASH,
        MATTER_BALLS,
        SINGULARITY;

        public double requiredPower() {
            return switch (this) {
                case TRASH -> 0;
                case MATTER_BALLS -> AEConfig.get().condenserMatterBallsPower();
                case SINGULARITY -> AEConfig.get().condenserSingularityPower();
            };
        }
    }

    /**
     * Port of {@code appeng.api.config.FuzzyMode}. Percentages are "percentage damaged" (inverse of durability).
     */
    public enum FuzzyMode {
        IGNORE_ALL(-1),
        PERCENT_99(0),
        PERCENT_75(25),
        PERCENT_50(50),
        PERCENT_25(75);

        public final float breakPoint;
        public final float percentage;

        FuzzyMode(float p) {
            this.percentage = p;
            this.breakPoint = p / 100.0f;
        }

        public int calculateBreakPoint(int maxDamage) {
            return (int) (this.percentage * maxDamage / 100.0f);
        }
    }

    /**
     * Port of {@code appeng.api.networking.pathing.ChannelMode}.
     */
    public enum ChannelMode {
        /** Cables carry infinite channels, effectively disabling pathfinding and channel requirements. */
        INFINITE(Integer.MAX_VALUE, 0),
        /** Default channel capacity per cable (8 / 32 dense, 8 for ad-hoc networks). */
        DEFAULT(8, 1),
        X2(16, 2),
        X3(24, 3),
        X4(32, 4);

        private final int adHocNetworkChannels;
        private final int cableCapacityFactor;

        ChannelMode(int adHocNetworkChannels, int cableCapacityFactor) {
            this.adHocNetworkChannels = adHocNetworkChannels;
            this.cableCapacityFactor = cableCapacityFactor;
        }

        /** @return The maximum number of channels supported by ad-hoc networks. */
        public int getAdHocNetworkChannels() {
            return adHocNetworkChannels;
        }

        /** @return Multiplier for the default capacity of cables. */
        public int getCableCapacityFactor() {
            return cableCapacityFactor;
        }
    }
}
