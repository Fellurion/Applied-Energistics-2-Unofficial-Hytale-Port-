/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.p2p.FluidP2PTunnelPart, ResourceHandlerP2PTunnelPart, CapabilityP2PTunnelPart).
 */
package de.fellurion.ae2.hytale.p2p;

import de.fellurion.ae2.core.automation.ExternalStorage;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.p2p.P2PTunnelLogic;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.StorageTarget;
import de.fellurion.ae2.hytale.security.AE2Security;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Fluid-P2P-Tunnel: wer vor dem Eingang steht, füllt die Tanks vor den Ausgängen; wer vor einem Ausgang steht,
 * entnimmt aus dem Tank vor dem Eingang. Dieselben Regeln wie beim {@link ItemTunnel} daneben, nur für Flüssigkeiten
 * (AE2 benutzt für beide dieselbe Klasse {@code ResourceHandlerP2PTunnelPart}).
 *
 * <p>In Hytale bewegen nur AE2-Geräte Flüssigkeiten zwischen Blöcken. Zeigt die Vorderseite eines Import-, Export-
 * oder Storage-Bus auf die Vorderseite eines Fluid-Tunnels, bekommt der Bus statt eines Tanks {@link #facing die
 * Sicht des Tunnels} als Ziel:
 * <ul>
 *   <li>Am <b>Eingang</b>: nur einfüllen. Die Menge wird auf die Ausgänge verteilt ({@code Menge / Ausgänge} je
 *       Ausgang; was ein Ausgang nicht annimmt, bekommt der nächste angeboten), jeder Ausgang füllt das Fluidlager
 *       vor sich.</li>
 *   <li>Am <b>Ausgang</b>: nur ansehen und entnehmen – und zwar das Fluidlager vor dem Eingang.</li>
 * </ul>
 * „Fluidlager vor dem Tunnel“ ist, was das Gerät dort als {@link StorageTarget} anbietet (Sky Stone Tank), oder ein
 * weiterer Fluid-Tunnel, der diesem zugewandt ist. Transportiert wird nur, solange die beteiligten Tunnel aktiv sind
 * (Strom und Kanal). Je 125 mB kostet das Netz des Tunnels {@code p2pTunnelTransportTax} (0,025 AE). Ein Tunnel wird
 * nie zweimal ineinander betreten (AE2: {@code CapabilityGuard}).
 *
 * <p>Abweichung von AE2: Ein Ausgang, der selbst nicht aktiv ist, gibt nichts heraus (AE2 prüft dort nur den
 * Eingang).
 */
public final class FluidTunnel implements TunnelBehaviour {

    private TunnelDevice device;
    private WorldNetworks nets;
    private WorldNetworks.Node node;
    private final Exposed exposed = new Exposed();

    // Fluidlager vor dem Tunnel: je Tick höchstens einmal nachgeschlagen
    private long adjacentTick = Long.MIN_VALUE;
    @Nullable
    private ExternalStorage adjacent;
    private boolean adjacentIsTunnel;
    /** Schutz gegen Kreise aus sich gegenüberstehenden Tunneln. */
    private int accessDepth;

    @Override
    public void onAttached(TunnelDevice device, WorldNetworks nets, WorldNetworks.Node node) {
        this.device = device;
        this.nets = nets;
        this.node = node;
    }

    @Override
    public void onRemoved(TunnelDevice device, WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        adjacent = null;
        this.node = null;
    }

    /**
     * Für Geräte, die auf den Block vor sich zugreifen: Steht dort ({@code at}) ein Fluid-P2P-Tunnel, dessen
     * Vorderseite auf {@code from} zeigt, liefert dies die Sicht des Tunnels (immer dasselbe Objekt je Tunnel),
     * sonst null – dann gilt der Block wie bisher.
     */
    @Nullable
    public static ExternalStorage facing(WorldNetworks.Node from, @Nullable WorldNetworks.Node at) {
        if (at == null || !(at.device() instanceof TunnelDevice tunnel)
                || !(tunnel.behaviour() instanceof FluidTunnel fluid) || fluid.node != at) {
            return null;
        }
        Dir f = at.facing();
        if (f == null || at.x() + f.dx != from.x() || at.y() + f.dy != from.y() || at.z() + f.dz != from.z()) {
            return null;
        }
        return fluid.exposed;
    }

    @Nullable
    private static FluidTunnel of(WorldNetworks nets, @Nullable P2PTunnelLogic tunnel) {
        TunnelDevice d = P2P.device(nets, tunnel);
        return d != null && d.behaviour() instanceof FluidTunnel fluid && fluid.node != null ? fluid : null;
    }

    /**
     * Das Fluidlager vor diesem Tunnel (AE2: {@code adjacentCapability}), oder null. Im AE2-Takt wird es je Tick
     * einmal nachgeschlagen; bei Zugriffen zwischen zwei Ticks jedes Mal, damit nie ein inzwischen abgebauter Tank
     * benutzt wird.
     */
    @Nullable
    private ExternalStorage adjacent() {
        long tick = nets.tickCount();
        if (tick != adjacentTick || !nets.isTicking()) {
            adjacentTick = tick;
            resolveAdjacent();
        }
        return adjacent;
    }

    private void resolveAdjacent() {
        adjacent = null;
        adjacentIsTunnel = false;
        Dir f = node.facing();
        if (f == null) {
            return;
        }
        int x = node.x() + f.dx, y = node.y() + f.dy, z = node.z() + f.dz;
        // Wie bei den Bussen: nicht in AE2-Blöcke fremder Besitzer greifen
        if (!AE2Security.deviceMayAccess(nets, node, x, y, z)) {
            return;
        }
        WorldNetworks.Node front = nets.node(x, y, z);
        ExternalStorage other = facing(node, front);
        if (other != null) {
            adjacent = other;
            adjacentIsTunnel = true;
        } else if (front != null && front.device() instanceof StorageTarget target) {
            adjacent = target.busStorage(f.opposite());
        }
    }

    /** true, wenn das Fluidlager vor diesem Tunnel jetzt benutzt werden darf (aktiv und nicht schon betreten). */
    private boolean enter() {
        if (accessDepth > 0 || node == null || !device.logic().isActive()) {
            return false;
        }
        accessDepth++;
        return true;
    }

    private void leave() {
        accessDepth--;
    }

    private long insertAdjacent(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || !enter()) {
            return 0;
        }
        try {
            ExternalStorage s = adjacent();
            return s == null ? 0 : s.insert(what, amount, mode, source);
        } finally {
            leave();
        }
    }

    private long extractAdjacent(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || !enter()) {
            return 0;
        }
        try {
            ExternalStorage s = adjacent();
            return s == null ? 0 : s.extract(what, amount, mode, source);
        } finally {
            leave();
        }
    }

    private int adjacentSlots() {
        if (!enter()) {
            return 0;
        }
        try {
            ExternalStorage s = adjacent();
            return s == null ? 0 : s.getSlots();
        } finally {
            leave();
        }
    }

    @Nullable
    private GenericStack adjacentStack(int slot) {
        if (!enter()) {
            return null;
        }
        try {
            ExternalStorage s = adjacent();
            GenericStack stack = s == null || slot >= s.getSlots() ? null : s.getStackInSlot(slot);
            return stack != null && stack.what() instanceof AEFluidKey ? stack : null;
        } finally {
            leave();
        }
    }

    private void adjacentStacks(KeyCounter out) {
        if (!enter()) {
            return;
        }
        try {
            ExternalStorage s = adjacent();
            for (int i = 0, n = s == null ? 0 : s.getSlots(); i < n; i++) {
                GenericStack stack = s.getStackInSlot(i);
                if (stack != null && stack.what() instanceof AEFluidKey) {
                    out.add(stack.what(), stack.amount());
                }
            }
        } finally {
            leave();
        }
    }

    /** Was ein Gerät vor diesem Tunnel sieht; je nach Rolle des Tunnels Einfüllen (Eingang) oder Entnehmen (Ausgang). */
    private final class Exposed implements ExternalStorage {

        // Ein Import-Bus fragt je Arbeitsgang alle Plätze einzeln ab: den Eingang dafür nur einmal je Tick suchen
        private long inputTick = Long.MIN_VALUE;
        @Nullable
        private FluidTunnel inputSeen;

        /** Der Eingang dieser Frequenz, wenn dieser Tunnel ein aktiver Ausgang ist. */
        @Nullable
        private FluidTunnel input() {
            P2PTunnelLogic logic = device.logic();
            if (node == null || !logic.isOutput() || !logic.isActive()) {
                return null;
            }
            long tick = nets.tickCount();
            if (tick != inputTick || !nets.isTicking()) {
                inputTick = tick;
                inputSeen = of(nets, logic.getInput());
            }
            FluidTunnel in = inputSeen;
            return in != null && in.node != null ? in : null;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            P2PTunnelLogic logic = device.logic();
            if (node == null || logic.isOutput() || !(what instanceof AEFluidKey) || amount <= 0) {
                return 0; // ein Ausgang nimmt nichts an
            }
            List<P2PTunnelLogic> outputs = logic.getOutputs();
            int outputTunnels = outputs.size();
            if (outputTunnels == 0) {
                return 0;
            }
            // Verteilung wie in AE2 (ResourceHandlerP2PTunnelPart.InputStorage.insert)
            long amountPerOutput = amount / outputTunnels;
            long overflow = amountPerOutput == 0 ? amount : amount % amountPerOutput;
            long total = 0;
            for (int i = 0; i < outputTunnels; i++) {
                FluidTunnel target = of(nets, outputs.get(i));
                long toSend = amountPerOutput + overflow;
                long received = target == null ? 0 : target.insertAdjacent(what, toSend, mode, source);
                overflow = toSend - received;
                total += received;
            }
            if (mode == Actionable.MODULATE) {
                logic.deductTransportCost(total, AEKeyType.FLUIDS);
            }
            return total;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            FluidTunnel in = input();
            if (in == null || !(what instanceof AEFluidKey)) {
                return 0; // ein Eingang gibt nichts heraus
            }
            long extracted = in.extractAdjacent(what, amount, mode, source);
            if (mode == Actionable.MODULATE) {
                device.logic().deductTransportCost(extracted, AEKeyType.FLUIDS);
            }
            return extracted;
        }

        @Override
        public int getSlots() {
            FluidTunnel in = input();
            return in == null ? 0 : in.adjacentSlots();
        }

        @Nullable
        @Override
        public GenericStack getStackInSlot(int slot) {
            FluidTunnel in = input();
            return in == null ? null : in.adjacentStack(slot);
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            FluidTunnel in = input();
            if (in != null) {
                in.adjacentStacks(out);
            }
        }

        @Override
        public String getDescription() {
            return "ae2.fluid_p2p_tunnel";
        }
    }

    /** Für {@code /ae2 p2p info}: ob vor dem Tunnel ein Fluidlager steht. */
    @Override
    public String debugState() {
        if (node == null) {
            return "";
        }
        ExternalStorage s = adjacent();
        return s == null ? "front=none" : "front=" + (adjacentIsTunnel ? "tunnel" : "tank") + "/" + s.getSlots();
    }
}
