/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.common.MEStorageMenu mit appeng.helpers.WirelessTerminalMenuHost als Host).
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import de.fellurion.ae2.core.energy.IEnergySource;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.ui.NetworkTerminalBackend;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Terminal-Zugriff eines Wireless (Crafting) Terminals: der Inhalt des verknüpften Netzes, bezahlt aus dem Akku des
 * Items (AE2: 1 AE je Item aus dem Terminal, nicht aus dem Netz).
 *
 * <p>Der „Block“ dieses Terminals ist der verknüpfte Access Point: Besitz, Zugriff und Autocrafting gelten für sein
 * Netz. Erreichbar ist es nur, solange die Sitzung steht (Item an seinem Platz, Reichweite, Ladung).
 *
 * <p>Abweichung von AE2: Craft-Anfrage und Crafting-Status öffnen als eigene Seiten am verknüpften Access Point; das
 * Terminal ist dann zu, und Reichweite bzw. Akku werden erst beim nächsten Öffnen wieder geprüft.
 */
final class WirelessTerminalBackend extends NetworkTerminalBackend {

    private final WirelessTerminalSession session;

    WirelessTerminalBackend(WorldNetworks nets, WirelessTerminalSession session, @Nullable UUID player) {
        super(nets, session.link().x(), session.link().y(), session.link().z(), player);
        this.session = session;
    }

    @Override
    public boolean onOpen(PlayerRef playerRef) {
        return session.open();
    }

    @Override
    public void onClose() {
        session.close();
    }

    @Override
    public boolean isOnline() {
        // die Welt zuerst: nach einem Weltwechsel gehören die Netze des Terminals einem anderen Thread
        return session.inWorld(nets.world()) && session.live() && super.isOnline();
    }

    @Override
    protected IEnergySource energy(Grid grid) {
        return session.host();
    }

    /** Kopfzeile: statt der Netzenergie der Akku des Terminals und sein Verbrauch an dieser Stelle. */
    @Override
    @Nullable
    public Status status() {
        Status s = super.status();
        if (s == null || !s.online()) {
            return s;
        }
        return new Status(true, session.getAECurrentPower(), session.capacity(), session.drainPerTick(), s.types(),
                s.items());
    }

    @Override
    public long revision() {
        long r = super.revision();
        if (r == Long.MIN_VALUE) {
            return r;
        }
        // neu zeichnen, wenn sich die Ladung um ein Prozent geändert hat (nicht bei jedem Verbrauch)
        double capacity = session.capacity();
        long percent = capacity <= 0 ? 0 : (long) Math.floor(session.getAECurrentPower() * 100 / capacity);
        return r * 31 + percent;
    }
}
