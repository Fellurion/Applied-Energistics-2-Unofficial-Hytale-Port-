/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.tools;

import java.util.Random;

/**
 * Numbers of the Tiny TNT. Port of {@code appeng.entity.TinyTNTPrimedEntity} and
 * {@code appeng.block.misc.TinyTNTBlock}.
 * <p>
 * AE2 destroys a block when {@code 2.3 − distance² − (blastResistance + 0.3) · 0.11 > 0.01}: soft ground up to about
 * 1.5 blocks from the charge, stone up to about 1.25 blocks. The blast itself is carried out by Hytale's explosion
 * (see the item definition of {@code AE2_Tiny_Tnt}), whose radius and block powers are chosen to give the same
 * result: soil within 1.5 blocks, rock and wood only next to the charge.
 */
public final class TinyTntLogic {

    /** Fuse in ticks (20 per second) after igniting. AE2: {@code setFuse(80)}. */
    public static final int FUSE_TICKS = 80;
    /** Damage to every entity in the blast. AE2: {@code e.hurt(explosion, 6)}. */
    public static final float ENTITY_DAMAGE = 6;
    /** AE2 hurts all entities in a box of ±1.5 blocks around the charge. */
    public static final double ENTITY_RANGE = 1.5;
    /** AE2 looks at blocks up to two blocks away. */
    public static final int BLOCK_RANGE = 2;

    private TinyTntLogic() {
    }

    /**
     * Fuse of a charge that was set off by another explosion. AE2 ({@code TinyTNTBlock#wasExploded}):
     * {@code random.nextInt(fuse / 4) + fuse / 8}, i.e. 10 to 29 ticks.
     */
    public static int chainFuse(Random random) {
        return random.nextInt(FUSE_TICKS / 4) + FUSE_TICKS / 8;
    }

    /**
     * AE2's own test whether the blast destroys a block (kept as the reference for the values of the Hytale
     * explosion).
     *
     * @param distanceSquared squared distance from the charge to the centre of the block
     * @param blastResistance Minecraft blast resistance of the block
     */
    public static boolean destroys(double distanceSquared, float blastResistance) {
        double strength = 2.3f - distanceSquared;
        strength -= (blastResistance + 0.3F) * 0.11f;
        return strength > 0.01;
    }
}
