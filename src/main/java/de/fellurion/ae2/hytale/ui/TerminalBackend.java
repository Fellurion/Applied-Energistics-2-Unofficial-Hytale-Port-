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

/** Was eine Terminal-Seite vom ME-Netz braucht (entkoppelt UI von Netzwerk-Logik). */
public interface TerminalBackend {

    /**
     * Ein Eintrag im Terminal. {@code key} identifiziert Item + Metadaten eindeutig, {@code display} ist ein
     * Stack mit allen Metadaten (Tooltip, Seelenbund usw.) und Menge 1.
     *
     * @param craftable true, wenn das Netz den Eintrag per Autocrafting herstellen kann (dann auch mit Menge 0)
     */
    record Entry(String key, ItemStack display, long amount, boolean craftable) {

        public Entry(String key, ItemStack display, long amount) {
            this(key, display, amount, false);
        }
    }

    /** Zustand des Netzes für die Kopfzeile (Energie in AE, Speicherbelegung). */
    record Status(boolean online, double storedPower, double maxPower, double avgUsage, int types, long items) {
    }

    /** Netz versorgt und Terminal hat einen Kanal. */
    boolean isOnline();

    /** Kurzer Statustext (z. B. "Online · 12.5k AE"). Unübersetzt; die Seiten nutzen {@link #status()}. */
    String statusText();

    /** Speicherauslastung (z. B. "12 Typen · 1.2k Items"). Unübersetzt; die Seiten nutzen {@link #status()}. */
    String storageText();

    /** Momentaufnahme aller lagernden (und herstellbaren) Stacks. */
    List<Entry> entries();

    /** Lagert ein; liefert die Anzahl, die eingelagert wurde (0..stack.getQuantity()). */
    long insert(ItemStack stack);

    /** Entnimmt bis zu {@code amount} Items des Eintrags {@code key}; null, wenn nichts da ist. */
    @Nullable
    ItemStack extract(String key, int amount);

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
