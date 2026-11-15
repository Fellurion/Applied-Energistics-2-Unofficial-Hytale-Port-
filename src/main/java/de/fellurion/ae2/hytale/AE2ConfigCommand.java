/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeIntPosition;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.hytale.ui.ConfigEdits;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * {@code /ae2 config x y z <op> <wert>} (Berechtigung {@code ae2.admin}): zeigt, was ein Gerät der Konfigurationsseite
 * anbietet ({@link ConfigHost}), und bedient es ohne Client – Grundlage für Tests auf einem Server ohne Spieler.
 * <ul>
 *   <li>{@code show -} – nur anzeigen</li>
 *   <li>{@code setting <n>} – Einstellung Nr. n (ab 0) weiterschalten</li>
 *   <li>{@code card <itemId>} – Upgrade-Karte einbauen (kreativ, nichts wird verbraucht)</li>
 *   <li>{@code slot <itemId>} – Item in den ersten freien Geisterplatz legen</li>
 *   <li>{@code priority <n>}, {@code number <n>} – Priorität bzw. Zahlenwert setzen</li>
 * </ul>
 * Danach meldet das Gerät wie nach einem Klick auf der Seite {@link ConfigHost#configChanged}.
 */
final class AE2ConfigCommand extends AbstractWorldCommand {

    private final RequiredArg<RelativeIntPosition> pos;
    private final RequiredArg<String> op;
    private final RequiredArg<String> value;

    AE2ConfigCommand() {
        super("config", "ae2bus.cmd.desc");
        requirePermission("ae2.admin");
        pos = withRequiredArg("x y z", "ae2cmd.arg.position", ArgTypes.RELATIVE_BLOCK_POSITION);
        op = withRequiredArg("op", "ae2bus.cmd.op", ArgTypes.STRING);
        value = withRequiredArg("value", "ae2bus.cmd.value", ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
        Vector3i p = context.get(pos).getBlockPosition(context, store);
        WorldNetworks nets = WorldNetworks.of(world);
        WorldNetworks.Node node = nets.node(p.x, p.y, p.z);
        if (node == null || !(node.device() instanceof ConfigHost host)) {
            context.sendMessage(Message.translation("ae2bus.cmd.none").param("x", p.x).param("y", p.y).param("z", p.z));
            return;
        }
        ConfigHost.Context ctx = new ConfigHost.Context(nets, node, "en-US");
        String arg = context.get(value);
        boolean done = switch (context.get(op)) {
            case "show" -> true;
            case "setting" -> {
                List<ConfigHost.Setting> settings = host.settings(ctx);
                int i = (int) number(arg, -1);
                if (i >= 0 && i < settings.size()) {
                    settings.get(i).next().run();
                }
                yield i >= 0 && i < settings.size();
            }
            case "card" -> {
                UpgradeInventory cards = host.upgradeCards();
                UpgradeCard card = UpgradeCard.byItemId(arg);
                yield cards != null && card != null && cards.insert(card);
            }
            case "slot" -> {
                ConfigHost.GhostSlots slots = host.ghostSlots(ctx);
                yield slots != null && ConfigEdits.put(slots.inventory(), slots.usable(), -1, AEItemKey.of(arg), 1,
                        !slots.amounts()) == ConfigEdits.Result.DONE;
            }
            case "priority" -> {
                if (host.hasPriority()) {
                    host.setPriority(node, (int) number(arg, host.priority()));
                }
                yield host.hasPriority();
            }
            case "number" -> {
                ConfigHost.NumberField field = host.numberField(ctx);
                if (field != null) {
                    field.setter().accept(ConfigEdits.addNumber(number(arg, field.value()), 0, field.min(), field.max()));
                }
                yield field != null;
            }
            default -> false;
        };
        if (!done) {
            context.sendMessage(Message.translation("ae2bus.cmd.failed"));
            return;
        }
        host.configChanged(node);
        context.sendMessage(describe(host, ctx));
    }

    /** Eine Zeile mit allem, was die Seite für dieses Gerät zeigen würde. */
    private static Message describe(ConfigHost host, ConfigHost.Context ctx) {
        WorldNetworks.Node node = ctx.node();
        String info = host.info(ctx);
        if (info == null) {
            info = ctx.tr(host.online(node) ? "ae2bus.info.online" : "ae2bus.info.offline");
        }
        ConfigHost.GhostSlots slots = host.ghostSlots(ctx);
        UpgradeInventory cards = host.upgradeCards();
        StringBuilder cardList = new StringBuilder();
        for (int i = 0; cards != null && i < cards.size(); i++) {
            cardList.append(i == 0 ? "" : ",").append(cards.get(i) == null ? "-" : cards.get(i).itemId);
        }
        StringBuilder settings = new StringBuilder();
        for (ConfigHost.Setting s : host.settings(ctx)) {
            settings.append(settings.isEmpty() ? "" : " | ").append(s.text());
        }
        ConfigHost.NumberField number = host.numberField(ctx);
        return Message.translation("ae2bus.cmd.state")
                .param("block", node.item() == null ? node.kind() : node.item().id())
                .param("info", info)
                .param("slots", slots == null ? "-" : ConfigEdits.used(slots.inventory(), slots.usable()) + "/" + slots.usable())
                .param("cards", cards == null ? "-" : cardList.toString())
                .param("settings", settings.isEmpty() ? "-" : settings.toString())
                .param("priority", host.hasPriority() ? Integer.toString(host.priority()) : "-")
                .param("number", number == null ? "-" : Long.toString(number.value()))
                .param("crouch", Boolean.toString(host.opensOnCrouch()));
    }

    private static long number(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
