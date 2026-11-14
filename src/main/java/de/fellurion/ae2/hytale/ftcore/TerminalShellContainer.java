/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.ftcore;

import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import de.fellurion.ae2.hytale.ui.TerminalView;
import de.fellurion.ftcore.shell.ShellContainer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Fenster für das Netz-Gitter des Terminal-Reiters. Ohne Fenster kennt der Server den Abschnitt nicht, und ohne
 * Abschnitt lässt sich nichts auf das Gitter ziehen (siehe FTCore {@code ShellContainer}).
 *
 * <p>Der Behälter dahinter ist immer leer und sperrt jeden normalen Zugriff ({@link TerminalView#newDropContainer}):
 * Das Gitter zeigt nur Anzeige-Kopien, Ein- und Auslagern rechnet {@link TerminalView} direkt gegen das ME-Netz.
 * Spieler ohne offene Terminal-Sitzung bekommen kein Fenster.
 */
final class TerminalShellContainer implements ShellContainer {

    @Override
    @Nonnull
    public String selector() {
        return TerminalView.NET_GRID;
    }

    @Override
    public int slots() {
        return FtCoreTerminals.LAYOUT.pageSize();
    }

    @Override
    @Nullable
    public ItemContainer containerOf(@Nonnull UUID playerId) {
        FtCoreTerminals.Session s = FtCoreTerminals.SESSIONS.get(playerId);
        return s == null ? null : s.dropContainer;
    }
}
