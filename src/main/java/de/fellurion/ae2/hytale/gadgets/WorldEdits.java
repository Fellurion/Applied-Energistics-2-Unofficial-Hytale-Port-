/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.gadgets;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BlockRotation;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.BlockPlaceUtils;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EnvironmentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.BlockAccess;
import org.joml.Vector3i;

import javax.annotation.Nullable;

/**
 * Änderungen an der Welt, die ein Werkzeug auslöst – so, dass jeder Schutz mitreden kann.
 *
 * <p>{@link #forPlayer}: Abbauen und Setzen laufen über Hytales eigene Wege für Spieler
 * ({@link BlockHarvestUtils#performBlockBreak}, {@link BlockPlaceUtils#placeBlock}); dabei entstehen
 * {@code BreakBlockEvent} bzw. {@code PlaceBlockEvent}, die Claim-Plugins abbrechen können. Für Flüssigkeiten kennt
 * Hytale kein Ereignis (auch der Eimer löst keines aus); {@link #mayModify} fragt deshalb mit einem
 * {@code PlaceBlockEvent} an der Stelle nach, ohne etwas zu setzen.
 *
 * <p>{@link #forConsole}: dieselben Änderungen ohne Spieler – nur für die Testbefehle der Konsole.
 */
abstract class WorldEdits {

    final World world;

    WorldEdits(World world) {
        this.world = world;
    }

    /**
     * Baut den Block ab.
     *
     * @param drops false = der Block verschwindet ohne Beute (er wird umgewandelt)
     * @return true, wenn die Stelle danach leer ist
     */
    abstract boolean breakBlock(int x, int y, int z, boolean drops);

    /**
     * Setzt einen Block an die Stelle; was dort steht, wird ersetzt (ohne Beute).
     *
     * @param rotation Rotationsindex des neuen Blocks
     * @return true, wenn der Block gesetzt wurde
     */
    abstract boolean placeBlock(int x, int y, int z, String blockKey, int rotation);

    /**
     * Darf die Zelle auf eine Art verändert werden, für die Hytale kein eigenes Ereignis hat (Flüssigkeit hinzufügen
     * oder entfernen, Feuer legen, eine Sprengladung zünden)?
     */
    abstract boolean mayModify(int x, int y, int z);

    // ------------------------------------------------------------------ Flüssigkeiten (für alle gleich)

    /**
     * Setzt die Flüssigkeit der Zelle (voller Pegel) oder entfernt sie ({@code fluidKey == null}); die Nachbarn
     * werden zum Nachfließen geweckt. Wie Hytales {@code PlaceFluidInteraction} / {@code RefillContainerInteraction}.
     *
     * @return true, wenn sich etwas geändert hat
     */
    boolean setFluid(int x, int y, int z, @Nullable String fluidKey) {
        ChunkStore chunks = world.getChunkStore();
        Ref<ChunkStore> section = chunks.getChunkSectionReferenceAtBlock(x, y, z);
        if (section == null || !section.isValid()) {
            return false;
        }
        Store<ChunkStore> store = chunks.getStore();
        FluidSection fluids = store.getComponent(section, FluidSection.getComponentType());
        BlockSection blocks = store.getComponent(section, BlockSection.getComponentType());
        ChunkSection chunkSection = store.getComponent(section, ChunkSection.getComponentType());
        if (fluids == null || blocks == null || chunkSection == null) {
            return false;
        }
        boolean changed;
        if (fluidKey == null) {
            changed = fluids.setFluid(x, y, z, 0, (byte) 0);
        } else {
            int id = Fluid.getAssetMap().getIndexOrDefault(fluidKey, Integer.MIN_VALUE);
            Fluid fluid = id == Integer.MIN_VALUE ? null : Fluid.getAssetMap().getAsset(id);
            if (fluid == null) {
                return false;
            }
            changed = fluids.setFluid(x, y, z, id, (byte) fluid.getMaxFluidLevel());
        }
        if (changed) {
            chunkSection.markNeedsSaving();
            BlockOperations.updateBlockArea(chunks, chunkSection, blocks, x, y, z);
        }
        return changed;
    }

    /** Asset-Schlüssel der Flüssigkeit in der Zelle, oder null. */
    @Nullable
    static String fluidKey(World world, int x, int y, int z) {
        ChunkStore chunks = world.getChunkStore();
        Ref<ChunkStore> section = chunks.getChunkSectionReferenceAtBlock(x, y, z);
        if (section == null || !section.isValid()) {
            return null;
        }
        FluidSection fluids = chunks.getStore().getComponent(section, FluidSection.getComponentType());
        int id = fluids == null ? 0 : fluids.getFluidId(x, y, z);
        if (id == 0) {
            return null;
        }
        Fluid fluid = Fluid.getAssetMap().getAsset(id);
        return fluid == null ? null : fluid.getId();
    }

    @Nullable
    static Ref<ChunkStore> section(World world, int x, int y, int z) {
        if (y < 0 || y >= 320) {
            return null;
        }
        Ref<ChunkStore> section = world.getChunkStore().getChunkSectionReferenceAtBlock(x, y, z);
        return section == null || !section.isValid() ? null : section;
    }

    /**
     * Hytales Abbauen eines Blocks ({@link BlockHarvestUtils#performBlockBreak}) – mit {@code breaker} entsteht dabei
     * das {@code BreakBlockEvent} des Spielers, ohne das Ereignis der Umwelt. Die Beute ist die, die der Block beim
     * normalen Abbauen gibt (so ruft Hytale selbst den Weg auf, wenn ein Block zu Ende abgebaut ist); ob sie fällt,
     * entscheidet Hytale (nicht im Kreativmodus).
     */
    private static void breakWithDrops(World world, BlockType type, int x, int y, int z, boolean drops,
                                       @Nullable Ref<EntityStore> breaker, @Nullable ItemStack held, Ref<ChunkStore> section,
                                       ComponentAccessor<EntityStore> entities) {
        int quantity = 0;
        String itemId = null;
        String dropList = null;
        BlockGathering gathering = drops ? type.getGathering() : null;
        if (gathering != null && gathering.getBreaking() != null) {
            quantity = Math.max(1, gathering.getBreaking().getQuantity());
            itemId = gathering.getBreaking().getItemId();
            dropList = gathering.getBreaking().getDropListId();
        } else if (gathering != null && gathering.getSoft() != null) {
            quantity = 1;
            itemId = gathering.getSoft().getItemId();
            dropList = gathering.getSoft().getDropListId();
        }
        BlockHarvestUtils.performBlockBreak(new Vector3i(x, y, z), type, held, quantity, itemId, dropList,
                drops ? SetBlockSettings.NONE : SetBlockSettings.NO_DROP_ITEMS, breaker, section, entities,
                world.getChunkStore().getStore());
    }

    // ------------------------------------------------------------------ Spieler

    /**
     * Änderungen im Namen eines Spielers.
     *
     * @param entities  der Zugriff der laufenden Interaktion (ihr CommandBuffer)
     * @param held      das Werkzeug in der Hand (steht im {@code BreakBlockEvent})
     * @param container Behälter und Slot des Werkzeugs (Hytales Setzen will sie wissen, fasst sie hier aber nicht an)
     */
    static WorldEdits forPlayer(World world, ComponentAccessor<EntityStore> entities, Ref<EntityStore> player,
                                @Nullable ItemStack held, ItemContainer container, short slot) {
        return new WorldEdits(world) {

            private boolean environmentAllows(Ref<ChunkStore> section, int x, int y, int z) {
                // wie Hytales tryPlaceBlock / damageSingleBlock: im Abenteuer-Modus sperren manche Umgebungen das Bauen
                Player p = entities.getComponent(player, Player.getComponentType());
                if (p != null && p.getGameMode() != GameMode.Adventure) {
                    return true;
                }
                EnvironmentSection env = world.getChunkStore().getStore().getComponent(section, EnvironmentSection.getComponentType());
                Environment environment = env == null ? null : Environment.getAssetMap().getAsset(env.get(x, y, z));
                return environment == null || environment.isBlockModificationAllowed();
            }

            @Override
            boolean breakBlock(int x, int y, int z, boolean drops) {
                Ref<ChunkStore> section = section(world, x, y, z);
                BlockType type = BlockAccess.blockType(world, x, y, z);
                if (section == null || type == null || !environmentAllows(section, x, y, z)
                        || !world.getGameplayConfig().getWorldConfig().isBlockBreakingAllowed(type)) {
                    return false;
                }
                breakWithDrops(world, type, x, y, z, drops, player, held, section, entities);
                return BlockAccess.blockType(world, x, y, z) == null;
            }

            @Override
            boolean placeBlock(int x, int y, int z, String blockKey, int rotation) {
                Ref<ChunkStore> section = section(world, x, y, z);
                if (section == null || BlockType.getAssetMap().getAsset(blockKey) == null
                        || !world.getGameplayConfig().getWorldConfig().isBlockPlacementAllowed()) {
                    return false;
                }
                RotationTuple tuple = RotationTuple.get(rotation);
                BlockRotation blockRotation = new BlockRotation(tuple.yaw().toPacket(), tuple.pitch().toPacket(), tuple.roll().toPacket());
                // quickReplace: der Block darf einen vorhandenen ersetzen; das PlaceBlockEvent entsteht trotzdem
                return BlockPlaceUtils.placeBlock(player, null, blockKey, container, new Vector3i(0, 1, 0), new Vector3i(x, y, z),
                        blockRotation, (byte) slot, false, section, world.getChunkStore().getStore(), entities, true, false, false);
            }

            @Override
            boolean mayModify(int x, int y, int z) {
                Ref<ChunkStore> section = section(world, x, y, z);
                if (section == null || !environmentAllows(section, x, y, z)
                        || !world.getGameplayConfig().getWorldConfig().isBlockPlacementAllowed()) {
                    return false;
                }
                PlaceBlockEvent probe = new PlaceBlockEvent(held, new Vector3i(x, y, z), RotationTuple.NONE);
                entities.invoke(player, probe);
                return !probe.isCancelled();
            }
        };
    }

    // ------------------------------------------------------------------ Konsole

    /** Änderungen ohne Spieler (Testbefehle): Abbauen über Hytales Weg für die Umwelt, Setzen direkt. */
    static WorldEdits forConsole(World world) {
        return new WorldEdits(world) {
            @Override
            boolean breakBlock(int x, int y, int z, boolean drops) {
                Ref<ChunkStore> section = section(world, x, y, z);
                BlockType type = BlockAccess.blockType(world, x, y, z);
                if (section == null || type == null) {
                    return false;
                }
                breakWithDrops(world, type, x, y, z, drops, null, null, section, world.getEntityStore().getStore());
                return BlockAccess.blockType(world, x, y, z) == null;
            }

            @Override
            boolean placeBlock(int x, int y, int z, String blockKey, int rotation) {
                return BlockAccess.setBlock(world, x, y, z, blockKey, rotation, SetBlockSettings.PERFORM_BLOCK_UPDATE);
            }

            @Override
            boolean mayModify(int x, int y, int z) {
                return section(world, x, y, z) != null;
            }
        };
    }
}
