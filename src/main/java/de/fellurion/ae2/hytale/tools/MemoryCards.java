/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.tools.MemoryCardItem, appeng.block.AEBaseEntityBlock#useItemOn,
 * appeng.parts.AEBasePart#useMemoryCard, appeng.util.SettingsFrom#MEMORY_CARD).
 */
package de.fellurion.ae2.hytale.tools;

import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.tools.MemoryCardSettings;
import de.fellurion.ae2.core.tools.MemoryCardSettings.CardStock;
import de.fellurion.ae2.core.tools.MemoryCardSettings.Part;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.CellHostDevice;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.items.MemoryCardData;
import de.fellurion.ae2.hytale.items.MemoryCardTarget;
import de.fellurion.ae2.hytale.ui.ConfigEdits;
import de.fellurion.ae2.hytale.ui.ConfigHost;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Was die Memory Card mit einem Gerät tut: speichern (geduckt + Rechtsklick) und übertragen (Rechtsklick). Hier
 * steht die Block-Seite, ohne Spieler aufrufbar; Karte in der Hand und Meldungen behandelt {@link ToolItems}.
 *
 * <ul>
 *   <li>Geräte, die {@link MemoryCardTarget} sind (P2P-Tunnel), gehen selbst mit der Karte um.</li>
 *   <li>Alle anderen kopiert die Karte von sich aus, soweit sie etwas zum Kopieren haben (AE2:
 *   {@code exportGenericSettings} / {@code importGenericSettings}): eingebaute Upgrade-Karten, Einstellungen
 *   ({@link ConfigHost#exportSettings}), Priorität und Filter bzw. Vorrat eines {@link ConfigHost}; bei Laufwerk und
 *   ME-Truhe die Priorität.</li>
 * </ul>
 *
 * <p>Die Art der Karte ist die Block-Art ({@code Kind}); Block und Kabel-Part eines Interface teilen sie sich wie in
 * AE2. Passt die Art, heißt es „geladen“. Passt sie nicht, übernimmt das Gerät wie in AE2 die Teile, die es versteht
 * („teilweise geladen: …“), und sonst nichts („unpassendes Gerät“).
 *
 * <p>Abweichung von AE2: Upgrade-Karten kommen immer aus dem Vorrat des Spielers ({@link CardStock}) und gehen dorthin
 * zurück – auch im Creative-Modus entsteht und verschwindet keine Karte.
 */
public final class MemoryCards {

    private MemoryCards() {
    }

    /** Ergebnis des Übertragens. */
    public enum Outcome {
        /** Karte derselben Geräteart: übernommen (AE2: „Loaded“). */
        LOADED,
        /** Karte einer anderen Geräteart, Teile übernommen (AE2: „Partially loaded configuration“). */
        PARTIAL,
        /** Nichts übernommen (AE2: „Invalid machine“). */
        INVALID,
        /** Die Karte passt, das Gerät konnte sie aber nicht übernehmen. */
        FAILED
    }

    /**
     * @param parts   übernommene Teile (leer bei Geräten, die selbst mit der Karte umgehen)
     * @param missing Karten, die der Spieler nicht (mehr) hatte: Karte → fehlende Anzahl
     */
    public record Loaded(Outcome outcome, Set<Part> parts, Map<UpgradeCard, Integer> missing) {
        static Loaded of(Outcome outcome) {
            return new Loaded(outcome, Set.of(), Map.of());
        }
    }

    /**
     * Kann die Memory Card mit diesem Block etwas anfangen? Ja bei allem, was im ME-Netz hängt (AE2 speichert von
     * jedem Gerät zumindest die Art), und bei Geräten mit etwas zum Kopieren. Nein bei Blöcken ohne beides (Quarzglas,
     * Budding Quartz …) und solange der Block noch nicht angemeldet ist (im Tick des Platzierens).
     */
    public static boolean isMachine(@Nullable WorldNetworks.Node node) {
        if (node == null) {
            return false;
        }
        Device device = node.device();
        return node.gridNode() != null || device instanceof ConfigHost || device instanceof MemoryCardTarget
                || device instanceof CellHostDevice;
    }

    /** Übersetzungsschlüssel des Blocks für den Tooltip der Karte, oder null. */
    @Nullable
    static String nameKey(WorldNetworks.Node node) {
        return node.item() == null ? null : "ae2.items." + node.item().id() + ".name";
    }

    // ------------------------------------------------------------------ Speichern

    /**
     * Was beim Speichern auf die Karte kommt; null, wenn das Gerät nichts zu speichern hat (nur bei Geräten, die
     * selbst entscheiden). Ein {@link MemoryCardTarget} darf sich dabei verändern.
     */
    @Nullable
    public static MemoryCardData save(WorldNetworks nets, WorldNetworks.Node node, String lang) {
        Device device = node.device();
        if (device instanceof MemoryCardTarget target) {
            return target.saveToCard(nets, node);
        }
        return new MemoryCardData(node.kind(), export(nets, node, lang).toJson(), nameKey(node));
    }

    /** Die kopierbaren Teile eines Geräts (AE2: {@code MemoryCardItem.exportGenericSettings}). */
    static MemoryCardSettings export(WorldNetworks nets, WorldNetworks.Node node, String lang) {
        MemoryCardSettings out = new MemoryCardSettings();
        Device device = node.device();
        if (device instanceof ConfigHost host) {
            UpgradeInventory upgrades = host.upgradeCards();
            if (upgrades != null) {
                out.upgrades(MemoryCardSettings.storeUpgrades(upgrades));
            }
            out.settings(host.exportSettings());
            if (host.hasPriority()) {
                out.priority(host.priority());
            }
            ConfigHost.GhostSlots slots = host.ghostSlots(new ConfigHost.Context(nets, node, lang));
            if (slots != null) {
                out.config(slots.inventory().toJsonList());
            }
        } else if (device instanceof CellHostDevice cells) {
            out.priority(cells.logic().getPriority());
        }
        return out;
    }

    // ------------------------------------------------------------------ Übertragen

    /**
     * Überträgt den Inhalt der Karte auf das Gerät.
     *
     * @param stock Vorrat an Upgrade-Karten des Spielers; null = ohne Spieler (Testbefehl): die eingebauten Karten
     *              bleiben dann, wie sie sind (AE2: {@code player == null})
     */
    public static Loaded load(WorldNetworks nets, WorldNetworks.Node node, MemoryCardData data,
                              @Nullable CardStock stock, String lang) {
        Device device = node.device();
        if (device instanceof MemoryCardTarget target) {
            return Loaded.of(switch (target.loadFromCard(nets, node, data)) {
                case LOADED -> Outcome.LOADED;
                case WRONG_TYPE -> Outcome.INVALID;
                case FAILED -> Outcome.FAILED;
            });
        }
        Loaded imported = importGeneric(nets, node, MemoryCardSettings.fromJson(data.payload()), stock, lang);
        if (node.kind().equals(data.type())) {
            return new Loaded(Outcome.LOADED, imported.parts(), imported.missing());
        }
        return imported;
    }

    /**
     * Übernimmt die Teile, die das Gerät versteht, in AE2s Reihenfolge: Karten, Einstellungen, Priorität, Filter
     * (AE2: {@code MemoryCardItem.importGenericSettings}). Liefert {@link Outcome#PARTIAL} bzw. {@link Outcome#INVALID},
     * je nachdem, ob etwas übernommen wurde.
     */
    static Loaded importGeneric(WorldNetworks nets, WorldNetworks.Node node, MemoryCardSettings in,
                                @Nullable CardStock stock, String lang) {
        Set<Part> parts = EnumSet.noneOf(Part.class);
        Map<UpgradeCard, Integer> missing = Map.of();
        Device device = node.device();
        if (device instanceof ConfigHost host) {
            UpgradeInventory upgrades = host.upgradeCards();
            if (stock != null && upgrades != null && in.upgrades() != null) {
                missing = MemoryCardSettings.restoreUpgrades(upgrades, in.upgrades(), stock);
                parts.add(Part.UPGRADES);
            }
            if (in.settings() != null && host.importSettings(in.settings())) {
                parts.add(Part.SETTINGS);
            }
            if (in.priority() != null && host.hasPriority()) {
                host.setPriority(node, in.priority());
                parts.add(Part.PRIORITY);
            }
            // erst jetzt: wie viele Filterplätze nutzbar sind, hängt von den eben eingebauten Karten ab
            ConfigHost.GhostSlots slots = host.ghostSlots(new ConfigHost.Context(nets, node, lang));
            if (slots != null && in.config() != null) {
                MemoryCardSettings.readConfig(slots.inventory(), in.config());
                if (!slots.amounts()) {
                    // wie die Konfigurationsseite: Plätze, die ohne Kapazitätskarte gesperrt sind, bleiben leer
                    ConfigEdits.clearHidden(slots.inventory(), slots.usable());
                }
                parts.add(Part.CONFIG_INV);
            }
            if (!parts.isEmpty()) {
                host.configChanged(node);
            }
        } else if (device instanceof CellHostDevice cells && in.priority() != null) {
            cells.logic().setPriority(in.priority());
            node.comp.setPriority(in.priority());
            node.markDirty();
            parts.add(Part.PRIORITY);
        }
        return new Loaded(parts.isEmpty() ? Outcome.INVALID : Outcome.PARTIAL, parts, missing);
    }
}
