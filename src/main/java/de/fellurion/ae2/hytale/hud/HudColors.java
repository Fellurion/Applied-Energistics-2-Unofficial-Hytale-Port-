/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.hud;

/**
 * Farben der Blockinfo. Grundtöne aus FTCores {@code Theme.ui} (wie {@code Pages/AE2/TerminalTheme.ui}), damit die
 * Anzeige zur FTCore-Leiste passt; Zellenfarben nach AE2s Drive-LEDs.
 */
public final class HudColors {

    /** Akzent der Froschteich-Oberflächen. */
    public static final String ACCENT = "#9ec47a";
    /** Werte und Fließtext. */
    public static final String TEXT = "#c9ccc8";
    /** Beschriftungen und Nebentext. */
    public static final String MUTED = "#868a85";
    /** Gut: online, Zelle leer. */
    public static final String GOOD = "#9ad47f";
    /** Achtung: kein Kanal, Typen voll. */
    public static final String WARN = "#e3a857";
    /** Schlecht: kein Strom, Konflikt, Zelle voll. */
    public static final String BAD = "#d08770";
    /** Info: Zelle teilweise belegt (AE2: blaue LED). */
    public static final String INFO = "#7fb8d9";

    private HudColors() {
    }
}
