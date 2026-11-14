/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: data/ae2/advancement/main/*.json).
 */
package de.fellurion.ae2.hytale.advancements;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.fellurion.ae2.hytale.ItemCatalog;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Alle Erfolge aus {@code ae2data/advancements.json}, in AE2-Baumreihenfolge (Tiefensuche), mit Indizes
 * Item → Kriterien und Auslöser → Kriterien. Unveränderlich nach dem Laden.
 */
public final class AdvancementRegistry {

    private static volatile AdvancementRegistry instance;

    private final List<Advancement> ordered;
    private final Map<String, Advancement> byId;
    private final Map<String, List<Advancement.Criterion>> byItem;
    private final Map<Trigger, List<Advancement.Criterion>> byTrigger;

    private AdvancementRegistry(List<Advancement> ordered) {
        this.ordered = List.copyOf(ordered);
        Map<String, Advancement> ids = new LinkedHashMap<>();
        Map<String, List<Advancement.Criterion>> items = new HashMap<>();
        Map<Trigger, List<Advancement.Criterion>> triggers = new EnumMap<>(Trigger.class);
        for (Advancement a : ordered) {
            ids.put(a.id(), a);
            for (Advancement.Criterion c : a.criteria().values()) {
                triggers.computeIfAbsent(c.trigger(), t -> new ArrayList<>()).add(c);
                for (String item : c.items()) {
                    items.computeIfAbsent(item, i -> new ArrayList<>()).add(c);
                }
            }
        }
        this.byId = Collections.unmodifiableMap(ids);
        this.byItem = Collections.unmodifiableMap(items);
        this.byTrigger = Collections.unmodifiableMap(triggers);
    }

    /** Die Erfolge aus dem Jar (beim ersten Zugriff geladen). */
    public static AdvancementRegistry get() {
        AdvancementRegistry r = instance;
        if (r == null) {
            synchronized (AdvancementRegistry.class) {
                r = instance;
                if (r == null) {
                    r = parse(ItemCatalog.resource("advancements.json"));
                    instance = r;
                }
            }
        }
        return r;
    }

    /** Liest {@code advancements.json}; Eltern müssen vor ihren Kindern stehen. */
    public static AdvancementRegistry parse(String json) {
        JsonArray arr = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("advancements");
        List<Advancement> list = new ArrayList<>();
        Map<String, Advancement> seen = new HashMap<>();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            String id = o.get("id").getAsString();
            String parent = o.has("parent") && !o.get("parent").isJsonNull() ? o.get("parent").getAsString() : null;
            int depth = 0;
            if (parent != null) {
                Advancement p = seen.get(parent);
                if (p == null) {
                    throw new IllegalArgumentException("Erfolg " + id + ": Elternteil " + parent + " fehlt oder steht dahinter");
                }
                depth = p.depth() + 1;
            }
            Map<String, Advancement.Criterion> criteria = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> ce : o.getAsJsonObject("criteria").entrySet()) {
                JsonObject c = ce.getValue().getAsJsonObject();
                Set<String> items = new LinkedHashSet<>();
                if (c.has("items")) {
                    for (JsonElement i : c.getAsJsonArray("items")) {
                        items.add(i.getAsString());
                    }
                }
                int channels = c.has("channels") ? c.get("channels").getAsInt() : 0;
                criteria.put(ce.getKey(), new Advancement.Criterion(id, ce.getKey(),
                        Trigger.fromId(c.get("trigger").getAsString()), Set.copyOf(items), channels));
            }
            List<List<String>> requirements = new ArrayList<>();
            for (JsonElement group : o.getAsJsonArray("requirements")) {
                List<String> g = new ArrayList<>();
                for (JsonElement c : group.getAsJsonArray()) {
                    if (!criteria.containsKey(c.getAsString())) {
                        throw new IllegalArgumentException("Erfolg " + id + ": unbekanntes Kriterium " + c.getAsString());
                    }
                    g.add(c.getAsString());
                }
                requirements.add(List.copyOf(g));
            }
            Advancement a = new Advancement(id, parent, o.get("icon").getAsString(), bool(o, "toast", true),
                    bool(o, "announce", true), Collections.unmodifiableMap(criteria), List.copyOf(requirements),
                    bool(o, "available", true), depth);
            if (seen.put(id, a) != null) {
                throw new IllegalArgumentException("Erfolg doppelt: " + id);
            }
            list.add(a);
        }
        return new AdvancementRegistry(list);
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsBoolean();
    }

    /** Alle Erfolge in Baumreihenfolge. */
    public List<Advancement> all() {
        return ordered;
    }

    public int size() {
        return ordered.size();
    }

    @Nullable
    public Advancement byId(String id) {
        return byId.get(id);
    }

    /** Kriterien, die durch dieses Item erfüllt werden (leer, wenn keins). */
    public List<Advancement.Criterion> criteriaForItem(String itemId) {
        return byItem.getOrDefault(itemId, List.of());
    }

    /** Alle Kriterien mit diesem Auslöser. */
    public List<Advancement.Criterion> criteria(Trigger trigger) {
        return byTrigger.getOrDefault(trigger, List.of());
    }
}
