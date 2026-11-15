/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.meteorite;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.camera.CameraEffect;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import de.fellurion.ae2.core.worldgen.Climate;
import de.fellurion.ae2.core.worldgen.CraterType;
import de.fellurion.ae2.core.worldgen.FalloutMode;
import de.fellurion.ae2.core.worldgen.MeteoriteBlock;
import de.fellurion.ae2.core.worldgen.MeteoriteLocator;
import de.fellurion.ae2.core.worldgen.MeteoritePlacer;
import de.fellurion.ae2.core.worldgen.MeteoriteSite;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/**
 * Das Einschlag-Ereignis: In Welten, in denen es eingeschaltet ist, fällt ab und zu in der Nähe eines Spielers ein
 * Meteorit vom Himmel – mit Vorwarnung, Feuerschweif, Einschlag und einem frischen Krater samt Meteorit.
 * Vor dem Start und noch einmal direkt vor dem Einschlag prüft der {@link BuildProtection Bauschutz} den Ort.
 *
 * <p>Derselbe Ablauf (ohne Effekte) baut auch Meteoriten der Weltgenerierung nach, die in Welten ohne
 * Standard-Generator nur vorgemerkt werden konnten. Alles läuft auf dem Welt-Thread ({@link #tick}).
 */
public final class MeteoriteImpacts {

    /** Dauer des Falls in AE2-Ticks (20 je Sekunde). */
    private static final int FALL_TICKS = 110;
    /** So viele Blöcke werden je Tick gesetzt, damit der Server beim Einschlag nicht hängt. */
    private static final int BLOCKS_PER_TICK = 1500;
    /** Bis hierhin hören und sehen Spieler den Einschlag. */
    private static final double AUDIENCE = 260;
    private static final int DEFAULT_SEA_LEVEL = 115;

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    @Nullable
    private static volatile BiConsumer<String, Throwable> warn;

    /** Zustand je Welt. */
    private static final class State {
        long ticks;
        long nextAttempt = -1;
        int failedAttempts;
        @Nullable
        String lastReason;
        @Nullable
        Impact active;
    }

    /** Ein laufender Einschlag. */
    private static final class Impact {
        final MeteoriteSite site;
        final boolean silent;
        final Vector3d from;
        int fallTicks;
        @Nullable
        List<WorldTerrain.Change> queue;
        int applied;

        Impact(MeteoriteSite site, boolean silent, int surfaceY) {
            this.site = site;
            this.silent = silent;
            this.fallTicks = silent ? 0 : FALL_TICKS;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double angle = r.nextDouble(Math.PI * 2);
            // schräger Anflug aus großer Höhe
            this.from = new Vector3d(site.x() + Math.cos(angle) * 90, surfaceY + 170, site.z() + Math.sin(angle) * 90);
        }
    }

    private MeteoriteImpacts() {
    }

    static void setup(BiConsumer<String, Throwable> warning, java.util.function.Consumer<String> information) {
        warn = warning;
        info = information;
    }

    @Nullable
    private static volatile java.util.function.Consumer<String> info;

    private static void info(String message) {
        java.util.function.Consumer<String> i = info;
        if (i != null) {
            i.accept(message);
        }
    }

    /** Nach {@code /ae2 meteorites impacts|interval}: den nächsten Einschlag neu planen. */
    static void settingsChanged(World world) {
        State s = STATES.get(key(world));
        if (s != null) {
            s.nextAttempt = -1;
        }
    }

    private static String key(World world) {
        return world.getName().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ Takt

    /** Einmal je AE2-Tick und Welt (Welt-Thread). */
    public static void tick(World world) {
        State state = STATES.computeIfAbsent(key(world), k -> new State());
        state.ticks++;
        try {
            if (state.ticks % 20 == 0) {
                MeteoriteCompass.tick(world);
            }
            if (state.active != null) {
                advance(world, state);
                return;
            }
            if (state.ticks % 40 != 0) {
                return;
            }
            MeteoriteConfig.Settings settings = MeteoriteConfig.of(world.getName());
            if (placePending(world, state, settings)) {
                return;
            }
            if (!settings.impacts() || world.getPlayerCount() == 0) {
                return;
            }
            long interval = settings.intervalMinutes() * 60L * 20L;
            if (state.nextAttempt < 0) {
                // erster Einschlag nach dem Einschalten/Serverstart: irgendwann im ersten Intervall
                state.nextAttempt = state.ticks + ThreadLocalRandom.current().nextLong(Math.max(1, interval / 4), interval + 1);
                return;
            }
            if (state.ticks < state.nextAttempt) {
                return;
            }
            // Je Durchgang nur ein Versuch, damit die Suche den Server nicht aufhält
            String[] why = new String[1];
            Impact impact = trySite(world, settings, null, why);
            state.lastReason = why[0];
            if (impact != null) {
                state.failedAttempts = 0;
                start(world, state, impact);
                state.nextAttempt = state.ticks + (long) (interval * (0.75 + ThreadLocalRandom.current().nextDouble(0.5)));
            } else if (++state.failedAttempts < 6) {
                state.nextAttempt = state.ticks + 40;
            } else {
                // kein geeigneter Ort in Reichweite: in ein paar Minuten wieder versuchen
                info("AE2: no free impact site near players in world " + world.getName() + " (last reason: "
                        + state.lastReason + (state.lastReason != null && state.lastReason.endsWith("building")
                        ? ", block " + BuildProtection.lastBuilding : "") + ") – trying again later");
                state.failedAttempts = 0;
                state.nextAttempt = state.ticks + 20L * ThreadLocalRandom.current().nextInt(120, 300);
            }
        } catch (RuntimeException | LinkageError e) {
            state.active = null;
            BiConsumer<String, Throwable> w = warn;
            if (w != null) {
                w.accept("AE2: meteorite impact in world " + world.getName() + " failed", e);
            }
        }
    }

    /**
     * Ergebnis von {@link #spawnNear}.
     *
     * @param rejected Übersetzungsschlüssel des Grundes, oder null bei Erfolg
     * @param site     der Einschlagort bei Erfolg
     */
    record SpawnResult(@Nullable String rejected, @Nullable MeteoriteSite site) {
    }

    /** Löst sofort einen Einschlag nahe {@code (x, z)} aus ({@code /ae2 meteorites spawn}). Der Bauschutz gilt. */
    static SpawnResult spawnNear(World world, double x, double z) {
        State state = STATES.computeIfAbsent(key(world), k -> new State());
        if (state.active != null) {
            return new SpawnResult("ae2meteor.reject.busy", null);
        }
        String[] reason = new String[1];
        Impact impact = null;
        for (int attempt = 0; attempt < 12 && impact == null; attempt++) {
            impact = trySite(world, MeteoriteConfig.of(world.getName()), new double[]{x, z}, reason);
        }
        if (impact == null) {
            return new SpawnResult(reason[0] == null ? "ae2meteor.reject.unloaded" : reason[0], null);
        }
        start(world, state, impact);
        return new SpawnResult(null, impact.site);
    }

    // ------------------------------------------------------------------ Ort suchen

    /**
     * Prüft eine zufällige Stelle im Ring um einen Spieler (bzw. um {@code around}) als Einschlagort.
     *
     * @param reason bekommt den Grund der Ablehnung
     * @return der vorbereitete Einschlag, oder null, wenn die Stelle nicht erlaubt ist
     */
    @Nullable
    private static Impact trySite(World world, MeteoriteConfig.Settings settings, @Nullable double[] around, String[] reason) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double cx, cz;
        if (around != null) {
            cx = around[0];
            cz = around[1];
        } else {
            List<PlayerRef> players = new ArrayList<>(world.getPlayerRefs());
            players.removeIf(p -> p.getTransform() == null);
            if (players.isEmpty()) {
                return null;
            }
            Vector3d p = players.get(rnd.nextInt(players.size())).getTransform().getPosition();
            cx = p.x;
            cz = p.z;
        }
        MeteoriteConfig.Global global = MeteoriteConfig.global();
        double angle = rnd.nextDouble(Math.PI * 2);
        double distance = rnd.nextDouble(global.minDistanceFromPlayers() + 12, global.maxDistanceFromPlayers());
        int x = (int) Math.floor(cx + Math.cos(angle) * distance);
        int z = (int) Math.floor(cz + Math.sin(angle) * distance);
        float radius = rnd.nextFloat() * 6.0f + 2;
        WorldTerrain terrain = new WorldTerrain(world, DEFAULT_SEA_LEVEL);
        MeteoriteSite site = plan(terrain, new MeteoriteLocator.Candidate(0, 0, x, z, radius, rnd.nextLong()), true);
        if (site == null) {
            reason[0] = terrain.missingChunks() ? "ae2meteor.reject.unloaded" : "ae2meteor.reject.water";
            return null;
        }
        MeteoritePlacer.place(terrain, site);
        String rejected = BuildProtection.check(world, site, terrain, settings, true);
        if (rejected != null) {
            reason[0] = rejected;
            return null;
        }
        return new Impact(site, false, terrain.groundHeight(x, z));
    }

    /** Plant einen Meteoriten im geladenen Gelände; null, wenn die Stelle nicht taugt (Wasser, nicht geladen). */
    @Nullable
    private static MeteoriteSite plan(WorldTerrain terrain, MeteoriteLocator.Candidate candidate, boolean event) {
        int ground = terrain.groundHeight(candidate.x(), candidate.z());
        if (terrain.missingChunks() || ground <= 1) {
            return null;
        }
        boolean water = terrain.surfaceHeight(candidate.x(), candidate.z()) > ground;
        if (water && event) {
            return null; // das Ereignis schlägt nicht in Gewässer ein
        }
        MeteoriteSite site = MeteoriteLocator.plan(candidate, climate(terrain.groundKey(candidate.x(), candidate.z()), water), terrain);
        if (terrain.missingChunks()) {
            return null;
        }
        if (event) {
            // Frischer Einschlag: leerer Krater oder erkaltete Schmelze – keine Lava (Brandgefahr), kein See
            CraterType crater = site.crater() == CraterType.LAVA || site.crater() == CraterType.OBSIDIAN
                    ? CraterType.OBSIDIAN : CraterType.NORMAL;
            site = site.withCrater(crater);
        }
        return site;
    }

    /** Klima aus dem Boden an der Einschlagstelle: Sand = heiß und trocken, Schnee/Eis = kalt, Asche/Vulkan = heiß. */
    static Climate climate(@Nullable String groundKey, boolean water) {
        String k = groundKey == null ? "" : groundKey;
        if (k.startsWith("Soil_Sand") || k.startsWith("Rock_Sandstone")) {
            return new Climate(water, Climate.Temperature.HOT, false, false, FalloutMode.SAND, MeteoriteBlock.SAND);
        }
        if (k.startsWith("Soil_Snow") || k.startsWith("Rock_Ice") || k.contains("Frozen") || k.contains("Cold")) {
            return new Climate(water, Climate.Temperature.COLD, true, true, FalloutMode.ICE_SNOW, MeteoriteBlock.SNOW_BLOCK);
        }
        if (k.startsWith("Soil_Ash") || k.startsWith("Rock_Volcanic") || k.startsWith("Rock_Basalt") || k.startsWith("Rock_Magma")
                || k.contains("Burnt")) {
            return new Climate(water, Climate.Temperature.HOT, false, false, FalloutMode.DEFAULT, MeteoriteBlock.STONE);
        }
        return new Climate(water, Climate.Temperature.TEMPERATE, true, false, FalloutMode.DEFAULT, MeteoriteBlock.COBBLESTONE);
    }

    // ------------------------------------------------------------------ nachgeholte Weltgenerierung

    /** Baut einen vorgemerkten Meteoriten, sobald sein Gelände geladen ist. */
    private static boolean placePending(World world, State state, MeteoriteConfig.Settings settings) {
        MeteoriteRegistry registry = MeteoriteRegistry.of(world.getName());
        if (!settings.worldgen() || registry.pending().isEmpty()) {
            return false;
        }
        for (MeteoriteRegistry.Pending p : registry.pending()) {
            WorldTerrain terrain = new WorldTerrain(world, DEFAULT_SEA_LEVEL);
            MeteoriteSite site = plan(terrain, new MeteoriteLocator.Candidate(0, 0, p.x(), p.z(), p.radius(), p.seed()), false);
            if (site == null) {
                continue; // Gelände (noch) nicht geladen
            }
            MeteoritePlacer.place(terrain, site);
            if (terrain.missingChunks()) {
                continue;
            }
            registry.removePending(p);
            if (BuildProtection.check(world, site, terrain, settings, false) != null) {
                continue; // dort wurde inzwischen gebaut: dieser Meteorit entfällt
            }
            Impact impact = new Impact(site, true, site.y());
            impact.queue = sorted(terrain.changes());
            state.active = impact;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ Ablauf eines Einschlags

    private static void start(World world, State state, Impact impact) {
        state.active = impact;
        MeteoriteSite s = impact.site;
        info("AE2: meteorite incoming in world " + world.getName() + " at " + s.x() + " " + s.y() + " " + s.z());
        for (PlayerRef player : audience(world, s.x(), s.z(), AUDIENCE)) {
            EventTitleUtil.showEventTitleToPlayer(player, Message.translation("ae2meteor.incoming.title"),
                    Message.translation("ae2meteor.incoming.subtitle"), EventTitleStyle.Default, null, 4.0f, 0.5f, 1.0f);
            sound2d(player, "SFX_Stalactite_Falling_Whistle", 1.0f, 0.6f);
        }
    }

    private static void advance(World world, State state) {
        Impact impact = state.active;
        if (impact == null) {
            return;
        }
        MeteoriteSite s = impact.site;
        if (impact.fallTicks > 0) {
            impact.fallTicks--;
            trail(world, impact);
            if (impact.fallTicks > 0) {
                return;
            }
            // Einschlag: den Ort noch einmal prüfen – in den letzten Sekunden könnte dort gebaut worden sein
            WorldTerrain terrain = new WorldTerrain(world, DEFAULT_SEA_LEVEL);
            MeteoritePlacer.place(terrain, s);
            String rejected = BuildProtection.check(world, s, terrain, MeteoriteConfig.of(world.getName()), false);
            if (rejected != null) {
                info("AE2: meteorite burned up over " + s.x() + " " + s.z() + " in world " + world.getName() + " (" + rejected + ")");
                burnUp(world, impact);
                state.active = null;
                return;
            }
            impact.queue = sorted(terrain.changes());
            impactEffects(world, impact);
            return; // die Blöcke folgen ab dem nächsten Tick, die Prüfung war für diesen Tick genug Arbeit
        }
        List<WorldTerrain.Change> queue = impact.queue;
        if (queue == null) {
            state.active = null;
            return;
        }
        int end = Math.min(queue.size(), impact.applied + BLOCKS_PER_TICK);
        for (int i = impact.applied; i < end; i++) {
            WorldTerrain.apply(world, queue.get(i));
        }
        impact.applied = end;
        if (end >= queue.size()) {
            MeteoriteRegistry.of(world.getName()).add(new MeteoriteRegistry.Entry(s.x(), s.y(), s.z(), s.radius(),
                    impact.silent ? MeteoriteRegistry.Source.WORLDGEN : MeteoriteRegistry.Source.IMPACT, true));
            if (!impact.silent) {
                for (PlayerRef player : audience(world, s.x(), s.z(), AUDIENCE)) {
                    NotificationUtil.sendNotification(player.getPacketHandler(), Message.translation("ae2meteor.landed")
                            .param("x", s.x()).param("z", s.z()), NotificationStyle.Default);
                }
            }
            state.active = null;
        }
    }

    /** Von oben nach unten setzen, damit nichts über einem schon ausgehöhlten Loch nachrutscht. */
    private static List<WorldTerrain.Change> sorted(List<WorldTerrain.Change> changes) {
        changes.sort(Comparator.comparingInt((WorldTerrain.Change c) -> -c.y()));
        return changes;
    }

    // ------------------------------------------------------------------ Effekte

    private static List<PlayerRef> audience(World world, double x, double z, double radius) {
        List<PlayerRef> out = new ArrayList<>();
        for (PlayerRef player : world.getPlayerRefs()) {
            if (player.getTransform() == null || !player.isValid()) {
                continue;
            }
            Vector3d p = player.getTransform().getPosition();
            if ((p.x - x) * (p.x - x) + (p.z - z) * (p.z - z) <= radius * radius) {
                out.add(player);
            }
        }
        return out;
    }

    private static List<Ref<EntityStore>> refs(List<PlayerRef> players) {
        List<Ref<EntityStore>> out = new ArrayList<>(players.size());
        for (PlayerRef p : players) {
            Ref<EntityStore> ref = p.getReference();
            if (ref != null && ref.isValid()) {
                out.add(ref);
            }
        }
        return out;
    }

    /** Feuerschweif: der Meteorit kommt schräg vom Himmel herunter. */
    private static void trail(World world, Impact impact) {
        MeteoriteSite s = impact.site;
        double t = 1.0 - impact.fallTicks / (double) FALL_TICKS;
        double x = impact.from.x + (s.x() + 0.5 - impact.from.x) * t;
        double y = impact.from.y + (s.y() + s.radius() - impact.from.y) * t;
        double z = impact.from.z + (s.z() + 0.5 - impact.from.z) * t;
        List<Ref<EntityStore>> viewers = refs(audience(world, s.x(), s.z(), AUDIENCE));
        if (viewers.isEmpty()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        particle("Fire_Projectile", x, y, z, viewers, store);
        if (impact.fallTicks % 2 == 0) {
            particle("Explosion_Small", x, y, z, viewers, store);
        }
        if (impact.fallTicks % 6 == 0) {
            particle("Smoke_Black", x, y + 1, z, viewers, store);
        }
    }

    private static void impactEffects(World world, Impact impact) {
        MeteoriteSite s = impact.site;
        double x = s.x() + 0.5, y = s.y() + s.radius() + 1, z = s.z() + 0.5;
        List<PlayerRef> players = audience(world, x, z, AUDIENCE);
        List<Ref<EntityStore>> viewers = refs(players);
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (!viewers.isEmpty()) {
            particle("Explosion_Big", x, y, z, viewers, store);
            particle("Falling_Stalactite_Hit", x, y, z, viewers, store);
            double ring = s.radius() + 4;
            for (int i = 0; i < 6; i++) {
                double a = i * Math.PI / 3;
                particle("Explosion_Medium", x + Math.cos(a) * ring, y, z + Math.sin(a) * ring, viewers, store);
                particle("Smoke_Black", x + Math.cos(a) * ring * 0.5, y + 2, z + Math.sin(a) * ring * 0.5, viewers, store);
            }
        }
        for (PlayerRef player : players) {
            Vector3d p = player.getTransform().getPosition();
            double distance = Math.sqrt((p.x - x) * (p.x - x) + (p.z - z) * (p.z - z));
            float near = (float) Math.max(0.15, 1.0 - distance / AUDIENCE);
            sound2d(player, "SFX_Gobliterator_Rocket_Barrage_Explosion", near, 0.7f);
            sound2d(player, "SFX_Global_Weather_Thunder", near, 0.8f);
            shake(player, near);
        }
    }

    /** Der Einschlagort ist nicht mehr frei: der Meteorit verglüht harmlos in der Luft. */
    private static void burnUp(World world, Impact impact) {
        MeteoriteSite s = impact.site;
        List<PlayerRef> players = audience(world, s.x(), s.z(), AUDIENCE);
        List<Ref<EntityStore>> viewers = refs(players);
        if (!viewers.isEmpty()) {
            particle("Explosion_Medium", s.x() + 0.5, s.y() + s.radius() + 40, s.z() + 0.5, viewers,
                    world.getEntityStore().getStore());
        }
        for (PlayerRef player : players) {
            NotificationUtil.sendNotification(player.getPacketHandler(), Message.translation("ae2meteor.burnedUp"),
                    NotificationStyle.Default);
        }
    }

    private static void particle(String id, double x, double y, double z, List<Ref<EntityStore>> viewers, Store<EntityStore> store) {
        try {
            ParticleUtil.spawnParticleEffect(id, x, y, z, viewers, store);
        } catch (RuntimeException ignored) {
            // Partikel gibt es in dieser Spielversion nicht: der Einschlag funktioniert auch ohne
        }
    }

    private static void sound2d(PlayerRef player, String id, float volume, float pitch) {
        try {
            int index = SoundEvent.getAssetMap().getIndex(id);
            if (index > 0) {
                SoundUtil.playSoundEvent2dToPlayer(player, index, SoundCategory.SFX, volume, pitch);
            }
        } catch (RuntimeException ignored) {
            // Ton gibt es nicht
        }
    }

    private static void shake(PlayerRef player, float intensity) {
        try {
            CameraEffect effect = CameraEffect.getAssetMap().getAsset("Impact_Strong");
            if (effect != null) {
                player.getPacketHandler().write(effect.createCameraShakePacket(intensity));
            }
        } catch (RuntimeException ignored) {
            // Kamera-Effekt gibt es nicht
        }
    }
}
