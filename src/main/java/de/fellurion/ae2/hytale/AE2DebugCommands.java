/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeIntPosition;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.crafting.CalculationStrategy;
import de.fellurion.ae2.core.crafting.CraftingCpuCluster;
import de.fellurion.ae2.core.crafting.CraftingPlan;
import de.fellurion.ae2.core.crafting.CraftingSubmitResult;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.hytale.crafting.HytaleRecipes;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;

/**
 * Admin-/Testbefehle (Berechtigung {@code ae2.admin}): erlauben, Netze komplett über die Konsole aufzubauen
 * und Autocrafting zu prüfen.
 * <ul>
 *   <li>{@code /ae2 setslot x y z slot itemId} – Item in einen Block-Slot legen (Zelle ins Drive, Pattern in den Provider)</li>
 *   <li>{@code /ae2 pattern x y z slot itemId} – erstes Rezept für itemId kodieren und in den Slot legen</li>
 *   <li>{@code /ae2 insert x y z itemId amount} – Items ins Netz des Blocks einlagern (kreativ)</li>
 *   <li>{@code /ae2 craft x y z itemId amount} – Crafting-Auftrag berechnen und starten</li>
 *   <li>{@code /ae2 cpus x y z} – Crafting-CPUs und Aufträge anzeigen</li>
 * </ul>
 */
final class AE2DebugCommands {

    private AE2DebugCommands() {
    }

    private abstract static class PosCommand extends AbstractWorldCommand {
        final RequiredArg<RelativeIntPosition> pos;

        PosCommand(String name, String desc) {
            super(name, desc);
            this.pos = withRequiredArg("x y z", "ae2cmd.arg.position", ArgTypes.RELATIVE_BLOCK_POSITION);
            requirePermission("ae2.admin");
        }

        @Override
        protected final void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i p = ctx.get(pos).getBlockPosition(ctx, store);
            ctx.sendMessage(run(ctx, world, WorldNetworks.of(world), p.x, p.y, p.z));
        }

        /** Ergebnis als übersetzte Meldung ({@code ae2cmd.*}). */
        abstract Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z);

        static Grid grid(WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node n = nets.node(x, y, z);
            return n == null || n.gridNode() == null ? null : n.gridNode().getGrid();
        }
    }

    static final class SetSlot extends PosCommand {
        private final RequiredArg<Integer> slot;
        private final RequiredArg<String> item;

        SetSlot() {
            super("setslot", "ae2cmd.setslot.desc");
            slot = withRequiredArg("slot", "ae2cmd.arg.slot", ArgTypes.INTEGER);
            item = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(world, x, y, z);
            String id = ctx.get(item);
            if (c == null || Item.getAssetMap().getAsset(id) == null) {
                return Message.translation("ae2cmd.noContainerOrItem");
            }
            c.setItemStackForSlot((short) (int) ctx.get(slot), new ItemStack(id, 1));
            return Message.translation("ae2cmd.setslot.done").param("item", id).param("slot", ctx.get(slot));
        }
    }

    static final class Pattern extends PosCommand {
        private final RequiredArg<Integer> slot;
        private final RequiredArg<String> item;

        Pattern() {
            super("pattern", "ae2cmd.pattern.desc");
            slot = withRequiredArg("slot", "ae2cmd.arg.slot", ArgTypes.INTEGER);
            item = withRequiredArg("itemId", "ae2cmd.arg.result", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(world, x, y, z);
            List<HytaleRecipes.Option> opts = HytaleRecipes.recipesFor(ctx.get(item));
            if (c == null || opts.isEmpty()) {
                return Message.translation("ae2cmd.pattern.noRecipe").param("item", ctx.get(item));
            }
            AEItemKey key = HytaleRecipes.encode(opts.getFirst());
            if (key == null) {
                return Message.translation("ae2cmd.pattern.notEncodable").param("recipe", opts.getFirst().id());
            }
            c.setItemStackForSlot((short) (int) ctx.get(slot), ItemKeys.toStack(key, 1));
            return Message.translation("ae2cmd.pattern.done").param("type", opts.getFirst().processing() ? "Processing" : "Crafting")
                    .param("recipe", opts.getFirst().id()).param("slot", ctx.get(slot));
        }
    }

    static final class Insert extends PosCommand {
        private final RequiredArg<String> item;
        private final RequiredArg<Integer> amount;

        Insert() {
            super("insert", "ae2cmd.insert.desc");
            item = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
            amount = withRequiredArg("amount", "ae2cmd.arg.amount", ArgTypes.INTEGER);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(nets, x, y, z);
            if (grid == null || Item.getAssetMap().getAsset(ctx.get(item)) == null) {
                return Message.translation("ae2cmd.noNetworkOrItem");
            }
            long in = grid.getStorageService().getInventory().insert(AEItemKey.of(ctx.get(item)), ctx.get(amount),
                    Actionable.MODULATE, IActionSource.empty());
            return Message.translation("ae2cmd.insert.done").param("amount", in).param("item", ctx.get(item));
        }
    }

    static final class Craft extends PosCommand {
        private final RequiredArg<String> item;
        private final RequiredArg<Integer> amount;

        Craft() {
            super("craft", "ae2cmd.craft.desc");
            item = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
            amount = withRequiredArg("amount", "ae2cmd.arg.amount", ArgTypes.INTEGER);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(nets, x, y, z);
            if (grid == null) {
                return Message.translation("ae2cmd.noNetwork");
            }
            CraftingPlan plan = grid.getCraftingService().calculate(IActionSource.empty(), AEItemKey.of(ctx.get(item)),
                    ctx.get(amount), CalculationStrategy.REPORT_MISSING_ITEMS);
            if (plan == null) {
                return Message.translation("ae2cmd.craft.notCraftable");
            }
            if (plan.simulation()) {
                return Message.translation("ae2cmd.craft.missing").param("items", String.valueOf(plan.missingItems()));
            }
            CraftingSubmitResult r = grid.getCraftingService().submitJob(plan, null, null, true, IActionSource.empty());
            return r.successful() ? Message.translation("ae2cmd.craft.started").param("bytes", plan.bytes())
                    : Message.translation("ae2cmd.craft.error").param("error", String.valueOf(r.errorCode()));
        }
    }

    /** Hält den Chunk an der Position geladen (wie ein Spieler daneben); Grundlage für Tests ohne Client. */
    static final class KeepLoaded extends PosCommand {
        KeepLoaded() {
            super("keeploaded", "ae2cmd.keeploaded.desc");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            long idx = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(x, z);
            world.getChunkStore().getChunkReferenceAsync(idx).thenAccept(ref -> world.execute(() -> {
                var wc = world.getChunkStore().getChunkComponent(idx,
                        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk.getComponentType());
                if (wc != null) {
                    wc.addKeepLoaded();
                }
            }));
            return Message.translation("ae2cmd.keeploaded.done").param("x", x).param("z", z);
        }
    }

    static final class Count extends PosCommand {
        private final RequiredArg<String> item;

        Count() {
            super("count", "ae2cmd.count.desc");
            item = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(nets, x, y, z);
            if (grid == null) {
                return Message.translation("ae2cmd.noNetwork");
            }
            return Message.translation("ae2cmd.count.done").param("item", ctx.get(item))
                    .param("amount", grid.getStorageService().getCachedInventory().get(AEItemKey.of(ctx.get(item))));
        }
    }

    static final class Cpus extends PosCommand {
        Cpus() {
            super("cpus", "ae2cmd.cpus.desc");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(nets, x, y, z);
            if (grid == null) {
                return Message.translation("ae2cmd.noNetwork");
            }
            Message out = Message.translation("ae2cmd.cpus.header").param("count", grid.getCraftingService().getCpus().size())
                    .param("craftable", grid.getCraftingService().getCraftables(k -> true).size());
            int i = 1;
            for (CraftingCpuCluster cpu : grid.getCraftingService().getCpus()) {
                var job = cpu.getJobStatus();
                Message jobText = job == null ? Message.translation("ae2cmd.cpus.idle")
                        : Message.translation("ae2cmd.cpus.job").param("item", String.valueOf(job.crafting()))
                        .param("progress", job.progress()).param("total", job.totalItems());
                out.insert(Message.raw("\n")).insert(Message.translation("ae2cmd.cpus.cpu").param("index", i++)
                        .param("bytes", cpu.getAvailableStorage()).param("job", jobText));
            }
            return out;
        }
    }

    /**
     * Lasttest: legt {@code types} verschiedene Items ins Netz und lagert danach {@code seconds} Sekunden lang je Tick
     * {@code ops}-mal ein Item aus und wieder ein (wie viele Import-/Export-Busse). Mit {@code /ae2 perf} messen.
     */
    static final class Stress extends PosCommand {
        private final RequiredArg<Integer> types;
        private final RequiredArg<Integer> ops;
        private final RequiredArg<Integer> seconds;

        Stress() {
            super("stress", "ae2cmd.stress.desc");
            types = withRequiredArg("types", "ae2cmd.arg.types", ArgTypes.INTEGER);
            ops = withRequiredArg("ops", "ae2cmd.arg.ops", ArgTypes.INTEGER);
            seconds = withRequiredArg("seconds", "ae2cmd.arg.seconds", ArgTypes.INTEGER);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(nets, x, y, z);
            if (grid == null) {
                return Message.translation("ae2cmd.noNetwork");
            }
            var inv = grid.getStorageService().getInventory();
            java.util.List<AEItemKey> keys = new java.util.ArrayList<>();
            for (Item it : Item.getAssetMap().getAssetMap().values()) {
                if (keys.size() >= ctx.get(types)) {
                    break;
                }
                String id = it.getId();
                if (id == null || id.startsWith("AE2_") || id.equals("Empty") || id.equals("Unknown")) {
                    continue;
                }
                AEItemKey k = AEItemKey.of(id);
                if (inv.insert(k, 1000, Actionable.MODULATE, IActionSource.empty()) > 0) {
                    keys.add(k);
                }
            }
            if (keys.isEmpty()) {
                return Message.translation("ae2cmd.stress.noSpace");
            }
            int perTick = ctx.get(ops);
            long[] left = {ctx.get(seconds) * 20L};
            java.util.Random rnd = new java.util.Random(42);
            nets.addTickTask(() -> {
                Grid g = grid(nets, x, y, z);
                if (g == null || --left[0] < 0) {
                    return false;
                }
                var st = g.getStorageService().getInventory();
                for (int i = 0; i < perTick; i++) {
                    AEItemKey k = keys.get(rnd.nextInt(keys.size()));
                    long got = st.extract(k, 1 + rnd.nextInt(16), Actionable.MODULATE, IActionSource.empty());
                    if (got > 0) {
                        st.insert(k, got, Actionable.MODULATE, IActionSource.empty());
                    }
                }
                return true;
            });
            return Message.translation("ae2cmd.stress.started").param("types", keys.size()).param("seconds", ctx.get(seconds))
                    .param("ops", perTick);
        }
    }
}
