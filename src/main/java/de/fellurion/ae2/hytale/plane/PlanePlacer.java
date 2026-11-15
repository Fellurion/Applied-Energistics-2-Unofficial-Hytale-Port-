/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.automation.ItemPlacementStrategy – der Teil, der die Minecraft-Welt anfasst).
 */
package de.fellurion.ae2.hytale.plane;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.SoftBlockDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.VariantRotation;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.blocktype.component.BlockPhysics;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.connectedblocks.ConnectedBlocksUtil;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import de.fellurion.ae2.core.automation.ItemPlacementStrategy;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.device.Drops;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Die Stelle vor einer Formation Plane, wie sie {@link ItemPlacementStrategy} braucht: ist sie frei, lässt sich ein
 * Item dort als Block setzen, und das Setzen bzw. Auswerfen selbst.
 *
 * <ul>
 *   <li><b>Frei</b> ist Luft (auch mit Flüssigkeit) und alles ohne festen Körper (Gras, Blumen), wie bei Hytales
 *   eigenem Platzieren.</li>
 *   <li><b>Als Block</b> gesetzt wird ein Item ohne Metadaten, das einen Blocktyp hat, wenn Hytales Platzierprüfung
 *   ({@code BlockOperations.testPlaceBlock}, auch für mehrteilige Blöcke) zustimmt, die Welt und die Zone Platzieren
 *   erlauben und der Bauschutz nichts dagegen hat. Verdrängte Pflanzen lassen ihren Drop fallen. Die Ausrichtung
 *   folgt der Blickrichtung der Plane, soweit der Blocktyp sie zulässt. Der Block gilt wie bei Spielern als
 *   „gesetzt“ ({@code BlockPhysics.markDeco}), wo der Blocktyp das vorsieht.</li>
 *   <li><b>Als Item</b> ausgeworfen wird über {@link Drops#at} in die Mitte der Stelle.</li>
 * </ul>
 * Blöcke werden sofort gesetzt, damit der Kern weiß, was wirklich untergebracht ist. Läuft gerade ein Durchlauf
 * eines Stores (das Netz wird aus einem Ereignis heraus befüllt), wird nichts gesetzt – die Items bleiben im Netz.
 */
final class PlanePlacer implements ItemPlacementStrategy.Target {

    /** AE2 zählt die Entities im Umkreis von 8 Blöcken. */
    private static final int ENTITY_RADIUS = 8;

    private final PlaneTarget target;
    private final List<Ref<EntityStore>> scratch = new ObjectArrayList<>();
    private final Vector3d min = new Vector3d();
    private final Vector3d max = new Vector3d();
    /** Ausgeworfene Stapel, die der räumliche Index noch nicht kennt (er hinkt einen Tick hinterher). */
    private long spawnTick = Long.MIN_VALUE;
    private int recentSpawns;

    // zuletzt nachgeschlagenes Item: beim Einlagern wird dasselbe Item oft hintereinander gefragt
    @Nullable
    private String cachedItemId;
    @Nullable
    private BlockType cachedType;
    private int cachedBlockId;
    private int cachedRotation;

    PlanePlacer(PlaneTarget target) {
        this.target = target;
    }

    @Override
    public boolean canBeReplaced() {
        if (!target.inWorld()) {
            return false;
        }
        Ref<ChunkStore> section = target.section();
        BlockSection blocks = section == null ? null : target.blocks(section);
        if (blocks == null) {
            return false;
        }
        return replaceable(blocks.get(target.x(), target.y(), target.z()));
    }

    private static boolean replaceable(int blockId) {
        if (blockId == 0) {
            return true;
        }
        BlockType type = BlockType.getAssetMap().getAsset(blockId);
        return type != null && (type == BlockType.EMPTY || type.getMaterial() == BlockMaterial.Empty);
    }

    @Override
    public int getMaxStackSize(AEItemKey what) {
        return ItemKeys.maxStack(what.itemId());
    }

    @Override
    public int countEntitiesAround() {
        World world = target.world();
        min.set(target.x() - ENTITY_RADIUS, target.y() - ENTITY_RADIUS, target.z() - ENTITY_RADIUS);
        max.set(target.x() + 1 + ENTITY_RADIUS, target.y() + 1 + ENTITY_RADIUS, target.z() + 1 + ENTITY_RADIUS);
        PlaneItems.collect(world, min, max, scratch);
        int count = scratch.size();
        scratch.clear();
        if (target.nets().tickCount() - spawnTick <= 2) {
            count += recentSpawns;
        }
        return count;
    }

    @Override
    public void spawnItemEntity(AEItemKey what, int amount) {
        if (amount <= 0) {
            return;
        }
        long now = target.nets().tickCount();
        if (now - spawnTick > 2) {
            recentSpawns = 0;
            spawnTick = now;
        }
        recentSpawns++;
        Drops.at(target.world(), target.x(), target.y(), target.z(), List.of(ItemKeys.toStack(what, amount)));
    }

    @Override
    public boolean canPlaceBlock(AEItemKey what) {
        return prepare(what) != null;
    }

    @Override
    public int placeBlock(AEItemKey what) {
        Ref<ChunkStore> section = prepare(what);
        BlockType type = cachedType;
        if (section == null || type == null) {
            return 0;
        }
        World world = target.world();
        ChunkStore chunkStore = world.getChunkStore();
        int x = target.x(), y = target.y(), z = target.z();
        int rotation = cachedRotation;
        // Was verdrängt wird (Gras, Blumen), lässt seinen Drop fallen – wie beim Platzieren durch Spieler
        List<ItemStack> displaced = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        BlockBoundingBoxes boxes = BlockBoundingBoxes.getAssetMap().getAsset(type.getHitboxTypeIndex());
        if (boxes != null) {
            FillerBlockUtil.forEachFillerBlock(boxes.get(rotation),
                    (dx, dy, dz) -> collectDisplaced(x + dx, y + dy, z + dz, displaced, seen));
        } else {
            collectDisplaced(x, y, z, displaced, seen);
        }
        boolean placed;
        try {
            placed = BlockOperations.setBlock(chunkStore, section, x, y, z, cachedBlockId, type, rotation, 0,
                    SetBlockSettings.PERFORM_BLOCK_UPDATE);
            if (placed) {
                // Wie ein von Spielern gesetzter Block: braucht keine Stütze und gibt beim Abbauen sich selbst
                // zurück statt seines natürlichen Drops (sonst ließe sich mit zwei Planes Beute vermehren)
                if (type.canBePlacedAsDeco()) {
                    BlockPhysics.markDeco(chunkStore.getStore(), section, x, y, z);
                }
                BlockSection blocks = target.blocks(section);
                if (blocks != null) {
                    Dir back = target.facing().opposite();
                    ConnectedBlocksUtil.setConnectedBlockAndNotifyNeighbors(chunkStore, cachedBlockId, RotationTuple.get(rotation),
                            new Vector3i(back.dx, back.dy, back.dz), new Vector3i(x, y, z), section, blocks);
                }
            }
        } catch (RuntimeException e) {
            Planes.warn("AE2: formation plane could not place " + type.getId() + " at " + x + " " + y + " " + z, e);
            // Zählt als gesetzt, wenn der Block jetzt dort steht – sonst bleibt das Item im Netz
            BlockSection blocks = target.blocks(section);
            placed = blocks != null && blocks.get(x, y, z) == cachedBlockId;
        }
        if (!placed) {
            return 0;
        }
        Drops.at(world, x, y, z, displaced);
        Planes.blockChanged(target.nets(), x, y, z);
        return 1;
    }

    /**
     * Merkt den Drop eines weichen Blocks, den der neue Block gleich überschreibt. Bei mehrteiligen Blöcken (hohes
     * Gras) zählt der Ursprung, und zwar nur einmal.
     */
    private void collectDisplaced(int bx, int by, int bz, List<ItemStack> out, LongOpenHashSet seen) {
        Ref<ChunkStore> section = target.section(bx, by, bz);
        BlockSection blocks = section == null ? null : target.blocks(section);
        int id = blocks == null ? 0 : blocks.get(bx, by, bz);
        if (id == 0) {
            return;
        }
        int filler = blocks.getFiller(bx, by, bz);
        if (filler != 0) {
            bx -= FillerBlockUtil.unpackX(filler);
            by -= FillerBlockUtil.unpackY(filler);
            bz -= FillerBlockUtil.unpackZ(filler);
        }
        if (!seen.add(BlockAccess.pack(bx, by, bz))) {
            return;
        }
        BlockType old = BlockType.getAssetMap().getAsset(id);
        BlockGathering gathering = old == null ? null : old.getGathering();
        SoftBlockDropType soft = gathering == null ? null : gathering.getSoft();
        if (soft == null) {
            return;
        }
        out.addAll(BlockHarvestUtils.getDrops(old, 1, soft.getItemId(), soft.getDropListId()));
    }

    /**
     * Prüft alles, was zum Setzen von {@code what} nötig ist, und merkt Blocktyp und Ausrichtung.
     *
     * @return die Sektion des Ziels, wenn der Block jetzt gesetzt werden darf, sonst null
     */
    @Nullable
    private Ref<ChunkStore> prepare(AEItemKey what) {
        if (what.nbt() != null) {
            return null; // Items mit Metadaten oder Abnutzung behalten ihren Zustand nur als Item
        }
        if (!what.itemId().equals(cachedItemId)) {
            cachedItemId = what.itemId();
            cachedType = null;
            Item item = Item.getAssetMap().getAsset(what.itemId());
            String key = item == null || !item.hasBlockType() ? null : item.getBlockId();
            if (key != null && !BlockAccess.EMPTY.equals(key)) {
                int id = BlockType.getAssetMap().getIndexOrDefault(key, Integer.MIN_VALUE);
                BlockType type = id == Integer.MIN_VALUE ? null : BlockType.getAssetMap().getAsset(id);
                // Blöcke, die beim Setzen ein ganzes Prefab bauen, setzt nur ein Spieler
                if (type != null && id > 0 && !type.isUnknown() && type.getPrefabListAssetId() == null) {
                    cachedType = type;
                    cachedBlockId = id;
                    cachedRotation = rotationFor(type, target.facing());
                }
            }
        }
        BlockType type = cachedType;
        if (type == null || !target.inWorld()) {
            return null;
        }
        World world = target.world();
        Store<ChunkStore> store = world.getChunkStore().getStore();
        if (!world.isInThread() || store.isProcessing() || world.getEntityStore().getStore().isProcessing()) {
            return null;
        }
        if (!world.getGameplayConfig().getWorldConfig().isBlockPlacementAllowed()) {
            return null;
        }
        Ref<ChunkStore> section = target.section();
        BlockSection blocks = section == null ? null : target.blocks(section);
        if (blocks == null) {
            return null;
        }
        int x = target.x(), y = target.y(), z = target.z();
        if (!target.environmentAllows(section, x, y, z) || !target.mayModify()) {
            return null;
        }
        return BlockOperations.testPlaceBlock(store, blocks, x, y, z, type, cachedRotation) ? section : null;
    }

    /**
     * Ausrichtung eines gesetzten Blocks: Zeigt die Plane waagerecht, wird der Block um die Hochachse so gedreht, als
     * hätte ihn jemand gesetzt, der in Blickrichtung der Plane schaut; der Blocktyp rundet das auf eine Ausrichtung,
     * die er zulässt. Zeigt sie nach oben oder unten, bleibt die Grundausrichtung.
     */
    static int rotationFor(BlockType type, Dir facing) {
        VariantRotation variant = type.getVariantRotation();
        if (variant == null || variant == VariantRotation.None || facing.dy != 0) {
            return 0;
        }
        RotationTuple wanted = RotationTuple.NONE;
        for (Rotation yaw : Rotation.VALUES) {
            RotationTuple candidate = RotationTuple.of(yaw, Rotation.None);
            if (BlockAccess.facing(candidate.index(), Dir.NORTH) == facing) {
                wanted = candidate;
                break;
            }
        }
        try {
            RotationTuple allowed = variant.verify(wanted);
            return allowed == null ? 0 : allowed.index();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
