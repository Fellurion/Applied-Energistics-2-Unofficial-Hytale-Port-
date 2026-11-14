/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.hud;

import com.hypixel.hytale.server.core.Message;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Inhalt der Blockinfo: Symbol, Name, Zustandszeile, bis zu {@link #MAX_LINES} Infozeilen und ein optionaler
 * Füllbalken. Unveränderlich; {@link LookAtHud} vergleicht zwei Stände und schickt nur, was sich geändert hat.
 *
 * @param itemId     Item für das Symbol (null = kein Symbol)
 * @param name       Anzeigename
 * @param status     Zustandszeile mit Punkt davor, oder null
 * @param lines      Infozeilen (höchstens {@link #MAX_LINES})
 * @param bar        Füllstand 0..1 oder negativ für keinen Balken
 * @param barColor   Farbe des Balkens
 */
public record LookInfo(@Nullable String itemId, String name, @Nullable Span status, List<Line> lines, double bar,
                       String barColor) {

    /** Mehr Zeilen passen nicht in die Anzeige (Drive: Übersicht + 5 Zeilen mit je 2 Zellen). */
    public static final int MAX_LINES = 6;

    /** Ein Textstück in einer Farbe. */
    public record Span(String text, String color) {
    }

    /** Eine Zeile aus farbigen Textstücken. */
    public record Line(List<Span> spans) {

        /** Anzahl Zeichen (für die Breitenschätzung). */
        public int length() {
            int n = 0;
            for (Span s : spans) {
                n += s.text().length();
            }
            return n;
        }

        /** Als Nachricht für {@code TextSpans}: jedes Stück mit eigener Farbe. */
        public Message toMessage() {
            if (spans.isEmpty()) {
                return Message.raw("");
            }
            Span first = spans.getFirst();
            Message m = Message.raw(first.text()).color(first.color());
            for (int i = 1; i < spans.size(); i++) {
                Span s = spans.get(i);
                m.insert(Message.raw(s.text()).color(s.color()));
            }
            return m;
        }
    }

    public boolean hasBar() {
        return bar >= 0;
    }

    @Nullable
    public Line line(int i) {
        return i < lines.size() ? lines.get(i) : null;
    }

    public static Builder builder(@Nullable String itemId, String name) {
        return new Builder(itemId, name);
    }

    /** Wird von den {@link InfoProvider}n gefüllt. */
    public static final class Builder {
        private final String itemId;
        private String name;
        private Span status;
        private final List<Line> lines = new ArrayList<>(MAX_LINES);
        private double bar = -1;
        private String barColor = HudColors.ACCENT;
        private boolean suppressed;

        private Builder(@Nullable String itemId, String name) {
            this.itemId = itemId;
            this.name = name;
        }

        /** Anderer Anzeigename als der des Items. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Zustandszeile (mit farbigem Punkt davor). */
        public Builder status(String text, String color) {
            this.status = new Span(text, color);
            return this;
        }

        /** Zeile „Beschriftung Wert“: Beschriftung gedämpft, Wert hell. */
        public Builder line(String label, String value) {
            return line(label, value, HudColors.TEXT);
        }

        /** Zeile „Beschriftung Wert“ mit eigener Wertfarbe. */
        public Builder line(String label, String value, String valueColor) {
            return line(new Span(label + "  ", HudColors.MUTED), new Span(value, valueColor));
        }

        /** Zeile aus beliebigen Textstücken; ab {@link #MAX_LINES} Zeilen wird nichts mehr angenommen. */
        public Builder line(Span... spans) {
            if (lines.size() < MAX_LINES && spans.length > 0) {
                lines.add(new Line(List.of(spans)));
            }
            return this;
        }

        /** Zeile aus einer Liste von Textstücken. */
        public Builder line(List<Span> spans) {
            if (lines.size() < MAX_LINES && !spans.isEmpty()) {
                lines.add(new Line(List.copyOf(spans)));
            }
            return this;
        }

        /** Noch freie Zeilen. */
        public int freeLines() {
            return MAX_LINES - lines.size();
        }

        /** Füllbalken 0..1 (auf 0,5 % gerundet, damit kleine Schwankungen kein Paket auslösen). */
        public Builder bar(double fraction, String color) {
            double f = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
            this.bar = Math.round(f * 200) / 200.0;
            this.barColor = color;
            return this;
        }

        /** Für diesen Block keine Anzeige (z. B. reine Deko-Blöcke). */
        public Builder suppress() {
            this.suppressed = true;
            return this;
        }

        public boolean suppressed() {
            return suppressed;
        }

        public LookInfo build() {
            return new LookInfo(itemId, name, status, List.copyOf(lines), bar, barColor);
        }
    }
}
