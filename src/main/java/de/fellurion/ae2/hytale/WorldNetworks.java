/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.grid.CableType;
import de.fellurion.ae2.core.grid.ControllerState;
import de.fellurion.ae2.core.grid.Direction;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.MEWorld;
import de.fellurion.ae2.core.grid.NodeType;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.device.Devices;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alle AE2-Blöcke einer Welt und ihr {@link MEWorld} (Kern). Läuft ausschließlich auf dem Welt-Thread.
 *
 * <p>Platzieren/Laden meldet einen Block an; im nächsten Tick wird er im Kern als GridNode angelegt
 * (Ausrichtung aus der Blockrotation, Farbe aus der Komponente). Danach tickt der Kern (Kanäle, Energie,
 * Speicher), und die Blockzustände (Kabelform, Online, Füllstand ...) werden nachgezogen.
 */
public final class WorldNetworks {

    private static final Map<String, WorldNetworks> BY_WORLD = new ConcurrentHashMap<>();

    public static WorldNetworks of(World world) {
        return BY_WORLD.computeIfAbsent(world.getName(), n -> new WorldNetworks(world));
    }

    public static void forget(World world) {
        BY_WORLD.remove(world.getName());
    }

    /** Ein AE2-Block in der Welt. */
    public static final class Node {
        public final long pos;
        public final Ref<ChunkStore> ref;
        public final AE2BlockComponent comp;
        public final BlockModule.BlockStateInfo info;
        @Nullable
        ItemCatalog.Info item;
        @Nullable
        Dir facing;
        @Nullable
        GridNode gridNode;
        @Nullable
        Device device;
        String shownState;
        /** Kabel: zuletzt gezeigte Form als Zahl (Maske | Stufe << 6), spart den State-String je Tick. */
        int shownCableCode = -1;

        Node(long pos, Ref<ChunkStore> ref, AE2BlockComponent comp, BlockModule.BlockStateInfo info) {
            this.pos = pos;
            this.ref = ref;
            this.comp = comp;
            this.info = info;
        }

        public String kind() {
            return comp.kind();
        }

        public int x() {
            return BlockAccess.unpackX(pos);
        }

        public int y() {
            return BlockAccess.unpackY(pos);
        }

        public int z() {
            return BlockAccess.unpackZ(pos);
        }

        /** Front-Richtung (aus der Blockrotation) oder null für nicht ausrichtbare Blöcke. */
        @Nullable
        public Dir facing() {
            return facing;
        }

        @Nullable
        public GridNode gridNode() {
            return gridNode;
        }

        @Nullable
        public Device device() {
            return device;
        }

        @Nullable
        public ItemCatalog.Info item() {
            return item;
        }

        /** Persistenz der Komponente beim nächsten Chunk-Speichern. */
        public void markDirty() {
            info.markNeedsSaving();
        }
    }

    private final World world;
    private final MEWorld me = new MEWorld();
    private final AE2Perf perf = new AE2Perf();
    private boolean ticking;
    /** Zusatzaufgaben je Tick (Lasttest {@code /ae2 stress}); false = fertig. */
    private final java.util.List<java.util.function.BooleanSupplier> tickTasks = new java.util.ArrayList<>();
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final List<Node> pending = new ArrayList<>();
    private long tickCount;
    /** Noch nicht in AE2-Ticks umgesetzte Zeit dieser Welt (siehe {@link AE2TickSystem}). */
    float pendingTickSeconds;
    /** Zählt jede An-/Abmeldung von AE2-Blöcken (z. B. für verbundenes Quarzglas). */
    private long topologyVersion;

    private WorldNetworks(World world) {
        this.world = world;
    }

    public World world() {
        return world;
    }

    public MEWorld me() {
        return me;
    }

    @Nullable
    public Node node(int x, int y, int z) {
        return nodes.get(BlockAccess.pack(x, y, z));
    }

    public Iterable<Node> nodes() {
        return nodes.values();
    }

    public int size() {
        return nodes.size();
    }

    public long tickCount() {
        return tickCount;
    }

    public long topologyVersion() {
        return topologyVersion;
    }

    // ------------------------------------------------------------------ Lebenszyklus

    void nodeAdded(long pos, Ref<ChunkStore> ref, AE2BlockComponent comp, BlockModule.BlockStateInfo info, boolean placed) {
        Node old = nodes.remove(pos);
        if (old != null) {
            detach(old, false);
        }
        Node n = new Node(pos, ref, comp, info);
        nodes.put(pos, n);
        pending.add(n);
        topologyVersion++;
    }

    void nodeRemoved(long pos, Ref<ChunkStore> ref, boolean broken) {
        Node n = nodes.get(pos);
        if (n != null && n.ref.equals(ref)) {
            nodes.remove(pos);
            pending.remove(n);
            detach(n, broken);
        }
    }

    void nodeRemovedByRef(Ref<ChunkStore> ref) {
        for (Node n : new ArrayList<>(nodes.values())) {
            if (n.ref.equals(ref)) {
                nodeRemoved(n.pos, ref, false);
                return;
            }
        }
    }

    private void detach(Node n, boolean broken) {
        topologyVersion++;
        if (broken && "mysterious_cube".equals(n.kind())) {
            // Der Meteoriten-Kompass zeigt nur auf Meteoriten, in denen der Cube noch liegt
            de.fellurion.ae2.hytale.meteorite.MeteoriteRegistry.of(world.getName()).cubeRemoved(n.x(), n.y(), n.z());
            de.fellurion.ae2.hytale.meteorite.CubeDrops.onRemoved(world, n.x(), n.y(), n.z());
        }
        if (n.device != null) {
            try {
                n.device.onRemoved(this, n, broken);
            } catch (RuntimeException ignored) {
                // Abbauen darf nie an einem Gerät scheitern
            }
        }
        if (n.gridNode != null) {
            me.removeNode(n.pos);
            n.gridNode = null;
        }
    }

    /** Legt angemeldete Blöcke im Kern an (verzögert, damit Rotation/Chunk sicher lesbar sind). */
    private void attachPending() {
        if (pending.isEmpty()) {
            return;
        }
        List<Node> batch = new ArrayList<>(pending);
        pending.clear();
        for (Node n : batch) {
            if (nodes.get(n.pos) != n) {
                continue;
            }
            BlockType bt = BlockAccess.blockType(world, n.x(), n.y(), n.z());
            if (bt == null) {
                pending.add(n); // Chunk noch nicht bereit – nächster Tick
                continue;
            }
            n.item = bt.getItem() == null ? null : ItemCatalog.get(bt.getItem().getId());
            n.facing = resolveFacing(n);
            n.device = Devices.create(this, n);
            String kind = n.kind();
            if (NodeType.isNetworked(kind)) {
                NodeType type = n.device == null ? null : n.device.nodeType(this, n);
                if (type == null) {
                    type = nodeType(n);
                }
                AEColor color = NetKindsColor.of(n.comp);
                n.gridNode = me.addNode(BlockPos(n), type, toCore(n.facing), color, n.device == null ? null : n.device.owner());
            }
            if (n.device != null) {
                n.device.onAttached(this, n);
            }
        }
    }

    private static long BlockPos(Node n) {
        return de.fellurion.ae2.core.grid.BlockPos.asLong(n.x(), n.y(), n.z());
    }

    private NodeType nodeType(Node n) {
        String kind = n.kind();
        if ("cable".equals(kind)) {
            return NodeType.cable(CableType.fromId(n.comp.cableType() == null ? "glass" : n.comp.cableType()));
        }
        boolean part = n.item != null && n.item.part();
        if (part && ("interface".equals(kind) || "pattern_provider".equals(kind) || "energy_acceptor".equals(kind))) {
            return NodeType.forPart(kind);
        }
        return NodeType.forBlock(kind);
    }

    @Nullable
    private Dir resolveFacing(Node n) {
        ItemCatalog.Info info = n.item;
        if (info == null || info.rotation() == null || "None".equals(info.rotation()) || info.front() == null) {
            return null;
        }
        int rot = BlockAccess.rotationIndex(world, n.x(), n.y(), n.z());
        return BlockAccess.facing(rot, info.front());
    }

    @Nullable
    static Direction toCore(@Nullable Dir d) {
        return d == null ? null : Direction.valueOf(d.name());
    }

    /**
     * Der Block bei x/y/z wurde gedreht (Schraubenschlüssel): liest die Ausrichtung neu, zieht die Verbindungen im
     * Kern nach ({@link MEWorld#updateNode}) und sagt es dem Gerät ({@link Device#onRotated}). Block-Entity, Gerät
     * und GridNode bleiben dieselben – nichts wird ab- und wieder angemeldet.
     *
     * @return false, wenn dort kein AE2-Block angemeldet ist
     */
    public boolean nodeRotated(int x, int y, int z) {
        Node n = node(x, y, z);
        if (n == null) {
            return false;
        }
        if (pending.contains(n)) {
            return true; // noch nicht im Kern angelegt: attachPending liest die neue Rotation ohnehin
        }
        Dir before = n.facing;
        n.facing = resolveFacing(n);
        if (n.facing == before) {
            return true;
        }
        topologyVersion++;
        if (n.gridNode != null) {
            me.updateNode(BlockPos(n), toCore(n.facing), NetKindsColor.of(n.comp));
        }
        if (n.device != null) {
            n.device.onRotated(this, n);
        }
        return true;
    }

    // ------------------------------------------------------------------ Takt

    /** Ein AE2-Tick (20 pro Sekunde), aufgerufen vom {@link AE2TickSystem} auf dem Welt-Thread. */
    void tick() {
        ticking = true;
        try {
            tickInner();
        } finally {
            ticking = false;
        }
    }

    /**
     * true während des AE2-Takts. Außerhalb (Spieleraktionen zwischen zwei Ticks, z. B. im Terminal) speichern
     * Zellen ihre Änderungen sofort ins Item, damit eine gleichzeitig entnommene Zelle nie veraltet ist.
     */
    public boolean isTicking() {
        return ticking;
    }

    private void tickInner() {
        tickCount++;
        AE2Perf pf = perf;
        pf.beginTick();
        attachPending();
        pf.endPhase(AE2Perf.Phase.ATTACH);
        boolean detail = pf.detail();
        if (!tickTasks.isEmpty()) {
            tickTasks.removeIf(t -> !t.getAsBoolean());
        }
        for (Node n : nodes.values()) {
            if (n.device != null) {
                long t0 = detail ? System.nanoTime() : 0;
                n.device.tick(this, n);
                if (detail) {
                    pf.addKind(n.kind(), System.nanoTime() - t0);
                }
            }
        }
        pf.endPhase(AE2Perf.Phase.DEVICES);
        me.tick();
        pf.endPhase(AE2Perf.Phase.CORE);
        de.fellurion.ae2.hytale.advancements.AE2Advancements.onNetworkTick(this);
        pf.endPhase(AE2Perf.Phase.ADVANCEMENTS);
        for (Node n : nodes.values()) {
            long t0 = detail ? System.nanoTime() : 0;
            if (n.device != null) {
                n.device.afterCoreTick(this, n);
            }
            updateVisual(n);
            if (detail) {
                pf.addKind(n.kind(), System.nanoTime() - t0);
            }
        }
        pf.endPhase(AE2Perf.Phase.AFTER);
        if (tickCount % 10 == 0) {
            de.fellurion.ae2.hytale.ui.TerminalPage.refreshOpenTerminals();
        }
        de.fellurion.ae2.hytale.crafting.CraftingAccess.refreshOpen(this);
        pf.endPhase(AE2Perf.Phase.UI);
        pf.endTick();
    }

    /** Hängt eine Aufgabe an den Welt-Takt (läuft vor den Geräten, bis sie false liefert). */
    void addTickTask(java.util.function.BooleanSupplier task) {
        tickTasks.add(task);
    }

    /** Zeitmessung dieses Welt-Takts ({@code /ae2 perf}). */
    public AE2Perf perf() {
        return perf;
    }

    /** Zieht den sichtbaren Block-State nach (Kabelform, Online, Conflicted, Füllstand, Working). */
    private void updateVisual(Node n) {
        if (n.device == null && n.gridNode != null && "cable".equals(n.kind())) {
            GridNode g = n.gridNode;
            int level = isSmart(n) ? channelLevel(g.getUsedChannels(), g.getMaxChannels()) : 0;
            int code = g.getConnectionMask() | (level << 6);
            if (code == n.shownCableCode && n.shownState != null) {
                return;
            }
            n.shownCableCode = code;
        }
        String state = desiredState(n);
        if (state == null || state.equals(n.shownState)) {
            return;
        }
        n.shownState = state;
        BlockAccess.setState(world, n.x(), n.y(), n.z(), state);
    }

    @Nullable
    private String desiredState(Node n) {
        if (n.device != null) {
            String s = n.device.visualState(this, n);
            if (s != null) {
                return s;
            }
        }
        GridNode g = n.gridNode;
        if (g == null) {
            return null;
        }
        if ("cable".equals(n.kind())) {
            int mask = g.getConnectionMask();
            int level = isSmart(n) ? channelLevel(g.getUsedChannels(), g.getMaxChannels()) : 0;
            if (level > 0) {
                return "C" + mask + "_L" + level;
            }
            return mask == 0 ? "default" : "C" + mask;
        }
        if (g.isController()) {
            if (g.getGrid() != null && g.getGrid().getPathingService().getControllerState() == ControllerState.CONTROLLER_CONFLICT) {
                return "Conflicted";
            }
            return g.isPowered() ? "Online" : "default";
        }
        return g.isOnline() ? "Online" : "default";
    }

    /** Lichtstufen der Smart-Kabel (passend zu den Kabel-Texturen). */
    static final int CHANNEL_LEVELS = 4;

    private static boolean isSmart(Node n) {
        String t = n.comp.cableType();
        return "smart".equals(t) || "smart_dense".equals(t);
    }

    /**
     * Kanal-Lichtstufe wie AE2s Smart-Kabel: 0 = aus, {@link #CHANNEL_LEVELS} nur bei voller Belegung,
     * dazwischen aufgerundet.
     */
    static int channelLevel(int used, int max) {
        if (used <= 0) {
            return 0;
        }
        if (max <= 0 || used >= max) {
            return CHANNEL_LEVELS;
        }
        int lvl = (int) Math.ceil(CHANNEL_LEVELS * (double) used / max);
        return Math.max(1, Math.min(CHANNEL_LEVELS - 1, lvl));
    }

    /** Farbe der Komponente → Kern-Farbe (fluix = transparent). */
    static final class NetKindsColor {
        private NetKindsColor() {
        }

        @Nullable
        static AEColor of(AE2BlockComponent comp) {
            if (!"cable".equals(comp.kind())) {
                return null;
            }
            AEColor c = AEColor.fromId(comp.color());
            return c == null ? AEColor.TRANSPARENT : c;
        }
    }
}
