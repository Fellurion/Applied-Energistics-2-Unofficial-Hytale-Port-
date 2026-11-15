/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.fluid;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Regeln, welche Flüssigkeiten des Spiels das ME-Netz lagert – ohne Hytale-Klassen, damit sie sich prüfen lassen.
 *
 * <p>Gelagert wird nur die <b>Quelle</b> einer Flüssigkeit (ein voller Block = 1000 mB = ein Eimer); fließende
 * Flüssigkeit, Feuer und endliches Testwasser sind keine Quellen. Was eine Quelle ist, steht in den Assets des Spiels
 * ({@link FluidTypes}); die Liste lässt sich im Datenordner des Plugins erweitern und einschränken
 * ({@link FluidConfig}).
 */
public final class FluidRules {

    /** Kennung der leeren Zelle im Spiel. */
    public static final String EMPTY = "Empty";
    private static final String SOURCE_SUFFIX = "_Source";

    private FluidRules() {
    }

    /**
     * Eine Flüssigkeit des Spiels.
     *
     * @param id      Asset-Kennung, z. B. {@code Water_Source}
     * @param source  true: Quelle – sie verbreitet sich, verschwindet aber nicht von selbst
     * @param unknown true: Platzhalter für ein Asset, das es nicht (mehr) gibt
     */
    public record Candidate(String id, boolean source, boolean unknown) {
    }

    /**
     * Die lagerbaren Flüssigkeiten in der Reihenfolge von {@code all}: alle Quellen und alles aus {@code allow},
     * ohne {@code deny}, ohne Platzhalter und ohne die leere Zelle.
     */
    public static List<String> storable(Collection<Candidate> all, Set<String> allow, Set<String> deny) {
        List<String> out = new ArrayList<>();
        for (Candidate c : all) {
            if (c.id() == null || EMPTY.equals(c.id()) || c.unknown() || deny.contains(c.id()) || out.contains(c.id())) {
                continue;
            }
            if (c.source() || allow.contains(c.id())) {
                out.add(c.id());
            }
        }
        return out;
    }

    /** Name ohne die Endung der Quelle: {@code Water_Source} → {@code Water}. */
    public static String baseName(String fluidId) {
        return fluidId.endsWith(SOURCE_SUFFIX) && fluidId.length() > SOURCE_SUFFIX.length()
                ? fluidId.substring(0, fluidId.length() - SOURCE_SUFFIX.length()) : fluidId;
    }

    /** Das Item, das die Flüssigkeit im Spiel üblicherweise darstellt: {@code Water_Source} → {@code Fluid_Water}. */
    public static String conventionalIconItem(String fluidId) {
        return "Fluid_" + baseName(fluidId);
    }

    /** Lesbarer Ersatzname, wenn es keine Übersetzung gibt: {@code Slime_Red_Source} → {@code Slime Red}. */
    public static String fallbackName(String fluidId) {
        return baseName(fluidId).replace('_', ' ');
    }

    /**
     * Die nächste Flüssigkeit beim Durchschalten eines Filterplatzes: die erste nach {@code current}, die noch nicht
     * vergeben ist; nach der letzten geht es vorn weiter.
     *
     * @param current die Flüssigkeit im Platz; null = der Platz ist leer, es beginnt vorn
     * @param taken   Flüssigkeiten, die schon in einem anderen Platz stehen
     * @return null, wenn es keine andere freie Flüssigkeit gibt
     */
    @Nullable
    public static String next(List<String> storable, @Nullable String current, Predicate<String> taken) {
        int n = storable.size();
        int start = current == null ? -1 : storable.indexOf(current);
        for (int i = 1; i <= n; i++) {
            String candidate = storable.get(Math.floorMod(start + i, n));
            if (!candidate.equals(current) && !taken.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
