/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.meteorite;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * Einstellungen der Meteoriten ({@code meteorites.json} im Datenordner des Plugins), global und je Welt.
 *
 * <p>Standard wie in Minecraft: Meteoriten entstehen bei der Weltgenerierung (in Welten mit dem normalen
 * Hytale-Gelände); das Einschlag-Ereignis ist aus und wird je Welt eingeschaltet ({@code /ae2 meteorites impacts on}).
 * Gelesen wird von mehreren Threads (Weltgenerierung), deshalb ist der Stand ein unveränderlicher Schnappschuss.
 */
public final class MeteoriteConfig {

    /** Wie streng der Bauschutz beim Einschlag-Ereignis ist. */
    public enum Protection {
        /** Nur unberührtes, rein natürliches Gelände ohne Ansprüche (Claims), fern von Betten und Spawn. */
        NORMAL,
        /** Zusätzlich nur Gelände, das mit diesem Plugin generiert wurde – schützt auch alte Bauten aus Naturblöcken. */
        STRICT
    }

    /**
     * Einstellungen einer Welt.
     *
     * @param worldgen         Meteoriten bei der Weltgenerierung
     * @param worldgenExplicit die Welt wurde ausdrücklich eingestellt (nötig für Welten ohne Standard-Gelände)
     * @param impacts          Einschlag-Ereignis aktiv
     * @param intervalMinutes  mittlerer Abstand zwischen zwei Einschlägen
     */
    public record Settings(boolean worldgen, boolean worldgenExplicit, boolean impacts, int intervalMinutes,
                           Protection protection) {
    }

    /** Globale Werte. */
    public record Global(int minDistanceFromSpawn, int minDistanceFromPlayers, int maxDistanceFromPlayers,
                         boolean respectClaimPlugins, Set<String> naturalBlocks) {
    }

    private record Snapshot(Settings defaults, Global global, Map<String, JsonObject> worlds) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Settings DEFAULTS = new Settings(true, false, false, 60, Protection.NORMAL);
    private static final Global GLOBAL_DEFAULTS = new Global(150, 48, 140, true, Set.of());

    private static volatile Snapshot snapshot = new Snapshot(DEFAULTS, GLOBAL_DEFAULTS, Map.of());
    @Nullable
    private static Path file;
    @Nullable
    private static BiConsumer<String, Throwable> warn;

    private MeteoriteConfig() {
    }

    static void load(Path dataDirectory, BiConsumer<String, Throwable> warning) {
        file = dataDirectory.resolve("meteorites.json");
        warn = warning;
        if (Files.isRegularFile(file)) {
            try {
                snapshot = parse(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (IOException | RuntimeException e) {
                warning.accept("AE2: could not read " + file + " – using defaults", e);
            }
        } else {
            save();
        }
    }

    public static Global global() {
        return snapshot.global();
    }

    /** Einstellungen der Welt (Weltname ohne Groß-/Kleinschreibung). */
    public static Settings of(String world) {
        Snapshot s = snapshot;
        JsonObject o = s.worlds().get(key(world));
        return o == null ? s.defaults() : settings(o, s.defaults(), true);
    }

    public static void setWorldgen(String world, boolean on) {
        update(world, o -> o.addProperty("worldgen", on));
    }

    public static void setImpacts(String world, boolean on) {
        update(world, o -> o.addProperty("impacts", on));
    }

    public static void setInterval(String world, int minutes) {
        update(world, o -> o.addProperty("impactIntervalMinutes", Math.max(1, minutes)));
    }

    public static void setProtection(String world, Protection protection) {
        update(world, o -> o.addProperty("protection", protection.name().toLowerCase(Locale.ROOT)));
    }

    private static synchronized void update(String world, java.util.function.Consumer<JsonObject> change) {
        Snapshot s = snapshot;
        Map<String, JsonObject> worlds = new TreeMap<>(s.worlds());
        JsonObject o = worlds.containsKey(key(world)) ? worlds.get(key(world)).deepCopy() : new JsonObject();
        change.accept(o);
        worlds.put(key(world), o);
        snapshot = new Snapshot(s.defaults(), s.global(), Map.copyOf(worlds));
        save();
    }

    /**
     * Gibt einen Block (oder mit {@code *} am Ende eine ganze Gruppe) als natürliches Gelände frei – für Blöcke
     * anderer Mods, die der Bauschutz sonst für gebaut hält.
     *
     * @return false, wenn der Eintrag schon vorhanden war
     */
    public static synchronized boolean allowNatural(String blockPattern) {
        Snapshot s = snapshot;
        if (s.global().naturalBlocks().contains(blockPattern)) {
            return false;
        }
        Set<String> blocks = new java.util.TreeSet<>(s.global().naturalBlocks());
        blocks.add(blockPattern);
        Global g = s.global();
        snapshot = new Snapshot(s.defaults(), new Global(g.minDistanceFromSpawn(), g.minDistanceFromPlayers(),
                g.maxDistanceFromPlayers(), g.respectClaimPlugins(), Set.copyOf(blocks)), s.worlds());
        save();
        return true;
    }

    private static Set<String> strings(JsonObject o, String key) {
        Set<String> out = new java.util.TreeSet<>();
        try {
            if (o.has(key) && o.get(key).isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray(key)) {
                    out.add(e.getAsString());
                }
            }
        } catch (RuntimeException ignored) {
            // kaputter Eintrag: ohne Freigaben
        }
        return Set.copyOf(out);
    }

    private static String key(String world) {
        return world == null ? "" : world.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ Datei

    private static Snapshot parse(JsonObject json) {
        Settings defaults = settings(json, DEFAULTS, false);
        Global global = new Global(
                integer(json, "minDistanceFromSpawn", GLOBAL_DEFAULTS.minDistanceFromSpawn()),
                integer(json, "minDistanceFromPlayers", GLOBAL_DEFAULTS.minDistanceFromPlayers()),
                Math.max(integer(json, "maxDistanceFromPlayers", GLOBAL_DEFAULTS.maxDistanceFromPlayers()),
                        integer(json, "minDistanceFromPlayers", GLOBAL_DEFAULTS.minDistanceFromPlayers()) + 16),
                bool(json, "respectClaimPlugins", GLOBAL_DEFAULTS.respectClaimPlugins()), strings(json, "naturalBlocks"));
        Map<String, JsonObject> worlds = new TreeMap<>();
        if (json.has("worlds") && json.get("worlds").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("worlds").entrySet()) {
                if (e.getValue().isJsonObject()) {
                    worlds.put(key(e.getKey()), e.getValue().getAsJsonObject());
                }
            }
        }
        return new Snapshot(defaults, global, Map.copyOf(worlds));
    }

    private static Settings settings(JsonObject o, Settings base, boolean world) {
        Protection protection = base.protection();
        if (o.has("protection")) {
            try {
                protection = Protection.valueOf(o.get("protection").getAsString().toUpperCase(Locale.ROOT));
            } catch (RuntimeException ignored) {
                // unbekannter Wert: Vorgabe behalten
            }
        }
        return new Settings(bool(o, "worldgen", base.worldgen()), world && o.has("worldgen"),
                bool(o, "impacts", base.impacts()),
                Math.max(1, integer(o, "impactIntervalMinutes", base.intervalMinutes())), protection);
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        try {
            return o.has(key) ? o.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int integer(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static synchronized void save() {
        Path f = file;
        if (f == null) {
            return;
        }
        Snapshot s = snapshot;
        JsonObject json = new JsonObject();
        json.addProperty("worldgen", s.defaults().worldgen());
        json.addProperty("impacts", s.defaults().impacts());
        json.addProperty("impactIntervalMinutes", s.defaults().intervalMinutes());
        json.addProperty("protection", s.defaults().protection().name().toLowerCase(Locale.ROOT));
        json.addProperty("minDistanceFromSpawn", s.global().minDistanceFromSpawn());
        json.addProperty("minDistanceFromPlayers", s.global().minDistanceFromPlayers());
        json.addProperty("maxDistanceFromPlayers", s.global().maxDistanceFromPlayers());
        json.addProperty("respectClaimPlugins", s.global().respectClaimPlugins());
        com.google.gson.JsonArray natural = new com.google.gson.JsonArray();
        s.global().naturalBlocks().stream().sorted().forEach(natural::add);
        json.add("naturalBlocks", natural);
        JsonObject worlds = new JsonObject();
        s.worlds().forEach(worlds::add);
        json.add("worlds", worlds);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            BiConsumer<String, Throwable> w = warn;
            if (w != null) {
                w.accept("AE2: could not save " + f, e);
            }
        }
    }
}
