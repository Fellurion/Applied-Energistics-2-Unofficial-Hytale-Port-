/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3i;

import javax.annotation.Nullable;

/** Kleine Helfer für Blockzugriffe über ChunkStore/BlockSection (Hytale hat keine World.getBlock-API). */
public final class BlockAccess {

    private BlockAccess() {
    }

    /** Die sechs Richtungen in der Reihenfolge der Kabel-Bitmaske. */
    public enum Dir {
        EAST(1, 0, 0), WEST(-1, 0, 0), UP(0, 1, 0), DOWN(0, -1, 0), SOUTH(0, 0, 1), NORTH(0, 0, -1);

        public final int dx, dy, dz;

        Dir(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        public int bit() {
            return 1 << ordinal();
        }

        public Dir opposite() {
            return switch (this) {
                case EAST -> WEST;
                case WEST -> EAST;
                case UP -> DOWN;
                case DOWN -> UP;
                case SOUTH -> NORTH;
                case NORTH -> SOUTH;
            };
        }

        @Nullable
        public static Dir of(int dx, int dy, int dz) {
            for (Dir d : values()) {
                if (d.dx == dx && d.dy == dy && d.dz == dz) {
                    return d;
                }
            }
            return null;
        }
    }

    /** Packt eine Blockposition in einen long (x/z je 26 Bit, y 12 Bit). */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int unpackX(long p) {
        return (int) (p >> 38);
    }

    public static int unpackY(long p) {
        return (int) (p << 52 >> 52);
    }

    public static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }

    public static long offset(long p, Dir d) {
        return pack(unpackX(p) + d.dx, unpackY(p) + d.dy, unpackZ(p) + d.dz);
    }

    @Nullable
    private static BlockSection section(World world, int x, int y, int z) {
        ChunkStore cs = world.getChunkStore();
        Ref<ChunkStore> sec = cs.getChunkSectionReferenceAtBlock(x, y, z);
        if (sec == null || !sec.isValid()) {
            return null;
        }
        return cs.getStore().getComponent(sec, BlockSection.getComponentType());
    }

    /** BlockType an der Position oder null (nicht geladen / leer). */
    @Nullable
    public static BlockType blockType(World world, int x, int y, int z) {
        BlockSection bs = section(world, x, y, z);
        if (bs == null) {
            return null;
        }
        int id = bs.get(x, y, z);
        if (id <= 1) {
            return null;
        }
        return BlockType.getAssetMap().getAsset(id);
    }

    public static int rotationIndex(World world, int x, int y, int z) {
        BlockSection bs = section(world, x, y, z);
        return bs == null ? 0 : bs.getRotationIndex(x, y, z);
    }

    /** Richtung, in die ein Block mit Rotationsindex {@code rot} zeigt, wenn das Modell unrotiert nach {@code defaultFront} zeigt. */
    public static Dir facing(int rot, Dir defaultFront) {
        RotationTuple rt = RotationTuple.get(rot);
        Vector3i v = Rotation.rotate(new Vector3i(defaultFront.dx, defaultFront.dy, defaultFront.dz), rt.yaw(), rt.pitch(), rt.roll());
        Dir d = Dir.of(v.x, v.y, v.z);
        return d == null ? defaultFront : d;
    }

    @Nullable
    public static Ref<ChunkStore> blockEntity(World world, int x, int y, int z) {
        return BlockModule.getBlockEntity(world, x, y, z);
    }

    @Nullable
    public static <T extends Component<ChunkStore>> T component(ComponentType<ChunkStore, T> type, World world, int x, int y, int z) {
        return BlockModule.getComponent(type, world, x, y, z);
    }

    /** Block-Id (Asset-Key) an der Position; null, wenn der Chunk nicht geladen ist; {@code "Empty"} für Luft. */
    @Nullable
    public static String blockKey(World world, int x, int y, int z) {
        BlockSection bs = section(world, x, y, z);
        if (bs == null) {
            return null;
        }
        int id = bs.get(x, y, z);
        if (id == 0) {
            return EMPTY;
        }
        BlockType bt = BlockType.getAssetMap().getAsset(id);
        return bt == null ? null : bt.getId();
    }

    /** Asset-Key von Luft. */
    public static final String EMPTY = "Empty";

    /** Ohne Partikel, Ton und Drops (für erzeugte Strukturen und Wachstum). */
    public static final int QUIET = SetBlockSettings.NO_SEND_PARTICLES | SetBlockSettings.NO_SEND_AUDIO
            | SetBlockSettings.NO_DROP_ITEMS;

    /**
     * Setzt einen Block (mit Block-Entity, falls der Blocktyp eine hat). {@code "Empty"} = Luft.
     *
     * @param rotation Rotationsindex ({@link #rotationFacing}), 0 = unrotiert
     * @param settings {@link SetBlockSettings}, z. B. {@link #QUIET}
     * @return true, wenn sich der Block geändert hat
     */
    public static boolean setBlock(World world, int x, int y, int z, String blockKey, int rotation, int settings) {
        int id = EMPTY.equals(blockKey) ? 0 : BlockType.getAssetMap().getIndexOrDefault(blockKey, Integer.MIN_VALUE);
        BlockType type = id == Integer.MIN_VALUE ? null : BlockType.getAssetMap().getAsset(id);
        if (type == null) {
            return false;
        }
        ChunkStore cs = world.getChunkStore();
        Ref<ChunkStore> sec = cs.getChunkSectionReferenceAtBlock(x, y, z);
        if (sec == null || !sec.isValid()) {
            return false;
        }
        return BlockOperations.setBlock(cs, sec, x, y, z, id, type, rotation, 0, settings);
    }

    /**
     * Rotationsindex, mit dem ein Modell, das unrotiert nach {@code defaultFront} zeigt, nach {@code target} zeigt
     * (Umkehrung von {@link #facing}).
     */
    public static int rotationFacing(Dir target, Dir defaultFront) {
        int key = target.ordinal() * 6 + defaultFront.ordinal();
        int cached = ROTATION_CACHE[key];
        if (cached >= 0) {
            return cached;
        }
        int found = 0;
        for (RotationTuple rt : RotationTuple.VALUES) {
            if (rt != null && facing(rt.index(), defaultFront) == target) {
                found = rt.index();
                break;
            }
        }
        ROTATION_CACHE[key] = found;
        return found;
    }

    private static final int[] ROTATION_CACHE = new int[36];

    static {
        java.util.Arrays.fill(ROTATION_CACHE, -1);
    }

    /** Item-Container eines Blocks (Truhe, AE2-Maschine, ...) oder null. */
    @Nullable
    public static ItemContainer container(World world, int x, int y, int z) {
        ItemContainerBlock icb = component(ItemContainerBlock.getComponentType(), world, x, y, z);
        return icb == null ? null : icb.getItemContainer();
    }

    /**
     * Setzt einen benannten Block-State (z. B. Kabelform {@code C5}, {@code Online}).
     * {@code "default"} = Grundzustand. Nicht vorhandene States werden ignoriert.
     *
     * @return true, wenn der State existiert und gesetzt wurde (oder schon gesetzt war)
     */
    public static boolean setState(World world, int x, int y, int z, String state) {
        ChunkStore cs = world.getChunkStore();
        Ref<ChunkStore> sec = cs.getChunkSectionReferenceAtBlock(x, y, z);
        if (sec == null || !sec.isValid()) {
            return false;
        }
        Store<ChunkStore> store = cs.getStore();
        BlockSection bs = store.getComponent(sec, BlockSection.getComponentType());
        if (bs == null) {
            return false;
        }
        BlockType current = BlockType.getAssetMap().getAsset(bs.get(x, y, z));
        if (current == null) {
            return false;
        }
        if (!"default".equals(state) && current.getBlockForState(state) == null) {
            return false;
        }
        BlockOperations.setBlockInteractionState(cs, sec, x, y, z, current, state, false);
        return true;
    }

    /** Aktueller State-Name des Blocks ("default", wenn Grundzustand). */
    public static String currentState(World world, int x, int y, int z) {
        BlockType bt = blockType(world, x, y, z);
        if (bt == null) {
            return "default";
        }
        String s = bt.getCurrentInteractionState();
        return s == null ? "default" : s;
    }
}
