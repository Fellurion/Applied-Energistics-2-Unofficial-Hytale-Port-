/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.automation.FormationPlanePart, appeng.menu.implementations.FormationPlaneMenu und
 * appeng.client.gui.implementations.FormationPlaneScreen).
 */
package de.fellurion.ae2.hytale.plane;

import de.fellurion.ae2.core.automation.FormationPlaneLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.YesNo;
import de.fellurion.ae2.core.tools.MemoryCardSettings;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.device.Drops;
import de.fellurion.ae2.hytale.device.Watchers;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formation Plane: ein Speicher des Netzes, der alles, was in ihn eingelagert wird, vor sich als Block setzt oder
 * als Item auswirft.
 *
 * <p>Filter, Upgrade-Karten (Fuzzy, Inverter, bis zu 5 Kapazitätskarten), „als Block setzen / als Item auswerfen“
 * und die Priorität stellt der Spieler in {@code hytale.ui.DeviceConfigPage} ein (Block benutzen). Alles steht als
 * JSON in der Block-Komponente ({@code Data}), die Priorität zusätzlich in {@code Priority}. Wird der Block abgebaut,
 * fallen die eingebauten Karten heraus.
 *
 * <p>Das Gerät sieht alle {@value Planes#PROBE_INTERVAL} Ticks nach, ob sich der Block (oder die Flüssigkeit) vor der
 * Plane geändert hat,
 * und sagt es dem Kern (AE2: Nachbar-Update) – der merkt sich sonst, dass die Stelle belegt war, und fragt die Welt
 * nicht bei jedem Einlagern neu.
 */
public final class FormationPlaneDevice implements Device, ConfigHost {

    /** Namen der Einstellungen wie in AE2 ({@code appeng.api.config.Settings}), für die Memory Card. */
    public static final String SETTING_PLACE_BLOCK = "place_block";
    public static final String SETTING_FUZZY_MODE = "fuzzy_mode";

    private static final int UNKNOWN = Integer.MIN_VALUE;

    private final FormationPlaneLogic logic = new FormationPlaneLogic();
    private final Watchers watchers = new Watchers();
    private final Runnable changed = this::blockChanged;
    @Nullable
    private PlaneTarget target;
    private int phase;
    private int lastProbe = UNKNOWN;
    private boolean saveRequested;

    @Override
    public Object owner() {
        return logic;
    }

    public FormationPlaneLogic logic() {
        return logic;
    }

    /** Die Stelle vor der Plane; null, solange die Ausrichtung unbekannt ist. */
    @Nullable
    public PlaneTarget target() {
        return target;
    }

    // ------------------------------------------------------------------ Konfigurationsseite (ConfigHost)

    @Override
    public Watchers watchers() {
        return watchers;
    }

    @Override
    public UpgradeInventory upgradeCards() {
        return logic.getUpgrades();
    }

    @Override
    public GhostSlots ghostSlots(Context ctx) {
        return GhostSlots.filter(logic.getConfig(), logic.availableSlots(),
                logic.isInverted() ? "ae2plane.filter.inverted" : "ae2plane.filter.normal");
    }

    /** AE2: Knopf „Block setzen“ und – mit Fuzzy-Karte – der Fuzzy-Modus. */
    @Override
    public List<Setting> settings(Context ctx) {
        List<Setting> out = new ArrayList<>(2);
        out.add(Setting.toggle(ctx, "ae2plane.setting.place.", logic.getPlaceBlock() == YesNo.YES,
                place -> logic.setPlaceBlock(place ? YesNo.YES : YesNo.NO)));
        if (logic.getUpgrades().isInstalled(UpgradeCard.FUZZY)) {
            out.add(Setting.fuzzy(ctx, logic.getFuzzyMode(), logic::setFuzzyMode));
        }
        return out;
    }

    /**
     * Einstellungen unter AE2s Namen, für die Memory Card (AE2: der Config-Manager von {@code FormationPlanePart}) –
     * beide, auch der Fuzzy-Modus, dessen Knopf erst mit der Fuzzy-Karte erscheint. Filter, Karten und Priorität
     * kopiert die Karte von sich aus.
     */
    @Override
    public Map<String, String> exportSettings() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(SETTING_PLACE_BLOCK, MemoryCardSettings.yesNo(logic.getPlaceBlock() == YesNo.YES));
        out.put(SETTING_FUZZY_MODE, logic.getFuzzyMode().name());
        return out;
    }

    @Override
    public boolean importSettings(Map<String, String> in) {
        boolean any = MemoryCardSettings.importYesNo(in, SETTING_PLACE_BLOCK,
                place -> logic.setPlaceBlock(place ? YesNo.YES : YesNo.NO));
        any |= MemoryCardSettings.importEnum(in, SETTING_FUZZY_MODE, FuzzyMode.class, logic::setFuzzyMode);
        return any;
    }

    @Override
    public boolean hasPriority() {
        return true;
    }

    @Override
    public int priority() {
        return logic.getPriority();
    }

    /** Priorität setzen (Kern und Block-Komponente). */
    @Override
    public void setPriority(WorldNetworks.Node node, int priority) {
        logic.setPriority(priority);
        node.comp.setPriority(priority);
        node.markDirty();
    }

    /** Was vor der Plane steht; dahinter, wenn sie offline ist. */
    @Override
    public String info(Context ctx) {
        String front = ctx.tr("ae2plane.info.front", "block", frontName(ctx.nets(), ctx.lang()));
        return online(ctx.node()) ? front : front + " · " + ctx.tr("ae2plane.info.offline");
    }

    /** Hinweis, was die Plane mit dem tut, was sie nicht setzen kann. */
    @Override
    public String note(Context ctx) {
        return ctx.tr(logic.getPlaceBlock() == YesNo.YES ? "ae2plane.hint.place" : "ae2plane.hint.drop");
    }

    /** Anzeigename dessen, was vor der Plane steht („nichts“, wenn die Stelle frei ist). */
    String frontName(WorldNetworks nets, String lang) {
        PlaneTarget t = target;
        if (t == null) {
            return TerminalTexts.tr(lang, "ae2plane.front.none");
        }
        int block = t.blockId();
        if (block == PlaneTarget.NOT_LOADED) {
            return TerminalTexts.tr(lang, "ae2plane.front.unloaded");
        }
        return PlaneHud.blockName(lang, block);
    }

    // ------------------------------------------------------------------ Gerät

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        String json = node.comp.data();
        if (json != null) {
            try {
                logic.fromJson(json);
            } catch (RuntimeException ignored) {
                // kaputte Konfiguration: Standardwerte
            }
        }
        logic.setPriority(node.comp.priority());
        logic.setSaveListener(() -> saveRequested = true);
        phase = Planes.phase(node, Planes.PROBE_INTERVAL);
        aim(nets, node);
    }

    /** Gedreht: Die Vorderseite zeigt auf eine andere Stelle – die Plane setzt und wirft ab sofort dort. */
    @Override
    public void onRotated(WorldNetworks nets, WorldNetworks.Node node) {
        aim(nets, node);
    }

    /** Richtet die Plane auf die Stelle vor ihrer Vorderseite aus; ein früheres Ziel wird dabei aufgegeben. */
    private void aim(WorldNetworks nets, WorldNetworks.Node node) {
        PlaneTarget old = target;
        if (old != null) {
            Planes.unwatch(nets, old, changed);
        }
        target = null;
        lastProbe = UNKNOWN;
        Dir facing = node.facing();
        if (facing == null) {
            logic.setPlacementStrategy(null);
            return;
        }
        PlaneTarget t = new PlaneTarget(nets, node, facing);
        target = t;
        // neue Strategien: was sich die alten über die alte Stelle gemerkt haben („belegt“), gilt hier nicht
        logic.setPlacementStrategy(Planes.createPlacementStrategies(t));
        Planes.watch(nets, t, changed);
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        PlaneTarget t = target;
        if (t == null || (nets.tickCount() + phase) % Planes.PROBE_INTERVAL != 0) {
            return;
        }
        int probe = t.probe();
        if (probe != lastProbe) {
            lastProbe = probe;
            logic.onNeighborChanged();
        }
    }

    @Override
    public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
        if (saveRequested) {
            saveRequested = false;
            node.comp.setData(logic.toJson());
            node.markDirty();
        }
        watchers.tick(nets.tickCount());
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        PlaneTarget t = target;
        if (t != null) {
            Planes.unwatch(nets, t, changed);
        }
        if (broken) {
            // Abgebaut: eingebaute Karten fallen heraus (AE2: UpgradeablePart.addAdditionalDrops)
            Drops.upgrades(nets.world(), node.x(), node.y(), node.z(), logic.getUpgrades());
        } else {
            // Chunk wird entladen: ausstehende Änderungen noch speichern
            node.comp.setData(logic.toJson());
            node.markDirty();
        }
        watchers.run(); // offene Seiten merken, dass es die Plane nicht mehr gibt, und schließen sich
    }

    /** Jemand hat gemeldet, dass sich der Block vor der Plane geändert hat. */
    private void blockChanged() {
        PlaneTarget t = target;
        if (t != null) {
            lastProbe = t.probe();
            logic.onNeighborChanged();
        }
    }

}
