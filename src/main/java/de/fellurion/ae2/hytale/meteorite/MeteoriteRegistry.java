/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.server.services.compass.ServerCompassService – merkt sich, wo Mysterious Cubes liegen).
 */
package de.fellurion.ae2.hytale.meteorite;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Was AE2 je Welt über Meteoriten weiß ({@code meteorites/<welt>.json} im Datenordner): bekannte Meteoriten (für den
 * Kompass und damit nie zwei aufeinander fallen), von Spielern veränderte Chunks (Bauschutz) und Meteoriten, die
 * noch auf ihr Gelände warten. Wird von mehreren Threads benutzt (Weltgenerierung, Welt-Thread, Karten-Thread).
 */
public final class MeteoriteRegistry {

    public enum Source {
        WORLDGEN, IMPACT
    }

    /**
     * Ein bekannter Meteorit.
     *
     * @param cube der Mysterious Cube liegt noch drin (nur dann zeigt der Kompass hin)
     */
    public record Entry(int x, int y, int z, float radius, Source source, boolean cube) {
        double distanceSquared(double px, double pz) {
            double dx = x - px, dz = z - pz;
            return dx * dx + dz * dz;
        }
    }

    /** Ein Meteorit der Weltgenerierung, dessen Gelände erst geladen sein muss (Welten ohne Standard-Generator). */
    public record Pending(int x, int z, float radius, long seed) {
    }

    private static final Map<String, MeteoriteRegistry> BY_WORLD = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();
    @Nullable
    private static volatile Path directory;
    @Nullable
    private static volatile BiConsumer<String, Throwable> warn;

    private final String world;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final Set<Long> touched = ConcurrentHashMap.newKeySet();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    private MeteoriteRegistry(String world) {
        this.world = world;
    }

    static void setup(Path dataDirectory, BiConsumer<String, Throwable> warning) {
        directory = dataDirectory.resolve("meteorites");
        warn = warning;
    }

    public static MeteoriteRegistry of(String worldName) {
        return BY_WORLD.computeIfAbsent(worldName.toLowerCase(Locale.ROOT), w -> {
            MeteoriteRegistry r = new MeteoriteRegistry(w);
            r.load();
            return r;
        });
    }

    /** Schreibt alle geänderten Welten (alle 30 s und beim Herunterfahren). */
    static void saveAll() {
        for (MeteoriteRegistry r : BY_WORLD.values()) {
            r.saveIfDirty();
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------------------ Meteoriten

    public void add(Entry entry) {
        entries.put(key(entry.x(), entry.z()), entry);
        pending.remove(key(entry.x(), entry.z()));
        dirty = true;
    }

    public Collection<Entry> all() {
        return entries.values();
    }

    public int size() {
        return entries.size();
    }

    /** Der Mysterious Cube an dieser Stelle wurde abgebaut: der Kompass zeigt nicht mehr hin. */
    public void cubeRemoved(int x, int y, int z) {
        Entry e = entries.get(key(x, z));
        if (e != null && e.cube() && e.y() == y) {
            entries.put(key(x, z), new Entry(e.x(), e.y(), e.z(), e.radius(), e.source(), false));
            dirty = true;
        }
    }

    /** Nächster bekannter Meteorit, höchstens {@code maxDistance} Blöcke entfernt. */
    @Nullable
    public Entry nearest(double x, double z, boolean withCubeOnly, double maxDistance) {
        Entry best = null;
        double bestD = maxDistance * maxDistance;
        for (Entry e : entries.values()) {
            if (withCubeOnly && !e.cube()) {
                continue;
            }
            double d = e.distanceSquared(x, z);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ wartende Meteoriten

    public void addPending(Pending p) {
        if (!entries.containsKey(key(p.x(), p.z())) && pending.putIfAbsent(key(p.x(), p.z()), p) == null) {
            dirty = true;
        }
    }

    public List<Pending> pending() {
        return new ArrayList<>(pending.values());
    }

    public void removePending(Pending p) {
        if (pending.remove(key(p.x(), p.z())) != null) {
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ von Spielern veränderte Chunks

    /** Ein Spieler hat in diesem Chunk (32×32 Blöcke) einen Block gesetzt oder abgebaut. */
    public void touch(int chunkX, int chunkZ) {
        if (touched.add(key(chunkX, chunkZ))) {
            dirty = true;
        }
    }

    public boolean isTouched(int chunkX, int chunkZ) {
        return touched.contains(key(chunkX, chunkZ));
    }

    public int touchedCount() {
        return touched.size();
    }

    // ------------------------------------------------------------------ Datei

    private Path file() {
        Path dir = directory;
        return dir == null ? null : dir.resolve(world.replaceAll("[^a-z0-9_.-]", "_") + ".json");
    }

    private void load() {
        Path f = file();
        if (f == null || !Files.isRegularFile(f)) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.has("meteorites")) {
                for (JsonElement el : json.getAsJsonArray("meteorites")) {
                    JsonObject o = el.getAsJsonObject();
                    add(new Entry(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                            o.get("r").getAsFloat(), "impact".equals(o.get("source").getAsString()) ? Source.IMPACT : Source.WORLDGEN,
                            !o.has("cube") || o.get("cube").getAsBoolean()));
                }
            }
            if (json.has("touchedChunks")) {
                for (JsonElement el : json.getAsJsonArray("touchedChunks")) {
                    JsonArray a = el.getAsJsonArray();
                    touched.add(key(a.get(0).getAsInt(), a.get(1).getAsInt()));
                }
            }
            if (json.has("pending")) {
                for (JsonElement el : json.getAsJsonArray("pending")) {
                    JsonObject o = el.getAsJsonObject();
                    Pending p = new Pending(o.get("x").getAsInt(), o.get("z").getAsInt(), o.get("r").getAsFloat(), o.get("seed").getAsLong());
                    pending.put(key(p.x(), p.z()), p);
                }
            }
            dirty = false;
        } catch (IOException | RuntimeException e) {
            BiConsumer<String, Throwable> w = warn;
            if (w != null) {
                w.accept("AE2: could not read " + f, e);
            }
        }
    }

    private void saveIfDirty() {
        Path f = file();
        if (!dirty || f == null) {
            return;
        }
        dirty = false;
        JsonObject json = new JsonObject();
        JsonArray list = new JsonArray();
        for (Entry e : entries.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("x", e.x());
            o.addProperty("y", e.y());
            o.addProperty("z", e.z());
            o.addProperty("r", e.radius());
            o.addProperty("source", e.source() == Source.IMPACT ? "impact" : "worldgen");
            o.addProperty("cube", e.cube());
            list.add(o);
        }
        json.add("meteorites", list);
        JsonArray chunks = new JsonArray();
        for (long k : touched) {
            JsonArray a = new JsonArray();
            a.add((int) (k >> 32));
            a.add((int) k);
            chunks.add(a);
        }
        json.add("touchedChunks", chunks);
        JsonArray wait = new JsonArray();
        for (Pending p : pending.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("x", p.x());
            o.addProperty("z", p.z());
            o.addProperty("r", p.radius());
            o.addProperty("seed", p.seed());
            wait.add(o);
        }
        json.add("pending", wait);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            dirty = true;
            BiConsumer<String, Throwable> w = warn;
            if (w != null) {
                w.accept("AE2: could not save " + f, e);
            }
        }
    }
}
