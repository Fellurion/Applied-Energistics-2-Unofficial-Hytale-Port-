/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.spatial.SpatialAnchorBlockEntity).
 */
package de.fellurion.ae2.hytale.spatial;

import com.hypixel.hytale.math.util.ChunkUtil;
import de.fellurion.ae2.core.grid.BlockPos;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.spatial.SpatialAnchorLogic;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * Spatial Anchor: hält alle Chunks geladen und aktiv, in denen sein ME-Netz Blöcke hat – solange er Strom und
 * einen Kanal hat. Die Regeln (Verbrauch 80 + n·(n+1)/2 AE/t, 200 Ticks Nachsicht bei Stromausfall, Freigabe beim
 * Entfernen) stehen in {@link SpatialAnchorLogic}; das Laden selbst macht {@link AnchorChunks}.
 *
 * <p>Abweichungen von AE2: Ein Chunk ist hier 32×32 Blöcke groß. Nach einem Server-Neustart sind die Chunks nicht
 * von selbst wieder geladen (siehe {@link AnchorChunks}). Die Chunk-Anzeige (Overlay) gibt es nicht; die Blickanzeige
 * nennt die Zahl der gehaltenen Chunks und den Verbrauch.
 */
final class AnchorDevice implements Device {

    private final AnchorChunks chunks;
    private final SpatialAnchorLogic logic;
    @Nullable
    private WorldNetworks.Node node;
    @Nullable
    private WorldNetworks nets;

    // Chunks des Netzes: nur neu bestimmen, wenn sich in der Welt Blöcke an- oder abgemeldet haben
    @Nullable
    private Grid cachedGrid;
    private long cachedTopology = Long.MIN_VALUE;
    private int cachedSize = -1;
    private Set<Long> cachedChunks = Set.of();

    AnchorDevice(WorldNetworks nets) {
        this.chunks = SpatialWorld.of(nets).anchors;
        this.logic = new SpatialAnchorLogic(new SpatialAnchorLogic.ChunkLoader() {
            @Override
            public boolean forceChunk(long chunk) {
                return chunks.force(chunk);
            }

            @Override
            public boolean releaseChunk(long chunk) {
                return chunks.release(chunk);
            }
        }, new Network());
    }

    SpatialAnchorLogic logic() {
        return logic;
    }

    @Override
    public Object owner() {
        return logic;
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        this.nets = nets;
        this.node = node;
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        if (logic.countLoadedChunks() > 0) {
            chunks.keepAlive(nets.tickCount());
        }
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        // abgebaut oder der eigene Chunk wird entladen: in beiden Fällen hält dieser Anker nichts mehr
        logic.onRemoved();
        this.node = null;
        this.nets = null;
    }

    private final class Network implements SpatialAnchorLogic.Network {
        @Override
        public boolean hasGridBooted() {
            GridNode g = gridNode();
            return g != null && g.hasGridBooted();
        }

        @Override
        public boolean isOnline() {
            GridNode g = gridNode();
            return g != null && g.isOnline();
        }

        @Override
        public Set<Long> chunks() {
            GridNode g = gridNode();
            Grid grid = g == null ? null : g.getGrid();
            WorldNetworks n = nets;
            if (grid == null || n == null) {
                return null;
            }
            long topology = n.topologyVersion();
            if (grid != cachedGrid || topology != cachedTopology || grid.size() != cachedSize) {
                Set<Long> set = new HashSet<>();
                for (GridNode other : grid.getNodes()) {
                    long pos = other.getPos();
                    set.add(ChunkUtil.indexChunkFromBlock(BlockPos.getX(pos), BlockPos.getZ(pos)));
                }
                cachedGrid = grid;
                cachedTopology = topology;
                cachedSize = grid.size();
                cachedChunks = set;
            }
            return cachedChunks;
        }

        @Override
        public void setIdlePowerUsage(double usage) {
            GridNode g = gridNode();
            if (g != null && !g.isRemoved()) {
                g.setIdlePowerUsage(usage);
            }
        }

        @Nullable
        private GridNode gridNode() {
            WorldNetworks.Node n = node;
            return n == null ? null : n.gridNode();
        }
    }
}
