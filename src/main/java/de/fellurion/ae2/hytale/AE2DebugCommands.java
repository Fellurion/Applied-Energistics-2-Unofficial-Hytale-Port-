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
            this.pos = withRequiredArg("x y z", "Position", ArgTypes.RELATIVE_BLOCK_POSITION);
            requirePermission("ae2.admin");
        }

        @Override
        protected final void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i p = ctx.get(pos).getBlockPosition(ctx, store);
            ctx.sendMessage(Message.raw("[AE2] " + run(ctx, world, WorldNetworks.of(world), p.x, p.y, p.z)));
        }

        abstract String run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z);

        static Grid grid(WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node n = nets.node(x, y, z);
            return n == null || n.gridNode() == null ? null : n.gridNode().getGrid();
        }
    }

    static final class SetSlot extends PosCommand {
        private final RequiredArg<Integer> slot;
        private final RequiredArg<String> item;

        SetSlot() {
            super("setslot", "Item in einen Block-Slot legen");
            slot = withRequiredArg("slot", "Slot", ArgTypes.INTEGER);
            item = withRequiredArg("itemId", "Item", ArgTypes.STRING);
        }

        @Override
        String run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(world, x, y, z);
            String id = ctx.get(item);
            if (c == null || Item.getAssetMap().getAsset(id) == null) {
                return "Kein Container oder unbekanntes Item";
            }
            c.setItemStackForSlot((short) (int) ctx.get(slot), new ItemStack(id, 1));
            return id + " -> Slot " + ctx.get(slot);
        }
    }

    static final class Pattern extends PosCommand {
        private final RequiredArg<Integer> slot;
        private final RequiredArg<String> item;

        Pattern() {
            super("pattern", "Rezept als Pattern kodieren und in einen Slot legen");
            slot = withRequiredArg("slot", "Slot", ArgTypes.INTEGER);
            item = withRequiredArg("itemId", "Ergebnis-Item", ArgTypes.STRING);
        }

        @Override
        String run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(world, x, y, z);
            List<HytaleRecipes.Option> opts = HytaleRecipes.recipesFor(ctx.get(item));
            if (c == null || opts.isEmpty()) {
                return "Kein Container oder kein Rezept für " + ctx.get(item);
            }
            AEItemKey key = HytaleRecipes.encode(opts.getFirst());
            if (key == null) {
                return "Rezept nicht kodierbar: " + opts.getFirst().id();
            }
            c.setItemStackForSlot((short) (int) ctx.get(slot), ItemKeys.toStack(key, 1));
            return "Pattern (" + (opts.getFirst().processing() ? "Processing" : "Crafting") + ", Rezept "
                    + opts.getFirst().id() + ") -> Slot " + ctx.get(slot);
        }
    }

    static final class Insert extends PosCommand {
        private final RequiredArg<String> item;
        private final RequiredArg<Integer> amount;

        Insert() {
            super("insert", "Items ins ME-Netz einlagern (kreativ)");
            item = withRequiredArg("itemId", "Item", ArgTypes.STRING);
            amount = withRequiredArg("amount", "Menge", ArgTypes.INTEGER);
        }

        @Override
        String run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(nets, x, y, z);
            if (grid == null || Item.getAssetMap().getAsset(ctx.get(item)) == null) {
                return "Kein Netz oder unbekanntes Item";
            }
            long in = grid.getStorageService().getInventory().insert(AEItemKey.of(ctx.get(item)), ctx.get(amount),
                    Actionable.MODULATE, IActionSource.empty());
            return in + "× " + ctx.get(item) + " eingelagert";
        }
    }

    static final class Craft extends PosCommand {
        private final RequiredArg<String> item;
        private final RequiredArg<Integer> amount;

        Craft() {
            super("craft", "Crafting-Auftrag berechnen und starten");
            item = withRequiredArg("itemId", "Item", ArgTypes.STRING);
            amount = withRequiredArg("amount", "Menge", ArgTypes.INTEGER);
        }

        @Override
        String run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(nets, x, y, z);
            if (grid == null) {
                return "Kein Netz";
            }
            CraftingPlan plan = grid.getCraftingService().calculate(IActionSource.empty(), AEItemKey.of(ctx.get(item)),
                    ctx.get(amount), CalculationStrategy.REPORT_MISSING_ITEMS);
            if (plan == null) {
                return "Nicht herstellbar (kein Pattern)";
            }
            if (plan.simulation()) {
                return "Fehlend: " + plan.missingItems();
            }
            CraftingSubmitResult r = grid.getCraftingService().submitJob(plan, null, null, true, IActionSource.empty());
            return r.successful() ? "Gestartet (" + plan.bytes() + " Bytes)" : "Fehler: " + r.errorCode();
        }
    }

    /** Hält den Chunk an der Position geladen (wie ein Spieler daneben); Grundlage für Tests ohne Client. */
    static final class KeepLoaded extends PosCommand {
        KeepLoaded() {
            super("keeploaded", "Chunk an der Position geladen halten");
        }

        @Override
        String run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            long idx = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(x, z);
            world.getChunkStore().getChunkReferenceAsync(idx).thenAccept(ref -> world.execute(() -> {
                var wc = world.getChunkStore().getChunkComponent(idx,
                        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk.getComponentType());
                if (wc != null) {
                    wc.addKeepLoaded();
                }
            }));
            return "Chunk bei " + x + " " + z + " bleibt geladen";
        }
    }

    static final class Count extends PosCommand {
        private final RequiredArg<String> item;

        Count() {
            super("count", "Menge eines Items im ME-Netz");
            item = withRequiredArg("itemId", "Item", ArgTypes.STRING);
        }

        @Override
        String run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(nets, x, y, z);
            if (grid == null) {
                return "Kein Netz";
            }
            return ctx.get(item) + ": " + grid.getStorageService().getCachedInventory().get(AEItemKey.of(ctx.get(item)));
        }
    }

    static final class Cpus extends PosCommand {
        Cpus() {
            super("cpus", "Crafting-CPUs anzeigen");
        }

        @Override
        String run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(nets, x, y, z);
            if (grid == null) {
                return "Kein Netz";
            }
            StringBuilder sb = new StringBuilder(grid.getCraftingService().getCpus().size() + " CPUs");
            int i = 1;
            for (CraftingCpuCluster cpu : grid.getCraftingService().getCpus()) {
                var job = cpu.getJobStatus();
                sb.append(String.format(Locale.ROOT, " | CPU%d %d Bytes %s", i++, cpu.getAvailableStorage(),
                        job == null ? "frei" : job.crafting() + " " + job.progress() + "/" + job.totalItems()));
            }
            sb.append(" | Craftbar: ").append(grid.getCraftingService().getCraftables(k -> true).size());
            return sb.toString();
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
            super("stress", "Lasttest: Items rein/raus je Tick");
            types = withRequiredArg("types", "Anzahl Item-Typen", ArgTypes.INTEGER);
            ops = withRequiredArg("ops", "Operationen je Tick", ArgTypes.INTEGER);
            seconds = withRequiredArg("seconds", "Dauer in Sekunden", ArgTypes.INTEGER);
        }

        @Override
        String run(CommandContext ctx, World world, WorldNetworks nets, int x, int y, int z) {
            Grid grid = grid(nets, x, y, z);
            if (grid == null) {
                return "Kein Netz";
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
                return "Kein Platz im Netz (Zellen?)";
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
            return keys.size() + " Typen eingelagert, Last läuft " + ctx.get(seconds) + " s mit " + perTick + " Ops/Tick";
        }
    }
}
