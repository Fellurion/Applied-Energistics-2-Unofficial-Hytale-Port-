/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.automation.ItemPickupStrategy – der Teil, der die Minecraft-Welt anfasst).
 */
package de.fellurion.ae2.hytale.plane;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.blocktype.component.BlockPhysics;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import de.fellurion.ae2.core.automation.ItemPickupStrategy;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Drops;
import de.fellurion.ae2.hytale.security.AE2Security;
import org.joml.Vector3i;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Der Block vor einer Annihilation Plane, wie ihn {@link ItemPickupStrategy} braucht: darf er abgebaut werden, was
 * gibt er her, und das Abbauen selbst.
 *
 * <ul>
 *   <li><b>Abbaubar</b> ist, was ein Spieler mit Werkzeug abbauen könnte ({@link PlaneBlocks}); nicht: Luft
 *   (Flüssigkeiten sind in Hytale keine Blöcke), Blöcke der Sperrliste ({@code planes.json}), Stellen in nicht
 *   geladenen Chunks, Zonen ohne Blockänderungen, Stellen, die der Bauschutz der Plane verwehrt, und AE2-Blöcke
 *   fremder Netze ohne Zugriff ({@code AE2Security.deviceMayAccess}).</li>
 *   <li><b>Drops</b> berechnet Hytale selbst ({@code BlockHarvestUtils.getDrops} mit der Drop-Regel des Blocks).
 *   Bei Containern, die nicht zu AE2 gehören, kommt der Inhalt dazu.</li>
 *   <li><b>Abbauen</b>: Der Inhalt wird dem Container entnommen, bevor der Block verschwindet – sonst ließe Hytale
 *   ihn zusätzlich fallen. Der Block geht über Hytales normales Abbauen ohne Spieler
 *   ({@code performBlockBreak}, Drops abgeschaltet): mit Ton, Partikeln, Nachbar-Updates und dem Ereignis für andere
 *   Plugins.</li>
 *   <li><b>AE2-Blöcke</b> werden wie von einem Spieler abgebaut: Zellen, Karten und Slot-Inhalt fallen heraus und
 *   werden als herumliegende Items eingesammelt – so macht es auch AE2. Vorher schreibt das Gerät seinen Zustand in
 *   die Items (Zelleninhalte), wie es das sonst am Ende des Takts täte.</li>
 * </ul>
 */
final class PlaneBreaker implements ItemPickupStrategy.Target {

    private final PlaneTarget target;

    // Ergebnis von canHandleBlock; gilt für die folgenden Aufrufe desselben Ticks
    @Nullable
    private BlockType type;
    private int blockId;
    private int baseX, baseY, baseZ;
    /** Eigene Drops des Blocks aus obtainBlockDrops (Drop-Listen würfeln: derselbe Wurf gilt beim Abbauen). */
    @Nullable
    private List<GenericStack> ownDrops;
    @Nullable
    private ItemContainer container;

    PlaneBreaker(PlaneTarget target) {
        this.target = target;
    }

    @Override
    public boolean canHandleBlock() {
        type = null;
        ownDrops = null;
        container = null;
        World world = target.world();
        Store<ChunkStore> store = world.getChunkStore().getStore();
        if (!target.inWorld() || !world.isInThread() || store.isProcessing() || world.getEntityStore().getStore().isProcessing()) {
            return false;
        }
        Ref<ChunkStore> section = target.section();
        BlockSection blocks = section == null ? null : target.blocks(section);
        if (blocks == null) {
            return false;
        }
        int x = target.x(), y = target.y(), z = target.z();
        int id = blocks.get(x, y, z);
        if (id == 0) {
            return false; // Luft – auch mit Flüssigkeit darin
        }
        BlockType t = BlockType.getAssetMap().getAsset(id);
        if (t == null || t == BlockType.EMPTY || t.isUnknown()) {
            return false;
        }
        // Teil eines größeren Blocks: abgebaut wird an dessen Ursprung
        int bx = x, by = y, bz = z;
        int filler = blocks.getFiller(x, y, z);
        if (filler != 0) {
            bx -= FillerBlockUtil.unpackX(filler);
            by -= FillerBlockUtil.unpackY(filler);
            bz -= FillerBlockUtil.unpackZ(filler);
            section = target.section(bx, by, bz);
            blocks = section == null ? null : target.blocks(section);
            if (blocks == null) {
                return false;
            }
            BlockType base = BlockType.getAssetMap().getAsset(blocks.get(bx, by, bz));
            if (base == null || !base.getId().equals(t.getId())) {
                return false; // verwaister Füllblock
            }
            id = blocks.get(bx, by, bz);
            t = base;
        }
        if (!PlaneBlocks.breakable(t) || PlaneBlocks.blacklisted(id, t)) {
            return false;
        }
        if (!world.getGameplayConfig().getWorldConfig().isBlockBreakingAllowed(t) || !target.environmentAllows(section, bx, by, bz)) {
            return false;
        }
        if (!target.mayModify() || (bx != x || bz != z) && !target.mayModify(bx, bz)) {
            return false;
        }
        // AE2-Blöcke eines fremden Netzes: nur, wenn dessen Besitzer dem Besitzer dieses Netzes Zugriff gibt –
        // sonst ließen sich mit einer Plane neben einem fremden Laufwerk dessen Zellen holen (Regel der Busse)
        if (!AE2Security.deviceMayAccess(target.nets(), target.plane(), bx, by, bz)) {
            return false;
        }
        type = t;
        blockId = id;
        baseX = bx;
        baseY = by;
        baseZ = bz;
        return true;
    }

    @Override
    public float getDestroySpeed() {
        return type == null ? 0f : PlaneBlocks.hardness(type);
    }

    @Override
    public List<GenericStack> obtainBlockDrops() {
        List<GenericStack> out = new ArrayList<>();
        BlockType t = type;
        PlaneBlocks.DropRule rule = t == null ? null : PlaneBlocks.dropRule(t);
        if (rule == null) {
            return out;
        }
        World world = target.world();
        String itemId = rule.itemId();
        String dropListId = rule.dropListId();
        BlockGathering gathering = t.getGathering();
        if (gathering != null && gathering.shouldUseDefaultDropWhenPlaced()) {
            // Von Spielern als Deko gesetzte Blöcke geben sich selbst zurück, nicht ihren natürlichen Drop
            Ref<ChunkStore> section = target.section(baseX, baseY, baseZ);
            BlockPhysics physics = section == null ? null
                    : world.getChunkStore().getStore().getComponent(section, BlockPhysics.getComponentType());
            if (physics != null && physics.isDeco(baseX, baseY, baseZ)) {
                itemId = null;
                dropListId = null;
            }
        }
        for (ItemStack drop : BlockHarvestUtils.getDrops(t, rule.quantity(), itemId, dropListId)) {
            add(out, drop);
        }
        ownDrops = List.copyOf(out);
        // Inhalt fremder Container gehört zu dem, was ins Netz passen muss. AE2-Blöcke: siehe Klassenkommentar.
        if (target.nets().node(baseX, baseY, baseZ) == null) {
            ItemContainer c = BlockAccess.container(world, baseX, baseY, baseZ);
            if (c != null && !c.isEmpty()) {
                container = c;
                for (short slot = 0; slot < c.getCapacity(); slot++) {
                    add(out, c.getItemStack(slot));
                }
            }
        }
        return out;
    }

    @Override
    @Nullable
    public List<GenericStack> destroyBlock(List<GenericStack> drops) {
        BlockType t = type;
        List<GenericStack> own = ownDrops;
        ItemContainer c = container;
        type = null;
        ownDrops = null;
        container = null;
        if (t == null || own == null) {
            return null;
        }
        World world = target.world();
        Ref<ChunkStore> section = target.section(baseX, baseY, baseZ);
        BlockSection blocks = section == null ? null : target.blocks(section);
        if (blocks == null || blocks.get(baseX, baseY, baseZ) != blockId) {
            return null; // inzwischen etwas anderes: nichts angefasst
        }
        List<GenericStack> released = new ArrayList<>(own);
        if (c != null) {
            // Was Hytale beim Entfernen fallen ließe, nimmt die Plane vorher selbst heraus – genau das wird eingelagert
            for (ItemStack stack : c.dropAllItemStacks()) {
                add(released, stack);
            }
            // Wer den Container noch kennt (ein Storage-Bus bis zu seiner nächsten Zielsuche), legt nichts mehr hinein
            c.setGlobalFilter(FilterType.DENY_ALL);
        }
        WorldNetworks.Node ae2 = target.nets().node(baseX, baseY, baseZ);
        if (ae2 != null && ae2.device() != null) {
            // AE2-Gerät: seinen Zustand jetzt in die Items seines Containers schreiben (sonst täte es das erst am
            // Ende dieses Takts) – was gleich herausfällt, ist damit auf dem Stand dieses Augenblicks
            try {
                ae2.device().afterCoreTick(target.nets(), ae2);
            } catch (RuntimeException e) {
                Planes.warn("AE2: could not save " + ae2.kind() + " before an annihilation plane breaks it", e);
            }
        }
        try {
            BlockHarvestUtils.performBlockBreak(new Vector3i(baseX, baseY, baseZ), t, null, 0, null, null,
                    SetBlockSettings.NO_DROP_ITEMS, (Ref<EntityStore>) null, section, world.getEntityStore().getStore(),
                    world.getChunkStore().getStore());
        } catch (RuntimeException e) {
            Planes.warn("AE2: annihilation plane could not break " + t.getId() + " at " + baseX + " " + baseY + " " + baseZ, e);
        }
        BlockSection after = target.blocks(section);
        if (after == null || after.get(baseX, baseY, baseZ) == blockId) {
            // Der Block steht noch: seinen eigenen Drop gibt es dann nicht. Der schon entnommene Inhalt wird
            // eingelagert, der Container nimmt wieder an.
            released.subList(0, own.size()).clear();
            if (c != null) {
                c.setGlobalFilter(FilterType.ALLOW_ALL);
            }
        } else {
            Planes.blockChanged(target.nets(), baseX, baseY, baseZ);
            if (baseX != target.x() || baseY != target.y() || baseZ != target.z()) {
                Planes.blockChanged(target.nets(), target.x(), target.y(), target.z());
            }
        }
        return released;
    }

    @Override
    public void spawnDrops(List<GenericStack> drops) {
        List<ItemStack> stacks = new ArrayList<>();
        for (GenericStack drop : drops) {
            if (!(drop.what() instanceof AEItemKey key)) {
                continue;
            }
            int max = ItemKeys.maxStack(key.itemId());
            long left = drop.amount();
            while (left > 0) {
                int n = (int) Math.min(left, max);
                stacks.add(ItemKeys.toStack(key, n));
                left -= n;
            }
        }
        Drops.at(target.world(), target.x(), target.y(), target.z(), stacks);
    }

    @Override
    public boolean isItemBlacklisted(AEItemKey item) {
        return PlaneConfig.itemBlocked(item.itemId());
    }

    private static void add(List<GenericStack> out, @Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return;
        }
        AEItemKey key = ItemKeys.of(stack);
        if (key != null) {
            out.add(new GenericStack(key, stack.getQuantity()));
        }
    }
}
