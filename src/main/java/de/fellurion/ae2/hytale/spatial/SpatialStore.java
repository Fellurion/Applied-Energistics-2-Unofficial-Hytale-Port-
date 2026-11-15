/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.spatial.SpatialStoragePlotManager / SpatialStorageWorldData).
 */
package de.fellurion.ae2.hytale.spatial;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.fellurion.ae2.core.spatial.SpatialStoragePlot;
import de.fellurion.ae2.core.spatial.SpatialStoragePlotManager;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Der Raumspeicher auf der Platte: {@code <Datenordner>/spatial/}.
 *
 * <ul>
 *   <li>{@code plots.json} – Liste aller Plots (Kennung, Größe, Besitzer, letzter Transfer), der Stand des
 *       {@link SpatialStoragePlotManager};</li>
 *   <li>{@code plot-<id>.dat} – die Blöcke eines Plots ({@link PlotFile}); in ihrem Kopf steht derselbe Eintrag noch
 *       einmal, so dass eine verlorene Liste aus den Dateien wieder aufgebaut wird;</li>
 *   <li>{@code config.json} – Einstellungen: welche Blöcke nie versetzt werden.</li>
 * </ul>
 *
 * <p>AE2 legt die Plots in einer eigenen Dimension ab; dieser Port erzeugt keine zweite Welt, ein Plot ist eine
 * Datei. Alle Dateien werden über eine Temp-Datei und atomares Umbenennen geschrieben. Zugriff nur von
 * Welt-Threads; die Methoden sind synchronisiert, weil mehrere Welten denselben Speicher teilen.
 */
public final class SpatialStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern PLOT_FILE = Pattern.compile("plot-(\\d+)\\.dat");

    /** Bauteile von Block-Entities, die an ihrem Ort hängen (Schlafplatz, Teleporter, Portal, Spawner ...). */
    static final Set<String> DEFAULT_BLACKLIST_COMPONENTS = Set.of("RespawnBlock", "Teleporter", "BlockMapMarker",
            "Portal", "Instance", "InstanceConfig", "PrefabSpawner", "BlockSpawner", "SpawnMarkerBlock",
            "CompanionBlockSpawnerBlock");
    static final Set<String> DEFAULT_BLACKLIST_BLOCKS = Set.of("Rock_Bedrock");

    /**
     * Einstellungen ({@code config.json}).
     *
     * @param blacklistBlocks     Block-Ids, die nie versetzt werden ({@code *} am Ende = beliebige Fortsetzung);
     *                            AE2: Tag {@code ae2:blacklisted/spatial}
     * @param blacklistComponents Kennungen von Block-Entity-Bauteilen, deren Blöcke nie versetzt werden
     * @param moveUnbreakable     auch Blöcke versetzen, die kein Spieler abbauen kann (Barrieren, Portale ...)
     */
    public record Config(Set<String> blacklistBlocks, Set<String> blacklistComponents, boolean moveUnbreakable) {
    }

    private final Path dir;
    private final BiConsumer<String, Throwable> warn;
    private final SpatialStoragePlotManager plots = new SpatialStoragePlotManager();
    private volatile Config config = new Config(DEFAULT_BLACKLIST_BLOCKS, DEFAULT_BLACKLIST_COMPONENTS, false);
    /** Zählt jede geladene Einstellung, damit abgeleitete Tabellen neu gebaut werden. */
    private volatile int configVersion;

    SpatialStore(Path dir, BiConsumer<String, Throwable> warn) {
        this.dir = dir;
        this.warn = warn;
    }

    public Path directory() {
        return dir;
    }

    public SpatialStoragePlotManager plots() {
        return plots;
    }

    public Config config() {
        return config;
    }

    int configVersion() {
        return configVersion;
    }

    Path plotFile(int plotId) {
        return dir.resolve("plot-" + plotId + ".dat");
    }

    Path tempFile(int plotId) {
        return dir.resolve("plot-" + plotId + ".tmp");
    }

    // ------------------------------------------------------------------ Laden

    /** Liest Einstellungen und Plot-Liste und gleicht die Liste mit den vorhandenen Plot-Dateien ab. */
    synchronized void load() {
        loadConfig();
        Path index = dir.resolve("plots.json");
        if (Files.isRegularFile(index)) {
            try {
                plots.load(Files.readString(index, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                warn.accept("AE2: spatial plot list " + index + " is unreadable; rebuilding it from the plot files", e);
            }
        }
        boolean restored = false;
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    Matcher m = PLOT_FILE.matcher(name);
                    if (m.matches()) {
                        int id = Integer.parseInt(m.group(1));
                        plots.reserveIdsUpTo(id);
                        if (plots.getPlot(id) == null) {
                            restored |= restoreFromFile(id, file);
                        }
                    } else if (name.endsWith(".tmp")) {
                        // Rest eines Transfers, der vor dem Umbenennen abbrach: der Inhalt steht noch in der Welt
                        Files.deleteIfExists(file);
                    }
                }
            } catch (IOException | RuntimeException e) {
                warn.accept("AE2: could not scan the spatial storage folder " + dir, e);
            }
        }
        plots.setSaveListener(this::saveIndex);
        if (restored) {
            saveIndex();
        }
    }

    private boolean restoreFromFile(int id, Path file) {
        try {
            SpatialStoragePlot plot = PlotFile.readHeader(file);
            if (plot != null && plot.getId() == id && plots.restorePlot(plot)) {
                warn.accept("AE2: spatial plot " + id + " was missing in plots.json and has been restored from " + file,
                        null);
                return true;
            }
        } catch (IOException | RuntimeException e) {
            warn.accept("AE2: spatial plot file " + file + " is unreadable", e);
        }
        return false;
    }

    private void loadConfig() {
        Path file = dir.resolve("config.json");
        Config loaded = config;
        boolean write = !Files.isRegularFile(file);
        if (!write) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                loaded = new Config(strings(o, "blacklistBlocks", DEFAULT_BLACKLIST_BLOCKS),
                        strings(o, "blacklistComponents", DEFAULT_BLACKLIST_COMPONENTS),
                        o.has("moveUnbreakable") && o.get("moveUnbreakable").getAsBoolean());
            } catch (IOException | RuntimeException e) {
                warn.accept("AE2: " + file + " is unreadable, using the default spatial settings", e);
            }
        }
        config = loaded;
        configVersion++;
        if (write) {
            JsonObject o = new JsonObject();
            o.add("blacklistBlocks", array(loaded.blacklistBlocks()));
            o.add("blacklistComponents", array(loaded.blacklistComponents()));
            o.addProperty("moveUnbreakable", loaded.moveUnbreakable());
            try {
                writeAtomic(file, GSON.toJson(o));
            } catch (IOException e) {
                warn.accept("AE2: could not write " + file, e);
            }
        }
    }

    private static Set<String> strings(JsonObject o, String key, Set<String> fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) {
            return fallback;
        }
        Set<String> set = new LinkedHashSet<>();
        for (JsonElement s : e.getAsJsonArray()) {
            if (s.isJsonPrimitive() && !s.getAsString().isBlank()) {
                set.add(s.getAsString().trim());
            }
        }
        return Set.copyOf(set);
    }

    private static JsonArray array(Set<String> values) {
        JsonArray a = new JsonArray();
        values.stream().sorted().forEach(a::add);
        return a;
    }

    // ------------------------------------------------------------------ Schreiben

    private synchronized void saveIndex() {
        try {
            writeAtomic(dir.resolve("plots.json"), plots.toJson());
        } catch (IOException e) {
            warn.accept("AE2: could not write the spatial plot list", e);
        }
    }

    /** Entfernt einen Plot samt Datei (AE2: {@code freePlot(id, true)}). */
    synchronized boolean deletePlot(int plotId) {
        if (plots.getPlot(plotId) == null) {
            return false;
        }
        plots.freePlot(plotId);
        try {
            Files.deleteIfExists(plotFile(plotId));
        } catch (IOException e) {
            warn.accept("AE2: could not delete the file of spatial plot " + plotId, e);
        }
        return true;
    }

    void warn(String message, @Nullable Throwable cause) {
        warn.accept(message, cause);
    }

    static void writeAtomic(Path file, String text) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        move(tmp, file);
    }

    static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
