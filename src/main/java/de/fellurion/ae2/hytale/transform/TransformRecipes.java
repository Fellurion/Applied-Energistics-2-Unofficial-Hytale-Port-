/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.recipes.transform.TransformRecipe / TransformCircumstance / TransformLogic).
 */
package de.fellurion.ae2.hytale.transform;

import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.machines.Ingredient;
import de.fellurion.ae2.core.util.MiniJson;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-Welt-Umwandlungen aus {@code ae2data/transform.json}: Gegenstände, die zusammen in einer Flüssigkeit liegen
 * oder von einer Explosion getroffen werden, werden zu etwas anderem (Fluix-Kristalle, Budding-Quarz reparieren,
 * verschränkte Singularitäten). Reine Logik ohne Hytale-Klassen; die Welt liefern {@link InWorldTransforms} und
 * {@link ExplosionTransforms}.
 *
 * <p>Regeln wie in AE2 ({@code TransformLogic.tryTransform}): In einer Flüssigkeit ist der Auslöser ein am Boden
 * liegender Gegenstand, der die <b>erste</b> Zutat eines Rezepts ist; bei einer Explosion ein Gegenstand, der
 * <b>irgendeine</b> Zutat ist (die anderen könnten in der Explosion sonst verloren gehen). Alle Zutaten müssen in
 * seiner Nähe liegen; je Zutat wird die angegebene Menge verbraucht (AE2: immer 1).
 */
public final class TransformRecipes {

    private static final String FLUID_PREFIX = "fluid:";

    /**
     * Ein Rezept.
     *
     * @param id           Kennung aus der Datei
     * @param inputs       Zutaten; die erste löst die Umwandlung aus
     * @param circumstance {@code fluid:<name>} (z. B. {@code fluid:water}) oder {@code explosion}
     * @param output       Ergebnis
     */
    public record Recipe(String id, List<Ingredient> inputs, String circumstance, Ingredient output) {

        public boolean isFluid() {
            return circumstance.startsWith(FLUID_PREFIX);
        }

        public boolean isExplosion() {
            return "explosion".equals(circumstance);
        }

        /**
         * Passt die Flüssigkeit {@code fluidKey} (Asset-Schlüssel, z. B. {@code Water}, {@code Water_Source},
         * {@code Water_Finite})? AE2 prüft den Flüssigkeits-Tag; hier zählt jede Flüssigkeit, deren Schlüssel mit dem
         * Namen beginnt.
         */
        public boolean fluidMatches(@Nullable String fluidKey) {
            if (!isFluid() || fluidKey == null) {
                return false;
            }
            String want = circumstance.substring(FLUID_PREFIX.length()).toLowerCase(Locale.ROOT);
            String have = fluidKey.toLowerCase(Locale.ROOT);
            return !want.isEmpty() && (have.equals(want) || have.startsWith(want + "_"));
        }
    }

    /** Ein Gegenstand am Boden: Item und Menge. */
    public record Pile(String itemId, int count) {
    }

    /**
     * Ein gefundenes Rezept.
     *
     * @param recipe   das Rezept
     * @param consumed je Eintrag der übergebenen Liste die Menge, die verbraucht wird
     */
    public record Match(Recipe recipe, int[] consumed) {
    }

    private final List<Recipe> recipes = new ArrayList<>();
    private final Set<String> fluidTriggers = new HashSet<>();
    private final Set<String> explosionTriggers = new HashSet<>();

    /** Liest den Inhalt von {@code ae2data/transform.json}; unlesbare Einträge werden übersprungen. */
    public static TransformRecipes fromJson(String json) {
        TransformRecipes result = new TransformRecipes();
        if (MiniJson.parse(json) instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                List<Ingredient> inputs = new ArrayList<>();
                boolean valid = true;
                for (Object in : MiniJson.getList(m, "inputs")) {
                    Ingredient ing = parse(in);
                    if (ing == null) {
                        valid = false;
                        break;
                    }
                    inputs.add(ing);
                }
                Ingredient output = parse(m.get("output"));
                String circumstance = MiniJson.getString(m, "circumstance", "");
                if (!valid || inputs.isEmpty() || output == null || output.itemId() == null || circumstance.isEmpty()) {
                    continue;
                }
                result.add(new Recipe(MiniJson.getString(m, "id", ""), List.copyOf(inputs), circumstance, output));
            }
        }
        return result;
    }

    @Nullable
    private static Ingredient parse(Object value) {
        try {
            return Ingredient.fromJson(value);
        } catch (RuntimeException e) {
            return null; // weder ItemId noch ResourceTypeId
        }
    }

    public TransformRecipes add(Recipe recipe) {
        recipes.add(recipe);
        Ingredient first = recipe.inputs().getFirst();
        if (recipe.isFluid() && first.itemId() != null) {
            fluidTriggers.add(first.itemId());
        }
        if (recipe.isExplosion()) {
            // AE2 (getTransformableItemsExplosion): jede Zutat löst aus, nicht nur die erste
            for (Ingredient ing : recipe.inputs()) {
                if (ing.itemId() != null) {
                    explosionTriggers.add(ing.itemId());
                }
            }
        }
        return this;
    }

    public List<Recipe> recipes() {
        return List.copyOf(recipes);
    }

    /**
     * Ist das Item die erste Zutat irgendeines Flüssigkeits-Rezepts? Billige Vorprüfung (ein Set-Lookup) für jeden
     * Gegenstand am Boden – AE2: {@code TransformLogic.canTransformInAnyFluid}.
     */
    public boolean isFluidTrigger(@Nullable String itemId) {
        return itemId != null && fluidTriggers.contains(itemId);
    }

    /** Gibt es für diesen Auslöser ein Rezept in dieser Flüssigkeit? AE2: {@code canTransformInFluid}. */
    public boolean canTransformIn(@Nullable String itemId, @Nullable String fluidKey) {
        if (!isFluidTrigger(itemId)) {
            return false;
        }
        for (Recipe r : recipes) {
            if (r.fluidMatches(fluidKey) && matches(r.inputs().getFirst(), itemId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sucht ein Rezept, dessen Zutaten alle vorhanden sind.
     *
     * @param fluidKey Flüssigkeit, in der der Auslöser liegt
     * @param piles    Gegenstände in Reichweite; der erste Eintrag ist der Auslöser
     * @return das erste passende Rezept mit den zu verbrauchenden Mengen, sonst null
     */
    @Nullable
    public Match match(@Nullable String fluidKey, List<Pile> piles) {
        if (piles.isEmpty()) {
            return null;
        }
        String trigger = piles.getFirst().itemId();
        for (Recipe r : recipes) {
            if (!r.fluidMatches(fluidKey) || !matches(r.inputs().getFirst(), trigger)) {
                continue;
            }
            int[] consumed = consume(r, piles);
            if (consumed != null) {
                return new Match(r, consumed);
            }
        }
        return null;
    }

    /**
     * Ist das Item eine Zutat irgendeines Explosions-Rezepts? Billige Vorprüfung (ein Set-Lookup) für jeden
     * Gegenstand, den eine Explosion trifft – AE2: {@code TransformLogic.canTransformInExplosion}.
     */
    public boolean isExplosionTrigger(@Nullable String itemId) {
        return itemId != null && explosionTriggers.contains(itemId);
    }

    /**
     * Sucht ein Explosions-Rezept, dessen Zutaten alle vorhanden sind.
     *
     * @param piles Gegenstände in Reichweite; der erste Eintrag ist der von der Explosion getroffene Gegenstand
     * @return das erste passende Rezept mit den zu verbrauchenden Mengen, sonst null
     */
    @Nullable
    public Match matchExplosion(List<Pile> piles) {
        if (piles.isEmpty()) {
            return null;
        }
        String trigger = piles.getFirst().itemId();
        for (Recipe r : recipes) {
            if (!r.isExplosion()) {
                continue;
            }
            boolean triggers = false;
            for (Ingredient ing : r.inputs()) {
                if (matches(ing, trigger)) {
                    triggers = true;
                    break;
                }
            }
            if (!triggers) {
                continue;
            }
            int[] consumed = consume(r, piles);
            if (consumed != null) {
                return new Match(r, consumed);
            }
        }
        return null;
    }

    /** Verteilt die Zutaten des Rezepts auf die Gegenstände; null, wenn eine Zutat fehlt. */
    @Nullable
    private static int[] consume(Recipe r, List<Pile> piles) {
        int[] consumed = new int[piles.size()];
        for (Ingredient ing : r.inputs()) {
            int missing = ing.quantity();
            for (int i = 0; i < piles.size() && missing > 0; i++) {
                Pile p = piles.get(i);
                int free = p.count() - consumed[i];
                if (free > 0 && matches(ing, p.itemId())) {
                    int take = Math.min(free, missing);
                    consumed[i] += take;
                    missing -= take;
                }
            }
            if (missing > 0) {
                return null;
            }
        }
        return consumed;
    }

    private static boolean matches(Ingredient ingredient, String itemId) {
        if (ingredient.itemId() != null) {
            return ingredient.itemId().equals(itemId);
        }
        return ingredient.testItem(SimpleStack.of(itemId, 1));
    }
}
