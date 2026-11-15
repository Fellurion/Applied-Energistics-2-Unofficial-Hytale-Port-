/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.p2p.RedstoneP2PTunnelPart).
 */
package de.fellurion.ae2.hytale.p2p;

import de.fellurion.ae2.core.p2p.P2PTunnelLogic;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.hud.HudColors;
import de.fellurion.ae2.hytale.hud.HudContext;
import de.fellurion.ae2.hytale.hud.LookInfo;
import de.fellurion.ae2.hytale.signal.Signals;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;

/**
 * Redstone-P2P-Tunnel für das AE2-Signal ({@link Signals}), das in Hytale Redstone ersetzt.
 *
 * <p>Wie in AE2 liest der Eingang das Signal des Blocks vor sich: Er ist „an“, wenn dieser Block sendet. Jeder
 * aktive Ausgang der Frequenz sendet dann selbst ({@link Signals#setEmitting} an seiner Position) und steuert damit
 * seine Nachbarn an. Ein Ausgang hört auf zu senden, sobald der Eingang aus ist, einer der beiden Tunnel Strom oder
 * Kanal verliert, die Verknüpfung endet oder der Block abgebaut, ersetzt oder entladen wird.
 *
 * <p>Abweichungen von AE2: Das AE2-Signal kennt nur an/aus (Redstone: Stärke 0–15) und keine Richtung – ein
 * Ausgang steuert alle sechs Nachbarn an, nicht nur den Block vor sich. Der Ausgang prüft den Eingang selbst nach
 * (bei jeder Signal- oder Verknüpfungsänderung und einmal je Sekunde), statt sich den Wert schicken zu lassen; so
 * bleibt kein Signal stehen, wenn nur der Eingang seinen Kanal verliert.
 */
final class RedstoneTunnel implements TunnelBehaviour {

    /** Ausgang: sendet gerade. Eingang: der Block davor sendet (und der Tunnel ist aktiv). */
    private boolean powered;
    private long seenSignals = Long.MIN_VALUE;
    private long seenChanges = Long.MIN_VALUE;
    private boolean seenActive;

    @Override
    public void tick(TunnelDevice device, WorldNetworks nets, WorldNetworks.Node node) {
        Signals signals = Signals.of(nets.world());
        P2PTunnelLogic logic = device.logic();
        boolean active = logic.isActive();
        long version = signals.version();
        long changes = logic.getChangeCount();
        if (version == seenSignals && changes == seenChanges && active == seenActive
                && nets.tickCount() % 20 != (node.pos & 15)) {
            return;
        }
        seenSignals = version;
        seenChanges = changes;
        seenActive = active;
        if (logic.isOutput()) {
            powered = active && inputPowered(nets, signals, logic.getInput());
            signals.setEmitting(node.x(), node.y(), node.z(), powered);
        } else {
            // Ein Eingang sendet nie (war er eben noch Ausgang, hört er damit auf)
            signals.setEmitting(node.x(), node.y(), node.z(), false);
            powered = active && frontEmits(signals, node);
        }
    }

    /** Ist der Eingang aktiv und sendet der Block vor ihm? */
    private static boolean inputPowered(WorldNetworks nets, Signals signals, @Nullable P2PTunnelLogic input) {
        if (input == null || !input.isActive()) {
            return false;
        }
        WorldNetworks.Node in = P2P.node(nets, input);
        return in != null && frontEmits(signals, in);
    }

    private static boolean frontEmits(Signals signals, WorldNetworks.Node node) {
        Dir f = node.facing();
        return f != null && signals.isEmitting(node.x() + f.dx, node.y() + f.dy, node.z() + f.dz);
    }

    @Override
    public void onRemoved(TunnelDevice device, WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        Signals.of(nets.world()).setEmitting(node.x(), node.y(), node.z(), false);
        powered = false;
    }

    @Override
    public void describe(TunnelDevice device, HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        out.line(TerminalTexts.tr(ctx.locale(), "ae2p2p.hud.signal"),
                TerminalTexts.tr(ctx.locale(), powered ? "ae2p2p.hud.on" : "ae2p2p.hud.off"),
                powered ? HudColors.GOOD : HudColors.TEXT);
    }

    @Override
    public String debugState() {
        return "signal=" + (powered ? "on" : "off");
    }
}
