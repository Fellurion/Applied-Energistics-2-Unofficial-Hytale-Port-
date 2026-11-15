/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.client.gui.me.patternaccess.PatternAccessTermScreen#refreshList / #getPatternSearchText).
 */
package de.fellurion.ae2.hytale.crafting;

import de.fellurion.ae2.core.crafting.IPatternDetails;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.GenericStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Suche des Pattern-Zugriffsterminals – reine Logik ohne Server.
 *
 * <p>Wie in AE2 bleibt ein Pattern Provider sichtbar, wenn sein Name zum Suchtext passt oder eines seiner Patterns.
 * AE2 durchsucht bei Patterns die Namen der Ergebnisse; hier zählen zusätzlich die Zutaten (jeweils die Hauptzutat,
 * nicht ihre Ersatz-Items – sonst träfe „Holz“ jedes Rezept mit beliebigem Stamm). Groß-/Kleinschreibung ist egal;
 * gesucht wird im übersetzten Namen und in der Item-Id (auch mit Leerzeichen statt Unterstrichen).
 */
public final class PatternSearch {

    private PatternSearch() {
    }

    /** Suchtext auf Kleinbuchstaben ohne Rand; leer = alles zeigen. */
    public static String normalize(@Nullable String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Suchtext eines Patterns: Namen und Ids der Ergebnisse und Zutaten, klein geschrieben, eine Zeile je Item.
     *
     * @param itemName liefert den (übersetzten) Namen zu einer Item-Id
     */
    public static String text(@Nullable IPatternDetails pattern, Function<String, String> itemName) {
        if (pattern == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (GenericStack out : pattern.getOutputs()) {
            append(sb, out, itemName);
        }
        for (IPatternDetails.IInput in : pattern.getInputs()) {
            GenericStack[] possible = in.getPossibleInputs();
            if (possible != null && possible.length > 0) {
                append(sb, possible[0], itemName);
            }
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, @Nullable GenericStack stack, Function<String, String> itemName) {
        if (stack == null || !(stack.what() instanceof AEItemKey key)) {
            return;
        }
        String id = key.itemId().toLowerCase(Locale.ROOT);
        String name = itemName.apply(key.itemId());
        if (name != null && !name.isBlank()) {
            sb.append(name.toLowerCase(Locale.ROOT)).append('\n');
        }
        sb.append(id).append('\n');
        if (id.indexOf('_') >= 0) {
            sb.append(id.replace('_', ' ')).append('\n');
        }
    }

    /** Passt ein einzelnes Pattern (sein {@link #text Suchtext}) zum normalisierten Suchtext? */
    public static boolean matchesPattern(String query, @Nullable String patternText) {
        return query.isEmpty() || (patternText != null && patternText.contains(query));
    }

    /**
     * Bleibt ein Pattern Provider bei diesem Suchtext sichtbar?
     *
     * @param query        normalisierter Suchtext ({@link #normalize})
     * @param providerName Name (und Position) des Providers
     * @param patternTexts Suchtexte seiner Patterns
     */
    public static boolean matchesProvider(String query, @Nullable String providerName, List<String> patternTexts) {
        if (query.isEmpty()) {
            return true;
        }
        if (providerName != null && providerName.toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (String text : patternTexts) {
            if (text != null && !text.isEmpty() && text.contains(query)) {
                return true;
            }
        }
        return false;
    }
}
