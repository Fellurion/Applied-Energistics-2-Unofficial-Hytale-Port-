/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: Tag ae2:blacklisted/spatial in appeng.spatial.CachedPlane).
 */
package de.fellurion.ae2.hytale.spatial;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktick.BlockTickManager;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Welche Blöcke ein Raumtransfer nie anfasst – die Hytale-Fassung von AE2s Tag {@code ae2:blacklisted/spatial}.
 * Eine Stelle mit einem solchen Block wird übersprungen: der Block bleibt in der Welt, und was der Plot an dieser
 * Stelle gespeichert hat, bleibt im Plot (so macht es AE2s {@code CachedPlane}).
 *
 * <p>Nie versetzt werden:
 * <ul>
 *   <li>Blöcke der Liste {@code blacklistBlocks} (Standard: Grundgestein);</li>
 *   <li>Blöcke, die kein Spieler abbauen kann – Hytales Begriff von „unzerstörbar“: ohne {@code Gathering} oder mit
 *       {@code GatherType: Unbreakable} (Grundgestein, Barrieren, Portale, Spawner, Markierungen). Abschaltbar mit
 *       {@code moveUnbreakable};</li>
 *   <li>Blöcke, deren Block-Entity ein Bauteil der Liste {@code blacklistComponents} trägt (Schlafplatz,
 *       Teleporter, Karten-Markierung ...): solche Blöcke sind bei der Engine mit ihrem Ort angemeldet und würden
 *       beim Versetzen verwaiste Einträge hinterlassen.</li>
 * </ul>
 * Der Matrix-Rahmen ist die Ausnahme: wie in AE2 wird er beim Transfer zu Luft.
 *
 * <p>Die Tabellen je Block-Index entstehen beim ersten Bedarf. Sie sind reine Zwischenspeicher: benutzen zwei
 * Welt-Threads sie zugleich, wird schlimmstenfalls ein Eintrag doppelt berechnet.
 */
final class MoveRules {

    private static final byte UNKNOWN = 0;
    private static final byte MOVABLE = 1;
    private static final byte IMMOVABLE = 2;

    private final SpatialStore.Config config;
    private final List<ComponentType<ChunkStore, ?>> componentTypes = new ArrayList<>();
    private final List<String> exact = new ArrayList<>();
    private final List<String> prefixes = new ArrayList<>();
    private byte[] movable = new byte[0];
    private byte[] flags = new byte[0];
    final int matrixFrame;

    private static final byte FLAG_KNOWN = 1;
    private static final byte FLAG_MULTI_CELL = 2;
    private static final byte FLAG_TICKING = 4;
    private static final byte FLAG_SOLID = 8;
    private static final byte FLAG_ENTITY = 16;

    MoveRules(SpatialStore.Config config) {
        this.config = config;
        for (String key : config.blacklistBlocks()) {
            if (key.endsWith("*")) {
                prefixes.add(key.substring(0, key.length() - 1));
            } else {
                exact.add(key);
            }
        }
        var data = ChunkStore.REGISTRY.getData();
        for (String id : config.blacklistComponents()) {
            ComponentType<ChunkStore, ?> type = data.getComponentType(id);
            if (type != null) {
                componentTypes.add(type);
            }
        }
        this.matrixFrame = BlockType.getAssetMap().getIndexOrDefault("AE2_Matrix_Frame", Integer.MIN_VALUE);
    }

    /** Darf der Block mit diesem Index nicht versetzt werden? */
    boolean immovable(int blockId) {
        if (blockId <= 0) {
            return false;
        }
        byte[] table = movable;
        if (blockId >= table.length) {
            table = java.util.Arrays.copyOf(table, Math.max(blockId + 1, BlockType.getAssetMap().getNextIndex()));
            movable = table;
        }
        byte state = table[blockId];
        if (state == UNKNOWN) {
            state = compute(blockId) ? IMMOVABLE : MOVABLE;
            table[blockId] = state;
        }
        return state == IMMOVABLE;
    }

    private boolean compute(int blockId) {
        BlockType type = BlockType.getAssetMap().getAsset(blockId);
        if (type == null || blockId == matrixFrame) {
            return false;
        }
        String key = type.getId();
        if (key != null) {
            if (exact.contains(key) || baseKeyListed(type)) {
                return true;
            }
            for (String prefix : prefixes) {
                if (key.startsWith(prefix)) {
                    return true;
                }
            }
        }
        if (!config.moveUnbreakable() && !type.isUnknown() && !breakable(type)) {
            return true;
        }
        Holder<ChunkStore> entity = type.getBlockEntity();
        return entity != null && hasImmovableComponent(entity.getArchetype());
    }

    /** Zustands-Varianten ({@code *Truhe_State_...}) folgen dem Eintrag ihres Grundblocks. */
    private boolean baseKeyListed(BlockType type) {
        if (type.getItem() == null) {
            return false;
        }
        String itemId = type.getItem().getId();
        if (itemId == null) {
            return false;
        }
        if (exact.contains(itemId)) {
            return true;
        }
        for (String prefix : prefixes) {
            if (itemId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Kann ein Spieler diesen Block im Abenteuer-Modus irgendwie abbauen? */
    static boolean breakable(BlockType type) {
        BlockGathering gathering = type.getGathering();
        if (gathering == null) {
            return false;
        }
        if (gathering.isSoft() || gathering.isHarvestable()) {
            return true;
        }
        BlockBreakingDropType breaking = gathering.getBreaking();
        return breaking != null && breaking.getGatherType() != null && !"Unbreakable".equals(breaking.getGatherType());
    }

    /** Trägt eine Block-Entity mit diesen Bauteilen eines, das an ihrem Ort hängt? */
    boolean hasImmovableComponent(@Nullable Archetype<ChunkStore> archetype) {
        if (archetype == null) {
            return false;
        }
        for (int i = 0; i < componentTypes.size(); i++) {
            if (archetype.contains(componentTypes.get(i))) {
                return true;
            }
        }
        return false;
    }

    Set<String> blacklistedComponents() {
        return config.blacklistComponents();
    }

    // ------------------------------------------------------------------ weitere Tabellen je Block

    @SuppressWarnings("removal") // BlockTickManager: dieselbe Abfrage wie in Hytales BlockOperations.setBlock
    private byte flags(int blockId) {
        if (blockId <= 0) {
            return FLAG_KNOWN;
        }
        byte[] table = flags;
        if (blockId >= table.length) {
            table = java.util.Arrays.copyOf(table, Math.max(blockId + 1, BlockType.getAssetMap().getNextIndex()));
            flags = table;
        }
        byte f = table[blockId];
        if (f == 0) {
            f = FLAG_KNOWN;
            BlockType type = BlockType.getAssetMap().getAsset(blockId);
            if (type != null) {
                BlockBoundingBoxes boxes = BlockBoundingBoxes.getAssetMap().getAsset(type.getHitboxTypeIndex());
                if (boxes != null && boxes.protrudesUnitBox()) {
                    f |= FLAG_MULTI_CELL;
                }
                if (BlockTickManager.getBlockTickProvider().getTickProcedure(blockId) != null) {
                    f |= FLAG_TICKING;
                }
                if (type.getMaterial() == BlockMaterial.Solid) {
                    f |= FLAG_SOLID;
                }
                if (type.getBlockEntity() != null) {
                    f |= FLAG_ENTITY;
                }
            }
            table[blockId] = f;
        }
        return f;
    }

    /** Belegt der Block mehr als eine Zelle (Bett, Tür, große Möbel)? */
    boolean multiCell(int blockId) {
        return (flags(blockId) & FLAG_MULTI_CELL) != 0;
    }

    /** Hat der Block eine Tick-Prozedur (wächst, fließt, fällt)? */
    boolean ticking(int blockId) {
        return (flags(blockId) & FLAG_TICKING) != 0;
    }

    /** Fester Block, in dem ein Spieler stecken bliebe. */
    boolean solid(int blockId) {
        return (flags(blockId) & FLAG_SOLID) != 0;
    }

    /** Sieht der Blocktyp eine Block-Entity vor? */
    boolean declaresEntity(int blockId) {
        return (flags(blockId) & FLAG_ENTITY) != 0;
    }
}
