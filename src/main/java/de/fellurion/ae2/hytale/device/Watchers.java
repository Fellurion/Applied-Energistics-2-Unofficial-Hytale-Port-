/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.AEBaseMenu#broadcastChanges – offene Menüs sehen Änderungen am Gerät).
 */
package de.fellurion.ae2.hytale.device;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Offene Seiten eines Geräts. Das Gerät ruft sie regelmäßig aus seinem Takt (und einmal, wenn es entfernt wird),
 * damit die Seite zeigt, was sich inzwischen geändert hat – ohne dass das Gerät die Seiten kennt.
 */
public final class Watchers {

    /** Takt der Auffrischung in AE2-Ticks (0,5 s). */
    public static final int INTERVAL_TICKS = 10;

    private final List<BooleanSupplier> watchers = new CopyOnWriteArrayList<>();

    /** Meldet einen Beobachter an; er wird abgemeldet, sobald er {@code false} liefert oder scheitert. */
    public void add(BooleanSupplier watcher) {
        watchers.add(watcher);
    }

    public void remove(BooleanSupplier watcher) {
        watchers.remove(watcher);
    }

    public boolean isEmpty() {
        return watchers.isEmpty();
    }

    /** Ruft alle Beobachter. */
    public void run() {
        for (BooleanSupplier w : watchers) {
            boolean keep;
            try {
                keep = w.getAsBoolean();
            } catch (RuntimeException e) {
                keep = false; // eine kaputte Seite darf den Geräte-Takt nicht stören
            }
            if (!keep) {
                watchers.remove(w);
            }
        }
    }

    /** Für {@code afterCoreTick}: ruft die Beobachter alle {@link #INTERVAL_TICKS} Ticks. */
    public void tick(long tickCount) {
        if (!watchers.isEmpty() && tickCount % INTERVAL_TICKS == 0) {
            run();
        }
    }
}
