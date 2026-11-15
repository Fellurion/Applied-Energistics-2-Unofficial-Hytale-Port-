/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.emitter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
 * Einstellungen dieses Bereichs für den Server-Betreiber ({@code monitors.json} im Datenordner des Plugins):
 * ob Monitore ihr Item zusätzlich in der Welt zeigen. Umschalten mit {@code /ae2 monitordisplay on|off}.
 */
final class EmitterSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String KEY_DISPLAY = "inWorldDisplay";

    private static volatile boolean monitorDisplay = true;
    @Nullable
    private static Path file;
    @Nullable
    private static BiConsumer<String, Throwable> warn;

    private EmitterSettings() {
    }

    static void load(Path dataDirectory, BiConsumer<String, Throwable> warning) {
        file = dataDirectory.resolve("monitors.json");
        warn = warning;
        if (!Files.isRegularFile(file)) {
            return; // Standard gilt; die Datei entsteht erst, wenn etwas umgestellt wird
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has(KEY_DISPLAY) && o.get(KEY_DISPLAY).isJsonPrimitive()) {
                monitorDisplay = o.get(KEY_DISPLAY).getAsBoolean();
            }
        } catch (IOException | RuntimeException e) {
            warning.accept("AE2: could not read " + file + " – using defaults", e);
        }
    }

    /** Zeigen Monitore ihr Item in der Welt (Item mit Namensschild vor dem Bildschirm)? */
    static boolean monitorDisplay() {
        return monitorDisplay;
    }

    static void setMonitorDisplay(boolean on) {
        monitorDisplay = on;
        Path target = file;
        if (target == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty(KEY_DISPLAY, on);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            BiConsumer<String, Throwable> w = warn;
            if (w != null) {
                w.accept("AE2: could not write " + target, e);
            }
        }
    }
}
