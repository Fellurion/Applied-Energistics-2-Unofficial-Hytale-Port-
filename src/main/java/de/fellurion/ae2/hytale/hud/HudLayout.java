/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.hud;

/**
 * Maße der Blockinfo (Pixel), passend zu {@code Hud/AE2/LookAt.ui}. Die Anzeige ist so breit wie ihr längster Text
 * (geschätzt, auf {@link #WIDTH_STEP} gerundet, damit sie bei wechselnden Zahlen nicht zappelt) und so hoch wie
 * ihre Zeilen. Unsichtbare Elemente bekommen keine Lage: alle Positionen stehen absolut.
 *
 * @param width  Breite der Platte
 * @param height Höhe der Platte
 * @param status mit Zustandszeile
 * @param lines  Anzahl Infozeilen
 * @param bar    mit Füllbalken
 */
public record HudLayout(int width, int height, boolean status, int lines, boolean bar) {

    /** Linker Rand der Texte (rechts neben dem 32-px-Symbol). */
    public static final int TEXT_LEFT = 52;
    /** Rechter Rand der Texte und des Balkens. */
    public static final int RIGHT_PAD = 12;
    /** Oberkante der ersten Zeile unter dem Namen. */
    public static final int FIRST_ROW = 26;
    public static final int ROW_HEIGHT = 15;
    public static final int BAR_GAP = 4;
    public static final int BAR_HEIGHT = 4;
    public static final int BOTTOM_PAD = 8;
    /** Mindesthöhe: Symbol 32 px mit 9 px Rand oben und unten. */
    public static final int MIN_HEIGHT = 50;
    public static final int MIN_WIDTH = 220;
    public static final int MAX_WIDTH = 440;
    public static final int WIDTH_STEP = 20;
    /** Platz für das Kürzel „AE2“ rechts neben dem Namen. */
    public static final int MOD_TAG_WIDTH = 34;

    /** Geschätzte Zeichenbreite: Name (12 px fett) bzw. Zeilen (10 px). */
    static final double NAME_CHAR = 7.5;
    static final double LINE_CHAR = 6.3;

    public static HudLayout of(LookInfo info) {
        boolean status = info.status() != null;
        int lines = info.lines().size();
        int rows = (status ? 1 : 0) + lines;
        int bottom = FIRST_ROW + rows * ROW_HEIGHT;
        if (info.hasBar()) {
            bottom += BAR_GAP + BAR_HEIGHT;
        }
        int height = Math.max(MIN_HEIGHT, bottom + BOTTOM_PAD);

        double text = info.name().length() * NAME_CHAR + MOD_TAG_WIDTH;
        if (status) {
            text = Math.max(text, 11 + info.status().text().length() * LINE_CHAR);
        }
        for (LookInfo.Line line : info.lines()) {
            text = Math.max(text, line.length() * LINE_CHAR);
        }
        int raw = (int) Math.ceil(TEXT_LEFT + RIGHT_PAD + text);
        int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, ceilTo(raw, WIDTH_STEP)));
        return new HudLayout(width, height, status, lines, info.hasBar());
    }

    private static int ceilTo(int v, int step) {
        return (v + step - 1) / step * step;
    }

    /** Oberkante der Zustandszeile. */
    public int statusTop() {
        return FIRST_ROW;
    }

    /** Oberkante der Infozeile {@code i}. */
    public int lineTop(int i) {
        return FIRST_ROW + ((status ? 1 : 0) + i) * ROW_HEIGHT;
    }

    /** Oberkante des Balkens. */
    public int barTop() {
        return lineTop(lines) + BAR_GAP;
    }

    /** Breite der Balkenbahn. */
    public int barWidth() {
        return width - TEXT_LEFT - RIGHT_PAD;
    }
}
