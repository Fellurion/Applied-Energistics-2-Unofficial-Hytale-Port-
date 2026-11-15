/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

/**
 * Numbers and maths of the Matter Cannon. Port of the calculations in
 * {@code appeng.items.tools.powered.MatterCannonItem} ({@code fireCannon}, {@code standardAmmo},
 * {@code getDamageFromPenetration}).
 * <p>
 * Deviation from AE2: Hytale blocks have no hardness value. {@link #hardnessFromGatherPower} derives one from the
 * share of a block's health a bare hand removes per hit (the block's gather type), scaled so that soil and stone end
 * up where Minecraft has dirt and stone.
 */
public final class MatterCannonLogic {

    /** AE energy per unit of shot power. */
    public static final int ENERGY_PER_SHOT = 1600;
    /** Length of the shot in blocks (AE2: {@code InteractionUtil.getPlayerRay(p, 255)}). */
    public static final double RANGE = 255;
    /** AE2 widens every entity's bounding box by this much on all sides when testing the shot. */
    public static final double ENTITY_MARGIN = 0.3;
    /**
     * Items the cannon holds. AE2: the cannon is a storage cell with 512 bytes, 8 bytes per type and a single type –
     * (512 − 8) bytes at 8 items per byte.
     */
    public static final int AMMO_CAPACITY = (512 - 8) * 8;
    /** AE2 multiplies a block's destroy speed by this to get the penetration it costs. */
    public static final float HARDNESS_FACTOR = 9.0f;
    /** Share of the penetration that is left after a block was destroyed. */
    public static final float BLOCK_LOSS = 0.60f;
    /** Bare-hand power that corresponds to Minecraft hardness 1 (soil: power 0.1 = hardness 0.5 like dirt). */
    private static final float POWER_PER_HARDNESS = 0.05f;

    private MatterCannonLogic() {
    }

    /**
     * Shot power as in {@code fireCannon}: 1 + speed cards, limited by the ammo left and by the stored energy.
     *
     * @return the shot power, or 0 if the energy does not suffice for a single shot
     */
    public static int shotPower(int speedCards, long ammoCount, double storedPower) {
        if (ammoCount <= 0 || storedPower < ENERGY_PER_SHOT) {
            return 0;
        }
        int power = 1 + Math.max(0, speedCards);
        power = (int) Math.min(power, ammoCount);
        return Math.min(power, (int) storedPower / ENERGY_PER_SHOT);
    }

    /** Energy a shot of this power uses. */
    public static double energyFor(int shotPower) {
        return (double) ENERGY_PER_SHOT * shotPower;
    }

    /** AE2: {@code getDamageFromPenetration} – {@code ceil(penetration / 20)}. */
    public static int damageFromPenetration(float penetration) {
        return (int) Math.ceil(penetration / 20.0f);
    }

    /** Penetration left after hitting a living entity: the damage dealt is subtracted. */
    public static float afterEntity(float penetration) {
        return penetration - damageFromPenetration(penetration);
    }

    /**
     * Can a shot of this penetration destroy a block of this hardness? AE2:
     * {@code hardness >= 0 && penetration > hardness} with {@code hardness = destroySpeed * 9}.
     *
     * @param hardness Minecraft-style hardness (destroy speed); negative = indestructible
     */
    public static boolean breaks(float penetration, float hardness) {
        float cost = hardness * HARDNESS_FACTOR;
        return cost >= 0.0 && penetration > cost;
    }

    /** Penetration left after destroying a block: {@code (penetration − hardness·9) · 0.6}. */
    public static float afterBlock(float penetration, float hardness) {
        return (penetration - hardness * HARDNESS_FACTOR) * BLOCK_LOSS;
    }

    /**
     * Minecraft-style hardness of a Hytale block from the power a bare hand has against its gather type (the share
     * of the block's health one hit removes): {@code 0.05 / power}. Soil (0.1) gives 0.5 like dirt, rock (0.035) 1.43
     * like stone (1.5), wood (0.03) 1.67 like logs (2.0); ore and metal (0.001) give 50 like obsidian.
     *
     * @param unarmedPower power of the bare hand, &lt;= 0 if the block cannot be damaged at all
     * @return the hardness, or -1 for a block that cannot be destroyed
     */
    public static float hardnessFromGatherPower(float unarmedPower) {
        return unarmedPower <= 0 ? -1 : POWER_PER_HARDNESS / unarmedPower;
    }

    /**
     * Distance along a ray at which it enters an axis-aligned box, or -1 if it misses the box or the box lies beyond
     * {@code maxDistance}. A ray that starts inside the box enters at 0. The direction must be normalised.
     */
    public static double rayBox(double ox, double oy, double oz, double dx, double dy, double dz, double minX,
            double minY, double minZ, double maxX, double maxY, double maxZ, double maxDistance) {
        double near = 0;
        double far = maxDistance;
        double[] o = {ox, oy, oz};
        double[] d = {dx, dy, dz};
        double[] lo = {minX, minY, minZ};
        double[] hi = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (o[i] < lo[i] || o[i] > hi[i]) {
                    return -1;
                }
                continue;
            }
            double t1 = (lo[i] - o[i]) / d[i];
            double t2 = (hi[i] - o[i]) / d[i];
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
            if (near > far) {
                return -1;
            }
        }
        return near;
    }
}
