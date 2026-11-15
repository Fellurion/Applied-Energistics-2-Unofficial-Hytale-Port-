/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.block.misc.LightDetectorBlock, appeng.blockentity.misc.LightDetectorBlockEntity).
 */
package de.fellurion.ae2.hytale.emitter;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightData;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.signal.Signals;

/**
 * Light Detecting Fixture: sendet das AE2-Signal ({@link Signals}), solange es an seiner Stelle hell genug ist.
 *
 * <p>Wie in AE2 wird die Helligkeit alle 31 Ticks gelesen ({@code LightDetectorBlockEntity.commonTick}); das Signal
 * ist dort {@code Helligkeit − 6}, hier also „an“ ab Helligkeit 7 von 15. Die Helligkeit ist das Maximum aus
 * Blocklicht und Himmelslicht mal Sonnenstand (Minecraft: {@code getMaxLocalRawBrightness}) und kommt aus den
 * Lichtdaten der Chunk-Sektion – ein Lesezugriff ohne Suche.
 *
 * <p>Abweichungen: kein abgestuftes Signal (das AE2-Signal kennt nur an/aus); Nachbaränderungen lösen keine
 * sofortige Messung aus, die nächste kommt spätestens nach 31 Ticks. Der Block ist kein Netzgerät.
 */
public final class LightDetectorDevice implements Device {

    /** AE2: {@code lastCheck > 30}. */
    static final int INTERVAL_TICKS = 31;
    /** AE2: Signal = Helligkeit − 6. */
    static final int SIGNAL_OFFSET = 6;
    /** Höchste Helligkeit (Hytale wie Minecraft). */
    static final int MAX_LIGHT = ChunkLightData.MAX_VALUE;

    private final Signals signals;
    private final int phase;
    /** Zuletzt gemessene Helligkeit 0..15, oder −1 vor der ersten Messung. */
    private int lastLight = -1;
    private boolean emitting;

    LightDetectorDevice(WorldNetworks nets, WorldNetworks.Node node) {
        this.signals = Signals.of(nets.world());
        this.phase = (int) Math.floorMod(node.pos, (long) INTERVAL_TICKS);
    }

    /** Zuletzt gemessene Helligkeit 0..15, oder −1, solange noch nicht gemessen wurde. */
    public int light() {
        return lastLight;
    }

    public boolean isEmitting() {
        return emitting;
    }

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        updateLight(nets, node);
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        if (nets.tickCount() % INTERVAL_TICKS == phase) {
            updateLight(nets, node);
        }
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        emitting = false;
        signals.setEmitting(node.x(), node.y(), node.z(), false);
    }

    /** Misst neu und meldet eine geänderte Ausgabe; ohne lesbare Lichtdaten bleibt alles, wie es ist. */
    void updateLight(WorldNetworks nets, WorldNetworks.Node node) {
        int light = lightAt(nets.world(), node.x(), node.y(), node.z());
        if (light < 0) {
            return;
        }
        lastLight = light;
        boolean on = light - SIGNAL_OFFSET > 0;
        if (on != emitting) {
            emitting = on;
            signals.setEmitting(node.x(), node.y(), node.z(), on);
        }
    }

    /**
     * Helligkeit 0..15 an der Blockposition: das Maximum aus Blocklicht und Himmelslicht mal Sonnenstand. −1, wenn
     * die Sektion nicht geladen ist oder ihr Licht gerade neu berechnet wird.
     *
     * <p>Gelesen wird das fertige Licht der Sektion. Das berechnet die Engine erst, wenn auch die Nachbar-Chunks
     * geladen sind; bis dahin (etwa in einem einzeln geladen gehaltenen Chunk) gilt das Licht, das die Sektion für
     * sich allein hat – Himmelslicht und eigene Lichtquellen, ohne das, was von nebenan hereinscheint.
     */
    public static int lightAt(World world, int x, int y, int z) {
        ChunkStore cs = world.getChunkStore();
        Ref<ChunkStore> section = cs.getChunkSectionReferenceAtBlock(x, y, z);
        if (section == null || !section.isValid()) {
            return -1;
        }
        BlockSection blocks = cs.getStore().getComponent(section, BlockSection.getComponentType());
        if (blocks == null) {
            return -1;
        }
        ChunkLightData data;
        if (blocks.hasGlobalLight()) {
            data = blocks.getGlobalLight();
        } else if (blocks.hasLocalLight()) {
            data = blocks.getLocalLight();
        } else {
            return -1;
        }
        int block = data.getBlockLightIntensity(x, y, z);
        int sky = data.getSkyLight(x, y, z);
        if (sky > 0) {
            WorldTimeResource time = world.getEntityStore().getStore().getResource(WorldTimeResource.getResourceType());
            sky = time == null ? 0 : (int) (sky * time.getSunlightFactor());
        }
        return Math.max(0, Math.min(MAX_LIGHT, Math.max(block, sky)));
    }
}
