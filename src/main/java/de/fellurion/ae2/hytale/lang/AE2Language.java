/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.lang;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.modules.i18n.event.MessagesUpdated;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * AE2-Sprache je Spieler, unabhängig von der Hytale-Sprache: Standard ist Englisch, umstellbar mit
 * {@code /ae2 language de|en}.
 *
 * <p>Hytale übersetzt {@code Message.translation(...)} und Item-Namen im Client, mit den Texten, die der Server beim
 * Beitreten in der Client-Sprache schickt. AE2 schickt danach alle eigenen Schlüssel ({@code ae2*.}) in der gewählten
 * Sprache nach ({@code UpdateTranslations AddOrUpdate}) – so sind Items, Blöcke, Fenster und Meldungen von AE2
 * einheitlich in einer Sprache. Wechselt der Spieler die Hytale-Sprache, schickt der Server alles neu; AE2 legt
 * seine Texte danach wieder darüber.
 */
public final class AE2Language {

    public static final String ENGLISH = "en-US";
    public static final String GERMAN = "de-DE";

    /** Eigene Übersetzungsschlüssel: {@code ae2.}, {@code ae2ui.}, {@code ae2gui.}, {@code ae2cmd.} usw. */
    private static final Pattern AE2_KEY = Pattern.compile("^ae2[a-z]*\\..+");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Map<UUID, String> CHOICE = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_CLIENT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> KEYS_BY_LANGUAGE = new ConcurrentHashMap<>();

    private static volatile String defaultLanguage = ENGLISH;
    @Nullable
    private static Path file;
    @Nullable
    private static ScheduledFuture<?> watcher;
    @Nullable
    private static JavaPlugin plugin;

    private AE2Language() {
    }

    public static void setup(JavaPlugin p) {
        plugin = p;
        file = p.getDataDirectory().resolve("language.json");
        load();
        if (!Files.isRegularFile(file)) {
            save(); // Datei mit "default" anlegen, damit Server-Betreiber den Standard umstellen können
        }
        p.getEventRegistry().registerGlobal(PlayerReadyEvent.class, AE2Language::onPlayerReady);
        p.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, e -> {
            UUID id = e.getPlayerRef().getUuid();
            LAST_CLIENT.remove(id);
            PENDING.remove(id);
        });
        p.getEventRegistry().registerGlobal(MessagesUpdated.class, e -> {
            // Sprachdateien neu geladen: AE2-Texte frisch zusammenstellen und erneut anwenden
            KEYS_BY_LANGUAGE.clear();
            for (PlayerRef ref : Universe.get().getPlayers()) {
                PENDING.put(ref.getUuid(), System.currentTimeMillis());
            }
        });
        // Wechselt ein Spieler die Hytale-Sprache, schickt der Server alle Texte neu – danach AE2 wieder darüberlegen
        watcher = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(AE2Language::checkClients, 2, 2, TimeUnit.SECONDS);
        p.getLogger().at(Level.INFO).log("AE2: language %s by default, players switch with /ae2 language", defaultLanguage);
    }

    public static void shutdown() {
        if (watcher != null) {
            watcher.cancel(false);
            watcher = null;
        }
    }

    /** AE2-Sprache des Spielers ({@code en-US} oder {@code de-DE}). */
    public static String of(UUID player) {
        return CHOICE.getOrDefault(player, defaultLanguage);
    }

    public static String of(PlayerRef player) {
        return of(player.getUuid());
    }

    /** {@code en}, {@code de}, {@code english}, {@code deutsch}, {@code en-US}, {@code de-DE} → Sprachcode, sonst null. */
    @Nullable
    public static String parse(String input) {
        String s = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "en", "en-us", "english", "englisch" -> ENGLISH;
            case "de", "de-de", "german", "deutsch" -> GERMAN;
            default -> null;
        };
    }

    /** Setzt die AE2-Sprache des Spielers, speichert sie und schickt ihm die Texte sofort. */
    public static void set(PlayerRef player, String language) {
        if (defaultLanguage.equals(language)) {
            CHOICE.remove(player.getUuid());
        } else {
            CHOICE.put(player.getUuid(), language);
        }
        save();
        send(player, language);
    }

    // ------------------------------------------------------------------ Anwenden

    private static void onPlayerReady(PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player != null) {
                LAST_CLIENT.put(player.getUuid(), String.valueOf(player.getLanguage()));
                applyIfNeeded(player);
            }
        });
    }

    /** Alle 2 s: Hytale-Sprache gewechselt? Dann nach dem Neusenden der Hytale-Texte AE2 wieder anwenden. */
    private static void checkClients() {
        try {
            long now = System.currentTimeMillis();
            for (PlayerRef player : Universe.get().getPlayers()) {
                UUID id = player.getUuid();
                String client = String.valueOf(player.getLanguage());
                String last = LAST_CLIENT.put(id, client);
                if (last != null && !last.equals(client)) {
                    PENDING.put(id, now);
                    continue;
                }
                Long since = PENDING.get(id);
                if (since != null && now - since >= 1500) {
                    PENDING.remove(id);
                    applyIfNeeded(player);
                }
            }
        } catch (RuntimeException e) {
            JavaPlugin p = plugin;
            if (p != null) {
                p.getLogger().at(Level.FINE).withCause(e).log("AE2: language check failed");
            }
        }
    }

    /** Nur nötig, wenn die AE2-Sprache von der Hytale-Sprache abweicht (sonst stimmen die Texte schon). */
    private static void applyIfNeeded(PlayerRef player) {
        String language = of(player);
        if (!language.equals(resolve(player.getLanguage()))) {
            send(player, language);
        }
    }

    private static void send(PlayerRef player, String language) {
        Map<String, String> keys = keys(language);
        if (!keys.isEmpty() && player.isValid()) {
            player.getPacketHandler().write(new UpdateTranslations(UpdateType.AddOrUpdate, keys));
        }
    }

    private static String resolve(@Nullable String language) {
        try {
            I18nModule i18n = I18nModule.get();
            return i18n == null ? Objects.requireNonNullElse(language, ENGLISH) : i18n.resolveLanguage(language);
        } catch (RuntimeException e) {
            return Objects.requireNonNullElse(language, ENGLISH);
        }
    }

    /** Alle AE2-Texte einer Sprache (fehlende fallen wie bei Hytale auf Englisch zurück). */
    static Map<String, String> keys(String language) {
        return KEYS_BY_LANGUAGE.computeIfAbsent(language, l -> {
            Map<String, String> out = new HashMap<>();
            I18nModule i18n = I18nModule.get();
            if (i18n == null) {
                return out;
            }
            for (Map.Entry<String, String> e : i18n.getMessages(ENGLISH).entrySet()) {
                if (AE2_KEY.matcher(e.getKey()).matches()) {
                    out.put(e.getKey(), e.getValue());
                }
            }
            if (!ENGLISH.equals(l)) {
                for (Map.Entry<String, String> e : i18n.getMessages(l).entrySet()) {
                    if (AE2_KEY.matcher(e.getKey()).matches()) {
                        out.put(e.getKey(), e.getValue());
                    }
                }
            }
            return Map.copyOf(out);
        });
    }

    // ------------------------------------------------------------------ Speichern

    private static void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.has("default")) {
                String d = parse(json.get("default").getAsString());
                defaultLanguage = d == null ? ENGLISH : d;
            }
            if (json.has("players")) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : json.getAsJsonObject("players").entrySet()) {
                    String lang = parse(e.getValue().getAsString());
                    if (lang != null) {
                        CHOICE.put(UUID.fromString(e.getKey()), lang);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            JavaPlugin p = plugin;
            if (p != null) {
                p.getLogger().at(Level.WARNING).withCause(e).log("AE2: could not read %s – using English", file);
            }
        }
    }

    private static synchronized void save() {
        if (file == null) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("default", defaultLanguage);
        JsonObject players = new JsonObject();
        CHOICE.forEach((id, lang) -> players.addProperty(id.toString(), lang));
        json.add("players", players);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            JavaPlugin p = plugin;
            if (p != null) {
                p.getLogger().at(Level.WARNING).withCause(e).log("AE2: could not save %s", file);
            }
        }
    }
}
