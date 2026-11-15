/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.quantum;

import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.quantum.QuantumBridgeLogic;
import de.fellurion.ae2.core.quantum.QuantumBridgeLogic.Formation;
import de.fellurion.ae2.core.quantum.QuantumCluster;
import de.fellurion.ae2.core.quantum.QuantumCluster.LinkState;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.hud.HudColors;
import de.fellurion.ae2.hytale.hud.HudContext;
import de.fellurion.ae2.hytale.hud.LookInfo;
import de.fellurion.ae2.hytale.hud.LookInfo.Span;
import de.fellurion.ae2.hytale.hud.NetworkStatus;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import java.util.Locale;

/**
 * Blickanzeige für Quantum Ring und Quantum Link Chamber: ob die Brücke gebildet ist (und wenn nicht, warum),
 * Frequenz des Singularity, Verbindung zur Gegenstelle und Strom.
 */
final class QuantumHud {

    /** Grundverbrauch einer Brücke: neun Blöcke zu je 22 AE/t (AE2: QuantumBridgeBlockEntity). */
    private static final double BRIDGE_IDLE_POWER = 9 * 22.0;

    private QuantumHud() {
    }

    private static String tr(HudContext ctx, String key, Object... params) {
        return TerminalTexts.tr(ctx.locale(), "ae2quantum.hud." + key, params);
    }

    /** Frequenz als Dezimalzahl, wie sie auch im Tooltip des Singularity steht. */
    static String formatFrequency(long frequency) {
        return Long.toString(frequency);
    }

    static void describe(HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        if (!(node.device() instanceof QuantumDevice device)) {
            return; // im Tick des Platzierens ist das Gerät noch nicht angemeldet
        }
        QuantumBridgeLogic logic = device.logic();
        QuantumCluster cluster = logic.getCluster();
        if (cluster == null || cluster.isDestroyed() || cluster.getCenter() == null) {
            unformed(ctx, device, out);
            return;
        }

        // Singularity und Frequenz gehören zur Link Chamber, auch wenn ein Ring angesehen wird
        QuantumDevice center = logic.isCenter() ? device : Quantum.device(ctx.nets(), cluster.getCenter());
        int singularities = center == null ? 0 : center.singularities();
        LinkState state = cluster.getLinkState();
        GridNode g = node.gridNode();
        NetworkStatus network = g == null ? NetworkStatus.NOT_CONNECTED : NetworkStatus.of(g);

        if (network == NetworkStatus.CONFLICT) {
            // Controller auf beiden Seiten: wie zwei per Kabel verbundene Netze mit je einem Controller
            out.status(ctx.tr(network.key()), network.color());
        } else {
            switch (state) {
                case LINKED -> out.status(tr(ctx, "linked"), HudColors.GOOD);
                case WAITING -> out.status(tr(ctx, "waiting"), HudColors.WARN);
                case FREQUENCY_IN_USE -> out.status(tr(ctx, "inUse"), HudColors.BAD);
                case NO_SINGULARITY -> out.status(tr(ctx, "noSingularity"), HudColors.WARN);
            }
        }

        out.line(tr(ctx, "block"), tr(ctx, logic.isCenter() ? "block.link" : logic.isCorner() ? "block.corner"
                : "block.edge"), logic.isCorner() ? HudColors.MUTED : HudColors.TEXT);

        long frequency = cluster.getFrequency();
        out.line(tr(ctx, "frequency"), frequency == 0 ? tr(ctx, "frequency.none") : formatFrequency(frequency),
                frequency == 0 ? HudColors.MUTED : HudColors.TEXT);

        switch (state) {
            case LINKED -> {
                QuantumCluster other = cluster.getOtherCluster();
                GridNode otherNode = other == null || other.getCenter() == null ? null : other.getCenter().getNode();
                if (otherNode == null) {
                    out.line(tr(ctx, "link"), tr(ctx, "link.linked"), HudColors.GOOD);
                } else {
                    long p = otherNode.getPos();
                    out.line(tr(ctx, "link"), tr(ctx, "link.linkedAt", "x", BlockPos.getX(p), "y", BlockPos.getY(p),
                            "z", BlockPos.getZ(p)), HudColors.GOOD);
                }
            }
            case WAITING -> out.line(tr(ctx, "link"), tr(ctx, "link.waiting"), HudColors.WARN);
            case FREQUENCY_IN_USE -> out.line(tr(ctx, "link"), tr(ctx, "link.inUse"), HudColors.BAD);
            case NO_SINGULARITY -> out.line(tr(ctx, "link"), tr(ctx, singularities > 0 ? "link.unentangled"
                    : "link.noSingularity"), HudColors.WARN);
        }

        boolean powered = g != null && g.isPowered();
        out.line(tr(ctx, "power"), tr(ctx, powered ? "power.on" : "power.off", "usage", ctx.rate(BRIDGE_IDLE_POWER)),
                powered ? HudColors.GOOD : HudColors.BAD);

        if (singularities > 1) {
            out.line(new Span(tr(ctx, "hint.stack", "count", singularities), HudColors.WARN));
        } else if (state == LinkState.WAITING) {
            out.line(new Span(tr(ctx, "hint.loaded"), HudColors.MUTED));
        }
    }

    /** Einzelner Block oder unvollständige Brücke: sagt, was fehlt. */
    private static void unformed(HudContext ctx, QuantumDevice device, LookInfo.Builder out) {
        QuantumBridgeLogic logic = device.logic();
        Formation formation = logic.checkFormation();
        if (formation == Formation.FORMED) {
            // vollständig, der Kern bildet die Brücke gerade (ein bis zwei Takte)
            out.status(tr(ctx, "forming"), HudColors.MUTED);
            return;
        }
        out.status(tr(ctx, "notFormed"), HudColors.WARN);
        out.line(new Span(tr(ctx, "reason." + formation.name().toLowerCase(Locale.ROOT)), HudColors.TEXT));
        if (logic.isCenter() && device.singularities() > 0) {
            long frequency = logic.getQEFrequency();
            out.line(tr(ctx, "frequency"), frequency == 0 ? tr(ctx, "link.unentangled") : formatFrequency(frequency),
                    frequency == 0 ? HudColors.WARN : HudColors.TEXT);
        }
        out.line(new Span(tr(ctx, "hint.build"), HudColors.MUTED));
    }
}
