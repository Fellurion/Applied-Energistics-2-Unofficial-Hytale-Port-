/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.p2p.ItemP2PTunnelPart, ResourceHandlerP2PTunnelPart, CapabilityP2PTunnelPart).
 */
package de.fellurion.ae2.hytale.p2p;

import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import de.fellurion.ae2.core.automation.ExternalStorage;
import de.fellurion.ae2.core.automation.ExternalStorageFacade;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.p2p.P2PTunnelLogic;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.BusDevice;
import de.fellurion.ae2.hytale.security.AE2Security;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Item-P2P-Tunnel: wer vor dem Eingang steht, legt Items in die Inventare vor den Ausgängen; wer vor einem Ausgang
 * steht, entnimmt aus dem Inventar vor dem Eingang.
 *
 * <p>In Hytale bewegen nur AE2-Geräte Items zwischen Blöcken. Zeigt die Vorderseite eines Import-, Export- oder
 * Storage-Bus oder eines Pattern Providers auf die Vorderseite eines Item-Tunnels, bekommt das Gerät statt eines
 * Containers {@link #facing die Sicht des Tunnels} als Ziel – genau das, was in AE2 ein Nachbar über die
 * Item-Fähigkeit des Tunnel-Parts sieht:
 * <ul>
 *   <li>Am <b>Eingang</b>: nur einlegen. Die Menge wird auf die Ausgänge verteilt ({@code Menge / Ausgänge} je
 *       Ausgang; was ein Ausgang nicht annimmt, bekommt der nächste angeboten), jeder Ausgang legt sie in das Inventar
 *       vor sich.</li>
 *   <li>Am <b>Ausgang</b>: nur ansehen und entnehmen – und zwar das Inventar vor dem Eingang.</li>
 * </ul>
 * Transportiert wird nur, solange die beteiligten Tunnel aktiv sind (Strom und Kanal). Jedes transportierte Item
 * kostet das Netz des Tunnels {@code p2pTunnelTransportTax} (0,025 AE). Stehen sich zwei Tunnel gegenüber, reicht der
 * eine an den anderen weiter; ein Tunnel wird dabei nie zweimal ineinander betreten (AE2: {@code CapabilityGuard}).
 *
 * <p>Die Sicht gilt für die Seite, auf die die Vorderseite gerade zeigt: Wird der Tunnel gedreht
 * (Schraubenschlüssel), ist die bisherige Sicht sofort wertlos – ein Gerät, das sie noch hält, bewegt darüber nichts
 * mehr, bis es sein Ziel neu sucht –, und vor der neuen Vorderseite gibt es eine neue.
 *
 * <p>Abweichung von AE2: Ein Ausgang, der selbst nicht aktiv ist, gibt nichts heraus (AE2 prüft dort nur den
 * Eingang).
 */
public final class ItemTunnel implements TunnelBehaviour {

    private TunnelDevice device;
    private WorldNetworks nets;
    private WorldNetworks.Node node;
    /** Die Sicht für die Seite, auf die die Vorderseite zuletzt zeigte, als jemand danach fragte. */
    @Nullable
    private Exposed exposed;

    // Inventar vor dem Tunnel: je Tick höchstens einmal nachgeschlagen
    private long adjacentTick = Long.MIN_VALUE;
    @Nullable
    private ItemContainer adjacentContainer;
    @Nullable
    private ExternalStorage adjacent;
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
        adjacentContainer = null;
        adjacent = null;
        this.node = null;
    }

    /**
     * Für Geräte, die auf den Block vor sich zugreifen: Steht dort ({@code at}) ein Item-P2P-Tunnel, dessen
     * Vorderseite auf {@code from} zeigt, liefert dies die Sicht des Tunnels (dasselbe Objekt, solange er nicht
     * gedreht wird), sonst null – dann gilt der Block wie bisher.
     */
    @Nullable
    public static ExternalStorage facing(WorldNetworks.Node from, @Nullable WorldNetworks.Node at) {
        if (at == null || !(at.device() instanceof TunnelDevice tunnel)
                || !(tunnel.behaviour() instanceof ItemTunnel item) || item.node != at) {
            return null;
        }
        Dir f = at.facing();
        if (f == null || at.x() + f.dx != from.x() || at.y() + f.dy != from.y() || at.z() + f.dz != from.z()) {
            return null;
        }
        Exposed e = item.exposed;
        if (e == null || e.side != f) {
            // gedreht: die alte Sicht bleibt bei dem, der sie hält, und gilt nicht mehr (siehe Exposed#valid)
            e = item.new Exposed(f);
            item.exposed = e;
        }
        return e;
    }

    @Nullable
    private static ItemTunnel of(WorldNetworks nets, @Nullable P2PTunnelLogic tunnel) {
        TunnelDevice d = P2P.device(nets, tunnel);
        return d != null && d.behaviour() instanceof ItemTunnel item && item.node != null ? item : null;
    }

    /**
     * Das Inventar vor diesem Tunnel (AE2: {@code adjacentCapability}), oder null. Im AE2-Takt wird es je Tick einmal
     * nachgeschlagen; bei Zugriffen zwischen zwei Ticks (Spieler am Terminal) jedes Mal, damit nie ein inzwischen
     * abgebauter Container benutzt wird.
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
        Dir f = node.facing();
        if (f == null) {
            adjacentContainer = null;
            adjacent = null;
            return;
        }
        int x = node.x() + f.dx, y = node.y() + f.dy, z = node.z() + f.dz;
        // Wie bei den Bussen: nicht in AE2-Blöcke fremder Besitzer greifen
        if (!AE2Security.deviceMayAccess(nets, node, x, y, z)) {
            adjacentContainer = null;
            adjacent = null;
            return;
        }
        ExternalStorage other = facing(node, nets.node(x, y, z));
        if (other != null) {
            adjacentContainer = null;
            adjacent = other;
            return;
        }
        ItemContainer c = BlockAccess.container(nets.world(), x, y, z);
        if (c != adjacentContainer || c == null) {
            adjacentContainer = c;
            adjacent = c == null ? null : ExternalStorageFacade.of(BusDevice.slots(c));
        }
    }

    /** true, wenn das Inventar vor diesem Tunnel jetzt benutzt werden darf (aktiv und nicht schon betreten). */
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
            return s == null || slot >= s.getSlots() ? null : s.getStackInSlot(slot);
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
            if (s != null) {
                s.getAvailableStacks(out);
            }
        } finally {
            leave();
        }
    }

    /** Was ein Gerät vor diesem Tunnel sieht; je nach Rolle des Tunnels Einlegen (Eingang) oder Entnehmen (Ausgang). */
    private final class Exposed implements ExternalStorage {

        /** Die Seite des Tunnels, vor der diese Sicht ausgegeben wurde. */
        private final Dir side;

        Exposed(Dir side) {
            this.side = side;
        }

        /** Gibt es den Tunnel noch, und zeigt seine Vorderseite noch dorthin, wo diese Sicht ausgegeben wurde? */
        private boolean valid() {
            WorldNetworks.Node n = node;
            return n != null && n.facing() == side;
        }

        // Ein Import-Bus fragt je Arbeitsgang alle Plätze einzeln ab: den Eingang dafür nur einmal je Tick suchen
        private long inputTick = Long.MIN_VALUE;
        @Nullable
        private ItemTunnel inputSeen;

        /** Der Eingang dieser Frequenz, wenn dieser Tunnel ein aktiver Ausgang ist. */
        @Nullable
        private ItemTunnel input() {
            P2PTunnelLogic logic = device.logic();
            if (!valid() || !logic.isOutput() || !logic.isActive()) {
                return null;
            }
            long tick = nets.tickCount();
            if (tick != inputTick || !nets.isTicking()) {
                inputTick = tick;
                inputSeen = of(nets, logic.getInput());
            }
            ItemTunnel in = inputSeen;
            return in != null && in.node != null ? in : null;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            P2PTunnelLogic logic = device.logic();
            if (!valid() || logic.isOutput() || !(what instanceof AEItemKey) || amount <= 0) {
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
                ItemTunnel target = of(nets, outputs.get(i));
                long toSend = amountPerOutput + overflow;
                long received = target == null ? 0 : target.insertAdjacent(what, toSend, mode, source);
                overflow = toSend - received;
                total += received;
            }
            if (mode == Actionable.MODULATE) {
                logic.deductTransportCost(total, AEKeyType.ITEMS);
            }
            return total;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            ItemTunnel in = input();
            if (in == null || !(what instanceof AEItemKey)) {
                return 0; // ein Eingang gibt nichts heraus
            }
            long extracted = in.extractAdjacent(what, amount, mode, source);
            if (mode == Actionable.MODULATE) {
                device.logic().deductTransportCost(extracted, AEKeyType.ITEMS);
            }
            return extracted;
        }

        @Override
        public int getSlots() {
            ItemTunnel in = input();
            return in == null ? 0 : in.adjacentSlots();
        }

        @Nullable
        @Override
        public GenericStack getStackInSlot(int slot) {
            ItemTunnel in = input();
            return in == null ? null : in.adjacentStack(slot);
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            ItemTunnel in = input();
            if (in != null) {
                in.adjacentStacks(out);
            }
        }

        @Override
        public String getDescription() {
            return "ae2.item_p2p_tunnel";
        }
    }

    /** Für {@code /ae2 p2p info}: ob vor dem Tunnel ein Inventar steht. */
    @Override
    public String debugState() {
        if (node == null) {
            return "";
        }
        ExternalStorage s = adjacent();
        return s == null ? "front=none" : "front=" + (adjacentContainer != null ? "container" : "tunnel")
                + "/" + s.getSlots();
    }
}
