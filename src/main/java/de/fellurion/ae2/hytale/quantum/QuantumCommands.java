/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.quantum;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeIntPosition;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.quantum.QuantumBridgeLogic;
import de.fellurion.ae2.core.quantum.QuantumCluster;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * {@code /ae2 quantum …}: Admin-/Testbefehle (Berechtigung {@code ae2.admin}) für die Quantum Network Bridge – damit
 * lässt sie sich auch ohne Spieler und ohne die Herstellung der Singularities prüfen.
 * <ul>
 *   <li>{@code singularity x y z <Frequenz>} – legt ein Quantum Entangled Singularity mit dieser Frequenz in die
 *       Link Chamber (ersetzt den Inhalt des Slots); Frequenz 0 leert den Slot</li>
 *   <li>{@code info x y z} – Zustand der Brücke, zu der der Block gehört: gebildet oder nicht (und warum nicht),
 *       Frequenz, Verbindung, Gegenstelle, Größe und Strom des Netzes</li>
 * </ul>
 */
final class QuantumCommands extends AbstractCommandCollection {

    QuantumCommands() {
        super("quantum", "ae2quantum.cmd.desc");
        requirePermission("ae2.admin");
        addSubCommand(new Singularity());
        addSubCommand(new Info());
    }

    private abstract static class Sub extends AbstractWorldCommand {
        final RequiredArg<RelativeIntPosition> pos;

        Sub(String name, String description) {
            super(name, description);
            requirePermission("ae2.admin");
            pos = withRequiredArg("x y z", "ae2cmd.arg.position", ArgTypes.RELATIVE_BLOCK_POSITION);
        }

        @Override
        protected final void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i p = ctx.get(pos).getBlockPosition(ctx, store);
            ctx.sendMessage(run(ctx, WorldNetworks.of(world), p.x, p.y, p.z));
        }

        abstract Message run(CommandContext ctx, WorldNetworks nets, int x, int y, int z);
    }

    private static Message at(String key, int x, int y, int z) {
        return Message.translation(key).param("x", x).param("y", y).param("z", z);
    }

    private static String position(long pos) {
        return BlockPos.getX(pos) + " " + BlockPos.getY(pos) + " " + BlockPos.getZ(pos);
    }

    private static final class Singularity extends Sub {
        private final RequiredArg<String> frequency;

        Singularity() {
            super("singularity", "ae2quantum.cmd.singularity.desc");
            frequency = withRequiredArg("frequency", "ae2quantum.cmd.arg.frequency", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            ItemContainer container = node == null || !Quantum.KIND_LINK.equals(node.kind()) ? null
                    : BlockAccess.container(nets.world(), x, y, z);
            if (container == null || container.getCapacity() == 0) {
                return at("ae2quantum.cmd.noLink", x, y, z);
            }
            long f;
            try {
                f = Long.parseLong(ctx.get(frequency).trim());
            } catch (NumberFormatException e) {
                return Message.translation("ae2quantum.cmd.badFrequency").param("value", ctx.get(frequency));
            }
            if (f == 0) {
                ItemStack inside = container.getItemStack((short) 0);
                if (!ItemStack.isEmpty(inside)) {
                    container.removeItemStackFromSlot((short) 0);
                }
                return at("ae2quantum.cmd.singularity.cleared", x, y, z);
            }
            boolean ok = container.setItemStackForSlot((short) 0, Quantum.singularity(f)).succeeded();
            return at(ok ? "ae2quantum.cmd.singularity.set" : "ae2quantum.cmd.singularity.failed", x, y, z)
                    .param("frequency", f);
        }
    }

    private static final class Info extends Sub {
        Info() {
            super("info", "ae2quantum.cmd.info.desc");
        }

        @Override
        Message run(CommandContext ctx, WorldNetworks nets, int x, int y, int z) {
            WorldNetworks.Node node = nets.node(x, y, z);
            if (node == null || !(node.device() instanceof QuantumDevice device)) {
                return at("ae2quantum.cmd.noBridge", x, y, z);
            }
            QuantumBridgeLogic logic = device.logic();
            QuantumCluster cluster = logic.getCluster();
            GridNode g = node.gridNode();
            int blocks = g == null || g.getGrid() == null ? 0 : g.getGrid().size();
            String block = logic.isCenter() ? "link" : logic.isCorner() ? "corner" : "ring";
            if (cluster == null || cluster.isDestroyed() || cluster.getCenter() == null) {
                return Message.translation("ae2quantum.cmd.info.unformed")
                        .param("block", block)
                        .param("reason", logic.checkFormation().name().toLowerCase(Locale.ROOT))
                        .param("frequency", logic.getQEFrequency())
                        .param("blocks", blocks);
            }
            QuantumCluster other = cluster.getOtherCluster();
            GridNode otherNode = other == null || other.getCenter() == null ? null : other.getCenter().getNode();
            GridNode centerNode = cluster.getCenter().getNode();
            return Message.translation("ae2quantum.cmd.info.formed")
                    .param("block", block)
                    .param("center", centerNode == null ? "-" : position(centerNode.getPos()))
                    .param("frequency", cluster.getFrequency())
                    .param("side", cluster.getThisSide())
                    .param("state", cluster.getLinkState().name().toLowerCase(Locale.ROOT))
                    .param("other", otherNode == null ? "-" : position(otherNode.getPos()))
                    .param("blocks", blocks)
                    .param("power", g != null && g.isPowered() ? "powered" : "unpowered")
                    .param("sides", logic.getGridConnectableSides().size());
        }
    }
}
