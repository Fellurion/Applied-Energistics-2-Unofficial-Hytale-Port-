/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.misc.ToggleBusPart, InvertedToggleBusPart).
 */
package de.fellurion.ae2.hytale.p2p;

import com.hypixel.hytale.server.core.Message;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.core.parts.ToggleBusLogic;
import de.fellurion.ae2.hytale.AE2UseBlockInteraction.UseContext;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.signal.Signals;

import javax.annotation.Nullable;

/**
 * Toggle Bus und Inverted Toggle Bus: ein Kabelstück zwischen Rück- und Vorderseite, das sich schalten lässt. Ist
 * der Bus eingeschaltet, bilden beide Seiten ein Netz; ist er aus, sind es zwei.
 *
 * <p>Geschaltet wird wie in AE2 durch ein Signal – hier das AE2-Signal ({@link Signals#isPowered} an der Position
 * des Bus): Der Toggle Bus verbindet, solange er angesteuert wird, der Inverted Toggle Bus, solange nicht.
 *
 * <p><b>Abweichung von AE2:</b> Weil in Hytale kaum ein Spieler eine Signalquelle zur Hand hat, lässt sich der Bus
 * zusätzlich von Hand schalten: Benutzen (F) legt einen eingebauten Schalter um, der wie ein angesetzter Hebel
 * wirkt – steht er auf „an“, gilt der Bus als angesteuert, auch ohne Signal. Der Schalter wird im Block gespeichert
 * ({@code Config}: {@value #KEY_MANUAL}).
 */
final class ToggleBusDevice implements Device {

    static final String KEY_MANUAL = "toggleManual";

    private final ToggleBusLogic logic;
    /** Eingebauter Schalter (per Benutzen umgelegt). */
    private boolean manual;
    /** Ein Nachbar sendet das AE2-Signal. */
    private boolean signalled;
    private long seenSignals = Long.MIN_VALUE;

    ToggleBusDevice(boolean inverted, WorldNetworks.Node node) {
        this.logic = new ToggleBusLogic(inverted);
        this.manual = Boolean.parseBoolean(node.comp.config().get(KEY_MANUAL));
    }

    ToggleBusLogic logic() {
        return logic;
    }

    boolean manual() {
        return manual;
    }

    boolean signalled() {
        return signalled;
    }

    @Override
    public Object owner() {
        return logic;
    }

    @Override
    public NodeType nodeType(WorldNetworks nets, WorldNetworks.Node node) {
        return logic.mainNodeType();
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        Signals signals = Signals.of(nets.world());
        seenSignals = signals.version();
        signalled = signals.isPowered(node.x(), node.y(), node.z());
        logic.setSignal(signalled || manual);
        GridNode main = node.gridNode();
        if (main != null) {
            // Zweiter Knoten an der Vorderseite; ist der Bus an, verbindet der Kern beide sofort
            logic.createOuterNode(main);
        }
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        Signals signals = Signals.of(nets.world());
        long version = signals.version();
        if (version == seenSignals) {
            return;
        }
        seenSignals = version;
        signalled = signals.isPowered(node.x(), node.y(), node.z());
        logic.setSignal(signalled || manual);
    }

    /** Legt den eingebauten Schalter um und speichert ihn. */
    boolean toggleManual(WorldNetworks.Node node) {
        manual = !manual;
        if (manual) {
            node.comp.config().put(KEY_MANUAL, "true");
        } else {
            node.comp.config().remove(KEY_MANUAL);
        }
        node.markDirty();
        logic.setSignal(signalled || manual);
        return manual;
    }

    /** Benutzen (F): eingebauten Schalter umlegen. */
    static void use(UseContext ctx) {
        WorldNetworks nets = WorldNetworks.of(ctx.world());
        WorldNetworks.Node node = nets.node(ctx.pos().x, ctx.pos().y, ctx.pos().z);
        if (node == null || !(node.device() instanceof ToggleBusDevice bus)) {
            return; // im Tick des Platzierens ist das Gerät noch nicht angemeldet
        }
        bus.toggleManual(node);
        ctx.playerRef().sendMessage(Message.translation(bus.manual ? "ae2p2p.msg.toggle.on" : "ae2p2p.msg.toggle.off")
                .param("state", Message.translation(bus.logic.isEnabled() ? "ae2p2p.msg.toggle.connected"
                        : "ae2p2p.msg.toggle.separated")));
    }

    /** {@code Online}, wenn der Bus verbindet und sein Netz Strom hat (AE2: Statusanzeige des Parts). */
    @Nullable
    @Override
    public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
        GridNode g = node.gridNode();
        return logic.isEnabled() && g != null && g.isOnline() ? "Online" : "default";
    }
}
