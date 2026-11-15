/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.emitter;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeIntPosition;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.automation.IOBusLogic;
import de.fellurion.ae2.core.automation.LevelEmitterLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.devices.StorageMonitorLogic;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.hytale.AE2Command;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.BusDevice;
import de.fellurion.ae2.hytale.lang.AE2Language;
import de.fellurion.ae2.hytale.signal.Signals;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * Admin-/Testbefehle dieses Bereichs (Berechtigung {@code ae2.admin}); damit lässt sich alles ohne Spieler von der
 * Konsole aus prüfen:
 * <ul>
 *   <li>{@code /ae2 emitter x y z} – Zustand eines Level Emitters</li>
 *   <li>{@code /ae2 emitterset x y z item|threshold|mode|craft|fuzzy wert} – Emitter einstellen</li>
 *   <li>{@code /ae2 installcard x y z kartenItem} – Upgrade-Karte in ein Gerät mit Kartenfach einbauen</li>
 *   <li>{@code /ae2 busmode x y z ignore|high_signal|low_signal|signal_pulse} – Signalmodus eines Bus</li>
 *   <li>{@code /ae2 signal x y z} – sendet der Block, wird er angesteuert?</li>
 *   <li>{@code /ae2 light x y z} – Helligkeit an der Stelle (und der Light Detector dort)</li>
 *   <li>{@code /ae2 monitor x y z} – Zustand eines Monitors und Zahl der Anzeige-Entities</li>
 *   <li>{@code /ae2 monitorset x y z item|locked wert} – Monitor einstellen</li>
 *   <li>{@code /ae2 monitorinsert x y z item menge}, {@code /ae2 monitorextract x y z menge} – ein- und auslagern
 *   wie über einen Conversion Monitor (ohne Spielerinventar)</li>
 *   <li>{@code /ae2 monitordisplay on|off} – Anzeige der Monitore in der Welt ein-/ausschalten</li>
 * </ul>
 */
final class EmitterCommands {

    /** Für „kein Item“ bei {@code emitterset … item} und {@code monitorset … item}. */
    private static final String NONE = "none";

    private EmitterCommands() {
    }

    static void register() {
        AE2Command.registerSub(new EmitterInfo());
        AE2Command.registerSub(new EmitterSet());
        AE2Command.registerSub(new InstallCard());
        AE2Command.registerSub(new BusMode());
        AE2Command.registerSub(new Signal());
        AE2Command.registerSub(new Light());
        AE2Command.registerSub(new MonitorInfo());
        AE2Command.registerSub(new MonitorSet());
        AE2Command.registerSub(new MonitorInsert());
        AE2Command.registerSub(new MonitorExtract());
        AE2Command.registerSub(new MonitorDisplaySwitch());
    }

    private static Message msg(String key) {
        return Message.translation("ae2emitter.cmd." + key);
    }

    private static Message yesNo(boolean value) {
        return msg(value ? "yes" : "no");
    }

    private abstract static class PosCommand extends AbstractWorldCommand {
        final RequiredArg<RelativeIntPosition> pos;

        PosCommand(String name) {
            super(name, "ae2emitter.cmd." + name + ".desc");
            this.pos = withRequiredArg("x y z", "ae2cmd.arg.position", ArgTypes.RELATIVE_BLOCK_POSITION);
            requirePermission("ae2.admin");
        }

        @Override
        protected final void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i p = ctx.get(pos).getBlockPosition(ctx, store);
            WorldNetworks nets = WorldNetworks.of(world);
            ctx.sendMessage(run(ctx, world, nets, nets.node(p.x, p.y, p.z), p.x, p.y, p.z));
        }

        /** Ergebnis als übersetzte Meldung ({@code ae2emitter.cmd.*}); {@code node} ist null ohne AE2-Block. */
        abstract Message run(CommandContext ctx, World world, WorldNetworks nets, WorldNetworks.Node node, int x, int y,
                             int z);
    }

    /** Sprache für Texte, die in eine Meldung eingesetzt werden (Konsole: Englisch). */
    private static String lang(CommandContext ctx) {
        return ctx.isPlayer() ? AE2Language.of(ctx.sender().getUuid()) : AE2Language.ENGLISH;
    }

    // ------------------------------------------------------------------ Emitter

    private static Message describe(CommandContext ctx, WorldNetworks.Node node, LevelEmitterDevice device) {
        String lang = lang(ctx);
        LevelEmitterLogic storage = device.storage();
        return msg("emitter.state")
                .param("what", device.isEnergy() ? "AE" : device.watchedName(lang))
                .param("value", Long.toString(device.currentValue(node)))
                .param("threshold", Long.toString(device.logic().getReportingValue()))
                .param("mode", device.logic().getRedstoneMode().name())
                .param("cards", storage == null ? "-" : String.valueOf(storage.getUpgrades().toItemIds()))
                .param("active", yesNo(node.gridNode() != null && node.gridNode().isActive()))
                .param("emitting", yesNo(device.logic().isEmitting()));
    }

    private static final class EmitterInfo extends PosCommand {
        EmitterInfo() {
            super("emitter");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, WorldNetworks.Node node, int x, int y, int z) {
            if (node == null || !(node.device() instanceof LevelEmitterDevice device)) {
                return msg("noEmitter");
            }
            return describe(ctx, node, device);
        }
    }

    private static final class EmitterSet extends PosCommand {
        private final RequiredArg<String> key;
        private final RequiredArg<String> value;

        EmitterSet() {
            super("emitterset");
            key = withRequiredArg("item|threshold|mode|craft|fuzzy", "ae2emitter.cmd.arg.setting", ArgTypes.STRING);
            value = withRequiredArg("value", "ae2emitter.cmd.arg.value", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, WorldNetworks.Node node, int x, int y, int z) {
            if (node == null || !(node.device() instanceof LevelEmitterDevice device)) {
                return msg("noEmitter");
            }
            String v = ctx.get(value);
            LevelEmitterLogic storage = device.storage();
            try {
                switch (ctx.get(key).toLowerCase(Locale.ROOT)) {
                    case "threshold" -> device.logic().setReportingValue(Long.parseLong(v));
                    case "mode" -> device.logic().setRedstoneMode(v.toLowerCase(Locale.ROOT).startsWith("low")
                            ? RedstoneMode.LOW_SIGNAL : RedstoneMode.HIGH_SIGNAL);
                    case "item" -> {
                        if (storage == null) {
                            return msg("badValue");
                        }
                        if (NONE.equalsIgnoreCase(v)) {
                            storage.getConfig().setKey(0, null);
                        } else if (Item.getAssetMap().getAsset(v) == null) {
                            return msg("unknownItem").param("item", v);
                        } else {
                            storage.getConfig().setKey(0, AEItemKey.of(v));
                        }
                    }
                    case "craft" -> {
                        if (storage == null) {
                            return msg("badValue");
                        }
                        storage.setCraftViaRedstone(isYes(v));
                    }
                    case "fuzzy" -> {
                        if (storage == null) {
                            return msg("badValue");
                        }
                        storage.setFuzzyMode(FuzzyMode.valueOf(v.toUpperCase(Locale.ROOT)));
                    }
                    default -> {
                        return msg("badValue");
                    }
                }
            } catch (IllegalArgumentException e) {
                return msg("badValue");
            }
            device.configChanged(node);
            return describe(ctx, node, device);
        }
    }

    private static boolean isYes(String v) {
        return "yes".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }

    // ------------------------------------------------------------------ Karten und Busse

    private static final class InstallCard extends PosCommand {
        private final RequiredArg<String> card;

        InstallCard() {
            super("installcard");
            card = withRequiredArg("cardItemId", "ae2emitter.cmd.arg.card", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, WorldNetworks.Node node, int x, int y, int z) {
            UpgradeInventory upgrades = node != null && node.device() instanceof ConfigHost host
                    ? host.upgradeCards() : null;
            if (upgrades == null) {
                return msg("noCardSlots");
            }
            UpgradeCard c = UpgradeCard.byItemId(ctx.get(card));
            if (c == null || !upgrades.insert(c)) {
                return msg("cardRejected").param("card", ctx.get(card));
            }
            ((ConfigHost) node.device()).configChanged(node);
            return msg("cardInstalled").param("card", c.itemId).param("cards", String.valueOf(upgrades.toItemIds()));
        }
    }

    private static final class BusMode extends PosCommand {
        private final RequiredArg<String> mode;

        BusMode() {
            super("busmode");
            mode = withRequiredArg("mode", "ae2emitter.cmd.arg.mode", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, WorldNetworks.Node node, int x, int y, int z) {
            IOBusLogic bus = node != null && node.device() instanceof BusDevice device ? device.ioBus() : null;
            if (bus == null) {
                return msg("noBus");
            }
            try {
                bus.setRedstoneMode(RedstoneMode.valueOf(ctx.get(mode).toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return msg("badValue");
            }
            return msg("busmode.done").param("mode", bus.getRedstoneMode().name())
                    .param("card", yesNo(bus.isUpgradedWith(UpgradeCard.REDSTONE)));
        }
    }

    // ------------------------------------------------------------------ Signal und Licht

    private static final class Signal extends PosCommand {
        Signal() {
            super("signal");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, WorldNetworks.Node node, int x, int y, int z) {
            Signals signals = Signals.of(world);
            return msg("signal.state").param("x", x).param("y", y).param("z", z)
                    .param("emitting", yesNo(signals.isEmitting(x, y, z)))
                    .param("powered", yesNo(signals.isPowered(x, y, z)));
        }
    }

    private static final class Light extends PosCommand {
        Light() {
            super("light");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, WorldNetworks.Node node, int x, int y, int z) {
            int light = LightDetectorDevice.lightAt(world, x, y, z);
            if (node != null && node.device() instanceof LightDetectorDevice device) {
                device.updateLight(nets, node);
                return msg("light.detector").param("light", light).param("measured", device.light())
                        .param("emitting", yesNo(device.isEmitting()));
            }
            return msg("light.state").param("x", x).param("y", y).param("z", z).param("light", light);
        }
    }

    // ------------------------------------------------------------------ Monitore

    private static Message describe(World world, WorldNetworks nets, MonitorDevice device) {
        StorageMonitorLogic logic = device.logic();
        AEItemKey key = device.item();
        int monitorsWithItem = 0;
        for (WorldNetworks.Node n : nets.nodes()) {
            if (n.device() instanceof MonitorDevice m && m.displayAlive()) {
                monitorsWithItem++;
            }
        }
        return msg("monitor.state")
                .param("item", key == null ? NONE : key.itemId())
                .param("amount", Long.toString(logic.getAmount()))
                .param("locked", yesNo(logic.isLocked()))
                .param("active", yesNo(logic.isActive()))
                .param("display", yesNo(device.displayAlive()))
                .param("at", device.displayPosition())
                .param("entities", MonitorDisplay.count(world))
                .param("expected", monitorsWithItem);
    }

    private static final class MonitorInfo extends PosCommand {
        MonitorInfo() {
            super("monitor");
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, WorldNetworks.Node node, int x, int y, int z) {
            if (node == null || !(node.device() instanceof MonitorDevice device)) {
                // ohne Monitor an der Stelle: nur die Zählung der Anzeigen dieser Welt
                int alive = 0;
                for (WorldNetworks.Node n : nets.nodes()) {
                    if (n.device() instanceof MonitorDevice m && m.displayAlive()) {
                        alive++;
                    }
                }
                return msg("monitor.count").param("entities", MonitorDisplay.count(world)).param("expected", alive);
            }
            return describe(world, nets, device);
        }
    }

    private static final class MonitorSet extends PosCommand {
        private final RequiredArg<String> key;
        private final RequiredArg<String> value;

        MonitorSet() {
            super("monitorset");
            key = withRequiredArg("item|locked", "ae2emitter.cmd.arg.setting", ArgTypes.STRING);
            value = withRequiredArg("value", "ae2emitter.cmd.arg.value", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, WorldNetworks.Node node, int x, int y, int z) {
            if (node == null || !(node.device() instanceof MonitorDevice device)) {
                return msg("noMonitor");
            }
            String v = ctx.get(value);
            switch (ctx.get(key).toLowerCase(Locale.ROOT)) {
                case "locked" -> device.logic().setLocked(isYes(v));
                case "item" -> {
                    if (NONE.equalsIgnoreCase(v)) {
                        device.logic().setConfiguredItem(null);
                    } else if (Item.getAssetMap().getAsset(v) == null) {
                        return msg("unknownItem").param("item", v);
                    } else {
                        device.logic().setConfiguredItem(AEItemKey.of(v));
                    }
                }
                default -> {
                    return msg("badValue");
                }
            }
            return describe(world, nets, device);
        }
    }

    /** Lagert über den Monitor ein – derselbe Weg wie {@code MonitorUse}, nur ohne Spielerinventar. */
    private static final class MonitorInsert extends PosCommand {
        private final RequiredArg<String> item;
        private final RequiredArg<Integer> amount;

        MonitorInsert() {
            super("monitorinsert");
            item = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
            amount = withRequiredArg("amount", "ae2cmd.arg.amount", ArgTypes.INTEGER);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, WorldNetworks.Node node, int x, int y, int z) {
            if (node == null || !(node.device() instanceof MonitorDevice device) || !device.conversion()) {
                return msg("noConversionMonitor");
            }
            if (Item.getAssetMap().getAsset(ctx.get(item)) == null) {
                return msg("unknownItem").param("item", ctx.get(item));
            }
            long moved = device.logic().insert(AEItemKey.of(ctx.get(item)), ctx.get(amount),
                    IActionSource.ofPlayer("console", device.logic()));
            return msg("monitor.moved").param("amount", moved).param("item", ctx.get(item));
        }
    }

    /** Lagert über den Monitor aus (das Entnommene verfällt – nur zum Prüfen von Menge und Energie). */
    private static final class MonitorExtract extends PosCommand {
        private final RequiredArg<Integer> amount;

        MonitorExtract() {
            super("monitorextract");
            amount = withRequiredArg("amount", "ae2cmd.arg.amount", ArgTypes.INTEGER);
        }

        @Override
        Message run(CommandContext ctx, World world, WorldNetworks nets, WorldNetworks.Node node, int x, int y, int z) {
            if (node == null || !(node.device() instanceof MonitorDevice device) || !device.conversion()) {
                return msg("noConversionMonitor");
            }
            AEItemKey key = device.item();
            long moved = device.logic().extract(ctx.get(amount), IActionSource.ofPlayer("console", device.logic()));
            return msg("monitor.moved").param("amount", moved).param("item", key == null ? NONE : key.itemId());
        }
    }

    private static final class MonitorDisplaySwitch extends AbstractWorldCommand {
        private final RequiredArg<String> state;

        MonitorDisplaySwitch() {
            super("monitordisplay", "ae2emitter.cmd.monitordisplay.desc");
            state = withRequiredArg("on|off", "ae2emitter.cmd.arg.onOff", ArgTypes.STRING);
            requirePermission("ae2.admin");
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            boolean on = isYes(ctx.get(state));
            EmitterSettings.setMonitorDisplay(on);
            ctx.sendMessage(msg(on ? "monitordisplay.on" : "monitordisplay.off"));
        }
    }
}
