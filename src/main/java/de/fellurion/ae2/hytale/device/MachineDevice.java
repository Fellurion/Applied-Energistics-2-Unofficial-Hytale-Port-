/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.misc.InscriberBlockEntity, ChargerBlockEntity, VibrationChamberBlockEntity,
 * CondenserBlockEntity; für Karten und Einstellungen appeng.menu.implementations.InscriberMenu,
 * VibrationChamberMenu, CondenserMenu).
 */
package de.fellurion.ae2.hytale.device;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.CondenserOutput;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.inv.SlotAccess;
import de.fellurion.ae2.core.machines.ChargerLogic;
import de.fellurion.ae2.core.machines.ChargerRecipes;
import de.fellurion.ae2.core.machines.CondenserLogic;
import de.fellurion.ae2.core.machines.ICrankable;
import de.fellurion.ae2.core.machines.InscriberLogic;
import de.fellurion.ae2.core.machines.InscriberRecipes;
import de.fellurion.ae2.core.machines.VibrationChamberLogic;
import de.fellurion.ae2.core.tools.MemoryCardSettings;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.HytaleSlots;
import de.fellurion.ae2.hytale.ItemCatalog;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.security.AE2Security;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Maschinen mit Slots (Inscriber, Charger, Vibration Chamber, Condenser). Die Slots sind der
 * Hytale-Container des Blocks (öffnet sich beim Benutzen), die Logik kommt aus dem Kern.
 *
 * <p><b>Karten und Einstellungen</b> (AE2: die Knöpfe und Kartenplätze im Fenster der Maschine) stellt der Spieler in
 * {@code hytale.ui.DeviceConfigPage} ein, die sich öffnet, wenn er den Block <b>geduckt</b> benutzt: Inscriber
 * (Beschleunigungskarten; getrennte Seiten, Auto-Export, Größe der Eingabeplätze), Vibration Chamber
 * (Beschleunigungs- und Energiekarten), Condenser (Ausgabe: vernichten, Materiebälle, Singularitäten). Der Charger hat
 * wie in AE2 nichts einzustellen. Alles steht mit dem übrigen Zustand als JSON in der Block-Komponente ({@code Data});
 * abgebaut lässt die Maschine ihre Karten fallen.
 *
 * <p><b>Automation am Inscriber</b> ({@link AutomationAccess}): Busse und Pattern Provider legen wie in AE2 nur
 * passende Zutaten in die Eingabeplätze (höchstens so viele, wie „Größe der Eingabeplätze“ erlaubt) und entnehmen nur
 * das Ergebnis; mit getrennten Seiten erreichen sie die Platten nur von oben bzw. unten und dürfen sie dort auch wieder
 * herausnehmen. Abweichung von AE2: Im Slot-Fenster von Hytale gilt die Größe der Eingabeplätze nicht.
 */
public final class MachineDevice implements Device, ConfigHost, AutomationAccess, StorageTarget {

    /** Namen der Einstellungen wie in AE2 ({@code appeng.api.config.Settings}), für die Memory Card. */
    public static final String SETTING_SEPARATE_SIDES = "inscriber_separate_sides";
    public static final String SETTING_AUTO_EXPORT = "auto_export";
    public static final String SETTING_INPUT_CAPACITY = "inscriber_input_capacity";
    public static final String SETTING_CONDENSER_OUTPUT = "condenser_output";

    /** Namen der Platzgrößen wie in AE2 ({@code InscriberInputCapacity}), passend zu {@code INPUT_CAPACITIES}. */
    private static final String[] CAPACITY_NAMES = {"ONE", "FOUR", "SIXTY_FOUR"};

    /** Seiten in der Reihenfolge, in der AE2 beim Auto-Export die Nachbarn fragt. */
    private static final Dir[] EXPORT_ORDER = {Dir.DOWN, Dir.UP, Dir.NORTH, Dir.SOUTH, Dir.WEST, Dir.EAST};

    /** Gemeinsame Sicht auf die Kern-Maschinen. */
    private interface Machine {
        Object owner();

        void onInventoryChanged();

        boolean working();

        @Nullable
        String save();

        void load(String json);

        default void tick() {
        }
    }

    private static InscriberRecipes inscriberRecipes;
    private static ChargerRecipes chargerRecipes;

    /** Die Inscriber-Rezepte (z. B. für das Buch „AE2 Basics“); vor dem Plugin-Start leer. */
    public static InscriberRecipes inscriberRecipes() {
        return inscriberRecipes == null ? new InscriberRecipes() : inscriberRecipes;
    }

    static void loadRecipes() {
        inscriberRecipes = InscriberRecipes.fromJson(ItemCatalog.resource("inscriber.json"));
        chargerRecipes = ChargerRecipes.fromJson(ItemCatalog.resource("charger.json"));
    }

    private final String kind;
    @Nullable
    private Machine machine;
    @Nullable
    private ItemContainer container;
    private long lastSnapshot = Long.MIN_VALUE;
    private boolean stateChanged;
    private long lastSaveTick;
    /** Die Kernlogik nach Art – genau eine ist gesetzt (beim Charger keine: er hat nichts einzustellen). */
    @Nullable
    private InscriberLogic inscriber;
    @Nullable
    private VibrationChamberLogic chamber;
    @Nullable
    private CondenserLogic condenser;
    @Nullable
    private WorldNetworks nets;
    @Nullable
    private WorldNetworks.Node node;
    private final Watchers watchers = new Watchers();

    public MachineDevice(String kind) {
        this.kind = kind;
    }

    @Override
    public Object owner() {
        return machine == null ? null : machine.owner();
    }

    /** Wird vor {@code addNode} gebraucht, damit der Kern den Owner kennt. */
    public MachineDevice prepare(WorldNetworks nets, WorldNetworks.Node node) {
        this.nets = nets;
        this.node = node;
        container = BlockAccess.container(nets.world(), node.x(), node.y(), node.z());
        if (container == null) {
            return this;
        }
        withSlots(new HytaleSlots(container));
        if (machine != null && node.comp.data() != null) {
            try {
                machine.load(node.comp.data());
            } catch (RuntimeException ignored) {
                // alter/kaputter Zustand: neu starten
            }
        }
        return this;
    }

    /** Legt die Kernlogik der Maschine über ihren Plätzen an (im Spiel: der Container des Blocks). */
    public MachineDevice withSlots(SlotAccess slots) {
        machine = switch (kind) {
            case "inscriber" -> wrap(new InscriberLogic(slots, inscriberRecipes));
            case "charger" -> wrap(new ChargerLogic(slots, chargerRecipes, de.fellurion.ae2.hytale.items.PoweredItems.CHARGEABLE, new Random()));
            case "vibration_chamber" -> wrap(new VibrationChamberLogic(slots, MachineDevice::burnTicks, s -> null));
            case "condenser" -> wrap(new CondenserLogic(slots));
            default -> null;
        };
        return this;
    }

    /** Ziel für eine Kurbel (Inscriber/Charger), sonst null. */
    @Nullable
    public ICrankable crankable() {
        Object owner = machine == null ? null : machine.owner();
        if (owner instanceof InscriberLogic l) {
            return l.getCrankable();
        }
        if (owner instanceof ChargerLogic l) {
            return l.getCrankable();
        }
        return null;
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        if (machine == null || container == null) {
            return;
        }
        long snap = snapshot(container);
        if (snap != lastSnapshot) {
            lastSnapshot = snap;
            machine.onInventoryChanged();
        }
        machine.tick();
    }

    @Override
    public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
        if (machine == null) {
            return;
        }
        if (container != null) {
            lastSnapshot = snapshot(container);
        }
        if (stateChanged || nets.tickCount() - lastSaveTick >= 100) {
            stateChanged = false;
            lastSaveTick = nets.tickCount();
            save(node);
        }
        watchers.tick(nets.tickCount());
    }

    private void save(WorldNetworks.Node node) {
        String json = machine == null ? null : machine.save();
        if (json != null && !json.equals(node.comp.data())) {
            node.comp.setData(json);
            node.markDirty();
        }
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        if (machine != null && !broken) {
            node.comp.setData(machine.save());
            node.markDirty();
        }
        UpgradeInventory cards = upgradeCards();
        if (broken && cards != null) {
            // Abgebaut: eingebaute Karten fallen heraus (AE2: addAdditionalDrops)
            Drops.upgrades(nets.world(), node.x(), node.y(), node.z(), cards);
        }
        watchers.run(); // offene Seiten merken, dass es die Maschine nicht mehr gibt, und schließen sich
    }

    // ------------------------------------------------------------------ Konfigurationsseite (ConfigHost)

    /** Maschinen öffnen beim Benutzen ihr Slot-Fenster; Karten und Einstellungen gibt es geduckt. */
    @Override
    public boolean opensOnCrouch() {
        return inscriber != null || chamber != null || condenser != null;
    }

    @Override
    @Nullable
    public UpgradeInventory upgradeCards() {
        return inscriber != null ? inscriber.getUpgrades() : chamber != null ? chamber.getUpgrades() : null;
    }

    /** AE2: die Knöpfe links im Fenster (InscriberScreen: Seiten, Auto-Export, Platzgröße; CondenserScreen: Ausgabe). */
    @Override
    public List<Setting> settings(Context ctx) {
        InscriberLogic in = inscriber;
        if (in != null) {
            int capacity = in.getInputCapacity();
            return List.of(
                    Setting.toggle(ctx, "ae2bus.setting.separateSides.", in.isSeparateSides(), in::setSeparateSides),
                    Setting.toggle(ctx, "ae2bus.setting.autoExport.", in.isAutoExport(), in::setAutoExport),
                    new Setting(ctx.tr("ae2bus.setting.inputCapacity", "count", Integer.toString(capacity)),
                            () -> in.setInputCapacity(nextCapacity(capacity))));
        }
        CondenserLogic co = condenser;
        if (co != null) {
            return List.of(Setting.cycle(ctx, "ae2bus.setting.condenser.", co.getMode(), CondenserOutput.values(),
                    co::setMode));
        }
        return List.of();
    }

    /** 1 → 4 → 64 → 1 (AE2: InscriberInputCapacity). */
    static int nextCapacity(int capacity) {
        int[] all = InscriberLogic.INPUT_CAPACITIES;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == capacity) {
                return all[(i + 1) % all.length];
            }
        }
        return all[all.length - 1];
    }

    /** Condenser (kein Netzblock): gespeicherte Materie; Vibration Chamber: dazu die höchste Leistung. */
    @Override
    @Nullable
    public String info(Context ctx) {
        CondenserLogic co = condenser;
        if (co != null) {
            String stored = TerminalTexts.formatAmount((long) co.getStoredPower());
            String capacity = TerminalTexts.formatAmount((long) co.getStorage());
            return co.getRequiredPower() <= 0 ? ctx.tr("ae2bus.info.condenserTrash", "stored", stored, "capacity", capacity)
                    : ctx.tr("ae2bus.info.condenser", "stored", stored, "capacity", capacity, "required",
                    TerminalTexts.formatAmount((long) co.getRequiredPower()));
        }
        VibrationChamberLogic vc = chamber;
        if (vc != null) {
            String state = ctx.tr(online(ctx.node()) ? "ae2bus.info.online" : "ae2bus.info.offline");
            return state + " · " + ctx.tr("ae2bus.info.chamber", "rate", Long.toString(Math.round(vc.getMaxEnergyRate())),
                    "efficiency", Integer.toString(100 + vc.getUpgrades().getInstalledUpgrades(UpgradeCard.ENERGY) * 50));
        }
        return null;
    }

    /**
     * Einstellungen unter AE2s Namen, für die Memory Card (AE2: die Config-Manager von {@code InscriberBlockEntity}
     * und {@code CondenserBlockEntity}). Vibration Chamber und Charger haben wie in AE2 keine – von der Vibration
     * Chamber kopiert die Karte nur die Upgrade-Karten.
     */
    @Override
    @Nullable
    public Map<String, String> exportSettings() {
        InscriberLogic in = inscriber;
        if (in != null) {
            Map<String, String> out = new LinkedHashMap<>();
            out.put(SETTING_SEPARATE_SIDES, MemoryCardSettings.yesNo(in.isSeparateSides()));
            out.put(SETTING_AUTO_EXPORT, MemoryCardSettings.yesNo(in.isAutoExport()));
            String capacity = capacityName(in.getInputCapacity());
            if (capacity != null) {
                out.put(SETTING_INPUT_CAPACITY, capacity);
            }
            return out;
        }
        CondenserLogic co = condenser;
        return co == null ? null : Map.of(SETTING_CONDENSER_OUTPUT, co.getMode().name());
    }

    @Override
    public boolean importSettings(Map<String, String> in) {
        InscriberLogic logic = inscriber;
        if (logic != null) {
            boolean any = MemoryCardSettings.importYesNo(in, SETTING_SEPARATE_SIDES, logic::setSeparateSides);
            any |= MemoryCardSettings.importYesNo(in, SETTING_AUTO_EXPORT, logic::setAutoExport);
            int capacity = capacityOf(in == null ? null : in.get(SETTING_INPUT_CAPACITY));
            if (capacity > 0) {
                logic.setInputCapacity(capacity);
                any = true;
            }
            return any;
        }
        CondenserLogic co = condenser;
        return co != null && MemoryCardSettings.importEnum(in, SETTING_CONDENSER_OUTPUT, CondenserOutput.class,
                co::setMode);
    }

    /** {@code 4} → {@code "FOUR"}; null für eine Größe, die AE2 nicht kennt. */
    @Nullable
    static String capacityName(int capacity) {
        int[] all = InscriberLogic.INPUT_CAPACITIES;
        for (int i = 0; i < all.length && i < CAPACITY_NAMES.length; i++) {
            if (all[i] == capacity) {
                return CAPACITY_NAMES[i];
            }
        }
        return null;
    }

    /** {@code "FOUR"} → {@code 4}; 0 für einen unbekannten Namen. */
    static int capacityOf(@Nullable String name) {
        for (int i = 0; i < CAPACITY_NAMES.length && i < InscriberLogic.INPUT_CAPACITIES.length; i++) {
            if (CAPACITY_NAMES[i].equals(name)) {
                return InscriberLogic.INPUT_CAPACITIES[i];
            }
        }
        return 0;
    }

    /** Inscriber: was „getrennte Seiten“ und die Größe der Eingabeplätze für Busse bedeuten. */
    @Override
    @Nullable
    public String note(Context ctx) {
        return inscriber != null ? ctx.tr("ae2bus.hint.inscriber") : null;
    }

    @Override
    public Watchers watchers() {
        return watchers;
    }

    /** Karten und Einstellungen sofort speichern (sonst erst mit dem nächsten Zustandswechsel der Maschine). */
    @Override
    public void configChanged(WorldNetworks.Node node) {
        save(node);
    }

    // ------------------------------------------------------------------ Eigenes Lager (Condenser)

    /**
     * Storage-Bus am Condenser (AE2: {@code CondenserMEStorage}): Was das Netz dort einlagert – Items wie
     * Flüssigkeiten –, verschwindet und wird zu Materie; herausgegeben wird nur der Ausgabeplatz. Import- und
     * Export-Bus benutzen weiter die Plätze des Condensers.
     */
    @Override
    @Nullable
    public de.fellurion.ae2.core.storage.MEStorage meStorage(Dir side) {
        return condenser == null ? null : condenser.getMEStorage();
    }

    // ------------------------------------------------------------------ Automation (Inscriber)

    private static InscriberLogic.Side side(Dir side) {
        return side == Dir.UP ? InscriberLogic.Side.TOP : side == Dir.DOWN ? InscriberLogic.Side.BOTTOM
                : InscriberLogic.Side.SIDE;
    }

    @Override
    @Nullable
    public SlotAccess automationSlots(Dir side) {
        return inscriber == null ? null : inscriber.getExposedInventory(side(side));
    }

    @Override
    public boolean automationCanExtract(Dir side, int slot) {
        return inscriber == null || inscriber.canExtract(side(side), slot);
    }

    /**
     * Auto-Export des Inscribers: die Inventare neben der Maschine (AE2: {@code InternalInventory.wrapExternal} je
     * Seite); mit getrennten Seiten nicht die über und unter ihr. Fremde AE2-Blöcke bleiben außen vor.
     */
    private List<SlotAccess> exportTargets(boolean sidesOnly) {
        List<SlotAccess> out = new ArrayList<>();
        WorldNetworks nets = this.nets;
        WorldNetworks.Node self = this.node;
        if (nets == null || self == null) {
            return out;
        }
        for (Dir d : EXPORT_ORDER) {
            if (sidesOnly && (d == Dir.UP || d == Dir.DOWN)) {
                continue;
            }
            int x = self.x() + d.dx, y = self.y() + d.dy, z = self.z() + d.dz;
            if (!AE2Security.deviceMayAccess(nets, self, x, y, z)) {
                continue;
            }
            SlotAccess slots = automationSlotsAt(nets, x, y, z, d.opposite());
            if (slots != null) {
                out.add(slots);
            }
        }
        return out;
    }

    /**
     * Die Plätze des Blocks bei x/y/z, wie Automation sie von {@code side} aus sieht: die eigene Sicht des Geräts
     * ({@link AutomationAccess}) oder der ganze Container mit seinen Slot-Filtern; null = dort ist kein Inventar.
     */
    @Nullable
    static SlotAccess automationSlotsAt(WorldNetworks nets, int x, int y, int z, Dir side) {
        WorldNetworks.Node n = nets.node(x, y, z);
        SlotAccess view = n != null && n.device() instanceof AutomationAccess a ? a.automationSlots(side) : null;
        if (view != null) {
            return view;
        }
        ItemContainer c = BlockAccess.container(nets.world(), x, y, z);
        return c == null ? null : BusDevice.slots(c);
    }

    // ------------------------------------------------------------------ Anzeige und Kernlogik

    @Override
    public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
        if (machine == null) {
            return null;
        }
        if (machine.working()) {
            return "Working";
        }
        return node.gridNode() != null && node.gridNode().isOnline() ? "Online" : "default";
    }

    /** Billiger Fingerabdruck des Containers, um Änderungen durch Spieler/Trichter zu erkennen. */
    private static long snapshot(ItemContainer c) {
        long h = 1;
        for (short s = 0; s < c.getCapacity(); s++) {
            var st = c.getItemStack(s);
            h = h * 31 + (st == null ? 0 : st.getItemId().hashCode() * 17L + st.getQuantity());
        }
        return h;
    }

    /** AE2 nutzt Ofen-Brenndauern; Hytale: Ressourcentyp "Fuel" × FuelQuality (Holz ≈ 300 Ticks wie in MC). */
    private static int burnTicks(SimpleStack stack) {
        Item item = Item.getAssetMap().getAsset(stack.itemId());
        if (item == null || item.getResourceTypes() == null) {
            return 0;
        }
        for (ItemResourceType rt : item.getResourceTypes()) {
            if (rt != null && "Fuel".equals(rt.id)) {
                return (int) Math.round(300 * Math.max(0.1, item.getFuelQuality()));
            }
        }
        return 0;
    }

    private Machine wrap(InscriberLogic l) {
        inscriber = l;
        l.setExportTargets(this::exportTargets);
        l.setStateListener(() -> stateChanged = true);
        return machine(l, l::onInventoryChanged, l::isWorking, l::saveState, l::loadState, null);
    }

    private Machine wrap(ChargerLogic l) {
        l.setStateListener(() -> stateChanged = true);
        return machine(l, l::onInventoryChanged, l::isWorking, l::saveState, l::loadState, null);
    }

    private Machine wrap(VibrationChamberLogic l) {
        chamber = l;
        l.setStateListener(() -> stateChanged = true);
        return machine(l, l::onInventoryChanged, l::isOn, l::saveState, l::loadState, null);
    }

    private Machine wrap(CondenserLogic l) {
        condenser = l;
        // Condenser hat keinen GridNode – er tickt selbst
        return machine(null, l::onInventoryChanged, () -> false, l::saveState, l::loadState, l::tick);
    }

    private static Machine machine(@Nullable Object owner, Runnable changed, BooleanSupplier working,
                                   Supplier<String> save, java.util.function.Consumer<String> load,
                                   @Nullable Runnable tick) {
        return new Machine() {
            @Override
            public Object owner() {
                return owner;
            }

            @Override
            public void onInventoryChanged() {
                changed.run();
            }

            @Override
            public boolean working() {
                return working.getAsBoolean();
            }

            @Override
            public String save() {
                return save.get();
            }

            @Override
            public void load(String json) {
                load.accept(json);
            }

            @Override
            public void tick() {
                if (tick != null) {
                    tick.run();
                }
            }
        };
    }
}
