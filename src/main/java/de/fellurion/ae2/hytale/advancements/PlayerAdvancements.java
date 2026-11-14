/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: net.minecraft.server.PlayerAdvancements – Fortschritt je Spieler und Kriterium).
 */
package de.fellurion.ae2.hytale.advancements;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fortschritt eines Spielers: erfüllte Kriterien je Erfolg und Zeitpunkt des Erzielens.
 * Thread-sicher (alle Zugriffe synchronisiert), da Welt-Threads und Befehle darauf zugreifen.
 *
 * <p>JSON: {@code {"version":1,"completed":{"root":1730800000000},"criteria":{"presses":["logic","silicon"]}}}
 */
final class PlayerAdvancements {

    /** Ergebnis von {@link #award}. */
    enum Result { NOTHING, PROGRESS, COMPLETED }

    private final Map<String, Set<String>> criteria = new LinkedHashMap<>();
    private final Map<String, Long> completed = new LinkedHashMap<>();

    synchronized boolean isDone(String advancementId) {
        return completed.containsKey(advancementId);
    }

    /** Zeitpunkt des Erzielens (ms seit 1970) oder 0. */
    synchronized long completedAt(String advancementId) {
        Long t = completed.get(advancementId);
        return t == null ? 0 : t;
    }

    synchronized int completedCount() {
        return completed.size();
    }

    /** Kopie der erfüllten Kriterien eines Erfolgs. */
    synchronized Set<String> criteriaOf(String advancementId) {
        Set<String> s = criteria.get(advancementId);
        return s == null ? Set.of() : Set.copyOf(s);
    }

    /**
     * Vermerkt ein erfülltes Kriterium. Bereits erzielte Erfolge und schon erfüllte Kriterien ändern nichts.
     *
     * @return {@link Result#COMPLETED}, wenn der Erfolg dadurch gerade erzielt wurde
     */
    synchronized Result award(Advancement adv, String criterion, long now) {
        if (completed.containsKey(adv.id()) || !adv.criteria().containsKey(criterion)) {
            return Result.NOTHING;
        }
        Set<String> done = criteria.computeIfAbsent(adv.id(), k -> new LinkedHashSet<>());
        if (!done.add(criterion)) {
            return Result.NOTHING;
        }
        if (adv.isComplete(done)) {
            completed.put(adv.id(), now);
            return Result.COMPLETED;
        }
        return Result.PROGRESS;
    }

    /** Setzt alles zurück. */
    synchronized void clear() {
        criteria.clear();
        completed.clear();
    }

    synchronized JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject done = new JsonObject();
        completed.forEach(done::addProperty);
        root.add("completed", done);
        JsonObject crit = new JsonObject();
        criteria.forEach((adv, set) -> {
            JsonArray arr = new JsonArray();
            set.forEach(arr::add);
            crit.add(adv, arr);
        });
        root.add("criteria", crit);
        return root;
    }

    static PlayerAdvancements fromJson(JsonObject root) {
        PlayerAdvancements p = new PlayerAdvancements();
        if (root.has("completed")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("completed").entrySet()) {
                p.completed.put(e.getKey(), e.getValue().getAsLong());
            }
        }
        if (root.has("criteria")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("criteria").entrySet()) {
                Set<String> set = new LinkedHashSet<>();
                for (JsonElement c : e.getValue().getAsJsonArray()) {
                    set.add(c.getAsString());
                }
                p.criteria.put(e.getKey(), set);
            }
        }
        return p;
    }
}
