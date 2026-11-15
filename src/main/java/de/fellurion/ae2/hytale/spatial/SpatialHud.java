/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.implementations.SpatialIOPortMenu / SpatialAnchorMenu – die Werte ihrer Fenster).
 */
package de.fellurion.ae2.hytale.spatial;

import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.spatial.SpatialIOPortLogic;
import de.fellurion.ae2.core.spatial.SpatialPylonCluster;
import de.fellurion.ae2.core.spatial.SpatialPylonService;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.hud.HudColors;
import de.fellurion.ae2.hytale.hud.HudContext;
import de.fellurion.ae2.hytale.hud.LookInfo;
import de.fellurion.ae2.hytale.hud.NetworkStatus;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Blickanzeige für Pylon, Spatial IO Port und Spatial Anchor. Zeigt, was in AE2 im Fenster des Ports bzw. Ankers
 * steht: Größe des Gebiets, Energiebedarf gegen Vorrat, Effizienz – und, warum gerade kein Transfer möglich ist.
 * Liest nur zwischengespeicherte Werte (die Region eines Netzes wird nur nach Änderungen neu berechnet).
 */
final class SpatialHud {

    private SpatialHud() {
    }

    private static String tr(HudContext ctx, String key, Object... params) {
        return TerminalTexts.tr(ctx.locale(), "ae2spatial.hud." + key, params);
    }

    private static void status(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        GridNode g = node.gridNode();
        if (g != null) {
            NetworkStatus s = NetworkStatus.of(g);
            out.status(ctx.tr(s.key()), s.color());
        }
    }

    @Nullable
    private static SpatialPylonService service(HudContext ctx, WorldNetworks.Node node) {
        GridNode g = node.gridNode();
        Grid grid = g == null ? null : g.getGrid();
        return grid == null ? null : SpatialWorld.of(ctx.nets()).pylons.serviceOf(grid);
    }

    /** Größe, Effizienz und Energiebedarf des Gebiets – gemeinsam für Pylon und Port. */
    private static void region(HudContext ctx, @Nullable SpatialPylonService s, @Nullable Grid grid,
                               LookInfo.Builder out) {
        if (s == null || !s.hasRegion()) {
            out.line(tr(ctx, "region"), tr(ctx, "region.none"), HudColors.MUTED);
            return;
        }
        String size = Math.max(0, s.sizeX()) + " x " + Math.max(0, s.sizeY()) + " x " + Math.max(0, s.sizeZ());
        out.line(tr(ctx, "region"), s.isValidRegion() ? size : size + "  " + tr(ctx, "region.invalid"),
                s.isValidRegion() ? HudColors.TEXT : HudColors.WARN);
        out.line(tr(ctx, "efficiency"), String.format(Locale.ROOT, "%.0f %%", Math.floor(s.currentEfficiency()))
                + "  (" + tr(ctx, "pylons", "count", s.pylonCount()) + ")");
        if (grid != null) {
            double stored = grid.getEnergyService().getStoredPower();
            boolean enough = stored >= s.requiredPower();
            out.line(tr(ctx, "power"), ctx.amount(s.requiredPower()) + " / " + ctx.amount(stored) + " AE",
                    enough ? HudColors.TEXT : HudColors.WARN);
        }
    }

    static void pylon(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        if (!(node.device() instanceof PylonDevice pylon)) {
            return;
        }
        SpatialPylonCluster cluster = pylon.logic().getCluster();
        if (cluster == null) {
            out.line(tr(ctx, "line"), tr(ctx, "line.none"), HudColors.WARN);
            return;
        }
        out.line(tr(ctx, "line"), tr(ctx, "line.formed", "count", cluster.size(), "axis", cluster.axis().name()));
        GridNode g = node.gridNode();
        region(ctx, service(ctx, node), g == null ? null : g.getGrid(), out);
    }

    static void ioPort(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        if (!(node.device() instanceof IOPortDevice port)) {
            return;
        }
        GridNode g = node.gridNode();
        region(ctx, service(ctx, node), g == null ? null : g.getGrid(), out);
        SpatialIOPortLogic.Result result = port.logic().status(port.host(ctx.nets(), node));
        boolean ready = result == SpatialIOPortLogic.Result.OK;
        out.line(tr(ctx, "transfer"), tr(ctx, "state." + result.name()), ready ? HudColors.GOOD : HudColors.WARN);
        if (ready) {
            out.line(new LookInfo.Span(tr(ctx, "hint"), HudColors.MUTED));
        }
    }

    static void anchor(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        status(ctx, node, out);
        if (!(node.device() instanceof AnchorDevice anchor)) {
            return;
        }
        int chunks = anchor.logic().countLoadedChunks();
        out.line(tr(ctx, "chunks"), String.valueOf(chunks), chunks > 0 ? HudColors.TEXT : HudColors.MUTED);
        GridNode g = node.gridNode();
        out.line(tr(ctx, "usage"), ctx.rate(g == null ? anchor.logic().currentPower() : g.getIdlePowerUsage()) + " AE/t");
    }
}
