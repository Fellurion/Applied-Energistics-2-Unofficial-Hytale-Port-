/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.p2p.LightP2PTunnelPart).
 */
package de.fellurion.ae2.hytale.p2p;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightData;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.p2p.P2PTunnelLogic;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.hud.HudContext;
import de.fellurion.ae2.hytale.hud.LookInfo;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;

/**
 * Licht-P2P-Tunnel: der Eingang misst die Helligkeit vor sich, die Ausgänge leuchten entsprechend.
 *
 * <p>Der Eingang liest einmal je Sekunde die Helligkeit des Blocks vor sich (0–15; das Hellere aus Blocklicht und
 * Himmelslicht × Tageslicht, wie Minecrafts {@code getMaxLocalRawBrightness}). Ein Ausgang leuchtet mit diesem Wert,
 * vermindert um die Helligkeit, die vor ihm ohnehin herrscht (AE2: {@code blockLight}), solange der Eingang aktiv und
 * der Ausgang versorgt ist. Leuchten heißt hier: der Block wechselt in den State {@code L1}…{@code L15}, der Licht der
 * passenden Stärke abgibt (States des Items {@code AE2_Light_P2P_Tunnel}).
 *
 * <p>Abweichungen von AE2: feste Abfrage einmal je Sekunde statt 5–60 Ticks nach Bedarf; die Helligkeit vor dem
 * Ausgang wird neu gemessen, solange er nicht leuchtet (AE2: bei Nachbar-Updates) – und erst, wenn er seit zwei
 * Sekunden dunkel ist, damit sein eigenes, noch nicht abgeklungenes Licht nicht mitgemessen wird. Der zuletzt
 * gemessene Wert wird nicht gespeichert – nach dem Laden misst der Eingang binnen einer Sekunde neu. Wird ein Ausgang
 * gedreht (Schraubenschlüssel), gilt die gemerkte Helligkeit vor ihm nicht mehr: er geht aus und misst vor der neuen
 * Vorderseite neu, bevor er wieder leuchtet.
 */
final class LightTunnel implements TunnelBehaviour {

    private static final int INTERVAL = 20;
    private static final int DARK_CYCLES = 2;
    private static final String[] STATES = new String[16];

    static {
        for (int i = 1; i < STATES.length; i++) {
            STATES[i] = "L" + i;
        }
    }

    /** Eingang: gemessene Helligkeit. Ausgang: vom Eingang übernommener Wert. */
    private int level;
    /** Ausgang: Helligkeit vor dem Tunnel, gemessen solange er nicht leuchtet. */
    private int ambient;
    /** Ausgang: abgegebene Lichtstärke (0 = aus). */
    private int emitted;
    /** Ausgang: so viele Abfragen in Folge war er dunkel (erst dann gilt die Umgebung als frei von eigenem Licht). */
    private int darkCycles = DARK_CYCLES;
    /** Die Seite, vor der {@link #ambient} gemessen wurde. */
    @Nullable
    private Dir measuredFacing;

    @Override
    public void tick(TunnelDevice device, WorldNetworks nets, WorldNetworks.Node node) {
        if (nets.tickCount() % INTERVAL != (node.pos & 15)) {
            return;
        }
        P2PTunnelLogic logic = device.logic();
        Dir f = node.facing();
        if (f == null) {
            level = 0;
            emitted = 0;
            return;
        }
        int fx = node.x() + f.dx, fy = node.y() + f.dy, fz = node.z() + f.dz;
        if (!logic.isOutput()) {
            emitted = 0;
            level = logic.isActive() ? brightness(nets.world(), fx, fy, fz) : 0;
            return;
        }
        if (f != measuredFacing) {
            // gedreht (oder gerade angemeldet): Die Helligkeit vor der Vorderseite ist neu zu messen – und zwar erst,
            // wenn das eigene Licht abgeklungen ist
            boolean first = measuredFacing == null;
            measuredFacing = f;
            if (!first) {
                emitted = 0;
                darkCycles = 0;
                ambient = STATES.length - 1; // gilt als taghell, bis neu gemessen ist: so lange leuchtet er nicht
                return;
            }
        }
        P2PTunnelLogic input = logic.getInput();
        TunnelDevice source = input == null || !input.isActive() ? null : P2P.device(nets, input);
        GridNode g = node.gridNode();
        boolean lit = source != null && source.behaviour() instanceof LightTunnel && g != null && g.isPowered();
        level = lit ? ((LightTunnel) source.behaviour()).level : 0;
        if (emitted == 0 && darkCycles >= DARK_CYCLES) {
            ambient = brightness(nets.world(), fx, fy, fz);
        }
        emitted = Math.max(0, level - ambient);
        darkCycles = emitted > 0 ? 0 : Math.min(DARK_CYCLES, darkCycles + 1);
    }

    @Override
    public void onRemoved(TunnelDevice device, WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        level = 0;
        emitted = 0;
    }

    @Nullable
    @Override
    public String visualState(TunnelDevice device, WorldNetworks nets, WorldNetworks.Node node) {
        return emitted > 0 ? STATES[Math.min(emitted, 15)] : null;
    }

    @Override
    public void describe(TunnelDevice device, HudContext ctx, WorldNetworks.Node node, LookInfo.Builder out) {
        out.line(TerminalTexts.tr(ctx.locale(), "ae2p2p.hud.light"),
                String.valueOf(device.logic().isOutput() ? emitted : level));
    }

    @Override
    public String debugState() {
        return "light=" + level + " emitted=" + emitted;
    }

    /** Helligkeit 0–15 an der Stelle; 0, wenn der Chunk nicht geladen ist. */
    static int brightness(World world, int x, int y, int z) {
        ChunkStore cs = world.getChunkStore();
        Ref<ChunkStore> sec = cs.getChunkSectionReferenceAtBlock(x, y, z);
        if (sec == null || !sec.isValid()) {
            return 0;
        }
        BlockSection bs = cs.getStore().getComponent(sec, BlockSection.getComponentType());
        if (bs == null) {
            return 0;
        }
        ChunkLightData light = bs.getGlobalLight();
        int block = light.getBlockLightIntensity(x, y, z);
        int sky = light.getSkyLight(x, y, z);
        if (sky > block) {
            WorldTimeResource time = world.getEntityStore().getStore().getResource(WorldTimeResource.getResourceType());
            sky = time == null ? sky : (int) (sky * time.getSunlightFactor());
        }
        return Math.max(block, sky);
    }
}
