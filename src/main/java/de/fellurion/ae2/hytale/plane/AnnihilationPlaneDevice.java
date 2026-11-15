/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.automation.AnnihilationPlanePart).
 */
package de.fellurion.ae2.hytale.plane;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.automation.AnnihilationPlaneLogic;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Annihilation Plane: baut den Block vor sich ab und sammelt Items ein, die dort liegen; alles geht ins Netz.
 *
 * <p>Die Arbeit macht der Kern ({@link AnnihilationPlaneLogic}) in seinem Netz-Takt; er schläft, solange es nichts
 * zu tun gibt. Dieses Gerät sieht nur in Abständen nach, ob sich vor der Plane etwas geändert hat, und weckt ihn
 * dann (AE2 bekommt dafür Nachbar-Updates und Kollisionen, die es in Hytale für Blöcke nicht gibt):
 * <ul>
 *   <li>alle {@value Planes#PROBE_INTERVAL} Ticks: steht ein anderer Block (oder eine andere Flüssigkeit) vor der
 *   Plane als zuletzt?</li>
 *   <li>alle {@value #ENTITY_INTERVAL} Ticks: liegen Items im Blockraum vor ihr?</li>
 * </ul>
 * Nichts davon lädt Chunks. Ganz oben in der Welt und nach oben gerichtet erzeugt die Plane wie in AE2 Sky Stone
 * Dust, statt etwas abzubauen.
 *
 * <p>Wird die Plane gedreht (Schraubenschlüssel), zeigt sie auf eine andere Stelle: Ziel, Strategien und Beobachtung
 * werden dafür neu angelegt ({@link #onRotated}), als wäre sie dort gerade gesetzt worden.
 */
public final class AnnihilationPlaneDevice implements Device {

    /** So oft wird nach herumliegenden Items gesehen (Ticks); ein Vielfaches von {@link Planes#PROBE_INTERVAL}. */
    static final int ENTITY_INTERVAL = 10;
    /** AE2: 1 Sky Stone Dust alle 200 Ticks. */
    private static final String SKY_DUST = "AE2_Sky_Dust";
    private static final int SKY_DUST_TICKS = 200;
    private static final int UNKNOWN = Integer.MIN_VALUE;

    private final AnnihilationPlaneLogic logic = new AnnihilationPlaneLogic();
    private final Runnable changed = this::blockChanged;
    private final List<Ref<EntityStore>> scratch = new ObjectArrayList<>();
    private final Vector3d min = new Vector3d();
    private final Vector3d max = new Vector3d();
    @Nullable
    private PlaneTarget target;
    private int phase;
    private int lastProbe = UNKNOWN;
    /** Die zuletzt angebotenen Items – liegen sie beim nächsten Nachsehen noch da, ist das Netz voll. */
    private List<PlaneItems.WorldItem> offered = List.of();
    private int stuckStreak;
    private int skipChecks;

    @Override
    public Object owner() {
        return logic;
    }

    public AnnihilationPlaneLogic logic() {
        return logic;
    }

    /** Die Stelle vor der Plane; null, solange die Ausrichtung unbekannt ist. */
    @Nullable
    public PlaneTarget target() {
        return target;
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        phase = Planes.phase(node, ENTITY_INTERVAL);
        aim(nets, node);
    }

    /** Gedreht: Die Vorderseite zeigt auf eine andere Stelle – die Plane arbeitet ab sofort dort. */
    @Override
    public void onRotated(WorldNetworks nets, WorldNetworks.Node node) {
        aim(nets, node);
    }

    /** Richtet die Plane auf die Stelle vor ihrer Vorderseite aus; ein früheres Ziel wird dabei aufgegeben. */
    private void aim(WorldNetworks nets, WorldNetworks.Node node) {
        PlaneTarget old = target;
        if (old != null) {
            Planes.unwatch(nets, old, changed);
        }
        target = null;
        lastProbe = UNKNOWN; // beim nächsten Nachsehen gilt die neue Stelle als geändert: der Kern sieht hin
        offered = List.of();
        stuckStreak = 0;
        skipChecks = 0;
        logic.setPickupStrategies(List.of());
        logic.setEntitySource(null);
        logic.setContinuousGeneration(null, 0, 0);
        Dir facing = node.facing();
        if (facing == null) {
            return;
        }
        PlaneTarget t = new PlaneTarget(nets, node, facing);
        target = t;
        logic.setPickupStrategies(Planes.createPickupStrategies(t));
        logic.setEntitySource(() -> offered = PlaneItems.inFront(t, min, max, scratch));
        // AE2: an der Bauhöhe nach oben gerichtet entsteht stetig Sky Stone Dust
        if (PlaneConfig.skyDustGeneration() && facing == Dir.UP && t.y() >= PlaneTarget.WORLD_HEIGHT
                && Item.getAssetMap().getAsset(SKY_DUST) != null) {
            logic.setContinuousGeneration(AEItemKey.of(SKY_DUST), 1, SKY_DUST_TICKS);
        }
        Planes.watch(nets, t, changed);
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        PlaneTarget t = target;
        if (t == null) {
            return;
        }
        long tick = nets.tickCount() + phase;
        if (tick % Planes.PROBE_INTERVAL != 0) {
            return;
        }
        GridNode grid = node.gridNode();
        if (grid == null || !grid.isActive()) {
            lastProbe = UNKNOWN; // beim Wiedereinschalten neu hinsehen
            return;
        }
        int probe = t.probe();
        if (probe != lastProbe) {
            lastProbe = probe;
            logic.onNeighborChanged();
        }
        if (tick % ENTITY_INTERVAL == 0 && probe != PlaneTarget.NOT_LOADED && !logic.isContinuouslyGenerating()) {
            lookForItems(t);
        }
    }

    /**
     * Meldet dem Kern Items vor der Plane. Liegen dieselben Items danach immer noch da (das Netz ist voll), wird
     * seltener nachgesehen: nach 1, 2, 4, höchstens 8 Intervallen.
     */
    private void lookForItems(PlaneTarget t) {
        if (skipChecks > 0) {
            skipChecks--;
            return;
        }
        boolean stuck = false;
        for (PlaneItems.WorldItem item : offered) {
            if (item.isAlive()) {
                stuck = true;
                break;
            }
        }
        offered = List.of();
        stuckStreak = stuck ? Math.min(stuckStreak + 1, 4) : 0;
        PlaneItems.collectInFront(t, min, max, scratch);
        if (scratch.isEmpty()) {
            stuckStreak = 0;
            return;
        }
        scratch.clear();
        skipChecks = stuckStreak == 0 ? 0 : (1 << (stuckStreak - 1)) - 1;
        logic.onEntitiesDetected();
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        PlaneTarget t = target;
        if (t != null) {
            Planes.unwatch(nets, t, changed);
        }
    }

    /** Jemand hat gemeldet, dass sich der Block vor der Plane geändert hat. */
    private void blockChanged() {
        PlaneTarget t = target;
        if (t != null) {
            lastProbe = t.probe();
            logic.onNeighborChanged();
        }
    }
}
