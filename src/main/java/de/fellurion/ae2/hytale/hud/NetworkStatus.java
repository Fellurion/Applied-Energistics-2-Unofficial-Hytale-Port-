/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.integration.modules.igtooltip.parts.GridNodeStateProvider / GridNodeState).
 */
package de.fellurion.ae2.hytale.hud;

import de.fellurion.ae2.core.grid.AdHocNetworkError;
import de.fellurion.ae2.core.grid.ControllerState;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.grid.PathingService;

/**
 * Zustand eines Netzknotens wie in AE2s Jade-/TOP-Anzeige. Liest nur zwischengespeicherte Werte des Kerns
 * (Energie-, Kanal- und Controller-Zustand), nie das ganze Netz.
 */
public enum NetworkStatus {
    /** Versorgt und (falls nötig) mit Kanal. */
    ONLINE("status.online", HudColors.GOOD),
    /** Das Netz kann seinen Grundverbrauch nicht decken. */
    NO_POWER("status.noPower", HudColors.BAD),
    /** Gerät braucht einen Kanal und hat keinen. */
    NO_CHANNEL("status.noChannel", HudColors.WARN),
    /** Netz ohne Controller mit mehr als 8 kanalpflichtigen Geräten: alle verlieren ihren Kanal. */
    TOO_MANY_DEVICES("status.tooManyDevices", HudColors.WARN),
    /** Controller-Aufbau ungültig (zu groß, getrennt, Kreuz): kein Gerät hat einen Kanal. */
    CONFLICT("status.conflict", HudColors.BAD),
    /** Kanäle werden gerade neu verteilt. */
    BOOTING("status.booting", HudColors.MUTED),
    /** Knoten ohne Netz. */
    NOT_CONNECTED("status.notConnected", HudColors.MUTED);

    private final String key;
    private final String color;

    NetworkStatus(String key, String color) {
        this.key = key;
        this.color = color;
    }

    /** Übersetzungsschlüssel ohne Präfix (siehe {@code ae2hud.lang}). */
    public String key() {
        return key;
    }

    /** Textfarbe (#rrggbb). */
    public String color() {
        return color;
    }

    /** Zustand eines Knotens; reine Abfragen auf zwischengespeicherte Werte. */
    public static NetworkStatus of(GridNode node) {
        Grid grid = node.getGrid();
        if (grid == null) {
            return NOT_CONNECTED;
        }
        PathingService pathing = grid.getPathingService();
        if (pathing.getControllerState() == ControllerState.CONTROLLER_CONFLICT) {
            return CONFLICT;
        }
        if (!node.isPowered()) {
            return NO_POWER;
        }
        if (!node.meetsChannelRequirements()) {
            if (pathing.isNetworkBooting()) {
                return BOOTING;
            }
            if (pathing.getAdHocNetworkError() == AdHocNetworkError.TOO_MANY_CHANNELS) {
                return TOO_MANY_DEVICES;
            }
            return NO_CHANNEL;
        }
        return ONLINE;
    }
}
