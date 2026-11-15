/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.decorative.solid.BuddingCertusQuartzBlock, appeng.blockentity.misc.GrowthAcceleratorBlockEntity).
 */
package de.fellurion.ae2.hytale.device;

import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.machines.BuddingQuartzLogic;
import de.fellurion.ae2.core.machines.BuddingQuartzLogic.BudStage;
import de.fellurion.ae2.core.machines.BuddingQuartzLogic.Grade;
import de.fellurion.ae2.core.machines.GrowthAcceleratorLogic;
import de.fellurion.ae2.core.machines.ICrankable;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Certus-Quarz-Wachstum: Budding-Quarz lässt an seinen Seiten Knospen wachsen (klein → mittel → groß → Cluster) und
 * nutzt sich dabei ab; der Growth Accelerator beschleunigt angrenzenden Budding-Quarz.
 */
public final class GrowthDevices {

    private GrowthDevices() {
    }

    /** Block-Ids der Knospenstufen (Modell zeigt unrotiert nach oben). */
    private static final Map<BudStage, String> BUD_BLOCKS = Map.of(
            BudStage.SMALL, "AE2_Small_Quartz_Bud",
            BudStage.MEDIUM, "AE2_Medium_Quartz_Bud",
            BudStage.LARGE, "AE2_Large_Quartz_Bud",
            BudStage.CLUSTER, "AE2_Quartz_Cluster");

    /** Block-Ids der Budding-Stufen; nach „damaged“ bleibt ein normaler Certus-Quarz-Block. */
    private static final Map<Grade, String> BUDDING_BLOCKS = Map.of(
            Grade.FLAWLESS, "AE2_Flawless_Budding_Quartz",
            Grade.FLAWED, "AE2_Flawed_Budding_Quartz",
            Grade.CHIPPED, "AE2_Chipped_Budding_Quartz",
            Grade.DAMAGED, "AE2_Damaged_Budding_Quartz");
    private static final String QUARTZ_BLOCK = "AE2_Quartz_Block";

    /** Block-Id des Budding-Quarzes einer Stufe. */
    public static String buddingBlock(Grade grade) {
        return BUDDING_BLOCKS.get(grade);
    }

    /** Block-Id einer Knospenstufe. */
    public static String budBlock(BudStage stage) {
        return BUD_BLOCKS.get(stage);
    }

    @Nullable
    private static BudStage stageOf(String blockKey) {
        for (Map.Entry<BudStage, String> e : BUD_BLOCKS.entrySet()) {
            if (e.getValue().equals(blockKey)) {
                return e.getKey();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Budding-Quarz

    /**
     * Budding-Quarz. Minecraft tickt jeden Block im Schnitt alle 4096/3 Ticks zufällig; das bildet {@link #tick}
     * mit derselben Wahrscheinlichkeit je AE2-Tick nach. Gewachsen wird nur in geladenen, tickenden Chunks.
     */
    public static final class Budding implements Device {
        private final Grade grade;

        public Budding(String gradeId) {
            Grade g;
            try {
                g = Grade.fromId(gradeId);
            } catch (RuntimeException e) {
                g = Grade.DAMAGED;
            }
            this.grade = g;
        }

        @Override
        public void tick(WorldNetworks nets, WorldNetworks.Node node) {
            if (ThreadLocalRandom.current().nextDouble() < 1.0 / BuddingQuartzLogic.AVERAGE_RANDOM_TICK_INTERVAL) {
                randomTick(nets, node);
            }
        }

        /** Ein Zufallstick wie in Minecraft (auch vom Growth Accelerator ausgelöst). */
        public void randomTick(WorldNetworks nets, WorldNetworks.Node node) {
            int x = node.x(), y = node.y(), z = node.z();
            BuddingQuartzLogic.GrowthResult result = BuddingQuartzLogic.randomTick(grade, ThreadLocalRandom.current(),
                    direction -> neighbour(nets, x + direction.dx, y + direction.dy, z + direction.dz));
            if (result == null) {
                return;
            }
            Dir dir = Dir.valueOf(result.direction().name());
            int rotation = BlockAccess.rotationFacing(dir, Dir.UP);
            BlockAccess.setBlock(nets.world(), x + dir.dx, y + dir.dy, z + dir.dz, BUD_BLOCKS.get(result.newStage()),
                    rotation, BlockAccess.QUIET);
            if (result.decayed()) {
                // Der Block nutzt sich ab; aus „damaged“ wird ein normaler Certus-Quarz-Block
                String next = result.newGrade() == null ? QUARTZ_BLOCK : BUDDING_BLOCKS.get(result.newGrade());
                BlockAccess.setBlock(nets.world(), x, y, z, next, 0, BlockAccess.QUIET);
            }
        }

        private static BuddingQuartzLogic.Neighbour neighbour(WorldNetworks nets, int x, int y, int z) {
            String key = BlockAccess.blockKey(nets.world(), x, y, z);
            if (key == null) {
                return new BuddingQuartzLogic.Other(); // Nachbar-Chunk nicht geladen
            }
            if (BlockAccess.EMPTY.equals(key)) {
                return new BuddingQuartzLogic.Empty(false);
            }
            BudStage stage = stageOf(key);
            if (stage == null) {
                return new BuddingQuartzLogic.Other();
            }
            Dir facing = BlockAccess.facing(BlockAccess.rotationIndex(nets.world(), x, y, z), Dir.UP);
            return new BuddingQuartzLogic.Bud(stage, Direction.valueOf(facing.name()));
        }
    }

    // ------------------------------------------------------------------ Growth Accelerator

    /** Growth Accelerator: tickt angrenzenden Budding-Quarz alle 10 Ticks, solange er Strom hat (8 AE/t). */
    public static final class GrowthAccelerator implements Device {
        private final GrowthAcceleratorLogic logic;
        @Nullable
        private WorldNetworks nets;
        @Nullable
        private WorldNetworks.Node node;

        public GrowthAccelerator() {
            this.logic = new GrowthAcceleratorLogic(this::tickNeighbour);
        }

        @Override
        public Object owner() {
            return logic;
        }

        /** Kurbel-Anschluss (lokaler Puffer für 10 Umdrehungen). */
        public ICrankable crankable() {
            return logic.getCrankable();
        }

        @Override
        public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
            this.nets = nets;
            this.node = node;
        }

        @Override
        public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
            this.nets = null;
            this.node = null;
        }

        private void tickNeighbour(Direction direction) {
            WorldNetworks n = nets;
            WorldNetworks.Node self = node;
            if (n == null || self == null) {
                return;
            }
            WorldNetworks.Node target = n.node(self.x() + direction.dx, self.y() + direction.dy, self.z() + direction.dz);
            if (target != null && target.device() instanceof Budding budding) {
                budding.randomTick(n, target);
            }
        }

        @Override
        public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
            return logic.isPowered() ? "Online" : "default";
        }
    }
}
