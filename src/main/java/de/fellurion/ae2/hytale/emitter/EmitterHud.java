/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.integration.modules.igtooltip.parts – Jade/TOP-Zeilen für Level Emitter und Monitore).
 */
package de.fellurion.ae2.hytale.emitter;

import de.fellurion.ae2.core.automation.LevelEmitterLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.devices.StorageMonitorLogic;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.hud.HudColors;
import de.fellurion.ae2.hytale.hud.HudContext;
import de.fellurion.ae2.hytale.hud.InfoProviders;
import de.fellurion.ae2.hytale.hud.LookInfo;
import de.fellurion.ae2.hytale.hud.NetworkStatus;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

/**
 * Blickanzeige für Level Emitter, Energy Level Emitter, Light Detector und die Monitore. Liest nur Werte, die der
 * Kern ohnehin führt (zuletzt gemeldete Menge, Ausgabe, beobachtetes Item).
 */
final class EmitterHud {

    private EmitterHud() {
    }

    static void register() {
        InfoProviders.register(EmitterHud::emitter, "level_emitter", "energy_level_emitter");
        InfoProviders.register(EmitterHud::monitor, "storage_monitor", "conversion_monitor");
        InfoProviders.register(EmitterHud::lightDetector, "light_detector");
    }

    private static String tr(HudContext ctx, String key, Object... params) {
        return TerminalTexts.tr(ctx.locale(), "ae2emitter." + key, params);
    }

    /** Zustandszeile des Netzknotens (online, kein Strom …) wie bei den übrigen Netzblöcken. */
    private static void status(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        GridNode g = node.gridNode();
        if (g != null) {
            NetworkStatus s = NetworkStatus.of(g);
            out.status(ctx.tr(s.key()), s.color());
        }
    }

    private static void signal(HudContext ctx, boolean on, LookInfo.Builder out) {
        out.line(tr(ctx, "hud.signal"), tr(ctx, on ? "hud.on" : "hud.off"), on ? HudColors.GOOD : HudColors.MUTED);
    }

    /** Beobachtetes Item, Menge gegen Schwelle, sendet ja/nein. */
    private static void emitter(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        if (!(node.device() instanceof LevelEmitterDevice device)) {
            return;
        }
        String lang = ctx.locale();
        String threshold = TerminalTexts.formatAmount(device.logic().getReportingValue());
        LevelEmitterLogic storage = device.storage();
        if (storage == null) {
            out.line(tr(ctx, "hud.energy"), tr(ctx, "hud.energyOf", "amount",
                    ctx.amount(device.currentValue(node)), "threshold", threshold));
            out.line(tr(ctx, "hud.mode"), tr(ctx, "hud.mode." + modeKey(device)));
        } else {
            out.line(tr(ctx, "hud.watching"), device.watchedName(lang));
            if (storage.isUpgradedWith(UpgradeCard.CRAFTING)) {
                out.line(tr(ctx, "hud.mode"), tr(ctx, storage.isCraftViaRedstone() ? "hud.mode.craftVia"
                        : "hud.mode.whileCrafting"));
            } else {
                out.line(tr(ctx, "hud.amount"), tr(ctx, "hud.amountOf", "amount", device.amountText(lang, node),
                        "threshold", threshold));
                out.line(tr(ctx, "hud.mode"), tr(ctx, "hud.mode." + modeKey(device)));
            }
        }
        signal(ctx, device.logic().isEmitting(), out);
    }

    private static String modeKey(LevelEmitterDevice device) {
        return device.logic().getRedstoneMode() == de.fellurion.ae2.core.config.Enums.RedstoneMode.LOW_SIGNAL
                ? "below" : "above";
    }

    /** Item, Menge im AE2-Kurzformat (bzw. „herstellbar“), gesperrt ja/nein. */
    private static void monitor(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        if (!(node.device() instanceof MonitorDevice device)) {
            return;
        }
        StorageMonitorLogic logic = device.logic();
        AEItemKey key = device.item();
        if (key == null) {
            out.line(tr(ctx, "hud.item"), tr(ctx, "hud.noItem"), HudColors.MUTED);
        } else {
            out.line(tr(ctx, "hud.item"), ctx.itemName(key.itemId()));
            if (logic.isActive()) {
                long amount = logic.getAmount();
                if (amount == 0 && logic.canCraft()) {
                    out.line(tr(ctx, "hud.amount"), tr(ctx, "hud.craftable"), HudColors.INFO);
                } else {
                    out.line(tr(ctx, "hud.amount"), TerminalTexts.formatAmount(amount));
                }
            }
        }
        out.line(tr(ctx, "hud.lock"), tr(ctx, logic.isLocked() ? "hud.locked" : "hud.unlocked"),
                logic.isLocked() ? HudColors.WARN : HudColors.TEXT);
        // Monitore haben kein Fenster: die Gesten stehen hier
        if (device.conversion()) {
            hint(ctx, out, logic.isLocked() ? "hud.hint.conversionLocked" : "hud.hint.conversion");
            if (logic.isLocked()) {
                hint(ctx, out, "hud.hint.conversionSneak");
            }
        } else {
            hint(ctx, out, logic.isLocked() ? "hud.hint.storageLocked" : "hud.hint.storage");
        }
    }

    private static void hint(HudContext ctx, LookInfo.Builder out, String key) {
        out.line(new LookInfo.Span(tr(ctx, key), HudColors.MUTED));
    }

    /** Gemessene Helligkeit und ob der Sensor sendet. */
    private static void lightDetector(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        if (!(node.device() instanceof LightDetectorDevice device)) {
            return;
        }
        int light = device.light();
        out.line(tr(ctx, "hud.light"), light < 0 ? tr(ctx, "hud.lightUnknown") : tr(ctx, "hud.lightOf", "light",
                Integer.toString(light), "max", Integer.toString(LightDetectorDevice.MAX_LIGHT)));
        signal(ctx, device.isEmitting(), out);
    }
}
