/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.integration.modules.igtooltip.part.P2PStateDataProvider, appeng.util.helpers.P2PHelper).
 */
package de.fellurion.ae2.hytale.p2p;

import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.grid.AdHocNetworkError;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.p2p.MEP2PTunnelLogic;
import de.fellurion.ae2.core.p2p.P2PFrequency;
import de.fellurion.ae2.core.p2p.P2PTunnelLogic;
import de.fellurion.ae2.core.parts.QuartzFiberLogic;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.hud.HudColors;
import de.fellurion.ae2.hytale.hud.HudContext;
import de.fellurion.ae2.hytale.hud.LookInfo;
import de.fellurion.ae2.hytale.hud.LookInfo.Span;
import de.fellurion.ae2.hytale.hud.NetworkStatus;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import java.util.ArrayList;
import java.util.List;

/** Blickanzeige für P2P-Tunnel (Frequenz als Farbcode, Rolle, Gegenstellen), Quartz Fiber und Toggle Bus. */
final class P2PHud {

    /** Farbe je Hex-Ziffer der Frequenz (AE2: die 16 Kabelfarben), aufgehellt für dunklen Grund. */
    private static final String[] DIGIT_COLORS = new String[16];
    private static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C", "D", "E",
            "F"};

    static {
        AEColor[] colors = AEColor.values();
        for (int i = 0; i < 16; i++) {
            int rgb = colors[i].mediumVariant;
            int r = lighten(rgb >> 16 & 0xFF), g = lighten(rgb >> 8 & 0xFF), b = lighten(rgb & 0xFF);
            DIGIT_COLORS[i] = String.format("#%02x%02x%02x", r, g, b);
        }
    }

    private P2PHud() {
    }

    private static int lighten(int channel) {
        return channel + (255 - channel) * 35 / 100;
    }

    private static String tr(HudContext ctx, String key, Object... params) {
        return TerminalTexts.tr(ctx.locale(), "ae2p2p.hud." + key, params);
    }

    private static void status(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        GridNode g = node.gridNode();
        if (g != null) {
            NetworkStatus s = NetworkStatus.of(g);
            out.status(ctx.tr(s.key()), s.color());
        }
    }

    /** Tunnel: Frequenz (vier farbige Hex-Ziffern), Rolle mit Gegenstellen, Zustand der Tunnelart. */
    static void tunnel(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        if (!(node.device() instanceof TunnelDevice device)) {
            return;
        }
        P2PTunnelLogic logic = device.logic();
        GridNode mainNode = node.gridNode();
        if (mainNode != null && mainNode.getGrid() != null && mainNode.isPowered() && mainNode.getGrid()
                .getPathingService().getAdHocNetworkError() == AdHocNetworkError.NESTED_P2P_TUNNEL) {
            // AE2: ein Netz ohne Controller, in dem ein ME-Tunnel in einem getunnelten Netz sitzt, bekommt keine Kanäle
            out.status(tr(ctx, "nested"), HudColors.WARN);
        }
        short freq = logic.getFrequency();
        if (freq == 0) {
            out.line(tr(ctx, "frequency"), tr(ctx, "unlinked"), HudColors.MUTED);
            out.line(new Span(tr(ctx, "hint.link"), HudColors.MUTED));
        } else {
            List<Span> spans = new ArrayList<>(5);
            spans.add(new Span(tr(ctx, "frequency") + "  ", HudColors.MUTED));
            for (int i = 0; i < 4; i++) {
                int nibble = P2PFrequency.nibble(freq, i);
                spans.add(new Span(DIGITS[nibble], DIGIT_COLORS[nibble]));
            }
            out.line(spans);
            GridNode g = logic.getMainNode();
            Grid grid = g == null ? null : g.getGrid();
            if (logic.isOutput()) {
                boolean linked = logic.getInput() != null;
                out.line(tr(ctx, "role"), tr(ctx, linked ? "role.output" : "role.output.noInput"),
                        linked ? HudColors.TEXT : HudColors.WARN);
            } else {
                int outputs = grid == null ? 0 : grid.getP2PService().getOutputs(freq, logic.getType()).size();
                out.line(tr(ctx, "role"), tr(ctx, outputs == 1 ? "role.input.one" : "role.input", "count", outputs),
                        outputs == 0 ? HudColors.WARN : HudColors.TEXT);
                if (logic instanceof MEP2PTunnelLogic me && outputs > 0) {
                    out.line(tr(ctx, "connected"), me.getConnectionCount() + " / " + outputs);
                }
            }
        }
        if (TunnelBehaviour.linksOnly(device.type())) {
            out.line(new Span(tr(ctx, "linksOnly"), HudColors.MUTED));
        }
        device.behaviour().describe(device, ctx, node, out);
    }

    /** Quartz Fiber: ob beide Seiten an einem Netz hängen und Energie teilen. */
    static void fiber(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        if (!(node.device() instanceof FiberDevice device)) {
            status(ctx, node, out);
            return;
        }
        QuartzFiberLogic logic = device.logic();
        GridNode main = logic.getMainNode();
        GridNode outer = logic.getOuterNode();
        boolean back = main != null && main.getGrid() != null && main.getGrid().size() > 1;
        boolean front = outer != null && outer.getGrid() != null && outer.getGrid().size() > 1;
        boolean powered = main != null && outer != null && (main.isPowered() || outer.isPowered());
        if (back && front) {
            out.status(tr(ctx, powered ? "fiber.sharing" : "fiber.noPower"), powered ? HudColors.GOOD : HudColors.BAD);
        } else {
            out.status(tr(ctx, "fiber.oneSide"), HudColors.WARN);
        }
        out.line(tr(ctx, "fiber.back"), tr(ctx, side(back, main)), sideColor(back, main));
        out.line(tr(ctx, "fiber.front"), tr(ctx, side(front, outer)), sideColor(front, outer));
    }

    private static String side(boolean connected, GridNode node) {
        return !connected ? "fiber.none" : node.isPowered() ? "fiber.powered" : "fiber.unpowered";
    }

    private static String sideColor(boolean connected, GridNode node) {
        return !connected ? HudColors.MUTED : node.isPowered() ? HudColors.GOOD : HudColors.BAD;
    }

    /** Toggle Bus: verbunden/getrennt, Signal, eingebauter Schalter. */
    static void toggleBus(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        if (!(node.device() instanceof ToggleBusDevice device)) {
            status(ctx, node, out);
            return;
        }
        boolean enabled = device.logic().isEnabled();
        GridNode g = node.gridNode();
        if (enabled && g != null && !g.isPowered()) {
            status(ctx, node, out);
        } else {
            out.status(tr(ctx, enabled ? "toggle.connected" : "toggle.separated"),
                    enabled ? HudColors.GOOD : HudColors.WARN);
        }
        out.line(tr(ctx, "signal"), tr(ctx, device.signalled() ? "on" : "off"),
                device.signalled() ? HudColors.GOOD : HudColors.TEXT);
        out.line(tr(ctx, "toggle.manual"), tr(ctx, device.manual() ? "on" : "off"),
                device.manual() ? HudColors.GOOD : HudColors.TEXT);
        out.line(new Span(tr(ctx, device.logic().isInverted() ? "toggle.hint.inverted" : "toggle.hint"),
                HudColors.MUTED));
    }
}
