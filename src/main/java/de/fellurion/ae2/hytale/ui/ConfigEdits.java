/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.implementations.UpgradeableMenu / StorageBusMenu / SetStockAmountMenu / PriorityMenu,
 * appeng.menu.slot.FakeSlot).
 */
package de.fellurion.ae2.hytale.ui;

import de.fellurion.ae2.core.automation.ConfigInventory;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.hytale.fluid.FluidRules;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Bearbeiten der Filter- und Vorratsplätze eines Geräts ({@link ConfigInventory}), seiner Priorität und seines
 * Zahlenfelds – die Regeln hinter {@link DeviceConfigPage}, ohne Hytale-Klassen.
 *
 * <p>Wie in AE2 sind von den 63 Filterplätzen eines Bus nur die ersten {@code 18 + 9 · Kapazitätskarten} nutzbar;
 * Plätze dahinter werden geleert, sobald eine Karte entfernt wird.
 */
public final class ConfigEdits {

    private ConfigEdits() {
    }

    /** Ergebnis von {@link #put}. */
    public enum Result {
        /** Platz gesetzt. */
        DONE,
        /** Das Item steht schon im Filter. */
        DUPLICATE,
        /** Kein freier Platz. */
        FULL,
        /** Platz gibt es nicht oder er ist nicht freigeschaltet. */
        OUT_OF_RANGE,
        /** Kein Item gewählt. */
        NOTHING
    }

    private static int visible(ConfigInventory config, int visible) {
        return Math.max(0, Math.min(visible, config.size()));
    }

    /** Erster freier Platz unter den ersten {@code visible}, sonst -1. */
    public static int firstFree(ConfigInventory config, int visible) {
        for (int i = 0, n = visible(config, visible); i < n; i++) {
            if (config.getKey(i) == null) {
                return i;
            }
        }
        return -1;
    }

    /** Platz unter den ersten {@code visible}, in dem {@code key} steht, sonst -1. */
    public static int indexOf(ConfigInventory config, int visible, @Nullable AEKey key) {
        if (key == null) {
            return -1;
        }
        for (int i = 0, n = visible(config, visible); i < n; i++) {
            if (key.equals(config.getKey(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Belegte Plätze unter den ersten {@code visible}. */
    public static int used(ConfigInventory config, int visible) {
        int used = 0;
        for (int i = 0, n = visible(config, visible); i < n; i++) {
            if (config.getKey(i) != null) {
                used++;
            }
        }
        return used;
    }

    /**
     * Legt {@code key} in einen Platz (AE2: Item auf einen Geisterplatz legen bzw. Umschalt-Klick im Inventar).
     *
     * @param slot   Zielplatz (ein belegter wird ersetzt); -1 = erster freier
     * @param amount Menge für Vorratsplätze (Interface); Filter ignorieren sie
     * @param unique true: jedes Item höchstens einmal (Filter); false: mehrfach erlaubt (Vorrat in mehreren Plätzen)
     */
    public static Result put(ConfigInventory config, int visible, int slot, @Nullable AEKey key, long amount,
                             boolean unique) {
        if (key == null || !config.isSupportedType(key)) {
            return Result.NOTHING;
        }
        int n = visible(config, visible);
        if (slot >= n || slot < -1) {
            return Result.OUT_OF_RANGE;
        }
        if (unique && indexOf(config, n, key) >= 0) {
            return Result.DUPLICATE;
        }
        int target = slot >= 0 ? slot : firstFree(config, n);
        if (target < 0) {
            return Result.FULL;
        }
        if (config.getKey(target) != null) {
            config.setStack(target, null); // ersetzen: sonst behielte ein Vorratsplatz die alte Menge
        }
        config.setStack(target, new GenericStack(key, Math.max(1, amount)));
        return Result.DONE;
    }

    /**
     * Welche Flüssigkeit ein Rechtsklick mit einem Behälter-Item auf einen Filterplatz setzt. AE2 setzt mit dem
     * Rechtsklick eines gefüllten Behälters dessen Flüssigkeit statt des Items; weil es im Spiel nur für Wasser einen
     * Behälter gibt, schaltet hier jeder weitere Rechtsklick den Platz zur nächsten lagerbaren Flüssigkeit weiter.
     * <ul>
     *   <li>leerer Platz, gefüllter Behälter: dessen Flüssigkeit</li>
     *   <li>leerer Platz, leerer Behälter: die erste lagerbare Flüssigkeit, die noch nicht im Filter steht</li>
     *   <li>Platz mit Flüssigkeit: die nächste, die noch nicht im Filter steht (nach der letzten wieder die erste)</li>
     * </ul>
     *
     * @param contained Inhalt des Behälters; null = er ist leer
     * @param storable  Kennungen aller lagerbaren Flüssigkeiten in fester Reihenfolge
     * @return die Flüssigkeit für den Platz; null, wenn dort ein Item steht oder es keine andere freie gibt
     */
    @Nullable
    public static AEFluidKey fluidForSlot(ConfigInventory config, int visible, int slot, @Nullable AEFluidKey contained,
                                          List<String> storable) {
        int n = visible(config, visible);
        if (slot < 0 || slot >= n) {
            return null;
        }
        AEKey current = config.getKey(slot);
        if (current == null && contained != null) {
            return contained;
        }
        if (current != null && !(current instanceof AEFluidKey)) {
            return null;
        }
        String next = FluidRules.next(storable, current == null ? null : current.getId(),
                id -> indexOf(config, n, AEFluidKey.of(id)) >= 0);
        return next == null ? null : AEFluidKey.of(next);
    }

    /** Leert einen Platz. @return true, wenn dort etwas stand */
    public static boolean clear(ConfigInventory config, int visible, int slot) {
        if (slot < 0 || slot >= visible(config, visible) || config.getKey(slot) == null) {
            return false;
        }
        config.setStack(slot, null);
        return true;
    }

    /**
     * Leert alle Plätze hinter den ersten {@code visible} (AE2 leert deaktivierte Plätze, wenn eine Kapazitätskarte
     * entfernt wird). @return Anzahl geleerter Plätze
     */
    public static int clearHidden(ConfigInventory config, int visible) {
        int cleared = 0;
        for (int i = visible(config, visible); i < config.size(); i++) {
            if (config.getKey(i) != null) {
                config.setStack(i, null);
                cleared++;
            }
        }
        return cleared;
    }

    /**
     * Füllt den Filter mit dem, was gerade im angeschlossenen Inventar liegt (AE2: {@code StorageBusMenu.partition}):
     * die ersten {@code visible} Plätze bekommen die Items in der gelieferten Reihenfolge, alle übrigen werden leer.
     *
     * @return Anzahl gesetzter Plätze
     */
    public static int partition(ConfigInventory config, int visible, Iterable<? extends AEKey> contents) {
        int n = visible(config, visible);
        var it = contents.iterator();
        int set = 0;
        for (int i = 0; i < config.size(); i++) {
            AEKey next = null;
            while (i < n && next == null && it.hasNext()) {
                AEKey candidate = it.next();
                if (candidate != null && config.isSupportedType(candidate)) {
                    next = candidate;
                }
            }
            if (next == null) {
                config.setStack(i, null);
            } else {
                config.setStack(i, null);
                config.setStack(i, new GenericStack(next, 1));
                set++;
            }
        }
        return set;
    }

    /**
     * Ändert die Vorratsmenge eines Platzes um {@code delta}; sie bleibt zwischen 1 und der Stapelgröße (geleert
     * wird ein Platz durch Anklicken, nicht über die Menge).
     *
     * @return die neue Menge, 0 bei leerem Platz
     */
    public static long changeAmount(ConfigInventory config, int slot, long delta) {
        if (slot < 0 || slot >= config.size()) {
            return 0;
        }
        GenericStack cur = config.getStack(slot);
        if (cur == null) {
            return 0;
        }
        long max = Math.max(1, config.getMaxAmount(cur.what()));
        long next = Math.max(1, Math.min(max, cur.amount() + delta));
        if (next != cur.amount()) {
            config.setStack(slot, new GenericStack(cur.what(), next));
        }
        return config.getAmount(slot);
    }

    /** Priorität plus {@code delta}, ohne Überlauf (AE2 erlaubt jeden int-Wert, auch negative). */
    public static int addPriority(int current, int delta) {
        long sum = (long) current + delta;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum));
    }

    /** Vom Spieler eingegebene Priorität (Zahlenfeld) als int; unlesbare Werte ergeben {@code fallback}. */
    public static int toPriority(@Nullable Double value, int fallback) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return fallback;
        }
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(value)));
    }

    /**
     * Zahlenfeld eines Geräts ({@link ConfigHost.NumberField}): {@code current + delta}, ohne Überlauf und auf
     * {@code min..max} begrenzt.
     */
    public static long addNumber(long current, long delta, long min, long max) {
        long sum = current + delta;
        if (delta > 0 && sum < current) {
            sum = Long.MAX_VALUE;
        } else if (delta < 0 && sum > current) {
            sum = Long.MIN_VALUE;
        }
        return clamp(sum, min, max);
    }

    /** Vom Spieler eingegebene Zahl (Zahlenfeld), auf {@code min..max} begrenzt; unlesbare Werte ergeben {@code fallback}. */
    public static long toNumber(@Nullable Double value, long fallback, long min, long max) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return fallback;
        }
        return clamp(Math.round(value), min, max);
    }

    private static long clamp(long value, long min, long max) {
        return max < min ? min : Math.max(min, Math.min(max, value));
    }
}
