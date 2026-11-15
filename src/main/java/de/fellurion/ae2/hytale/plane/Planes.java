/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.automation.StackWorldBehaviors – Anmeldung der Pickup-/PlacementStrategy je Schlüsselart).
 */
package de.fellurion.ae2.hytale.plane;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import de.fellurion.ae2.core.automation.ItemPickupStrategy;
import de.fellurion.ae2.core.automation.ItemPlacementStrategy;
import de.fellurion.ae2.core.automation.PickupStrategy;
import de.fellurion.ae2.core.automation.PlacementStrategy;
import de.fellurion.ae2.core.automation.PlacementStrategyFacade;
import de.fellurion.ae2.core.stacks.AEKeyType;
import de.fellurion.ae2.hytale.AE2Command;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.UseHandlers;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Devices;
import de.fellurion.ae2.hytale.hud.InfoProviders;
import de.fellurion.ae2.hytale.ui.DeviceConfigPage;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Formation Plane und Annihilation Plane.
 *
 * <p><b>Strategien je Schlüsselart.</b> Was eine Plane mit Items bzw. Flüssigkeiten tut, steckt wie in AE2 in einer
 * {@link PickupStrategy} (Annihilation Plane) bzw. {@link PlacementStrategy} (Formation Plane) je
 * {@link AEKeyType}. Dieses Paket meldet die Strategien für Items an; ein weiterer Bereich meldet seine in seinem
 * {@code setup} dazu:
 * <pre>
 * Planes.registerPickupStrategy(AEKeyType.FLUIDS, target -> new MyFluidPickup(target));
 * Planes.registerPlacementStrategy(AEKeyType.FLUIDS, target -> new MyFluidPlacement(target));
 * </pre>
 * Jede Plane bekommt beim Anmelden je Art eine eigene Strategie für ihr {@link PlaneTarget} (die Stelle vor ihr,
 * mit Ladezustand und Bauschutz). Der Filter der Formation Plane nimmt dann auch die neue Art an.
 *
 * <p><b>Änderungen melden.</b> Planes schlafen, solange sich vor ihnen nichts tut, und sehen nur in Abständen nach,
 * ob dort ein anderer Block oder eine andere Flüssigkeit steht ({@link PlaneTarget#probe()}). Wer selbst einen Block
 * setzt oder entfernt, kann sie mit {@link #blockChanged} sofort wecken.
 */
public final class Planes {

    public static final String ANNIHILATION = "annihilation_plane";
    public static final String FORMATION = "formation_plane";

    /** Baut die Pickup-Strategie einer Annihilation Plane für eine Schlüsselart; null = diese Plane bekommt keine. */
    @FunctionalInterface
    public interface PickupFactory {
        @Nullable
        PickupStrategy create(PlaneTarget target);
    }

    /** Baut die Placement-Strategie einer Formation Plane für eine Schlüsselart; null = diese Plane bekommt keine. */
    @FunctionalInterface
    public interface PlacementFactory {
        @Nullable
        PlacementStrategy create(PlaneTarget target);
    }

    private static final Map<AEKeyType, PickupFactory> PICKUP = new EnumMap<>(AEKeyType.class);
    private static final Map<AEKeyType, PlacementFactory> PLACEMENT = new EnumMap<>(AEKeyType.class);
    /** Je Welt: Stelle → Planes, die darauf zeigen. Die innere Karte gehört dem Welt-Thread. */
    private static final Map<WorldNetworks, Long2ObjectOpenHashMap<List<Runnable>>> WATCHERS = new ConcurrentHashMap<>();
    @Nullable
    private static volatile HytaleLogger logger;

    private Planes() {
    }

    /** Meldet Geräte, Benutzen-Aktionen, Befehle und Systeme dieses Bereichs an (einmal beim Plugin-Start). */
    public static void setup(JavaPlugin plugin) {
        logger = plugin.getLogger();
        PlaneConfig.load(plugin.getDataDirectory(), Planes::warn);
        registerPickupStrategy(AEKeyType.ITEMS, target -> new ItemPickupStrategy(new PlaneBreaker(target)));
        registerPlacementStrategy(AEKeyType.ITEMS, target -> new ItemPlacementStrategy(new PlanePlacer(target)));
        Devices.register(ANNIHILATION, (nets, node) -> new AnnihilationPlaneDevice());
        Devices.register(FORMATION, (nets, node) -> new FormationPlaneDevice());
        // Formation Plane: Filter, Karten, Einstellungen, Priorität (AE2: FormationPlaneMenu). Die Annihilation
        // Plane hat in AE2 keine Oberfläche.
        UseHandlers.register(FORMATION, DeviceConfigPage::open);
        InfoProviders.register(PlaneHud::annihilation, ANNIHILATION);
        InfoProviders.register(PlaneHud::formation, FORMATION);
        AE2Command.registerSub(new PlaneCommands());
    }

    // ------------------------------------------------------------------ Strategien (AE2: StackWorldBehaviors)

    /**
     * Meldet an, wie Annihilation Planes Schlüssel dieser Art aufnehmen. Wie in AE2 gilt die erste Anmeldung je Art.
     * Die Strategien einer Plane werden in der Reihenfolge von {@link AEKeyType} gefragt.
     */
    public static void registerPickupStrategy(AEKeyType type, PickupFactory factory) {
        synchronized (PICKUP) {
            PICKUP.putIfAbsent(type, factory);
        }
    }

    /** Meldet an, wie Formation Planes Schlüssel dieser Art in die Welt bringen (erste Anmeldung je Art gilt). */
    public static void registerPlacementStrategy(AEKeyType type, PlacementFactory factory) {
        synchronized (PLACEMENT) {
            PLACEMENT.putIfAbsent(type, factory);
        }
    }

    static List<PickupStrategy> createPickupStrategies(PlaneTarget target) {
        List<PickupStrategy> out = new ArrayList<>(2);
        synchronized (PICKUP) {
            for (PickupFactory factory : PICKUP.values()) {
                PickupStrategy strategy = factory.create(target);
                if (strategy != null) {
                    out.add(strategy);
                }
            }
        }
        return out;
    }

    static PlacementStrategyFacade createPlacementStrategies(PlaneTarget target) {
        Map<AEKeyType, PlacementStrategy> out = new EnumMap<>(AEKeyType.class);
        synchronized (PLACEMENT) {
            for (Map.Entry<AEKeyType, PlacementFactory> e : PLACEMENT.entrySet()) {
                PlacementStrategy strategy = e.getValue().create(target);
                if (strategy != null) {
                    out.put(e.getKey(), strategy);
                }
            }
        }
        return new PlacementStrategyFacade(out);
    }

    // ------------------------------------------------------------------ Änderungen vor den Planes

    /**
     * An dieser Stelle wurde gerade ein Block gesetzt oder entfernt: Planes, die darauf zeigen, sehen sofort nach
     * (AE2: Nachbar-Update). Nur auf dem Thread der Welt aufrufen. Kostet nichts, wenn keine Plane dorthin zeigt.
     */
    public static void blockChanged(WorldNetworks nets, int x, int y, int z) {
        Long2ObjectOpenHashMap<List<Runnable>> byPos = WATCHERS.get(nets);
        List<Runnable> list = byPos == null ? null : byPos.get(BlockAccess.pack(x, y, z));
        if (list == null) {
            return;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            list.get(i).run();
        }
    }

    static void watch(WorldNetworks nets, PlaneTarget target, Runnable onChange) {
        WATCHERS.computeIfAbsent(nets, n -> new Long2ObjectOpenHashMap<>())
                .computeIfAbsent(BlockAccess.pack(target.x(), target.y(), target.z()), p -> new ArrayList<>(1)).add(onChange);
    }

    static void unwatch(WorldNetworks nets, PlaneTarget target, Runnable onChange) {
        Long2ObjectOpenHashMap<List<Runnable>> byPos = WATCHERS.get(nets);
        if (byPos == null) {
            return;
        }
        long pos = BlockAccess.pack(target.x(), target.y(), target.z());
        List<Runnable> list = byPos.get(pos);
        if (list != null) {
            list.remove(onChange);
            if (list.isEmpty()) {
                byPos.remove(pos);
            }
        }
        if (byPos.isEmpty()) {
            WATCHERS.remove(nets);
        }
    }

    // ------------------------------------------------------------------ gemeinsam

    /** Wie oft eine Plane nachsieht, was vor ihr steht (Ticks). */
    static final int PROBE_INTERVAL = 5;

    /** Verteilt die Planes einer Welt gleichmäßig über das Intervall. */
    static int phase(WorldNetworks.Node node, int interval) {
        return Math.floorMod(node.x() * 31 + node.y() * 17 + node.z() * 13, interval);
    }

    static void warn(String message, @Nullable Throwable cause) {
        HytaleLogger log = logger;
        if (log == null) {
            return;
        }
        HytaleLogger.Api api = log.at(Level.WARNING).atMostEvery(1, TimeUnit.MINUTES);
        if (cause != null) {
            api = api.withCause(cause);
        }
        api.log(message);
    }
}
