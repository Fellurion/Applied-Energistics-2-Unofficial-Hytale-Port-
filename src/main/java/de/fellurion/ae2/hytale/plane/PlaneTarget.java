/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: Position und Seite, mit denen AE2 seine Pickup-/PlacementStrategy erzeugt).
 */
package de.fellurion.ae2.hytale.plane;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EnvironmentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.meteorite.BuildProtection;

import javax.annotation.Nullable;

/**
 * Die Blockstelle vor einer Plane: Position, ob sie geladen ist und ob die Plane dort etwas verändern darf.
 * Alle Strategien einer Plane (Items, später Flüssigkeiten) arbeiten auf demselben Ziel.
 *
 * <p>Eine Plane hat keinen Spieler, den ein Schutz-Plugin fragen könnte; deshalb entscheidet
 * {@link BuildProtection#deviceMayModify}. Die Antwort wird je Spalte {@value #PROTECTION_CACHE_TICKS} Ticks lang
 * gemerkt (die Abfrage läuft über Reflection).
 */
public final class PlaneTarget {

    /** So lange gilt eine Antwort des Bauschutzes (5 s). */
    static final int PROTECTION_CACHE_TICKS = 100;
    /** Bauhöhe der Welt: gültige y-Werte sind 0 bis 319. */
    static final int WORLD_HEIGHT = 320;
    /** {@link #probe()} und {@link #blockId()}: Stelle nicht geladen oder außerhalb der Welt. */
    public static final int NOT_LOADED = -1;

    private final WorldNetworks nets;
    private final WorldNetworks.Node plane;
    private final int planeX, planeY, planeZ;
    private final BlockAccess.Dir facing;
    private final int x, y, z;

    private long protectionTick = Long.MIN_VALUE;
    private int protectionX, protectionZ;
    private boolean protectionAllows;

    public PlaneTarget(WorldNetworks nets, WorldNetworks.Node plane, BlockAccess.Dir facing) {
        this.nets = nets;
        this.plane = plane;
        this.planeX = plane.x();
        this.planeY = plane.y();
        this.planeZ = plane.z();
        this.facing = facing;
        this.x = planeX + facing.dx;
        this.y = planeY + facing.dy;
        this.z = planeZ + facing.dz;
    }

    public WorldNetworks nets() {
        return nets;
    }

    public World world() {
        return nets.world();
    }

    /** Der Block der Plane selbst. */
    public WorldNetworks.Node plane() {
        return plane;
    }

    /** Richtung von der Plane zum Ziel. */
    public BlockAccess.Dir facing() {
        return facing;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public int planeX() {
        return planeX;
    }

    public int planeY() {
        return planeY;
    }

    public int planeZ() {
        return planeZ;
    }

    /** Liegt das Ziel innerhalb der Bauhöhe? */
    public boolean inWorld() {
        return y >= 0 && y < WORLD_HEIGHT;
    }

    /** Sektion des Ziels; null, wenn der Chunk nicht geladen ist (er wird nie geladen). */
    @Nullable
    public Ref<ChunkStore> section() {
        return section(x, y, z);
    }

    /** Sektion einer beliebigen Stelle; null, wenn nicht geladen oder außerhalb der Bauhöhe. */
    @Nullable
    public Ref<ChunkStore> section(int bx, int by, int bz) {
        if (by < 0 || by >= WORLD_HEIGHT) {
            return null;
        }
        Ref<ChunkStore> ref = world().getChunkStore().getChunkSectionReferenceAtBlock(bx, by, bz);
        return ref == null || !ref.isValid() ? null : ref;
    }

    @Nullable
    public BlockSection blocks(Ref<ChunkStore> section) {
        return world().getChunkStore().getStore().getComponent(section, BlockSection.getComponentType());
    }

    /** Block-Id am Ziel (0 = Luft) oder {@link #NOT_LOADED}. Liest nur die Sektion, lädt nichts. */
    public int blockId() {
        Ref<ChunkStore> section = section();
        BlockSection blocks = section == null ? null : blocks(section);
        return blocks == null ? NOT_LOADED : blocks.get(x, y, z);
    }

    /**
     * Kennzahl dessen, was am Ziel steht, für die Änderungserkennung: ändert sich der Block, die Flüssigkeit oder
     * ihr Pegel, ändert sich (praktisch immer) auch die Zahl. {@link #NOT_LOADED}, solange die Stelle nicht geladen
     * ist; sonst nie negativ. Liest nur die Sektion, lädt nichts.
     */
    public int probe() {
        Ref<ChunkStore> section = section();
        BlockSection blocks = section == null ? null : blocks(section);
        if (blocks == null) {
            return NOT_LOADED;
        }
        int signature = blocks.get(x, y, z);
        FluidSection fluids = world().getChunkStore().getStore().getComponent(section, FluidSection.getComponentType());
        if (fluids != null) {
            int fluid = fluids.getFluidId(x, y, z);
            if (fluid != 0) {
                signature = signature * 31 + fluid * 4099 + fluids.getFluidLevel(x, y, z) + 1;
            }
        }
        return signature & Integer.MAX_VALUE;
    }

    /** Darf die Plane am Ziel Blöcke verändern (Bauschutz anderer Plugins)? */
    public boolean mayModify() {
        return mayModify(x, z);
    }

    /** Wie {@link #mayModify()} für eine andere Spalte (Basis eines mehrteiligen Blocks). */
    public boolean mayModify(int bx, int bz) {
        long now = nets.tickCount();
        if (bx != protectionX || bz != protectionZ || protectionTick == Long.MIN_VALUE
                || now - protectionTick >= PROTECTION_CACHE_TICKS || now < protectionTick) {
            protectionX = bx;
            protectionZ = bz;
            protectionTick = now;
            boolean allowed;
            try {
                allowed = BuildProtection.deviceMayModify(world(), planeX, planeZ, bx, bz);
            } catch (RuntimeException | LinkageError e) {
                allowed = false; // im Zweifel für den Schutz
            }
            protectionAllows = allowed;
        }
        return protectionAllows;
    }

    /** Erlaubt die Umgebung (etwa eine Dungeon-Zone) dort Blockänderungen? */
    public boolean environmentAllows(Ref<ChunkStore> section, int bx, int by, int bz) {
        EnvironmentSection env = world().getChunkStore().getStore().getComponent(section, EnvironmentSection.getComponentType());
        if (env == null) {
            return true;
        }
        Environment environment = Environment.getAssetMap().getAsset(env.get(bx, by, bz));
        return environment == null || environment.isBlockModificationAllowed();
    }
}
