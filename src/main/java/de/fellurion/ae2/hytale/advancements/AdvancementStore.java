/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.advancements;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Erfolgsfortschritt je Spieler im Datenordner des Plugins: {@code <Datenordner>/advancements/<uuid>.json}.
 *
 * <p>Geladen wird beim ersten Zugriff (kleine Datei), gespeichert sofort bei jeder Änderung – Erfolge sind selten,
 * so geht beim Absturz nichts verloren. Geschrieben wird über eine Temp-Datei und atomares Umbenennen.
 */
final class AdvancementStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dir;
    private final HytaleLogger logger;
    private final Map<UUID, PlayerAdvancements> cache = new ConcurrentHashMap<>();

    AdvancementStore(Path dir, HytaleLogger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    Path file(UUID player) {
        return dir.resolve(player + ".json");
    }

    /** Fortschritt des Spielers (lädt beim ersten Zugriff). */
    PlayerAdvancements get(UUID player) {
        return cache.computeIfAbsent(player, this::load);
    }

    private PlayerAdvancements load(UUID player) {
        Path f = file(player);
        if (!Files.isRegularFile(f)) {
            return new PlayerAdvancements();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            return PlayerAdvancements.fromJson(root);
        } catch (IOException | RuntimeException e) {
            logger.at(Level.WARNING).withCause(e).log("AE2-Erfolge von %s nicht lesbar (%s) – starte leer", player, f);
            return new PlayerAdvancements();
        }
    }

    /** Schreibt den Fortschritt sofort (atomar). */
    void save(UUID player, PlayerAdvancements progress) {
        synchronized (progress) {
            String json = GSON.toJson(progress.toJson());
            Path f = file(player);
            Path tmp = dir.resolve(player + ".json.tmp");
            try {
                Files.createDirectories(dir);
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                logger.at(Level.WARNING).withCause(e).log("AE2-Erfolge von %s nicht speicherbar (%s)", player, f);
            }
        }
    }

    /** Vergisst den Spieler (beim Verlassen; gespeichert ist schon alles). */
    void unload(UUID player) {
        cache.remove(player);
    }
}
