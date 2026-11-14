/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: data/ae2/advancement/main/*.json, appeng.datagen.providers.advancements.AdvancementGenerator).
 */
package de.fellurion.ae2.hytale.advancements;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ein AE2-Erfolg aus {@code ae2data/advancements.json}.
 *
 * <p>Wie in Minecraft: {@link #requirements()} ist eine UND-Liste von ODER-Gruppen aus Kriteriennamen. Eltern
 * sind nur für die Baumdarstellung da; ein Erfolg kann auch vor seinem Elternteil erzielt werden.
 *
 * @param id        AE2-Name ({@code "storage_cell"}), zugleich Übersetzungsschlüssel {@code ae2adv.<id>.title/.desc}
 * @param parent    Elternerfolg oder null (Wurzel)
 * @param icon      Hytale-ItemId des Symbols
 * @param toast     Benachrichtigung beim Erzielen (AE2 {@code show_toast})
 * @param announce  Chatmeldung an alle (AE2 {@code announce_to_chat})
 * @param available in dieser Portierung schon erreichbar (sonst „noch nicht portiert“)
 * @param depth     Tiefe im Baum (Wurzel = 0)
 */
public record Advancement(String id, @Nullable String parent, String icon, boolean toast, boolean announce,
                          Map<String, Criterion> criteria, List<List<String>> requirements, boolean available,
                          int depth) {

    /**
     * Ein Kriterium eines Erfolgs.
     *
     * @param items    bei {@link Trigger#HAS_ITEM}: eines dieser Items genügt
     * @param channels bei {@link Trigger#NETWORK_CHANNELS}: so viele belegte Kanäle sind nötig
     */
    public record Criterion(String advancementId, String id, Trigger trigger, Set<String> items, int channels) {
    }

    public String titleKey() {
        return "ae2adv." + id + ".title";
    }

    public String descKey() {
        return "ae2adv." + id + ".desc";
    }

    /** Ist jede UND-Gruppe durch mindestens ein erfülltes Kriterium abgedeckt? */
    public boolean isComplete(Set<String> doneCriteria) {
        return requirementsMet(doneCriteria) == requirements.size();
    }

    /** Anzahl der erfüllten UND-Gruppen (für die Anzeige „2 / 4“ bei den Pressen). */
    public int requirementsMet(Set<String> doneCriteria) {
        int met = 0;
        for (List<String> group : requirements) {
            for (String c : group) {
                if (doneCriteria.contains(c)) {
                    met++;
                    break;
                }
            }
        }
        return met;
    }
}
