/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.parts.automation.ImportBusPart / ExportBusPart / StorageBusPart).
 */
package de.fellurion.ae2.hytale.device;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import de.fellurion.ae2.core.automation.ConfigInventory;
import de.fellurion.ae2.core.automation.ExportBusLogic;
import de.fellurion.ae2.core.automation.ExternalStorage;
import de.fellurion.ae2.core.automation.ExternalStorageFacade;
import de.fellurion.ae2.core.automation.IOBusLogic;
import de.fellurion.ae2.core.automation.ImportBusLogic;
import de.fellurion.ae2.core.automation.StorageBusLogic;
import de.fellurion.ae2.core.cells.UpgradeCard;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.AccessRestriction;
import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.config.Enums.RedstoneMode;
import de.fellurion.ae2.core.config.Enums.SchedulingMode;
import de.fellurion.ae2.core.config.Enums.StorageFilter;
import de.fellurion.ae2.core.crafting.MultiCraftingTracker;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.inv.SlotAccess;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.MEStorage;
import de.fellurion.ae2.core.storage.NullStorage;
import de.fellurion.ae2.core.tools.MemoryCardSettings;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.HytaleSlots;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.security.AE2Security;
import de.fellurion.ae2.hytale.signal.Signals;
import de.fellurion.ae2.hytale.ui.ConfigEdits;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Import-, Export- und Storage-Bus. Ziel ist der Container des Blocks, auf den die Vorderseite zeigt
 * (Truhe, AE2-Maschine, Hytale-Werkbank mit Slots ...) bzw. das Lager, das ein AE2-Gerät dort selbst anbietet
 * ({@link StorageTarget}: die Flüssigkeit eines Sky Stone Tank). Das Ziel wird jede Sekunde neu gesucht.
 *
 * <p>Filter, Upgrade-Karten und Einstellungen stellt der Spieler in {@code hytale.ui.DeviceConfigPage} ein (Block
 * benutzen); was die Seite zeigt, liefert der Bus als {@link ConfigHost} (AE2: IOBusMenu / StorageBusMenu und ihre
 * Screens). Alles steht als JSON in der Block-Komponente ({@code Data}), die Priorität des Storage-Bus zusätzlich in
 * {@code Priority}. Wird der Block abgebaut, fallen die eingebauten Karten heraus.
 */
public final class BusDevice implements Device, ConfigHost {

    /** AE2: 18 Filterplätze, je Kapazitätskarte 9 mehr (höchstens 63). */
    public static final int BASE_FILTER_SLOTS = 18;
    public static final int SLOTS_PER_CAPACITY_CARD = 9;

    /** Namen der Einstellungen wie in AE2 ({@code appeng.api.config.Settings}), für die Memory Card. */
    public static final String SETTING_REDSTONE_CONTROLLED = "redstone_controlled";
    public static final String SETTING_FUZZY_MODE = "fuzzy_mode";
    public static final String SETTING_CRAFT_ONLY = "craft_only";
    public static final String SETTING_SCHEDULING_MODE = "scheduling_mode";
    public static final String SETTING_ACCESS = "access";
    public static final String SETTING_STORAGE_FILTER = "storage_filter";
    public static final String SETTING_FILTER_ON_EXTRACT = "filter_on_extract";

    private final String kind;
    @Nullable
    private final IOBusLogic ioBus;
    @Nullable
    private final StorageBusLogic storageBus;
    /** Woran das aktuelle Ziel erkannt wird: der Container bzw. das Interface-Gerät vor dem Bus. */
    @Nullable
    private Object target;
    /** Block vor dem Bus (für die Anzeige „Verbunden mit“), gültig solange {@link #target} gesetzt ist. */
    private int targetX, targetY, targetZ;
    private boolean saveRequested;
    private final Watchers watchers = new Watchers();
    // Redstone-Karte: das AE2-Signal der Welt, ob die Karte eingebaut ist und der zuletzt gelesene Signalstand
    @Nullable
    private Signals signals;
    private boolean redstoneCard;
    private long signalVersion = -1;

    public BusDevice(String kind) {
        this.kind = kind;
        switch (kind) {
            case "import_bus" -> {
                ioBus = new ImportBusLogic();
                storageBus = null;
            }
            case "export_bus" -> {
                ioBus = new ExportBusLogic();
                storageBus = null;
            }
            default -> {
                ioBus = null;
                storageBus = new StorageBusLogic();
            }
        }
    }

    @Override
    public Object owner() {
        return ioBus != null ? ioBus : storageBus;
    }

    @Nullable
    public IOBusLogic ioBus() {
        return ioBus;
    }

    @Nullable
    public StorageBusLogic storageBus() {
        return storageBus;
    }

    /** Offene Konfigurationsseiten dieses Bus. */
    @Override
    public Watchers watchers() {
        return watchers;
    }

    /** Filterplätze (Typen ohne Menge). */
    public ConfigInventory config() {
        return ioBus != null ? ioBus.getConfig() : storageBus.getConfig();
    }

    public UpgradeInventory upgrades() {
        return ioBus != null ? ioBus.getUpgrades() : storageBus.getUpgrades();
    }

    /** Item-Id des Bus (bestimmt, welche Karten passen). */
    public String hostItemId() {
        return upgrades().getHostItemId();
    }

    /** Nutzbare Filterplätze: {@code min(18 + 9 · Kapazitätskarten, 63)} wie im Kern. */
    public int visibleSlots() {
        return visibleSlots(upgrades().getInstalledUpgrades(UpgradeCard.CAPACITY), config().size());
    }

    public static int visibleSlots(int capacityCards, int configSize) {
        return Math.min(BASE_FILTER_SLOTS + Math.max(0, capacityCards) * SLOTS_PER_CAPACITY_CARD, configSize);
    }

    /** Zeigt die Vorderseite auf ein Inventar, das der Bus benutzen darf? */
    public boolean hasTarget() {
        return target != null;
    }

    public int targetX() {
        return targetX;
    }

    public int targetY() {
        return targetY;
    }

    public int targetZ() {
        return targetZ;
    }

    /** Priorität des Storage-Bus setzen (Kern und Block-Komponente); andere Busse haben keine. */
    @Override
    public void setPriority(WorldNetworks.Node node, int priority) {
        if (storageBus == null) {
            return;
        }
        storageBus.setPriority(priority);
        node.comp.setPriority(priority);
        node.markDirty();
    }

    // ------------------------------------------------------------------ Konfigurationsseite (ConfigHost)

    @Override
    public UpgradeInventory upgradeCards() {
        return upgrades();
    }

    @Override
    public GhostSlots ghostSlots(Context ctx) {
        boolean inverted = upgrades().isInstalled(UpgradeCard.INVERTER);
        String header = switch (kind) {
            case "import_bus" -> inverted ? "ae2bus.filter.importInverted" : "ae2bus.filter.import";
            case "export_bus" -> "ae2bus.filter.export";
            default -> inverted ? "ae2bus.filter.storageInverted" : "ae2bus.filter.storage";
        };
        return GhostSlots.filter(config(), visibleSlots(), header);
    }

    /** „Verbunden mit: …“ bzw. dass vor dem Bus kein Inventar steht; dahinter, wenn er offline ist. */
    @Override
    public String info(Context ctx) {
        String target;
        if (!hasTarget()) {
            target = ctx.tr("ae2bus.info.noTarget");
        } else {
            BlockType bt = BlockAccess.blockType(ctx.nets().world(), targetX, targetY, targetZ);
            String id = bt == null || bt.getItem() == null ? null : bt.getItem().getId();
            target = ctx.tr("ae2bus.info.target", "block", id == null ? "?" : TerminalTexts.itemName(ctx.lang(), id));
        }
        return online(ctx.node()) ? target : target + " · " + ctx.tr("ae2bus.info.offline");
    }

    /** Die Einstellungen, die der Bus mit seinen eingebauten Karten gerade hat (AE2: sichtbare Knöpfe der Screens). */
    @Override
    public List<Setting> settings(Context ctx) {
        List<Setting> out = new ArrayList<>();
        UpgradeInventory upgrades = upgrades();
        boolean fuzzy = upgrades.isInstalled(UpgradeCard.FUZZY);
        StorageBusLogic sb = storageBus;
        if (sb != null) {
            out.add(Setting.cycle(ctx, "ae2bus.setting.access.", sb.getAccess(), new AccessRestriction[]{
                    AccessRestriction.READ_WRITE, AccessRestriction.READ, AccessRestriction.WRITE}, sb::setAccess));
            out.add(Setting.cycle(ctx, "ae2bus.setting.storageFilter.", sb.getStorageFilter(), StorageFilter.values(),
                    sb::setStorageFilter));
            out.add(Setting.toggle(ctx, "ae2bus.setting.filterOnExtract.", sb.isFilterOnExtract(),
                    sb::setFilterOnExtract));
            if (fuzzy) {
                out.add(Setting.fuzzy(ctx, sb.getFuzzyMode(), sb::setFuzzyMode));
            }
            return out;
        }
        IOBusLogic io = ioBus;
        if (io == null) {
            return out;
        }
        if (io instanceof ExportBusLogic export) {
            out.add(Setting.cycle(ctx, "ae2bus.setting.scheduling.", export.getSchedulingMode(),
                    SchedulingMode.values(), export::setSchedulingMode));
        }
        if (fuzzy) {
            out.add(Setting.fuzzy(ctx, io.getFuzzyMode(), io::setFuzzyMode));
        }
        if (upgrades.isInstalled(UpgradeCard.REDSTONE)) {
            out.add(Setting.cycle(ctx, "ae2bus.setting.redstone.", io.getRedstoneMode(), RedstoneMode.values(),
                    io::setRedstoneMode));
        }
        if (io instanceof ExportBusLogic export && upgrades.isInstalled(UpgradeCard.CRAFTING)) {
            out.add(Setting.toggle(ctx, "ae2bus.setting.craftOnly.", export.isCraftOnly(), export::setCraftOnly));
        }
        return out;
    }

    @Override
    public boolean hasPriority() {
        return storageBus != null;
    }

    @Override
    public int priority() {
        return storageBus == null ? 0 : storageBus.getPriority();
    }

    /**
     * Einstellungen unter AE2s Namen ({@code appeng.api.config.Settings}) – alle, die der Bus hat, auch die, deren
     * Knopf erst mit der passenden Karte erscheint (AE2: {@code IConfigManager.exportSettings}).
     */
    @Override
    public Map<String, String> exportSettings() {
        Map<String, String> out = new LinkedHashMap<>();
        StorageBusLogic sb = storageBus;
        if (sb != null) {
            out.put(SETTING_ACCESS, sb.getAccess().name());
            out.put(SETTING_FUZZY_MODE, sb.getFuzzyMode().name());
            out.put(SETTING_STORAGE_FILTER, sb.getStorageFilter().name());
            out.put(SETTING_FILTER_ON_EXTRACT, MemoryCardSettings.yesNo(sb.isFilterOnExtract()));
            return out;
        }
        IOBusLogic io = ioBus;
        if (io != null) {
            out.put(SETTING_REDSTONE_CONTROLLED, io.getRedstoneMode().name());
            out.put(SETTING_FUZZY_MODE, io.getFuzzyMode().name());
            if (io instanceof ExportBusLogic export) {
                out.put(SETTING_CRAFT_ONLY, MemoryCardSettings.yesNo(export.isCraftOnly()));
                out.put(SETTING_SCHEDULING_MODE, export.getSchedulingMode().name());
            }
        }
        return out;
    }

    /** Übernimmt die Einstellungen, die dieser Bus kennt; fremde Namen und ungültige Werte bleiben ohne Wirkung. */
    @Override
    public boolean importSettings(Map<String, String> in) {
        boolean any = false;
        StorageBusLogic sb = storageBus;
        if (sb != null) {
            // Der Storage-Bus kennt wie in AE2 nur drei Zugriffsarten; NO_ACCESS ist für ihn kein gültiger Wert
            if (in == null || !AccessRestriction.NO_ACCESS.name().equals(in.get(SETTING_ACCESS))) {
                any |= MemoryCardSettings.importEnum(in, SETTING_ACCESS, AccessRestriction.class, sb::setAccess);
            }
            any |= MemoryCardSettings.importEnum(in, SETTING_FUZZY_MODE, FuzzyMode.class, sb::setFuzzyMode);
            any |= MemoryCardSettings.importEnum(in, SETTING_STORAGE_FILTER, StorageFilter.class, sb::setStorageFilter);
            any |= MemoryCardSettings.importYesNo(in, SETTING_FILTER_ON_EXTRACT, sb::setFilterOnExtract);
            return any;
        }
        IOBusLogic io = ioBus;
        if (io != null) {
            any |= MemoryCardSettings.importEnum(in, SETTING_REDSTONE_CONTROLLED, RedstoneMode.class,
                    io::setRedstoneMode);
            any |= MemoryCardSettings.importEnum(in, SETTING_FUZZY_MODE, FuzzyMode.class, io::setFuzzyMode);
            if (io instanceof ExportBusLogic export) {
                any |= MemoryCardSettings.importYesNo(in, SETTING_CRAFT_ONLY, export::setCraftOnly);
                any |= MemoryCardSettings.importEnum(in, SETTING_SCHEDULING_MODE, SchedulingMode.class,
                        export::setSchedulingMode);
            }
        }
        return any;
    }

    /** Storage-Bus: „Filter aus Inhalt“ (AE2: {@code StorageBusMenu.partition}). */
    @Override
    @Nullable
    public Action action(Context ctx) {
        return storageBus == null ? null : new Action(ctx.tr("ae2bus.filter.partition"), () -> partition(ctx));
    }

    /** Füllt den Filter mit dem, was gerade im angeschlossenen Inventar liegt. */
    private Feedback partition(Context ctx) {
        MEStorage target = storageBus.getTarget();
        if (target instanceof NullStorage) {
            return new Feedback(ctx.tr("ae2bus.msg.noTarget"), true);
        }
        // Items und Flüssigkeiten (Tank), Items zuerst
        List<AEKey> keys = new ArrayList<>(target.getAvailableStacks().keySet());
        keys.sort(Comparator.comparing((AEKey k) -> k.getType().ordinal()).thenComparing(AEKey::getId)
                .thenComparing(k -> k instanceof AEItemKey ik && ik.nbt() != null ? ik.nbt() : ""));
        int set = ConfigEdits.partition(config(), visibleSlots(), keys);
        return new Feedback(set > 0 ? ctx.tr("ae2bus.msg.partitioned", "count", Integer.toString(set))
                : ctx.tr("ae2bus.msg.partitionEmpty"), false);
    }

    /** Mit Redstone-Karte: ob der Bus gerade von einem benachbarten Sender angesteuert wird. */
    @Override
    @Nullable
    public String note(Context ctx) {
        if (ioBus == null || !upgrades().isInstalled(UpgradeCard.REDSTONE)) {
            return null;
        }
        Signals s = signals;
        WorldNetworks.Node n = ctx.node();
        boolean powered = s != null && n != null && s.isPowered(n.x(), n.y(), n.z());
        return ctx.tr(powered ? "ae2emitter.bus.signalOn" : "ae2emitter.bus.signalOff");
    }

    // ------------------------------------------------------------------ Gerät

    @Override
    public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
        String json = node.comp.data();
        try {
            if (json != null && ioBus != null) {
                ioBus.fromJson(json);
            } else if (json != null && storageBus != null) {
                storageBus.fromJson(json);
            }
        } catch (RuntimeException ignored) {
            // kaputte Konfiguration: Standardwerte
        }
        if (ioBus != null) {
            ioBus.setSaveListener(() -> saveRequested = true);
            if (ioBus instanceof ExportBusLogic export) {
                // Crafting-Karte: Fehlendes beim Autocrafting bestellen, das Ergebnis geht direkt ins Ziel
                // (AE2: ExportBusPart.insertCraftedItems)
                IActionSource source = IActionSource.ofMachine(export);
                export.setCraftingHook(new MultiCraftingTracker(export, export.getConfig().size(),
                        (slot, what, amount, mode) -> {
                            ExternalStorage t = export.getTarget();
                            return t == null ? 0 : t.insert(what, amount, mode, source);
                        }));
            }
        } else if (storageBus != null) {
            storageBus.setSaveListener(() -> saveRequested = true);
            storageBus.setPriority(node.comp.priority());
        }
        refreshTarget(nets, node);
        signals = Signals.of(nets.world());
        refreshRedstoneCard();
    }

    @Override
    public void tick(WorldNetworks nets, WorldNetworks.Node node) {
        if (nets.tickCount() % 20 == (node.pos & 15)) {
            refreshTarget(nets, node);
        }
        if (redstoneCard) {
            feedSignal(node);
        }
    }

    @Override
    public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
        if (saveRequested) {
            saveRequested = false;
            node.comp.setData(toJson());
            node.markDirty();
            refreshRedstoneCard(); // jede Änderung an Karten und Einstellungen läuft über das Speichern
        }
        watchers.tick(nets.tickCount());
    }

    // ------------------------------------------------------------------ Redstone-Karte

    /**
     * Meldet dem Bus, ob er angesteuert wird (AE2: {@code IOBusPart.onRedstoneLevelMayHaveChanged} mit
     * {@code getHost().hasRedstone()}): ein Nachbarblock sendet das AE2-Signal ({@link Signals}). Gelesen wird nur
     * mit eingebauter Redstone-Karte und nur, wenn sich in der Welt ein Signal geändert hat.
     */
    private void feedSignal(WorldNetworks.Node node) {
        Signals s = signals;
        if (s == null || ioBus == null) {
            return;
        }
        long version = s.version();
        if (version != signalVersion) {
            signalVersion = version;
            ioBus.setRedstonePowered(s.isPowered(node.x(), node.y(), node.z()));
        }
    }

    /** Merkt sich, ob die Redstone-Karte eingebaut ist; eine neu eingebaute Karte liest das Signal sofort. */
    private void refreshRedstoneCard() {
        boolean card = ioBus != null && ioBus.isUpgradedWith(UpgradeCard.REDSTONE);
        if (card != redstoneCard) {
            redstoneCard = card;
            signalVersion = -1;
        }
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        if (broken) {
            // Abgebaut: eingebaute Karten fallen heraus (AE2: UpgradeablePart.addAdditionalDrops)
            Drops.upgrades(nets.world(), node.x(), node.y(), node.z(), upgrades());
        } else {
            // Chunk wird entladen: ausstehende Änderungen noch speichern
            node.comp.setData(toJson());
            node.markDirty();
        }
        watchers.run(); // offene Seiten merken, dass es den Bus nicht mehr gibt, und schließen sich
    }

    /** Gedreht: Die Vorderseite zeigt auf einen anderen Block – das Ziel sofort neu suchen, nicht erst im Takt. */
    @Override
    public void onRotated(WorldNetworks nets, WorldNetworks.Node node) {
        refreshTarget(nets, node);
    }

    private String toJson() {
        return ioBus != null ? ioBus.toJson() : storageBus.toJson();
    }

    private void refreshTarget(WorldNetworks nets, WorldNetworks.Node node) {
        BlockAccess.Dir f = node.facing();
        ItemContainer c = null;
        InterfaceDevice iface = null;
        AutomationAccess access = null;
        ExternalStorage tunnel = null;
        MEStorage offered = null;
        int tx = 0, ty = 0, tz = 0;
        if (f != null) {
            tx = node.x() + f.dx;
            ty = node.y() + f.dy;
            tz = node.z() + f.dz;
            // Busse dürfen nicht in fremde AE2-Blöcke greifen (sonst zöge ein fremder Import-Bus Zellen aus dem Drive)
            if (AE2Security.deviceMayAccess(nets, node, tx, ty, tz)) {
                WorldNetworks.Node front = nets.node(tx, ty, tz);
                if (storageBus != null && front != null && "interface".equals(front.kind())) {
                    // Storage-Bus an einem ME-Interface: sieht dessen Vorrat bzw. – ohne Konfiguration – das ganze
                    // Netz dahinter (AE2: Sub-Netze). Nie den rohen Container: das Interface gäbe alles, was der Bus
                    // dort ablegt, sofort wieder ins Netz zurück.
                    iface = front.device() instanceof InterfaceDevice d ? d : null;
                } else {
                    // Item-P2P-Tunnel, der dem Bus zugewandt ist: Ziel sind die Inventare am anderen Tunnelende
                    tunnel = de.fellurion.ae2.hytale.p2p.ItemTunnel.facing(node, front);
                    if (tunnel == null) {
                        // ebenso der Fluid-P2P-Tunnel: Ziel sind die Tanks am anderen Tunnelende
                        tunnel = de.fellurion.ae2.hytale.p2p.FluidTunnel.facing(node, front);
                    }
                    if (tunnel == null && front != null && front.device() instanceof StorageTarget own) {
                        // Geräte mit eigenem Lager (Sky Stone Tank: Flüssigkeit, Condenser am Storage-Bus)
                        offered = storageBus != null ? own.meStorage(f.opposite()) : own.busStorage(f.opposite());
                    }
                    if (tunnel == null && offered == null) {
                        c = BlockAccess.container(nets.world(), tx, ty, tz);
                        // Maschinen mit eigenen Regeln für Automation (Inscriber) zeigen dem Bus ihre Sicht je Seite
                        access = c != null && front != null && front.device() instanceof AutomationAccess a ? a : null;
                    }
                }
            }
        }
        Object next = iface != null ? iface : tunnel != null ? tunnel : offered != null ? offered
                : access != null ? access : c;
        if (next == target) {
            return;
        }
        target = next;
        targetX = tx;
        targetY = ty;
        targetZ = tz;
        if (tunnel != null) {
            if (ioBus != null) {
                ioBus.setTarget(tunnel);
            } else {
                storageBus.setTarget(tunnel);
            }
        } else if (offered != null) {
            if (ioBus != null) {
                ioBus.setTarget((ExternalStorage) offered); // busStorage liefert immer ein ExternalStorage
            } else {
                storageBus.setTarget(offered);
            }
        } else if (ioBus != null) {
            ioBus.setTarget(c == null ? null : storage(nets, c, access, f.opposite(), tx, ty, tz));
        } else if (storageBus != null) {
            if (iface != null) {
                storageBus.setTarget(iface.logic().getExposedStorage());
            } else if (c == null) {
                storageBus.clearTarget();
            } else {
                storageBus.setTarget(storage(nets, c, access, f.opposite(), tx, ty, tz));
            }
        }
    }

    /**
     * Das Ziel als Lager: die Sicht des Geräts von der Seite {@code side} aus, an der der Bus sitzt
     * ({@link AutomationAccess}), sonst der ganze Container. Ist das Ziel ein AE2-Block, dessen Gerät Plätze für
     * Busse sperrt ({@link ExtractRules}, etwa die Eingangsplätze des IO-Ports), entnimmt der Bus dort nichts.
     */
    private static ExternalStorageFacade storage(WorldNetworks nets, ItemContainer c, @Nullable AutomationAccess access,
                                                 BlockAccess.Dir side, int x, int y, int z) {
        SlotAccess view = access == null ? null : access.automationSlots(side);
        if (view != null) {
            ExternalStorageFacade viewFacade = ExternalStorageFacade.of(view);
            viewFacade.setExtractFilter(slot -> access.automationCanExtract(side, slot));
            return viewFacade;
        }
        ExternalStorageFacade facade = ExternalStorageFacade.of(slots(c));
        WorldNetworks.Node target = nets.node(x, y, z);
        if (target != null) {
            // erst beim Entnehmen fragen: im Tick des Platzierens ist das Gerät des Ziels noch nicht angemeldet
            facade.setExtractFilter(slot -> !(target.device() instanceof ExtractRules rules) || rules.busMayExtract(slot));
        }
        return facade;
    }

    /**
     * Slots des Zielcontainers. Leere Slots nehmen nur an, was auch der Slot-Filter des Containers zulässt (Drive:
     * nur Zellen, Pattern Provider: nur Patterns, Interface: nur das konfigurierte Item) – sonst meldete der Bus
     * „eingelegt“, obwohl der Container ablehnt, und die Items wären weg.
     */
    public static HytaleSlots slots(ItemContainer c) {
        return new HytaleSlots(c, 0, c.getCapacity(), new BiPredicate<>() {
            /** Zuletzt geprüftes Item: beim Einlagern wird dasselbe Item für viele Slots hintereinander geprüft. */
            @Nullable
            private AEItemKey lastKey;
            @Nullable
            private ItemStack lastProbe;

            @Override
            public boolean test(Integer slot, SimpleStack stack) {
                try {
                    AEItemKey key = stack.key();
                    if (lastProbe == null || !key.equals(lastKey)) {
                        lastKey = key;
                        lastProbe = ItemKeys.toStack(key, 1);
                    }
                    return c.canAddItemStackToSlot((short) (int) slot, lastProbe, false, true);
                } catch (RuntimeException e) {
                    return false;
                }
            }
        });
    }

    public String kind() {
        return kind;
    }
}
