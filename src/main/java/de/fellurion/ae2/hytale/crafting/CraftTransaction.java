/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.slot.CraftingTermSlot – Zutaten entnehmen, bei Fehlschlag zurücklegen).
 */
package de.fellurion.ae2.hytale.crafting;

import java.util.List;

/**
 * Entnimmt die Zutaten einer Herstellung im Crafting-Terminal als Ganzes: erst alles simulieren, dann entnehmen,
 * und wenn doch ein Schritt weniger liefert als geplant, alles bisher Entnommene zurückgeben. So gehen nie Items
 * verloren, und es entsteht nie ein Ergebnis ohne vollständig bezahlte Zutaten. Reine Logik ohne Server.
 */
public final class CraftTransaction {

    private CraftTransaction() {
    }

    /** Ein Entnahme-Schritt (ein Netz-Eintrag oder ein Inventarfeld). */
    public interface Step {

        /** Geplante Stückzahl. */
        long wanted();

        /** Stückzahl, die sich jetzt entnehmen ließe, ohne etwas zu ändern. */
        long simulate();

        /** Entnimmt bis zu {@link #wanted()} Stück und liefert die entnommene Stückzahl. */
        long take();

        /** Gibt alles zurück, was {@link #take()} entnommen hat (Rückabwicklung). */
        void giveBack();
    }

    public enum Outcome {
        /** Alle Schritte haben vollständig geliefert. */
        DONE,
        /** Die Simulation hat eine Lücke gezeigt – nichts wurde angefasst. */
        NOT_AVAILABLE,
        /** Ein Schritt lieferte zu wenig – alles Entnommene wurde zurückgegeben. */
        ROLLED_BACK
    }

    public static Outcome run(List<? extends Step> steps) {
        for (Step s : steps) {
            if (s.simulate() < s.wanted()) {
                return Outcome.NOT_AVAILABLE;
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            long got;
            try {
                got = s.take();
            } catch (RuntimeException e) {
                // Der Schritt selbst kann schon etwas entnommen haben: mit zurückgeben
                got = -1;
            }
            if (got < s.wanted()) {
                for (int j = i; j >= 0; j--) {
                    try {
                        steps.get(j).giveBack();
                    } catch (RuntimeException ignored) {
                        // die übrigen Schritte trotzdem zurückgeben
                    }
                }
                return Outcome.ROLLED_BACK;
            }
        }
        return Outcome.DONE;
    }
}
