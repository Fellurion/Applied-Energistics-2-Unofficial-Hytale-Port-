/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.api.upgrades.IUpgradeableObject, appeng.api.util.IConfigurableObject, appeng.helpers.IPriorityHost,
 * appeng.helpers.IConfigInvHost – was AE2s UpgradeableMenu und seine Screens von ihrem Host brauchen).
 */
package de.fellurion.ae2.hytale.ui;

import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import de.fellurion.ae2.core.automation.ConfigInventory;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Watchers;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Ein Gerät ({@code hytale.device.Device}), das sich mit {@link DeviceConfigPage} einstellen lässt. Die Seite kennt
 * keine Gerätearten: Sie zeigt genau die Abschnitte, die das Gerät hier anbietet, und blendet die übrigen aus.
 *
 * <ul>
 *   <li><b>Geisterplätze</b> ({@link #ghostSlots}): Filter bzw. Vorrat; merken nur das Item (und beim Vorrat die
 *   Menge), verbrauchen nichts.</li>
 *   <li><b>Upgrade-Karten</b> ({@link #upgradeCards}): echte Items; welche Karten passen, bestimmt
 *   {@code core.cells.Upgrades} über die Item-Id des Kartenfachs.</li>
 *   <li><b>Einstellungen</b> ({@link #settings}): bis zu {@link #MAX_SETTINGS} Knöpfe, die je Klick weiterschalten.</li>
 *   <li><b>Zahl</b> ({@link #numberField}): ein Schwellwert o. Ä. mit Schrittknöpfen und Eingabefeld.</li>
 *   <li><b>Priorität</b> ({@link #hasPriority}, {@link #priority}, {@link #setPriority}).</li>
 * </ul>
 *
 * <p>Alles ist optional; ein einfaches Gerät überschreibt nur, was es hat – typisch {@link #settings},
 * {@link #configChanged} und {@link #watchers}. Alle Methoden laufen auf dem Welt-Thread. Die Seite prüft vor jeder
 * Aktion den Zugriff des Spielers neu und ruft danach {@link #configChanged}; dort speichert das Gerät seinen Zustand
 * in der Block-Komponente (sofern es das nicht ohnehin über die Listener seiner Kernlogik tut).
 */
public interface ConfigHost {

    /** So viele Einstellungen zeigt die Seite höchstens (Knöpfe {@code #Setting0..#Setting4}). */
    int MAX_SETTINGS = 5;

    /** Was die Seite dem Gerät bei jeder Abfrage mitgibt: Welt, Block und die Sprache des Spielers. */
    class Context {
        private final WorldNetworks nets;
        private final WorldNetworks.Node node;
        private final String lang;

        /** @param lang Sprache des Spielers, für den die Texte gedacht sind */
        public Context(WorldNetworks nets, WorldNetworks.Node node, String lang) {
            this.nets = nets;
            this.node = node;
            this.lang = lang;
        }

        public WorldNetworks nets() {
            return nets;
        }

        public WorldNetworks.Node node() {
            return node;
        }

        public String lang() {
            return lang;
        }

        /** Übersetzt einen vollen Schlüssel ({@code ae2bus.setting…}) in die Sprache des Spielers. */
        public String tr(String key, Object... params) {
            return TerminalTexts.tr(lang, key, params);
        }
    }

    /**
     * Geisterplätze des Geräts.
     *
     * @param inventory die Plätze
     * @param usable    nutzbare Plätze von vorn (Busse: {@code 18 + 9 · Kapazitätskarten}); Plätze dahinter leert
     *                  die Seite, sobald eine Karte entfernt wird
     * @param amounts   true: Plätze tragen Mengen (Vorrat des Interface) – eigenes Layout mit Mengenknöpfen, bis zu
     *                  9 Plätze, dasselbe Item darf mehrfach vorkommen; false: Filter, jedes Item höchstens einmal
     * @param headerKey Überschrift; Platzhalter {@code {used}} und {@code {slots}}
     * @param tipKey    Zusatzzeile im Tooltip eines belegten Platzes
     * @param hintKey   Bedienhinweis unten rechts
     */
    record GhostSlots(ConfigInventory inventory, int usable, boolean amounts, String headerKey, String tipKey,
                      String hintKey) {

        /** Filter mit den üblichen Texten (Tooltip „Klick entfernt“, Bedienhinweis der Busse). */
        public static GhostSlots filter(ConfigInventory inventory, int usable, String headerKey) {
            return new GhostSlots(inventory, usable, false, headerKey, "ae2bus.tip.filter", "ae2bus.hint.bus");
        }
    }

    /**
     * Eine Einstellung: fertiger Text des Knopfs und was ein Klick weiterschaltet (AE2: SettingToggleButton).
     * Wird bei jeder Abfrage neu erzeugt und zeigt damit immer den aktuellen Wert.
     */
    record Setting(String text, Runnable next) {

        /**
         * Schaltet reihum durch {@code values}. Text: {@code keyPrefix} + Name des Werts in Kleinbuchstaben, etwa
         * {@code "ae2bus.setting.redstone." + "low_signal"}.
         */
        public static <E extends Enum<E>> Setting cycle(Context ctx, String keyPrefix, E current, E[] values,
                                                        Consumer<E> setter) {
            return new Setting(ctx.tr(keyPrefix + current.name().toLowerCase(Locale.ROOT)), () -> {
                int at = -1;
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == current) {
                        at = i;
                    }
                }
                setter.accept(values[(at + 1) % values.length]);
            });
        }

        /** Ja/Nein-Einstellung. Text: {@code keyPrefix + "yes"} bzw. {@code keyPrefix + "no"}. */
        public static Setting toggle(Context ctx, String keyPrefix, boolean current, Consumer<Boolean> setter) {
            return new Setting(ctx.tr(keyPrefix + (current ? "yes" : "no")), () -> setter.accept(!current));
        }

        /** Fuzzy-Modus (sichtbar mit Fuzzy-Karte); dieselben Texte wie in der Zellen-Werkbank. */
        public static Setting fuzzy(Context ctx, FuzzyMode mode, Consumer<FuzzyMode> setter) {
            return cycle(ctx, "ae2ui.workbench.fuzzy.", mode, FuzzyMode.values(), setter);
        }
    }

    /**
     * Ein Zahlenwert des Geräts, etwa die Schwelle eines Level Emitters – unabhängig von den Mengen der
     * Geisterplätze.
     *
     * @param labelKey Beschriftung; Platzhalter {@code {value}}
     * @param steps    drei Schrittweiten der Knöpfe, aufsteigend (klein, mittel, groß)
     * @param setter   bekommt den neuen, bereits auf {@code min..max} begrenzten Wert
     */
    record NumberField(String labelKey, long value, long min, long max, long[] steps, LongConsumer setter) {

        /** Zahlenfeld mit den Schritten 1, 10 und 100. */
        public static NumberField of(String labelKey, long value, long min, long max, LongConsumer setter) {
            return new NumberField(labelKey, value, min, max, new long[]{1, 10, 100}, setter);
        }
    }

    /**
     * Echte Plätze des Geräts neben den Geisterplätzen (Interface: der Vorrat, aus dem der Spieler Stapel nehmen
     * und in den er welche legen kann). Nur im Vorrats-Layout ({@link GhostSlots#amounts}).
     *
     * @param container der Hytale-Container; null zeigt leere Plätze
     * @param slots     gezeigte Plätze von vorn (höchstens 9)
     * @param tipKey    Zusatzzeile im Tooltip eines belegten Platzes
     */
    record ItemSection(@Nullable ItemContainer container, int slots, String headerKey, String tipKey) {
    }

    /** Rückmeldung einer {@link Action}: fertiger Text, als Erfolg oder Fehler gefärbt. */
    record Feedback(String text, boolean error) {
    }

    /** Zusätzlicher Knopf neben „Filter leeren“ (Storage-Bus: „Filter aus Inhalt“): fertiger Text und Aktion. */
    record Action(String text, Supplier<Feedback> run) {
    }

    // ------------------------------------------------------------------ Kopf

    /** Sprachschlüssel des Seitentitels; null = Name des Block-Items. */
    @Nullable
    default String titleKey() {
        return null;
    }

    /** Ist das Gerät am Netz aktiv (Strom und Kanal)? Bestimmt die Infozeile, wenn {@link #info} nichts liefert. */
    default boolean online(WorldNetworks.Node node) {
        return node.gridNode() != null && node.gridNode().isActive();
    }

    /** Infozeile unter dem Titel (fertiger Text); null = „Online“ bzw. „Offline …“ nach {@link #online}. */
    @Nullable
    default String info(Context ctx) {
        return null;
    }

    // ------------------------------------------------------------------ Abschnitte

    /** Geisterplätze; null = das Gerät hat keinen Filter. */
    @Nullable
    default GhostSlots ghostSlots(Context ctx) {
        return null;
    }

    /** Kartenfach (bis zu 5 Plätze); null = das Gerät nimmt keine Upgrade-Karten. */
    @Nullable
    default UpgradeInventory upgradeCards() {
        return null;
    }

    /**
     * Die Einstellungen, die das Gerät mit seinen eingebauten Karten gerade hat (AE2: die sichtbaren Knöpfe des
     * Screens), in der Reihenfolge der Knöpfe; mehr als {@link #MAX_SETTINGS} zeigt die Seite nicht.
     */
    default List<Setting> settings(Context ctx) {
        return List.of();
    }

    /** Zahlenfeld; null = keines. */
    @Nullable
    default NumberField numberField(Context ctx) {
        return null;
    }

    /** Hat das Gerät eine Priorität? */
    default boolean hasPriority() {
        return false;
    }

    default int priority() {
        return 0;
    }

    /** Setzt die Priorität (Kern und Block-Komponente); jeder int-Wert ist erlaubt. */
    default void setPriority(WorldNetworks.Node node, int priority) {
    }

    /** Echte Plätze im Vorrats-Layout; null = keine. */
    @Nullable
    default ItemSection itemSection() {
        return null;
    }

    /** Zusätzlicher Knopf unter den Geisterplätzen; null = keiner. */
    @Nullable
    default Action action(Context ctx) {
        return null;
    }

    /** Zusätzliche Zeile unter dem Bedienhinweis (fertiger Text), etwa eine Einschränkung; null = keine. */
    @Nullable
    default String note(Context ctx) {
        return null;
    }

    // ------------------------------------------------------------------ Memory Card

    /**
     * Alle Einstellungen des Geräts als Name → Wert, für die Memory Card (AE2:
     * {@code IConfigManager.exportSettings}). {@link #settings} zeigt nur Knöpfe zum Weiterschalten; zum Kopieren
     * braucht es die Werte selbst. Namen wie in AE2 ({@code appeng.api.config.Settings}: {@code fuzzy_mode},
     * {@code redstone_controlled} …), Werte sind die Namen der Enum-Konstanten bzw. {@code YES}/{@code NO} – so
     * passt eine Einstellung auch zwischen verschiedenen Gerätearten. Anzugeben sind alle Einstellungen, auch die,
     * deren Knopf erst mit einer Karte erscheint.
     *
     * @return null = das Gerät hat keine Einstellungen (die Memory Card kopiert dann nur Filter, Priorität, Karten)
     */
    @Nullable
    default Map<String, String> exportSettings() {
        return null;
    }

    /**
     * Gegenstück zu {@link #exportSettings} (AE2: {@code IConfigManager.importSettings}): übernimmt die Einträge,
     * die das Gerät kennt, und übergeht alles andere (fremde Namen, ungültige Werte). Helfer:
     * {@code core.tools.MemoryCardSettings.importEnum} / {@code importYesNo}. Gespeichert wird danach über
     * {@link #configChanged}.
     *
     * @return true, wenn mindestens eine Einstellung übernommen wurde
     */
    default boolean importSettings(Map<String, String> settings) {
        return false;
    }

    // ------------------------------------------------------------------ Betrieb

    /**
     * Offene Seiten dieses Geräts. Das Gerät ruft {@code watchers.tick(nets.tickCount())} in {@code afterCoreTick}
     * und {@code watchers.run()} in {@code onRemoved}; dann zeigt die Seite Änderungen von außen und schließt sich,
     * wenn der Block verschwindet. Ohne (null) aktualisiert sie sich nur bei Klicks des Spielers.
     */
    @Nullable
    default Watchers watchers() {
        return null;
    }

    /**
     * Nach jeder Aktion des Spielers auf der Seite: Zustand in die Block-Komponente schreiben
     * ({@code node.comp.setData(…)} bzw. {@code node.comp.config()}, dann {@code node.markDirty()}). Geräte, deren
     * Kernlogik das Speichern selbst anstößt (Save-Listener), brauchen nichts zu tun.
     */
    default void configChanged(WorldNetworks.Node node) {
    }

    /**
     * true: Der Block öffnet beim Benutzen etwas anderes (sein Slot-Fenster); die Konfiguration öffnet sich, wenn
     * der Spieler ihn <b>geduckt</b> benutzt (wie die Priorität von Laufwerk und ME-Truhe).
     */
    default boolean opensOnCrouch() {
        return false;
    }
}
