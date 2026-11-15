/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/** Was eine Terminal-Seite vom ME-Netz braucht (entkoppelt UI von Netzwerk-Logik). */
public interface TerminalBackend {

    /**
     * Ein Eintrag im Terminal. {@code key} identifiziert Item + Metadaten eindeutig, {@code display} ist ein
     * Stack mit allen Metadaten (Tooltip, Seelenbund usw.) und Menge 1.
     *
     * <p>Bei einer Flüssigkeit ({@code fluid}) ist {@code key} der Schlüssel der Flüssigkeit, {@code display} das
     * Item, mit dem sie gezeigt wird ({@code hytale.fluid.FluidTypes}), und {@code amount} die Menge in mB. Solche
     * Einträge lassen sich nicht als Item entnehmen.
     *
     * @param craftable true, wenn das Netz den Eintrag per Autocrafting herstellen kann (dann auch mit Menge 0)
     * @param fluid     true = Flüssigkeit, {@code fluidId} ist dann gesetzt
     * @param fluidId   Kennung der Flüssigkeit (z. B. {@code Water_Source}), sonst null
     */
    record Entry(String key, ItemStack display, long amount, boolean craftable, boolean fluid, @Nullable String fluidId) {

        public Entry(String key, ItemStack display, long amount) {
            this(key, display, amount, false);
        }

        public Entry(String key, ItemStack display, long amount, boolean craftable) {
            this(key, display, amount, craftable, false, null);
        }

        /** Eintrag einer Flüssigkeit mit {@code amount} mB. */
        public static Entry ofFluid(String key, String fluidId, ItemStack display, long amount) {
            return new Entry(key, display, amount, false, true, fluidId);
        }
    }

    /**
     * Zustand des Netzes für die Kopfzeile (Energie in AE, Speicherbelegung).
     *
     * @param types Anzahl verschiedener Einträge (Items und Flüssigkeiten)
     * @param items Summe der Items
     * @param fluid Summe der Flüssigkeiten in mB
     */
    record Status(boolean online, double storedPower, double maxPower, double avgUsage, int types, long items,
                  long fluid) {

        public Status(boolean online, double storedPower, double maxPower, double avgUsage, int types, long items) {
            this(online, storedPower, maxPower, avgUsage, types, items, 0);
        }
    }

    /** Netz versorgt und Terminal hat einen Kanal. */
    boolean isOnline();

    /** Kurzer Statustext (z. B. "Online · 12.5k AE"). Unübersetzt; die Seiten nutzen {@link #status()}. */
    String statusText();

    /** Speicherauslastung (z. B. "12 Typen · 1.2k Items"). Unübersetzt; die Seiten nutzen {@link #status()}. */
    String storageText();

    /** Momentaufnahme aller lagernden (und herstellbaren) Items – ohne Flüssigkeiten, siehe {@link #fluids()}. */
    List<Entry> entries();

    /**
     * Momentaufnahme der lagernden Flüssigkeiten ({@link Entry#fluid()}, Mengen in mB). Das Terminal zeigt sie wie
     * AE2 im selben Gitter wie die Items; getrennt geliefert werden sie, damit niemand sie für Items hält (etwa das
     * Herstellen im Crafting-Terminal).
     */
    default List<Entry> fluids() {
        return List.of();
    }

    /**
     * Entnimmt bis zu {@code amount} mB der Flüssigkeit des Eintrags {@code key} (AE2-Energiekosten: 1 AE je
     * 125 mB).
     *
     * @param simulate true = nur prüfen, wie viel sich jetzt entnehmen ließe
     * @return die entnommene Menge in mB
     */
    default long extractFluid(String key, long amount, boolean simulate) {
        return 0;
    }

    /**
     * Lagert bis zu {@code amount} mB der Flüssigkeit {@code fluidId} ein (AE2-Energiekosten: 1 AE je 125 mB).
     *
     * @param simulate true = nur prüfen, wie viel jetzt angenommen würde
     * @return die eingelagerte Menge in mB
     */
    default long insertFluid(String fluidId, long amount, boolean simulate) {
        return 0;
    }

    /** Der Eintrag-Schlüssel der Flüssigkeit {@code fluidId}, wie ihn {@link #fluids()} liefert. */
    static String fluidKey(String fluidId) {
        return "fluid:" + fluidId;
    }

    /** Die Flüssigkeit zu einem Eintrag-Schlüssel aus {@link #fluidKey}; null, wenn es keiner ist. */
    @Nullable
    static String fluidIdOf(@Nullable String key) {
        return key != null && key.startsWith("fluid:") && key.length() > 6 ? key.substring(6) : null;
    }

    /** Lagert ein; liefert die Anzahl, die eingelagert wurde (0..stack.getQuantity()). */
    long insert(ItemStack stack);

    /** Entnimmt bis zu {@code amount} Items des Eintrags {@code key}; null, wenn nichts da ist. */
    @Nullable
    ItemStack extract(String key, int amount);

    /**
     * Prüft, ob sich alle Mengen (Eintrag {@code key} → Stückzahl) jetzt <b>zusammen</b> entnehmen ließen – Bestand
     * und Energie –, ohne etwas zu ändern. Das Crafting-Terminal simuliert damit die Zutaten, bevor es sie entnimmt.
     */
    default boolean canExtract(Map<String, Long> amounts) {
        return false;
    }

    /** Änderungszähler; ändert sich, wenn sich der Netzinhalt geändert hat (für automatische Aktualisierung). */
    long revision();

    /** Strukturierter Zustand für übersetzte Anzeigen; null = nur {@link #statusText()} verfügbar. */
    @Nullable
    default Status status() {
        return null;
    }

    /**
     * Öffnet die Craft-Anfrage (Menge wählen, berechnen, starten) für den Eintrag {@code key}.
     *
     * @return true, wenn eine Seite geöffnet wurde
     */
    default boolean requestCraft(String key, Player player, PlayerRef playerRef, Ref<EntityStore> ref,
                                 Store<EntityStore> store) {
        return false;
    }

    /** Kann dieses Terminal den Crafting-Status (CPUs, Aufträge) öffnen? */
    default boolean hasCraftingStatus() {
        return false;
    }

    /**
     * Öffnet den Crafting-Status des Netzes.
     *
     * @return true, wenn eine Seite geöffnet wurde
     */
    default boolean openCraftingStatus(Player player, PlayerRef playerRef, Ref<EntityStore> ref,
                                       Store<EntityStore> store) {
        return false;
    }

    // ------------------------------------------------------------------ Lebenszyklus der Ansicht

    /**
     * Gleich öffnet sich eine Ansicht auf dieses Backend (auch erneut, etwa nach „Zurück“ aus der Zugriffsverwaltung).
     * Terminals in einem Item (Wireless Terminal, tragbare Zelle) prüfen hier Item, Ladung und Reichweite und
     * beginnen ihre Sitzung.
     *
     * @return false, wenn nicht geöffnet werden darf (der Spieler hat die Meldung dann schon bekommen)
     */
    default boolean onOpen(PlayerRef playerRef) {
        return true;
    }

    /**
     * Die Ansicht auf dieses Backend wurde geschlossen (Seite zu, andere Seite geöffnet, Schale geschlossen). Darf
     * mehrfach kommen.
     */
    default void onClose() {
    }

    // ------------------------------------------------------------------ Zugriffsschutz (security.AE2Security)

    /** Darf der Spieler dieses Terminals das Netz benutzen? Ohne Zugriff zeigt das Terminal nichts und lehnt alles ab. */
    default boolean accessAllowed() {
        return true;
    }

    /** Erklärung für fehlenden Zugriff in {@code locale} (z. B. „Dieses ME-Netz gehört X …“); null bei Zugriff. */
    @Nullable
    default String accessDeniedText(String locale) {
        return null;
    }

    /**
     * Prüft vor einer Aktion den Zugriff; ohne Zugriff bekommt {@code playerRef} die Meldung (Benachrichtigung,
     * entprellt) und es kommt false zurück.
     */
    default boolean checkAccess(PlayerRef playerRef) {
        return true;
    }

    /** Darf der Spieler die Zugriffsliste verwalten (Besitzer des Netzes oder Admin)? Dann gibt es „Zugriff“. */
    default boolean canManageAccess() {
        return false;
    }

    /**
     * Öffnet die Zugriffsverwaltung des Netzes.
     *
     * @param locale Sprache der Texte
     * @param back   öffnet das Terminal wieder (Knopf „Zurück zum Terminal“)
     * @return true, wenn eine Seite geöffnet wurde
     */
    default boolean openAccess(Player player, PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                               String locale, @Nullable de.fellurion.ae2.hytale.security.AccessPage.Reopener back) {
        return false;
    }
}
