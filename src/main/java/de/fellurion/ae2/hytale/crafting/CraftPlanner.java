/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.items.CraftingTermMenu#findMissingIngredients, appeng.menu.slot.CraftingTermSlot).
 */
package de.fellurion.ae2.hytale.crafting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rechnet aus, womit sich ein Rezept im Crafting-Terminal herstellen lässt – reine Logik ohne Server.
 *
 * <p>Wie in AE2 kommen die Zutaten zuerst aus dem ME-Netz, was dort fehlt aus dem Inventar des Spielers. Eine Zutat
 * ist bei Hytale kein einzelnes Item, sondern ein Bedarf in <b>Einheiten</b>: ein festes Item zählt eine Einheit,
 * ein Ressourcentyp („beliebiger Stamm“) kann von verschiedenen Items gedeckt werden, und ein Item kann mehrere
 * Einheiten wert sein. Gerechnet wird wie in Hytales Werkbank: Von einem Item, das {@code n} Einheiten wert ist,
 * werden {@code ceil(Rest / n)} Stück genommen; was dabei übersteht, verfällt.
 *
 * <p>Vorräte mit eigenen Metadaten (Seelenbund, gefüllte Speicherzellen, kodierte Patterns …) werden nie als Zutat
 * verwendet ({@link Stock#plain()}).
 */
public final class CraftPlanner {

    private CraftPlanner() {
    }

    /** Woher ein Vorrat stammt; das Netz wird vor dem Inventar verbraucht. */
    public enum Source { NETWORK, INVENTORY }

    /**
     * Ein Vorrat: ein Eintrag des ME-Netzes oder ein Feld im Inventar des Spielers.
     *
     * @param ref    frei für den Aufrufer (z. B. Index des Netz-Eintrags oder des Inventarfelds)
     * @param plain  true, wenn das Item keine eigenen Metadaten trägt (nur solche werden verbraucht)
     * @param amount vorhandene Stückzahl
     */
    public record Stock(Source source, int ref, String itemId, boolean plain, long amount) {
    }

    /**
     * Eine Zutat eines Rezepts.
     *
     * @param units       benötigte Einheiten je Herstellung
     * @param unitsByItem welche Items die Zutat decken und wie viele Einheiten ein Stück wert ist
     */
    public record Ingredient(long units, Map<String, Integer> unitsByItem) {

        /** Genau ein bestimmtes Item, ein Stück = eine Einheit. */
        public static Ingredient of(String itemId, long amount) {
            return new Ingredient(amount, Map.of(itemId, 1));
        }

        /** true, wenn nur ein einziges Item passt (wird vor den Ressourcentypen bedient). */
        public boolean exact() {
            return unitsByItem.size() == 1;
        }

        int unitsOf(String itemId) {
            Integer u = unitsByItem.get(itemId);
            return u == null ? 0 : Math.max(0, u);
        }
    }

    /**
     * Eine Entnahme des Plans.
     *
     * @param stock  Index in der Vorratsliste
     * @param amount Stückzahl
     */
    public record Take(int stock, long amount) {
    }

    /**
     * Stand einer Zutat (in Einheiten), in der Reihenfolge der Zutaten des Rezepts.
     *
     * @param need      Bedarf für alle geplanten Herstellungen
     * @param network   insgesamt im Netz vorhanden
     * @param inventory insgesamt im Inventar vorhanden
     * @param covered   davon durch den Plan gedeckt (höchstens {@code need}); andere Zutaten desselben Rezepts
     *                  können denselben Vorrat schon verbraucht haben
     * @param firstUsed Index des ersten Vorrats, den der Plan für diese Zutat nimmt, sonst -1
     */
    public record Line(long need, long network, long inventory, long covered, int firstUsed) {

        public long have() {
            return network + inventory;
        }

        public boolean satisfied() {
            return covered >= need;
        }

        public long missing() {
            return Math.max(0, need - covered);
        }
    }

    /**
     * Ergebnis einer Planung.
     *
     * @param crafts   geplante Herstellungen
     * @param complete true, wenn alle Zutaten gedeckt sind
     * @param takes    Entnahmen, je Vorrat zusammengefasst (jeder Vorrat höchstens einmal)
     * @param lines    Stand je Zutat
     */
    public record Plan(int crafts, boolean complete, List<Take> takes, List<Line> lines) {
    }

    /** Vorratsliste mit Suchindex nach Item; für mehrere Rezepte wiederverwendbar. */
    public static final class Stocks {
        private final List<Stock> list;
        private final Map<String, int[]> byItem = new HashMap<>();

        private Stocks(List<Stock> stocks) {
            this.list = List.copyOf(stocks);
            Map<String, List<Integer>> idx = new HashMap<>();
            for (int i = 0; i < list.size(); i++) {
                Stock s = list.get(i);
                if (s.plain() && s.amount() > 0) {
                    idx.computeIfAbsent(s.itemId(), k -> new ArrayList<>()).add(i);
                }
            }
            idx.forEach((k, v) -> byItem.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        }

        public List<Stock> list() {
            return list;
        }

        public Stock get(int index) {
            return list.get(index);
        }

        /** Verwendbare Vorräte einer Zutat: erst Netz, dann Inventar, sonst in Listenreihenfolge. */
        private List<Integer> candidates(Ingredient ingredient) {
            List<Integer> out = new ArrayList<>();
            for (String itemId : ingredient.unitsByItem().keySet()) {
                if (ingredient.unitsOf(itemId) <= 0) {
                    continue;
                }
                int[] idx = byItem.get(itemId);
                if (idx != null) {
                    for (int i : idx) {
                        out.add(i);
                    }
                }
            }
            return out;
        }
    }

    public static Stocks index(List<Stock> stocks) {
        return new Stocks(stocks);
    }

    /**
     * Plant {@code crafts} Herstellungen. Der Plan ist auch dann vollständig beschrieben, wenn Zutaten fehlen
     * ({@link Plan#complete()} ist dann false, {@link Line#missing()} nennt die Lücke).
     */
    public static Plan plan(List<Ingredient> ingredients, Stocks stocks, int crafts) {
        int n = Math.max(0, crafts);
        long[] left = new long[stocks.list.size()];
        for (int i = 0; i < left.length; i++) {
            left[i] = stocks.list.get(i).amount();
        }
        // Wie viele Zutaten denselben Vorrat brauchen könnten: umkämpfte Vorräte zuletzt anfassen
        int[] demand = new int[left.length];
        List<List<Integer>> candidates = new ArrayList<>(ingredients.size());
        for (Ingredient ing : ingredients) {
            List<Integer> c = stocks.candidates(ing);
            candidates.add(c);
            for (int i : c) {
                demand[i]++;
            }
        }
        // Feste Items zuerst: sie haben keine Ausweichmöglichkeit
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing((Integer i) -> !ingredients.get(i).exact()).thenComparing(i -> i));

        Map<Integer, Long> taken = new LinkedHashMap<>();
        Line[] lines = new Line[ingredients.size()];
        boolean complete = true;
        for (int idx : order) {
            Ingredient ing = ingredients.get(idx);
            long need = multiply(ing.units(), n);
            List<Integer> c = new ArrayList<>(candidates.get(idx));
            c.sort(Comparator.comparing((Integer i) -> stocks.list.get(i).source())
                    .thenComparing(i -> demand[i]).thenComparing(i -> i));
            long network = 0;
            long inventory = 0;
            long remaining = need;
            int firstUsed = -1;
            for (int i : c) {
                Stock s = stocks.list.get(i);
                int per = ing.unitsOf(s.itemId());
                long total = multiply(s.amount(), per);
                if (s.source() == Source.NETWORK) {
                    network = add(network, total);
                } else {
                    inventory = add(inventory, total);
                }
                if (remaining <= 0 || left[i] <= 0) {
                    continue;
                }
                long items = Math.min(left[i], ceilDiv(remaining, per));
                left[i] -= items;
                taken.merge(i, items, Long::sum);
                remaining -= multiply(items, per);
                if (firstUsed < 0) {
                    firstUsed = i;
                }
            }
            long covered = Math.max(0, need - Math.max(0, remaining));
            lines[idx] = new Line(need, network, inventory, covered, firstUsed);
            complete &= remaining <= 0;
        }
        List<Take> takes = new ArrayList<>(taken.size());
        taken.forEach((stock, amount) -> takes.add(new Take(stock, amount)));
        return new Plan(n, complete, List.copyOf(takes), List.of(lines));
    }

    /** Wie oft sich das Rezept mit den Vorräten höchstens herstellen lässt (0..{@code limit}). */
    public static int maxCrafts(List<Ingredient> ingredients, Stocks stocks, int limit) {
        if (limit <= 0 || ingredients.isEmpty()) {
            return 0;
        }
        // Obergrenze je Zutat aus dem Gesamtbestand, danach binär suchen (gemeinsam genutzte Vorräte zählen nur einmal)
        long hi = limit;
        Plan one = plan(ingredients, stocks, 1);
        for (int i = 0; i < ingredients.size(); i++) {
            long units = Math.max(1, ingredients.get(i).units());
            hi = Math.min(hi, one.lines().get(i).have() / units);
        }
        if (hi <= 0 || !one.complete()) {
            return 0;
        }
        long lo = 1;
        while (lo < hi) {
            long mid = lo + (hi - lo + 1) / 2;
            if (plan(ingredients, stocks, (int) mid).complete()) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return (int) lo;
    }

    private static long ceilDiv(long a, long b) {
        return b <= 0 ? Long.MAX_VALUE : (a + b - 1) / b;
    }

    private static long multiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE / 4;
        }
    }

    private static long add(long a, long b) {
        long r = a + b;
        return r < 0 ? Long.MAX_VALUE / 4 : r;
    }
}
