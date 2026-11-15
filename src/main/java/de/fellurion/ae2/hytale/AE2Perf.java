/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.server.core.Message;

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
        ATTACH("attach"), DEVICES("devices"), CORE("core"), ADVANCEMENTS("advancements"), AFTER("after"), UI("ui"),
        METEORITES("meteorites");

        /** Übersetzungsschlüssel {@code ae2cmd.perf.phase.<key>}. */
        final String key;

        Phase(String key) {
            this.key = key;
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

    /** Rechnet Arbeit nach dem Netz-Takt (Meteoriten) dem zuletzt gemessenen Tick zu. */
    void addAfterTick(Phase p, long nanos) {
        int last = (cursor + WINDOW - 1) % WINDOW;
        samples[p.ordinal()][last] += nanos;
        totals[last] += nanos;
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

    /** Bericht als übersetzte Zeilen ({@code ae2cmd.perf.*}). */
    public List<Message> report(WorldNetworks nets) {
        List<Message> out = new ArrayList<>();
        int n = (int) Math.min(ticks, WINDOW);
        if (n == 0) {
            out.add(Message.translation("ae2cmd.perf.none"));
            return out;
        }
        long sum = 0, max = 0;
        for (int i = 0; i < n; i++) {
            sum += totals[i];
            max = Math.max(max, totals[i]);
        }
        out.add(Message.translation("ae2cmd.perf.world").param("world", nets.world().getName())
                .param("blocks", nets.size()).param("grids", nets.me().getGrids().size())
                .param("nodes", nets.me().getNodes().size()));
        out.add(Message.translation("ae2cmd.perf.tick").param("ticks", n).param("avg", ms(sum / (double) n))
                .param("max", ms(max)).param("percent", String.format(Locale.ROOT, "%.2f", sum / (double) n / 1e6 / 50.0 * 100.0)));
        for (Phase p : Phase.values()) {
            long ps = 0, pm = 0;
            for (int i = 0; i < n; i++) {
                ps += samples[p.ordinal()][i];
                pm = Math.max(pm, samples[p.ordinal()][i]);
            }
            out.add(Message.translation("ae2cmd.perf.phase")
                    .param("phase", Message.translation("ae2cmd.perf.phase." + p.key))
                    .param("avg", ms(ps / (double) n)).param("max", ms(pm)));
        }
        if (!byKind.isEmpty() && detailMeasured > 0) {
            out.add(Message.translation("ae2cmd.perf.kinds").param("ticks", detailMeasured));
            byKind.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .limit(12)
                    .forEach(e -> out.add(Message.translation("ae2cmd.perf.kind").param("kind", e.getKey())
                            .param("avg", ms(e.getValue()[0] / (double) detailMeasured))
                            .param("calls", e.getValue()[1] / detailMeasured)));
        } else if (detailTicks > 0) {
            out.add(Message.translation("ae2cmd.perf.detailLeft").param("ticks", detailTicks));
        }
        return out;
    }

    /** Nanosekunden als Millisekunden mit 3 Nachkommastellen. */
    private static String ms(double nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1e6);
    }
}
