/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zeitmessung des AE2-Takts je Welt ({@code /ae2 perf}). Die Phasen werden immer gemessen (wenige
 * {@link System#nanoTime()}-Aufrufe je Tick); die Zeit je Block-Art nur, solange {@link #detailTicks} läuft.
 */
public final class AE2Perf {

    /** Phasen eines AE2-Ticks in {@link WorldNetworks#tick()}. */
    public enum Phase {
        ATTACH("Anbinden"), DEVICES("Geräte vor Kern"), CORE("ME-Kern (Kanäle, Energie, Speicher, Crafting)"),
        ADVANCEMENTS("Erfolge"), AFTER("Geräte nach Kern + Blockzustand"), UI("Offene Terminals/Seiten");

        final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    /** Gleitendes Fenster: so viele Ticks fließen in Durchschnitt und Maximum ein (10 s). */
    static final int WINDOW = 200;

    private final long[][] samples = new long[Phase.values().length][WINDOW];
    private final long[] totals = new long[WINDOW];
    private int cursor;
    private long ticks;
    private final long[] current = new long[Phase.values().length];
    private long tickStart;
    private long phaseStart;

    /** Restliche Ticks mit Zeitmessung je Block-Art. */
    int detailTicks;
    private final Map<String, long[]> byKind = new HashMap<>();
    private long detailMeasured;

    void beginTick() {
        tickStart = System.nanoTime();
        phaseStart = tickStart;
        java.util.Arrays.fill(current, 0);
    }

    void endPhase(Phase p) {
        long now = System.nanoTime();
        current[p.ordinal()] += now - phaseStart;
        phaseStart = now;
    }

    void endTick() {
        long total = System.nanoTime() - tickStart;
        for (int i = 0; i < current.length; i++) {
            samples[i][cursor] = current[i];
        }
        totals[cursor] = total;
        cursor = (cursor + 1) % WINDOW;
        ticks++;
        if (detailTicks > 0) {
            detailTicks--;
            detailMeasured++;
        }
    }

    boolean detail() {
        return detailTicks > 0;
    }

    void addKind(String kind, long nanos) {
        long[] v = byKind.computeIfAbsent(kind, k -> new long[2]);
        v[0] += nanos;
        v[1]++;
    }

    /** Startet die Messung je Block-Art für {@code n} Ticks (alte Werte werden verworfen). */
    public void startDetail(int n) {
        byKind.clear();
        detailMeasured = 0;
        detailTicks = n;
    }

    /** Bericht als Textzeilen. */
    public List<String> report(WorldNetworks nets) {
        List<String> out = new ArrayList<>();
        int n = (int) Math.min(ticks, WINDOW);
        if (n == 0) {
            out.add("Noch keine Ticks gemessen.");
            return out;
        }
        long sum = 0, max = 0;
        for (int i = 0; i < n; i++) {
            sum += totals[i];
            max = Math.max(max, totals[i]);
        }
        out.add(String.format(Locale.ROOT, "Welt %s: %d AE2-Blöcke, %d Netze, %d Grid-Knoten",
                nets.world().getName(), nets.size(), nets.me().getGrids().size(), nets.me().getNodes().size()));
        out.add(String.format(Locale.ROOT, "AE2-Tick (letzte %d): Ø %.3f ms, max %.3f ms  (Budget 50 ms, %.2f %%)",
                n, sum / (double) n / 1e6, max / 1e6, sum / (double) n / 1e6 / 50.0 * 100.0));
        for (Phase p : Phase.values()) {
            long ps = 0, pm = 0;
            for (int i = 0; i < n; i++) {
                ps += samples[p.ordinal()][i];
                pm = Math.max(pm, samples[p.ordinal()][i]);
            }
            out.add(String.format(Locale.ROOT, "  %-46s Ø %.3f ms  max %.3f ms", p.label, ps / (double) n / 1e6, pm / 1e6));
        }
        if (!byKind.isEmpty() && detailMeasured > 0) {
            out.add(String.format(Locale.ROOT, "Je Block-Art (über %d Ticks, Geräte vor+nach Kern+Zustand):", detailMeasured));
            byKind.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .limit(12)
                    .forEach(e -> out.add(String.format(Locale.ROOT, "  %-28s Ø %.3f ms/Tick  (%d Aufrufe/Tick)", e.getKey(),
                            e.getValue()[0] / (double) detailMeasured / 1e6, e.getValue()[1] / detailMeasured)));
        } else if (detailTicks > 0) {
            out.add("Messung je Block-Art läuft noch " + detailTicks + " Ticks …");
        }
        return out;
    }
}
