/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.crafting;

import de.fellurion.ae2.hytale.crafting.CraftTransaction.Outcome;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Zutaten entnehmen: erst simulieren, dann nehmen, bei einer Lücke alles zurück (ohne Server). */
class CraftTransactionTest {

    /** Ein Lager mit Energie: jede Entnahme kostet 1 Energie je Item, wie das ME-Netz. */
    private static final class Store {
        final Map<String, Long> items = new HashMap<>();
        long energy;
        /** Simulation zeigt mehr, als sich wirklich entnehmen lässt (z. B. etwas greift dazwischen). */
        long lyingSimulation;
        boolean explode;

        long total() {
            return items.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private static final class Step implements CraftTransaction.Step {
        final Store store;
        final String item;
        final long wanted;
        long taken;
        int simulated;

        Step(Store store, String item, long wanted) {
            this.store = store;
            this.item = item;
            this.wanted = wanted;
        }

        @Override
        public long wanted() {
            return wanted;
        }

        @Override
        public long simulate() {
            simulated++;
            return Math.min(store.items.getOrDefault(item, 0L), store.energy) + store.lyingSimulation;
        }

        @Override
        public long take() {
            long n = Math.min(wanted, Math.min(store.items.getOrDefault(item, 0L), store.energy));
            store.items.merge(item, -n, Long::sum);
            store.energy -= n;
            taken = n;
            if (store.explode) {
                throw new IllegalStateException("kaputt");
            }
            return n;
        }

        @Override
        public void giveBack() {
            store.items.merge(item, taken, Long::sum);
            taken = 0;
        }
    }

    @Test
    void allesDaAllesEntnommen() {
        Store net = new Store();
        net.items.put("Iron", 10L);
        net.items.put("Copper", 5L);
        net.energy = 100;
        Store bag = new Store();
        bag.items.put("Iron", 3L);
        bag.energy = 100;

        Outcome outcome = CraftTransaction.run(List.of(new Step(net, "Iron", 10), new Step(net, "Copper", 2),
                new Step(bag, "Iron", 3)));

        assertEquals(Outcome.DONE, outcome);
        assertEquals(0, net.items.get("Iron"));
        assertEquals(3, net.items.get("Copper"));
        assertEquals(0, bag.items.get("Iron"));
        assertEquals(88, net.energy);
    }

    @Test
    void simulationZeigtLueckeNichtsWirdAngefasst() {
        Store net = new Store();
        net.items.put("Iron", 10L);
        net.items.put("Copper", 1L);
        net.energy = 100;
        Step first = new Step(net, "Iron", 10);
        Step second = new Step(net, "Copper", 2);

        assertEquals(Outcome.NOT_AVAILABLE, CraftTransaction.run(List.of(first, second)));
        assertEquals(10, net.items.get("Iron"));
        assertEquals(1, net.items.get("Copper"));
        assertEquals(100, net.energy);
        assertEquals(0, first.taken);
    }

    @Test
    void zuWenigEnergieNichtsWirdAngefasst() {
        Store net = new Store();
        net.items.put("Iron", 10L);
        net.energy = 4;

        assertEquals(Outcome.NOT_AVAILABLE, CraftTransaction.run(List.of(new Step(net, "Iron", 10))));
        assertEquals(10, net.items.get("Iron"));
    }

    @Test
    void lueckeBeimEntnehmenGibtAllesZurueck() {
        // Die Energie reicht für jeden Schritt einzeln, aber nicht für beide: der zweite liefert zu wenig
        Store net = new Store();
        net.items.put("Iron", 10L);
        net.items.put("Copper", 10L);
        net.energy = 12;
        Store bag = new Store();
        bag.items.put("Gold", 1L);
        bag.energy = 100;
        Step third = new Step(bag, "Gold", 1);
        long before = net.total() + bag.total();

        Outcome outcome = CraftTransaction.run(List.of(new Step(net, "Iron", 8), new Step(net, "Copper", 8), third));

        assertEquals(Outcome.ROLLED_BACK, outcome);
        assertEquals(10, net.items.get("Iron"));   // vollständig zurück
        assertEquals(10, net.items.get("Copper")); // auch die 4 Stück, die der zweite Schritt noch bekam
        assertEquals(1, bag.items.get("Gold"));    // der dritte Schritt wurde nie ausgeführt
        assertEquals(0, third.taken);
        assertEquals(before, net.total() + bag.total(), "kein Item verloren oder verdoppelt");
    }

    @Test
    void fehlerBeimEntnehmenGibtAllesZurueck() {
        Store net = new Store();
        net.items.put("Iron", 10L);
        net.energy = 100;
        Store broken = new Store();
        broken.items.put("Copper", 5L);
        broken.energy = 100;
        broken.explode = true;

        Outcome outcome = CraftTransaction.run(List.of(new Step(net, "Iron", 4), new Step(broken, "Copper", 5)));

        assertEquals(Outcome.ROLLED_BACK, outcome);
        assertEquals(10, net.items.get("Iron"));
        assertEquals(5, broken.items.get("Copper")); // was der fehlerhafte Schritt schon genommen hatte, ist zurück
    }

    @Test
    void erstAllesSimulierenDannEntnehmen() {
        Store net = new Store();
        net.items.put("Iron", 3L);
        net.energy = 100;
        net.lyingSimulation = 5; // Simulation verspricht mehr, als da ist
        Step step = new Step(net, "Iron", 5);

        assertEquals(Outcome.ROLLED_BACK, CraftTransaction.run(List.of(step)));
        assertEquals(1, step.simulated);
        assertEquals(3, net.items.get("Iron"));
    }
}
