/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.client.gui.implementations.IOPortScreen – dessen Knöpfe zeigen Richtung und Auswurf).
 */
package de.fellurion.ae2.hytale.ioport;

import de.fellurion.ae2.core.automation.IOPortLogic;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.hud.HudColors;
import de.fellurion.ae2.hytale.hud.HudContext;
import de.fellurion.ae2.hytale.hud.LookInfo;
import de.fellurion.ae2.hytale.hud.NetworkStatus;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import java.util.Locale;

/** Blickanzeige des IO-Ports: Netzzustand, Richtung, Auswurf, wartende und fertige Zellen, Bedienhinweis. */
final class IOPortHud {

    private IOPortHud() {
    }

    static void describe(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        GridNode g = node.gridNode();
        if (g != null) {
            NetworkStatus status = NetworkStatus.of(g);
            out.status(ctx.tr(status.key()), status.color());
        }
        if (!(node.device() instanceof IOPortDevice port)) {
            return;
        }
        IOPortLogic logic = port.logic();
        String l = ctx.locale();
        out.line(tr(l, "ae2ioport.hud.direction"), tr(l, "ae2ioport.hud.direction." + lower(logic.getOperationMode())));
        out.line(tr(l, "ae2ioport.hud.move"), tr(l, "ae2ioport.hud.move." + lower(logic.getFullnessMode())));
        int waiting = port.waitingCells();
        out.line(tr(l, "ae2ioport.hud.cells"), tr(l, "ae2ioport.hud.cells.value", "waiting", waiting, "done",
                port.finishedCells()), waiting > 0 ? HudColors.INFO : HudColors.TEXT);
        if (port.hasRedstoneCard()) {
            boolean enabled = logic.isEnabled();
            String mode = tr(l, "ae2ioport.hud.signal." + lower(port.shownRedstoneMode()));
            out.line(tr(l, "ae2ioport.hud.signal"), enabled ? mode : mode + " · " + tr(l, "ae2ioport.hud.signal.paused"),
                    enabled ? HudColors.TEXT : HudColors.WARN);
        }
        out.line(new LookInfo.Span(tr(l, "ae2ioport.hud.hint"), HudColors.MUTED));
    }

    private static String tr(String locale, String key, Object... params) {
        return TerminalTexts.tr(locale, key, params);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
