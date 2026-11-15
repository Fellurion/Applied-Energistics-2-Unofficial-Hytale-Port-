/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.server.subcommands.SpatialStorageCommand).
 */
package de.fellurion.ae2.hytale.spatial;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.blocktype.component.BlockPhysics;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeIntPosition;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.spatial.SpatialIOPortLogic;
import de.fellurion.ae2.core.spatial.SpatialPlotInfo;
import de.fellurion.ae2.core.spatial.SpatialPylonCluster;
import de.fellurion.ae2.core.spatial.SpatialPylonService;
import de.fellurion.ae2.core.spatial.SpatialStoragePlot;
import de.fellurion.ae2.core.spatial.TransitionInfo;
import de.fellurion.ae2.hytale.AE2BlockComponent;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.signal.Signals;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

/**
 * {@code /ae2 spatial …} (Admin, {@code ae2.admin}) – Auskunft und Prüfbefehle für Spatial IO:
 * <ul>
 *   <li>{@code info x y z} – Gebiet, Effizienz, Energiebedarf und Zustand am Pylon/Port/Anker</li>
 *   <li>{@code transfer x y z} – löst den Transfer am Port aus (wie ein Signal) und nennt Ergebnis und Dauer</li>
 *   <li>{@code plots} – alle Plots mit Größe, Besitzer und letztem Transfer</li>
 *   <li>{@code delete id} – löscht einen Plot samt Inhalt (AE2: Plot freigeben)</li>
 *   <li>{@code entities x y z radius} – zählt Entities und herumliegende Items im Umkreis</li>
 *   <li>{@code bench x y z size} – misst einen Tausch hin und zurück an einem Würfel (ohne Pylone und Zelle)</li>
 *   <li>{@code rules} – welche Blocktypen nie versetzt werden</li>
 *   <li>{@code probe x y z} – alles über eine Stelle in einer Zeile: Block, Rotation, Füllteil, Stützwert,
 *       Flüssigkeit, Bauteile der Block-Entity, AE2-Daten, Inhalt des Behälters (zum Vergleichen vor/nach einem
 *       Transfer)</li>
 *   <li>{@code setblock x y z block rotation}, {@code setfluid x y z fluid level} – Block mit Rotation bzw.
 *       Flüssigkeit setzen (die Befehle der Engine können das von der Konsole aus nicht)</li>
 *   <li>{@code movecell x y z x2 y2 z2} – legt die Zelle aus dem Ausgabe-Slot eines Ports in den Eingabe-Slot
 *       eines Ports</li>
 *   <li>{@code hash x y z size} – Prüfsumme über alle Blöcke, Flüssigkeiten und Block-Entities eines Würfels
 *       (vor und nach einem Tausch hin und zurück muss sie gleich sein)</li>
 *   <li>{@code signal x y z 0|1} – schaltet an der Stelle ein AE2-Signal aus oder ein (als stünde dort ein
 *       sendender Block), um einen Port daneben wie mit Redstone auszulösen</li>
 * </ul>
 */
final class SpatialCommands extends AbstractCommandCollection {

    SpatialCommands() {
        super("spatial", "ae2spatial.cmd.desc");
        requirePermission("ae2.admin");
        addSubCommand(new Info());
        addSubCommand(new Transfer());
        addSubCommand(new Plots());
        addSubCommand(new Delete());
        addSubCommand(new Entities());
        addSubCommand(new Bench());
        addSubCommand(new Rules());
        addSubCommand(new Probe());
        addSubCommand(new SetBlock());
        addSubCommand(new SetFluid());
        addSubCommand(new MoveCell());
        addSubCommand(new Signal());
        addSubCommand(new Hash());
    }

    private static Message tr(String key) {
        return Message.translation("ae2spatial.cmd." + key);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
    }

    private abstract static class Positioned extends AbstractWorldCommand {
        final RequiredArg<RelativeIntPosition> pos;

        Positioned(String name) {
            super(name, "ae2spatial.cmd." + name + ".desc");
            requirePermission("ae2.admin");
            this.pos = withRequiredArg("x y z", "ae2spatial.arg.position", ArgTypes.RELATIVE_BLOCK_POSITION);
        }

        @Override
        protected final void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i p = context.get(pos).getBlockPosition(context, store);
            run(context, world, WorldNetworks.of(world), p.x, p.y, p.z);
        }

        abstract void run(CommandContext context, World world, WorldNetworks nets, int x, int y, int z);
    }

    private static final class Info extends Positioned {
        Info() {
            super("info");
        }

        @Override
        void run(CommandContext context, World world, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            if (node == null) {
                context.sendMessage(tr("info.none"));
                return;
            }
            SpatialWorld spatial = SpatialWorld.of(nets);
            GridNode g = node.gridNode();
            Grid grid = g == null ? null : g.getGrid();
            if (node.device() instanceof PylonDevice pylon) {
                SpatialPylonCluster c = pylon.logic().getCluster();
                context.sendMessage(c == null ? tr("info.pylon.none")
                        : tr("info.pylon").param("count", c.size()).param("axis", c.axis().name())
                        .param("min", c.minX() + " " + c.minY() + " " + c.minZ())
                        .param("max", c.maxX() + " " + c.maxY() + " " + c.maxZ())
                        .param("state", pylon.visualState(nets, node)));
            }
            if (node.device() instanceof AnchorDevice anchor) {
                context.sendMessage(tr("info.anchor").param("chunks", anchor.logic().countLoadedChunks())
                        .param("held", spatial.anchors.heldChunks())
                        .param("power", String.format(Locale.ROOT, "%.1f", g == null ? 0 : g.getIdlePowerUsage()))
                        .param("online", String.valueOf(g != null && g.isOnline())));
                return;
            }
            if (grid == null) {
                context.sendMessage(tr("info.noNetwork"));
                return;
            }
            SpatialPylonService s = spatial.pylons.serviceOf(grid);
            if (!s.hasRegion()) {
                context.sendMessage(tr("info.noRegion").param("pylons", spatial.pylons.size()));
            } else {
                context.sendMessage(tr("info.region")
                        .param("valid", String.valueOf(s.isValidRegion()))
                        .param("min", s.innerMinX() + " " + s.innerMinY() + " " + s.innerMinZ())
                        .param("size", Math.max(0, s.sizeX()) + "x" + Math.max(0, s.sizeY()) + "x"
                                + Math.max(0, s.sizeZ()))
                        .param("pylons", s.pylonCount()).param("lines", s.clusterCount())
                        .param("efficiency", String.format(Locale.ROOT, "%.1f", s.currentEfficiency()))
                        .param("required", s.requiredPower())
                        .param("stored", String.format(Locale.ROOT, "%.0f", grid.getEnergyService().getStoredPower())));
            }
            if (node.device() instanceof IOPortDevice port) {
                SpatialIOPortLogic.Result status = port.logic().status(port.host(nets, node));
                ItemContainer c = BlockAccess.container(world, x, y, z);
                context.sendMessage(tr("info.port").param("status", status.name())
                        .param("input", describe(c, IOPortDevice.SLOT_INPUT))
                        .param("output", describe(c, IOPortDevice.SLOT_OUTPUT)));
            }
        }

        private static String describe(ItemContainer c, short slot) {
            if (c == null || c.getCapacity() <= slot) {
                return "-";
            }
            ItemStack stack = c.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) {
                return "-";
            }
            SpatialPlotInfo info = SpatialCells.isCell(stack) ? SpatialCells.info(stack) : null;
            return stack.getItemId() + (info == null ? "" : " " + info.serialNumber() + " " + info.sizeX() + "x"
                    + info.sizeY() + "x" + info.sizeZ());
        }
    }

    private static final class Transfer extends Positioned {
        Transfer() {
            super("transfer");
        }

        @Override
        void run(CommandContext context, World world, WorldNetworks nets, int x, int y, int z) {
            SpatialTransfers.Outcome outcome = SpatialTransfers.run(nets, x, y, z);
            context.sendMessage(tr("transfer.result").param("result", outcome.result().name())
                    .param("text", outcome.message()));
            if (outcome.result() == SpatialIOPortLogic.Result.OK) {
                context.sendMessage(timing(outcome.stats()));
            }
        }
    }

    private static Message timing(RegionSwap.Stats s) {
        return tr("timing").param("volume", s.volume).param("out", s.blocksOut).param("in", s.blocksIn)
                .param("entitiesOut", s.entitiesOut).param("entitiesIn", s.entitiesIn).param("skipped", s.skipped)
                .param("bytes", s.fileBytes).param("total", ms(s.totalNanos)).param("check", ms(s.checkNanos))
                .param("scan", ms(s.scanNanos)).param("take", ms(s.takeNanos)).param("save", ms(s.saveNanos))
                .param("place", ms(s.placeNanos));
    }

    private static final class Plots extends AbstractWorldCommand {
        Plots() {
            super("plots", "ae2spatial.cmd.plots.desc");
            requirePermission("ae2.admin");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            SpatialStore s = Spatial.store();
            var plots = s.plots().getPlots();
            context.sendMessage(tr("plots.header").param("count", plots.size()));
            for (SpatialStoragePlot plot : plots) {
                TransitionInfo t = plot.getLastTransition();
                long bytes = -1;
                try {
                    bytes = Files.size(s.plotFile(plot.getId()));
                } catch (IOException ignored) {
                    // keine Datei: der Plot ist leer
                }
                context.sendMessage(tr("plots.entry").param("id", plot.getId())
                        .param("size", plot.getSizeX() + "x" + plot.getSizeY() + "x" + plot.getSizeZ())
                        .param("owner", plot.getOwner() == null ? "-" : plot.getOwner())
                        .param("bytes", bytes)
                        .param("last", t == null ? "-" : t.worldId() + " " + (t.minX() + 1) + " " + (t.minY() + 1)
                                + " " + (t.minZ() + 1)));
            }
        }
    }

    private static final class Delete extends AbstractWorldCommand {
        private final RequiredArg<Integer> id;

        Delete() {
            super("delete", "ae2spatial.cmd.delete.desc");
            requirePermission("ae2.admin");
            id = withRequiredArg("id", "ae2spatial.arg.plot", ArgTypes.INTEGER);
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            boolean deleted = Spatial.store().deletePlot(context.get(id));
            context.sendMessage(tr(deleted ? "delete.done" : "delete.none").param("id", context.get(id)));
        }
    }

    private static final class Entities extends Positioned {
        private final RequiredArg<Integer> radius;

        Entities() {
            super("entities");
            radius = withRequiredArg("radius", "ae2spatial.arg.radius", ArgTypes.INTEGER);
        }

        @Override
        void run(CommandContext context, World world, WorldNetworks nets, int x, int y, int z) {
            double r = Math.max(1, context.get(radius));
            double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
            int[] counts = new int[2];
            var transformType = TransformComponent.getComponentType();
            var itemType = ItemComponent.getComponentType();
            world.getEntityStore().getStore().forEachChunk(transformType, (chunk, buffer) -> {
                boolean items = chunk.getArchetype().contains(itemType);
                for (int i = 0; i < chunk.size(); i++) {
                    TransformComponent t = chunk.getComponent(i, transformType);
                    if (t == null) {
                        continue;
                    }
                    Vector3d p = t.getPosition();
                    if (Math.abs(p.x - cx) <= r && Math.abs(p.y - cy) <= r && Math.abs(p.z - cz) <= r) {
                        counts[0]++;
                        if (items) {
                            counts[1]++;
                        }
                    }
                }
            });
            context.sendMessage(tr("entities").param("entities", counts[0]).param("items", counts[1]));
        }
    }

    /** Tauscht einen Würfel in einen Wegwerf-Plot und wieder zurück und nennt die Zeiten. */
    private static final class Bench extends Positioned {
        private final RequiredArg<Integer> size;

        Bench() {
            super("bench");
            size = withRequiredArg("size", "ae2spatial.arg.size", ArgTypes.INTEGER);
        }

        @Override
        void run(CommandContext context, World world, WorldNetworks nets, int x, int y, int z) {
            int s = Math.max(1, Math.min(SpatialStoragePlot.MAX_SIZE, context.get(size)));
            SpatialStore store = Spatial.store();
            // Plot 0 vergibt der Plot-Manager nie: er dient nur dieser Messung
            SpatialStoragePlot plot = new SpatialStoragePlot(0, s, s, s, null);
            RegionSwap.Stats out = new RegionSwap.Stats();
            RegionSwap.Refusal refusal = RegionSwap.swap(store, Spatial.rules(), world, plot, true, x, y, z, s, s, s,
                    x, z, out);
            if (refusal != null) {
                context.sendMessage(SpatialTransfers.refusalMessage(refusal));
                return;
            }
            context.sendMessage(timing(out));
            RegionSwap.Stats back = new RegionSwap.Stats();
            refusal = RegionSwap.swap(store, Spatial.rules(), world, plot, false, x, y, z, s, s, s, x, z, back);
            if (refusal != null) {
                // der Inhalt liegt in plot-0.dat und bleibt dort, bis ein weiterer Versuch gelingt
                context.sendMessage(SpatialTransfers.refusalMessage(refusal));
                return;
            }
            context.sendMessage(timing(back));
            try {
                Files.deleteIfExists(store.plotFile(0));
            } catch (IOException e) {
                store.warn("AE2: could not delete the benchmark plot file", e);
            }
        }
    }

    private static final class Rules extends AbstractWorldCommand {
        Rules() {
            super("rules", "ae2spatial.cmd.rules.desc");
            requirePermission("ae2.admin");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            MoveRules rules = Spatial.rules();
            int total = 0, immovable = 0;
            StringBuilder names = new StringBuilder();
            int next = BlockType.getAssetMap().getNextIndex();
            for (int id = 1; id < next; id++) {
                BlockType type = BlockType.getAssetMap().getAsset(id);
                if (type == null) {
                    continue;
                }
                total++;
                if (rules.immovable(id)) {
                    immovable++;
                    if (names.length() < 1500) {
                        names.append(names.isEmpty() ? "" : ", ").append(type.getId());
                    }
                }
            }
            context.sendMessage(tr("rules").param("total", total).param("immovable", immovable)
                    .param("components", String.join(", ", rules.blacklistedComponents()))
                    .param("names", names.toString()));
        }
    }
    /** Alles über eine Stelle in einer Zeile. */
    private static final class Probe extends Positioned {
        Probe() {
            super("probe");
        }

        @Override
        void run(CommandContext context, World world, WorldNetworks nets, int x, int y, int z) {
            context.sendMessage(tr("probe").param("text", describe(world, x, y, z)));
        }

        static String describe(World world, int x, int y, int z) {
            ChunkStore chunkStore = world.getChunkStore();
            Ref<ChunkStore> section = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
            if (section == null || !section.isValid()) {
                return "unloaded";
            }
            Store<ChunkStore> st = chunkStore.getStore();
            BlockSection blocks = st.getComponent(section, BlockSection.getComponentType());
            if (blocks == null) {
                return "unloaded";
            }
            StringBuilder sb = new StringBuilder();
            int id = blocks.get(x, y, z);
            BlockType type = BlockType.getAssetMap().getAsset(id);
            sb.append(id == 0 ? "Empty" : type == null ? "?" + id : type.getId());
            sb.append(" rot=").append(blocks.getRotationIndex(x, y, z));
            sb.append(" filler=").append(blocks.getFiller(x, y, z));
            BlockPhysics physics = st.getComponent(section, BlockPhysics.getComponentType());
            sb.append(" support=").append(physics == null ? 0 : physics.get(x, y, z));
            FluidSection fluids = st.getComponent(section, FluidSection.getComponentType());
            int fluidId = fluids == null ? 0 : fluids.getFluidId(x, y, z);
            if (fluidId != 0) {
                Fluid fluid = Fluid.getAssetMap().getAsset(fluidId);
                sb.append(" fluid=").append(fluid == null ? "?" : fluid.getId()).append(':')
                        .append(fluids.getFluidLevel(x, y, z));
            }
            BlockComponentSection components = st.getComponent(section, BlockComponentSection.getComponentType());
            Holder<ChunkStore> entity = components == null ? null : components.copyBlockHolder(ChunkUtil.indexBlock(x, y, z));
            if (entity != null) {
                try {
                    BsonDocument doc = ChunkStore.REGISTRY.serialize(entity);
                    BsonValue parts = doc.get("Components");
                    sb.append(" entity=").append(parts != null && parts.isDocument()
                            ? new java.util.TreeSet<>(parts.asDocument().keySet()) : doc.keySet());
                } catch (RuntimeException e) {
                    sb.append(" entity=?");
                }
                AE2BlockComponent ae2 = entity.getComponent(AE2BlockComponent.getComponentType());
                if (ae2 != null) {
                    sb.append(" ae2=").append(ae2.kind()).append(" energy=").append((long) ae2.energy())
                            .append(" priority=").append(ae2.priority()).append(" config=")
                            .append(new java.util.TreeMap<>(ae2.config())).append(" data=")
                            .append(ae2.data() == null ? "-" : ae2.data().length() + "#"
                                    + Integer.toHexString(ae2.data().hashCode()));
                }
                ItemContainerBlock container = entity.getComponent(ItemContainerBlock.getComponentType());
                if (container != null) {
                    ItemContainer c = container.getItemContainer();
                    sb.append(" items=[");
                    boolean first = true;
                    for (short slot = 0; slot < c.getCapacity(); slot++) {
                        ItemStack stack = c.getItemStack(slot);
                        if (ItemStack.isEmpty(stack)) {
                            continue;
                        }
                        sb.append(first ? "" : ", ").append(slot).append(':').append(stack.getItemId()).append('*')
                                .append(stack.getQuantity());
                        BsonDocument metadata = ItemKeys.metadataView(stack);
                        if (metadata != null && !metadata.isEmpty()) {
                            sb.append('#').append(Integer.toHexString(metadata.toJson().hashCode()));
                        }
                        first = false;
                    }
                    sb.append(']');
                }
            }
            return sb.toString();
        }
    }

    private static final class SetBlock extends Positioned {
        private final RequiredArg<String> block;
        private final RequiredArg<Integer> rotation;

        SetBlock() {
            super("setblock");
            block = withRequiredArg("block", "ae2spatial.arg.block", ArgTypes.STRING);
            rotation = withRequiredArg("rotation", "ae2spatial.arg.rotation", ArgTypes.INTEGER);
        }

        @Override
        void run(CommandContext context, World world, WorldNetworks nets, int x, int y, int z) {
            boolean done = BlockAccess.setBlock(world, x, y, z, context.get(block), context.get(rotation), BlockAccess.QUIET);
            context.sendMessage(tr(done ? "set.done" : "set.failed").param("text", Probe.describe(world, x, y, z)));
        }
    }

    private static final class SetFluid extends Positioned {
        private final RequiredArg<String> fluid;
        private final RequiredArg<Integer> level;

        SetFluid() {
            super("setfluid");
            fluid = withRequiredArg("fluid", "ae2spatial.arg.fluid", ArgTypes.STRING);
            level = withRequiredArg("level", "ae2spatial.arg.level", ArgTypes.INTEGER);
        }

        @Override
        void run(CommandContext context, World world, WorldNetworks nets, int x, int y, int z) {
            ChunkStore chunkStore = world.getChunkStore();
            Ref<ChunkStore> section = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
            int id = Fluid.getAssetMap().getIndexOrDefault(context.get(fluid), Integer.MIN_VALUE);
            boolean done = false;
            if (section != null && section.isValid() && id != Integer.MIN_VALUE) {
                Store<ChunkStore> st = chunkStore.getStore();
                FluidSection fluids = st.ensureAndGetComponent(section, FluidSection.getComponentType());
                fluids.setFluid(x, y, z, id, (byte) (int) context.get(level));
                // wie /fluid set der Engine: speichern und die Flüssigkeit anstoßen
                ChunkSection chunkSection = st.getComponent(section, ChunkSection.getComponentType());
                BlockSection blocks = st.getComponent(section, BlockSection.getComponentType());
                if (chunkSection != null) {
                    chunkSection.markNeedsSaving();
                }
                if (blocks != null) {
                    blocks.setTicking(x, y, z, true);
                }
                done = true;
            }
            context.sendMessage(tr(done ? "set.done" : "set.failed").param("text", Probe.describe(world, x, y, z)));
        }
    }

    private static final class MoveCell extends Positioned {
        private final RequiredArg<RelativeIntPosition> target;

        MoveCell() {
            super("movecell");
            target = withRequiredArg("x2 y2 z2", "ae2spatial.arg.position", ArgTypes.RELATIVE_BLOCK_POSITION);
        }

        @Override
        void run(CommandContext context, World world, WorldNetworks nets, int x, int y, int z) {
            Vector3i t = context.get(target).getBlockPosition(context, world.getEntityStore().getStore());
            ItemContainer from = BlockAccess.container(world, x, y, z);
            ItemContainer to = BlockAccess.container(world, t.x, t.y, t.z);
            if (from == null || to == null || from.getCapacity() <= IOPortDevice.SLOT_OUTPUT
                    || to.getCapacity() <= IOPortDevice.SLOT_OUTPUT) {
                context.sendMessage(tr("movecell.failed"));
                return;
            }
            ItemStack cell = from.getItemStack(IOPortDevice.SLOT_OUTPUT);
            if (!SpatialCells.isCell(cell) || !ItemStack.isEmpty(to.getItemStack(IOPortDevice.SLOT_INPUT))) {
                context.sendMessage(tr("movecell.failed"));
                return;
            }
            from.removeItemStackFromSlot(IOPortDevice.SLOT_OUTPUT, false);
            if (!to.setItemStackForSlot(IOPortDevice.SLOT_INPUT, cell, false).succeeded()) {
                from.setItemStackForSlot(IOPortDevice.SLOT_OUTPUT, cell, false);
                context.sendMessage(tr("movecell.failed"));
                return;
            }
            context.sendMessage(tr("movecell.done"));
        }
    }
    private static final class Signal extends Positioned {
        private final RequiredArg<Integer> state;

        Signal() {
            super("signal");
            state = withRequiredArg("state", "ae2spatial.arg.state", ArgTypes.INTEGER);
        }

        @Override
        void run(CommandContext context, World world, WorldNetworks nets, int x, int y, int z) {
            boolean on = context.get(state) != 0;
            Signals.of(world).setEmitting(x, y, z, on);
            context.sendMessage(tr(on ? "signal.on" : "signal.off"));
        }
    }
    /** Prüfsumme eines Würfels: Blöcke, Rotation, Füllteile, Stützwerte, Flüssigkeiten, Block-Entities. */
    private static final class Hash extends Positioned {
        private final RequiredArg<Integer> size;

        Hash() {
            super("hash");
            size = withRequiredArg("size", "ae2spatial.arg.size", ArgTypes.INTEGER);
        }

        @Override
        void run(CommandContext context, World world, WorldNetworks nets, int x0, int y0, int z0) {
            int n = Math.max(1, Math.min(SpatialStoragePlot.MAX_SIZE, context.get(size)));
            ChunkStore chunkStore = world.getChunkStore();
            Store<ChunkStore> st = chunkStore.getStore();
            long hash = 0xcbf29ce484222325L;
            int blocks = 0, fluidCells = 0, entities = 0, unloaded = 0;
            for (int x = x0; x < x0 + n; x++) {
                for (int z = z0; z < z0 + n; z++) {
                    Ref<ChunkStore> section = null;
                    BlockSection bs = null;
                    FluidSection fs = null;
                    BlockPhysics ph = null;
                    BlockComponentSection cs = null;
                    int sectionY = Integer.MIN_VALUE;
                    for (int y = y0; y < y0 + n; y++) {
                        if (ChunkUtil.chunkCoordinate(y) != sectionY) {
                            sectionY = ChunkUtil.chunkCoordinate(y);
                            section = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
                            boolean ok = section != null && section.isValid();
                            bs = ok ? st.getComponent(section, BlockSection.getComponentType()) : null;
                            fs = ok ? st.getComponent(section, FluidSection.getComponentType()) : null;
                            ph = ok ? st.getComponent(section, BlockPhysics.getComponentType()) : null;
                            cs = ok ? st.getComponent(section, BlockComponentSection.getComponentType()) : null;
                        }
                        if (bs == null) {
                            unloaded++;
                            continue;
                        }
                        int id = bs.get(x, y, z);
                        long v = id;
                        if (id != 0) {
                            blocks++;
                            v = v * 31 + bs.getRotationIndex(x, y, z);
                            v = v * 31 + bs.getFiller(x, y, z);
                            v = v * 31 + (ph == null ? 0 : ph.get(x, y, z));
                            if (cs != null && cs.hasBlockComponents(ChunkUtil.indexBlock(x, y, z))) {
                                Holder<ChunkStore> entity = cs.copyBlockHolder(ChunkUtil.indexBlock(x, y, z));
                                if (entity != null && entity.hasSerializableComponents(ChunkStore.REGISTRY.getData())) {
                                    entities++;
                                    v = v * 31 + ChunkStore.REGISTRY.serialize(entity).toJson().hashCode();
                                }
                            }
                        }
                        int fluid = fs == null ? 0 : fs.getFluidId(x, y, z);
                        if (fluid != 0) {
                            fluidCells++;
                            v = v * 31 + fluid * 16L + fs.getFluidLevel(x, y, z);
                        }
                        hash = (hash ^ v) * 0x100000001b3L;
                    }
                }
            }
            context.sendMessage(tr("hash").param("hash", Long.toHexString(hash)).param("blocks", blocks)
                    .param("fluids", fluidCells).param("entities", entities).param("unloaded", unloaded));
        }
    }
}
