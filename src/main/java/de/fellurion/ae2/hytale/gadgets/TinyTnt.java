/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.block.misc.TinyTNTBlock, appeng.entity.TinyTNTPrimedEntity).
 */
package de.fellurion.ae2.hytale.gadgets;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.ExplosionConfig;
import com.hypixel.hytale.server.core.entity.ExplosionUtils;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.ExplodeInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import de.fellurion.ae2.core.tools.TinyTntLogic;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.meteorite.BuildProtection;
import de.fellurion.ae2.hytale.signal.Signals;
import de.fellurion.ae2.hytale.transform.ExplosionTransforms;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Tiny TNT: eine kleine Sprengladung. Gezündet wird sie durch Benutzen (F), durch ein AE2-Signal am Block, durch den
 * Entropy Manipulator, durch Feuer und durch eine andere Explosion; nach der Zündschnur ({@value
 * TinyTntLogic#FUSE_TICKS} Ticks, von einer Explosion gezündet 10 bis 29) explodiert sie.
 *
 * <p>Die Explosion selbst ist Hytales eigene ({@link ExplosionUtils#performExplosion}) mit den Werten aus der
 * Item-Definition ({@code BlockType.ExplosionConfig} von {@code AE2_Tiny_Tnt}): Blockschaden, Schaden an Wesen,
 * Beute und Kettenreaktionen folgen damit den Regeln des Spiels. Vorher werden Gegenstände in Reichweite umgewandelt
 * ({@link ExplosionTransforms}).
 *
 * <p>Abweichungen von AE2: Die gezündete Ladung bleibt als Block stehen (Zustand {@value #STATE_LIT}) statt als
 * Wesen umherzuhüpfen; Redstone ist das AE2-Signal ({@link Signals}); steht die Ladung in einer Flüssigkeit, geht
 * die Zündschnur aus (AE2 lässt sie dort als Item fallen). In einem geschützten Gebiet (Claim), in das die Ladung
 * von außen hineinwirken würde, richtet die Explosion keinen Schaden an
 * ({@link BuildProtection#deviceMayModify(World, int, int, int, int, int, int)}).
 */
final class TinyTnt {

    static final String KIND = "tiny_tnt";
    static final String ITEM = "AE2_Tiny_Tnt";
    /** Block-State der gezündeten Ladung; denselben Namen setzen Hytales Feuer und Explosionen. */
    static final String STATE_LIT = "Exploding";
    private static final String FUSE_KEY = "fuse";
    private static final String FIRE = "Fire";

    @Nullable
    private static volatile HytaleLogger logger;
    private TinyTnt() {
    }

    static void setup(HytaleLogger log) {
        logger = log;
    }

    /** Die Ladung an einer Stelle. */
    static final class TntDevice implements Device {

        /** Nicht gezündet. */
        private static final int IDLE = -1;
        /** Zündschnur abgebrannt, die Explosion ist eingeplant. */
        private static final int FIRED = -2;

        private int fuse = IDLE;
        private long signalVersion = -1;
        @Nullable
        private Signals signals;

        @Override
        public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
            signals = Signals.of(nets.world());
            String saved = node.comp.config().get(FUSE_KEY);
            if (saved != null) {
                try {
                    fuse = Math.max(0, Integer.parseInt(saved));
                } catch (NumberFormatException e) {
                    fuse = TinyTntLogic.FUSE_TICKS;
                }
                return;
            }
            if (STATE_LIT.equals(BlockAccess.currentState(nets.world(), node.x(), node.y(), node.z()))) {
                // Hytale hat den Block gezündet: Feuer (volle Zündschnur, AE2: onCaughtFire) oder eine Explosion
                // (kurze Zündschnur, AE2: wasExploded)
                light(node, nextToFire(nets.world(), node.x(), node.y(), node.z()) ? TinyTntLogic.FUSE_TICKS
                        : TinyTntLogic.chainFuse(ThreadLocalRandom.current()));
            }
        }

        @Override
        public void tick(WorldNetworks nets, WorldNetworks.Node node) {
            if (fuse == IDLE) {
                // AE2 (neighborChanged/onPlace): ein anliegendes Signal zündet. Geprüft wird nur, wenn sich an den
                // Signalen der Welt etwas geändert hat.
                Signals s = signals;
                if (s != null && s.version() != signalVersion) {
                    signalVersion = s.version();
                    if (s.isPowered(node.x(), node.y(), node.z())) {
                        ignite(nets, node);
                    }
                }
                return;
            }
            if (fuse > 0) {
                fuse--;
                return;
            }
            if (fuse == 0) {
                fuse = FIRED;
                World world = nets.world();
                int x = node.x(), y = node.y(), z = node.z();
                // Blöcke ändern sich nicht mitten im Tick der Chunk-Systeme (wie Hytales eigene Sprengblöcke)
                world.execute(() -> detonate(world, x, y, z));
            }
        }

        @Override
        public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
            return fuse == IDLE ? "default" : STATE_LIT;
        }

        boolean isLit() {
            return fuse != IDLE;
        }

        int fuse() {
            return fuse;
        }

        /**
         * Zündet die Ladung.
         *
         * @return false, wenn sie schon brennt
         */
        boolean ignite(WorldNetworks nets, WorldNetworks.Node node) {
            if (fuse != IDLE) {
                return false;
            }
            light(node, TinyTntLogic.FUSE_TICKS);
            Feedback.sound(nets.world(), "SFX_Flame_Ignite", node.x() + 0.5, node.y() + 0.5, node.z() + 0.5);
            return true;
        }

        private void light(WorldNetworks.Node node, int ticks) {
            fuse = ticks;
            // überlebt das Entladen des Chunks: danach brennt die Zündschnur von vorn
            node.comp.config().put(FUSE_KEY, Integer.toString(ticks));
            node.markDirty();
        }

        /** Die Zündschnur geht aus (Ladung steht in einer Flüssigkeit). */
        private void putOut(WorldNetworks.Node node) {
            fuse = IDLE;
            node.comp.config().remove(FUSE_KEY);
            node.markDirty();
        }
    }

    /** Die Ladung an der Stelle, sobald sie angemeldet ist; sonst null. */
    @Nullable
    static TntDevice at(World world, int x, int y, int z) {
        WorldNetworks.Node node = WorldNetworks.of(world).node(x, y, z);
        return node != null && node.device() instanceof TntDevice tnt ? tnt : null;
    }

    /**
     * Zündet die Ladung an der Stelle.
     *
     * @return true, wenn dort eine Ladung steht und sie jetzt (oder schon vorher) brennt
     */
    static boolean ignite(World world, int x, int y, int z) {
        WorldNetworks nets = WorldNetworks.of(world);
        WorldNetworks.Node node = nets.node(x, y, z);
        if (node == null || !(node.device() instanceof TntDevice tnt)) {
            return false;
        }
        tnt.ignite(nets, node);
        return true;
    }

    private static boolean nextToFire(World world, int x, int y, int z) {
        for (BlockAccess.Dir d : BlockAccess.Dir.values()) {
            if (isFire(WorldEdits.fluidKey(world, x + d.dx, y + d.dy, z + d.dz))) {
                return true;
            }
        }
        return isFire(WorldEdits.fluidKey(world, x, y, z));
    }

    private static boolean isFire(@Nullable String fluidKey) {
        return fluidKey != null && fluidKey.contains(FIRE);
    }

    /** Die Zündschnur ist abgebrannt. Läuft zwischen zwei Ticks auf dem Welt-Thread. */
    private static void detonate(World world, int x, int y, int z) {
        try {
            WorldNetworks nets = WorldNetworks.of(world);
            WorldNetworks.Node node = nets.node(x, y, z);
            if (node == null || !(node.device() instanceof TntDevice tnt) || tnt.fuse() != TntDevice.FIRED) {
                return; // inzwischen abgebaut
            }
            String fluid = WorldEdits.fluidKey(world, x, y, z);
            if (fluid != null && !isFire(fluid)) {
                tnt.putOut(node); // AE2: im Wasser explodiert nichts
                Feedback.sound(world, "SFX_Flame_Break", x + 0.5, y + 0.5, z + 0.5);
                return;
            }
            explode(world, x, y, z);
        } catch (RuntimeException e) {
            HytaleLogger log = logger;
            if (log != null) {
                log.at(Level.WARNING).withCause(e).log("AE2: Tiny TNT at %d %d %d failed", x, y, z);
            }
        }
    }

    /**
     * Entfernt die Ladung und lässt sie explodieren. Nur zwischen zwei Ticks aufrufen ({@code world.execute}).
     */
    private static void explode(World world, int x, int y, int z) {
        BlockType base = BlockType.getAssetMap().getAsset(ITEM);
        ExplosionConfig config = base == null ? null : base.getExplosionConfig();
        // AE2 (dropFromExplosion): die Ladung selbst hinterlässt nichts
        BlockAccess.setBlock(world, x, y, z, BlockAccess.EMPTY, 0, BlockAccess.QUIET);
        Vector3d center = new Vector3d(x + 0.5, y + 0.5, z + 0.5);
        // Umwandlungen zuerst: sie betreffen nur lose Gegenstände und gelten auch dort, wo nichts beschädigt wird
        ExplosionTransforms.transformAround(world, center, TinyTntLogic.ENTITY_RANGE);
        int r = TinyTntLogic.BLOCK_RANGE;
        boolean allowed = config != null && BuildProtection.deviceMayModify(world, x, z, x - r, z - r, x + r, z + r);
        if (!allowed) {
            // kein Schaden: nur Knall und Rauch
            Feedback.particle(world, "Explosion_Small", center.x, center.y, center.z);
            Feedback.sound(world, "SFX_Explosion_Boomshroom_Barrel", center.x, center.y, center.z);
            return;
        }
        ExplosionConfig use = GadgetConfig.tinyTntBlockDamage() ? config : withoutBlockDamage(config);
        ExplosionTransforms.alreadyHandled(() -> ExplosionUtils.performExplosion(ExplodeInteraction.DAMAGE_SOURCE_EXPLOSION,
                center, new Rotation3f(0.0f, 0.0f, 0.0f), use, null, world.getEntityStore().getStore(),
                world.getChunkStore().getStore()));
    }

    /**
     * Dieselbe Explosion ohne Blockschaden (Schalter {@code tinyTntBlockDamage}, AE2: {@code BlockInteraction.KEEP}):
     * die Werte der Item-Definition, nur {@code DamageBlocks} aus.
     */
    private static ExplosionConfig withoutBlockDamage(ExplosionConfig config) {
        return ExplosionConfig.CODEC.decodeAndInherit(new BsonDocument("DamageBlocks", BsonBoolean.FALSE), config,
                new ExtraInfo());
    }
}
