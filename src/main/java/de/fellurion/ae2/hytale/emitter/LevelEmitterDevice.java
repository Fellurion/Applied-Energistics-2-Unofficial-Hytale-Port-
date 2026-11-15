/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.automation.StorageLevelEmitterPart / EnergyLevelEmitterPart,
 * appeng.menu.implementations.StorageLevelEmitterMenu / EnergyLevelEmitterMenu und ihre Screens).
 */
package de.fellurion.ae2.hytale.emitter;

import de.fellurion.ae2.core.automation.AbstractLevelEmitterLogic;
import de.fellurion.ae2.core.automation.EnergyLevelEmitterLogic;
import de.fellurion.ae2.core.automation.LevelEmitterLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.grid.GridNode;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.tools.MemoryCardSettings;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Device;
import de.fellurion.ae2.hytale.device.Drops;
import de.fellurion.ae2.hytale.device.Watchers;
import de.fellurion.ae2.hytale.signal.Signals;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ME Level Emitter und ME Energy Level Emitter. Die Kernlogik vergleicht Menge bzw. Energie mit der Schwelle; das
 * Gerät meldet jede Änderung der Ausgabe als AE2-Signal ({@link Signals}) und zeigt sie am Block (State
 * {@code Online} = sendet).
 *
 * <p>Eingestellt wird in {@code hytale.ui.DeviceConfigPage} (Block benutzen): beobachtetes Item, Schwelle,
 * Signalmodus, Karten – was die Seite zeigt, liefert das Gerät als {@link ConfigHost} wie AE2s Screens: mit
 * Crafting-Karte statt Schwelle und Signalmodus der Crafting-Modus, mit Fuzzy-Karte zusätzlich der Fuzzy-Modus.
 * Alles steht als JSON in der Block-Komponente ({@code Data}). Wird der Block abgebaut, fällt die Karte heraus.
 *
 * <p>Abweichung von AE2: Der Block hat kein eigenes Bild für „Fackel an“; solange er sendet, leuchtet seine
 * Statusanzeige ({@code Online}), sonst ist sie dunkel – auch wenn er am Netz ist.
 */
public final class LevelEmitterDevice implements Device, ConfigHost {

    /** Namen der Einstellungen wie in AE2 ({@code appeng.api.config.Settings}), für die Memory Card. */
    public static final String SETTING_REDSTONE_EMITTER = "redstone_emitter";
    public static final String SETTING_CRAFT_VIA_REDSTONE = "craft_via_redstone";
    public static final String SETTING_FUZZY_MODE = "fuzzy_mode";
    /**
     * Die Schwelle. In AE2 kein Setting, sondern ein eigener Eintrag der Karte
     * ({@code exported_level_emitter_value}); hier steht sie als Zahl bei den Einstellungen.
     */
    public static final String SETTING_VALUE = "level_emitter_value";

    /** Schritte der Knöpfe am Zahlenfeld: Items und AE. */
    private static final long[] ITEM_STEPS = {1, 10, 100};
    private static final long[] ENERGY_STEPS = {100, 1_000, 10_000};

    private final AbstractLevelEmitterLogic logic;
    /** Der Item-Emitter (mit Filterplatz und Kartenfach); null beim Energy Level Emitter. */
    @Nullable
    private final LevelEmitterLogic storage;
    /** Das AE2-Signal der Welt; null nur ohne Welt (Tests). */
    @Nullable
    private final Signals signals;
    private final int x, y, z;
    private final Watchers watchers = new Watchers();
    private boolean saveRequested;

    LevelEmitterDevice(WorldNetworks nets, WorldNetworks.Node node, boolean energy) {
        this(Signals.of(nets.world()), node.x(), node.y(), node.z(), node.comp.data(), energy);
    }

    /**
     * @param signals wohin der Emitter sendet
     * @param data    gespeicherter Zustand aus der Block-Komponente, oder null
     */
    public LevelEmitterDevice(@Nullable Signals signals, int x, int y, int z, @Nullable String data, boolean energy) {
        this.storage = energy ? null : new LevelEmitterLogic();
        this.logic = energy ? new EnergyLevelEmitterLogic() : storage;
        this.signals = signals;
        this.x = x;
        this.y = y;
        this.z = z;
        // Der Zustand muss stehen, bevor der Netzknoten entsteht: der Kern fragt beim Anlegen sofort nach dem
        // beobachteten Item.
        try {
            logic.loadState(data);
        } catch (RuntimeException ignored) {
            // kaputte Konfiguration: Standardwerte
        }
        logic.setSaveListener(() -> saveRequested = true);
        logic.setOutputListener(this::send);
        // Wie AE2 (prevState): die zuletzt gespeicherte Ausgabe gilt, bis das Netz etwas anderes sagt
        send(logic.isEmitting());
    }

    private void send(boolean on) {
        if (signals != null) {
            signals.setEmitting(x, y, z, on);
        }
    }

    @Override
    public Object owner() {
        return logic;
    }

    public AbstractLevelEmitterLogic logic() {
        return logic;
    }

    /** Der Item-Emitter oder null (Energy Level Emitter). */
    @Nullable
    public LevelEmitterLogic storage() {
        return storage;
    }

    public boolean isEnergy() {
        return storage == null;
    }

    /** Menge bzw. Energie, mit der die Schwelle gerade verglichen wird (Energie: der aktuelle Stand des Netzes). */
    public long currentValue(WorldNetworks.Node node) {
        if (storage == null) {
            GridNode g = node.gridNode();
            if (g != null && g.getGrid() != null) {
                return (long) g.getGrid().getEnergyService().getStoredPower();
            }
        }
        return logic.getLastReportedValue();
    }

    /**
     * Ist {@link #currentValue} nur eine Untergrenze? Ohne Item und mit Fuzzy-Karte hört der Kern auf zu zählen,
     * sobald die Schwelle überschritten ist.
     */
    public boolean valueIsLowerBound() {
        return storage != null && !storage.isUpgradedWith(UpgradeCard.CRAFTING)
                && (storage.getConfiguredKey() == null || storage.isUpgradedWith(UpgradeCard.FUZZY))
                && logic.getLastReportedValue() > logic.getReportingValue();
    }

    // ------------------------------------------------------------------ Konfigurationsseite (ConfigHost)

    @Override
    public Watchers watchers() {
        return watchers;
    }

    @Override
    @Nullable
    public GhostSlots ghostSlots(Context ctx) {
        if (storage == null) {
            return null;
        }
        return new GhostSlots(storage.getConfig(), 1, false, "ae2emitter.filter", "ae2bus.tip.filter",
                "ae2emitter.hint.emitter");
    }

    @Override
    @Nullable
    public UpgradeInventory upgradeCards() {
        return storage == null ? null : storage.getUpgrades();
    }

    /** AE2: Signalmodus, mit Fuzzy-Karte der Fuzzy-Modus; mit Crafting-Karte nur der Crafting-Modus. */
    @Override
    public List<Setting> settings(Context ctx) {
        List<Setting> out = new ArrayList<>(2);
        LevelEmitterLogic s = storage;
        if (s != null && s.isUpgradedWith(UpgradeCard.CRAFTING)) {
            out.add(Setting.toggle(ctx, "ae2emitter.setting.craft.", s.isCraftViaRedstone(), s::setCraftViaRedstone));
            return out;
        }
        out.add(Setting.cycle(ctx, isEnergy() ? "ae2emitter.setting.energy." : "ae2emitter.setting.level.",
                logic.getRedstoneMode(), new RedstoneMode[]{RedstoneMode.HIGH_SIGNAL, RedstoneMode.LOW_SIGNAL},
                logic::setRedstoneMode));
        if (s != null && s.isUpgradedWith(UpgradeCard.FUZZY)) {
            out.add(Setting.fuzzy(ctx, s.getFuzzyMode(), s::setFuzzyMode));
        }
        return out;
    }

    /** Die Schwelle; mit Crafting-Karte spielt sie keine Rolle und wird wie in AE2 nicht angeboten. */
    @Override
    @Nullable
    public NumberField numberField(Context ctx) {
        if (storage != null && storage.isUpgradedWith(UpgradeCard.CRAFTING)) {
            return null;
        }
        return new NumberField(isEnergy() ? "ae2emitter.threshold.energy" : "ae2emitter.threshold.items",
                logic.getReportingValue(), 0, Long.MAX_VALUE, isEnergy() ? ENERGY_STEPS : ITEM_STEPS,
                logic::setReportingValue);
    }

    /**
     * Einstellungen unter AE2s Namen, für die Memory Card (AE2: Config-Manager von {@code StorageLevelEmitterPart}
     * bzw. {@code EnergyLevelEmitterPart}) – alle, auch die, deren Knopf erst mit einer Karte erscheint –, dazu die
     * Schwelle, die AE2 ebenfalls kopiert ({@code AbstractLevelEmitterPart.exportSettings}). Das beobachtete Item
     * und die Karte kopiert die Memory Card von sich aus (Filter, Upgrades).
     */
    @Override
    public Map<String, String> exportSettings() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(SETTING_REDSTONE_EMITTER, logic.getRedstoneMode().name());
        LevelEmitterLogic s = storage;
        if (s != null) {
            out.put(SETTING_CRAFT_VIA_REDSTONE, MemoryCardSettings.yesNo(s.isCraftViaRedstone()));
            out.put(SETTING_FUZZY_MODE, s.getFuzzyMode().name());
        }
        out.put(SETTING_VALUE, Long.toString(logic.getReportingValue()));
        return out;
    }

    @Override
    public boolean importSettings(Map<String, String> in) {
        boolean any = false;
        // Emitter kennen wie in AE2 nur „an über“ und „an unter“ der Schwelle
        String mode = in == null ? null : in.get(SETTING_REDSTONE_EMITTER);
        if (RedstoneMode.HIGH_SIGNAL.name().equals(mode) || RedstoneMode.LOW_SIGNAL.name().equals(mode)) {
            logic.setRedstoneMode(RedstoneMode.valueOf(mode));
            any = true;
        }
        LevelEmitterLogic s = storage;
        if (s != null) {
            any |= MemoryCardSettings.importYesNo(in, SETTING_CRAFT_VIA_REDSTONE, s::setCraftViaRedstone);
            any |= MemoryCardSettings.importEnum(in, SETTING_FUZZY_MODE, FuzzyMode.class, s::setFuzzyMode);
        }
        String value = in == null ? null : in.get(SETTING_VALUE);
        if (value != null) {
            try {
                long threshold = Long.parseLong(value);
                if (threshold >= 0) {
                    logic.setReportingValue(threshold);
                    any = true;
                }
            } catch (NumberFormatException ignored) {
                // keine Zahl: bleibt, wie es ist
            }
        }
        return any;
    }

    /** „Stein: 150 von 100 · Signal an“ – oder dass der Emitter offline ist. */
    @Override
    public String info(Context ctx) {
        WorldNetworks.Node node = ctx.node();
        if (node == null || !online(node)) {
            return ctx.tr("ae2bus.info.offline");
        }
        return describe(ctx.lang(), node) + " · " + signalText(ctx.lang());
    }

    @Override
    @Nullable
    public String note(Context ctx) {
        LevelEmitterLogic s = storage;
        if (s == null) {
            return ctx.tr("ae2emitter.note.energy");
        }
        if (s.isUpgradedWith(UpgradeCard.CRAFTING)) {
            return ctx.tr(s.isCraftViaRedstone() ? "ae2emitter.note.craftVia" : "ae2emitter.note.whileCrafting");
        }
        return ctx.tr(s.getConfiguredKey() == null ? "ae2emitter.note.all" : "ae2emitter.note.item");
    }

    @Override
    public void configChanged(WorldNetworks.Node node) {
        save(node);
    }

    // ------------------------------------------------------------------ Texte (Seite, Blickanzeige, Befehle)

    /** Was beobachtet wird und wie viel davon da ist, etwa „Stein: 150 von 100“. */
    public String describe(String lang, WorldNetworks.Node node) {
        LevelEmitterLogic s = storage;
        if (s == null) {
            return TerminalTexts.tr(lang, "ae2emitter.info.energy", "amount", TerminalTexts.formatAmount(
                    currentValue(node)), "threshold", TerminalTexts.formatAmount(logic.getReportingValue()));
        }
        String what = watchedName(lang);
        if (s.isUpgradedWith(UpgradeCard.CRAFTING)) {
            return TerminalTexts.tr(lang, "ae2emitter.info.crafting", "item", what);
        }
        return TerminalTexts.tr(lang, "ae2emitter.info.level", "item", what, "amount", amountText(lang, node),
                "threshold", TerminalTexts.formatAmount(logic.getReportingValue()));
    }

    /** Name des beobachteten Items bzw. „Alle Items“. */
    public String watchedName(String lang) {
        AEKey key = storage == null ? null : storage.getConfiguredKey();
        if (key instanceof AEItemKey item) {
            return TerminalTexts.itemName(lang, item.itemId());
        }
        if (key instanceof de.fellurion.ae2.core.stacks.AEFluidKey fluid) {
            // Flüssigkeit im Filter (Menge und Schwelle dann in mB)
            return de.fellurion.ae2.hytale.fluid.FluidTypes.name(lang, fluid.fluidId());
        }
        return key == null ? TerminalTexts.tr(lang, "ae2emitter.watch.all") : key.getId();
    }

    /** Menge im AE2-Kurzformat; „über 100“, wenn der Kern nur bis zur Schwelle gezählt hat. */
    public String amountText(String lang, WorldNetworks.Node node) {
        if (valueIsLowerBound()) {
            return TerminalTexts.tr(lang, "ae2emitter.value.above", "threshold",
                    TerminalTexts.formatAmount(logic.getReportingValue()));
        }
        return TerminalTexts.formatAmount(currentValue(node));
    }

    public String signalText(String lang) {
        return TerminalTexts.tr(lang, logic.isEmitting() ? "ae2emitter.signal.on" : "ae2emitter.signal.off");
    }

    // ------------------------------------------------------------------ Gerät

    @Override
    public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
        if (saveRequested) {
            save(node);
        }
        watchers.tick(nets.tickCount());
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        send(false); // ein abgebauter oder entladener Emitter sendet nicht
        if (broken) {
            if (storage != null) {
                // Abgebaut: die eingebaute Karte fällt heraus (AE2: UpgradeablePart.addAdditionalDrops)
                Drops.upgrades(nets.world(), x, y, z, storage.getUpgrades());
            }
        } else {
            // Chunk wird entladen: Einstellungen und letzte Ausgabe speichern
            save(node);
        }
        watchers.run(); // offene Seiten schließen sich
    }

    /** Leuchtet, solange der Emitter sendet. */
    @Override
    public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
        return logic.isEmitting() ? "Online" : "default";
    }

    private void save(WorldNetworks.Node node) {
        saveRequested = false;
        node.comp.setData(logic.saveState());
        node.markDirty();
    }
}
