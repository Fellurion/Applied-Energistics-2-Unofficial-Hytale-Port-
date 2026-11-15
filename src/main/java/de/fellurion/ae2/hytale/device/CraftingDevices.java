/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.helpers.patternprovider.PatternProviderLogic, appeng.blockentity.crafting.*; für Einstellungen
 * und Karten appeng.menu.implementations.PatternProviderMenu, MolecularAssemblerMenu).
 */
package de.fellurion.ae2.hytale.device;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import de.fellurion.ae2.core.cells.UpgradeInventory;
import de.fellurion.ae2.core.config.Enums.LockCraftingMode;
import de.fellurion.ae2.core.crafting.CraftingUnitLogic;
import de.fellurion.ae2.core.crafting.MolecularAssemblerLogic;
import de.fellurion.ae2.core.crafting.PatternDetailsHelper;
import de.fellurion.ae2.core.crafting.PatternProviderLogic;
import de.fellurion.ae2.core.crafting.PatternProviderTarget;
import de.fellurion.ae2.core.inv.SlotAccess;
import de.fellurion.ae2.core.inv.SlotInventories;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.tools.MemoryCardSettings;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.HytaleSlots;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.ui.ConfigHost;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Autocrafting-Geräte: Pattern Provider, Molecular Assembler, Crafting-CPU-Blöcke. */
public final class CraftingDevices {

    private CraftingDevices() {
    }

    /** Kinds, in die ein Pattern Provider nicht hineinschiebt (Netz-Infrastruktur). */
    private static final Set<String> NO_PUSH = Set.of("cable", "controller", "drive", "me_chest", "energy_cell",
            "dense_energy_cell", "creative_energy_cell", "pattern_provider", "interface", "terminal", "crafting_terminal",
            "pattern_encoding_terminal", "pattern_access_terminal", "crafting_unit", "crafting_accelerator",
            "crafting_storage", "crafting_monitor", "import_bus", "export_bus", "storage_bus", "quartz_glass");

    // ------------------------------------------------------------------ Pattern Provider

    /**
     * Pattern Provider (Block und Part): Patterns liegen als Items in seinem Container (9 Slots); er schiebt
     * die Zutaten in angrenzende Maschinen/Container bzw. Molecular Assembler.
     *
     * <p>Einstellungen wie in AE2 (PatternProviderScreen) auf {@code hytale.ui.DeviceConfigPage}, die sich öffnet,
     * wenn der Spieler den Block <b>geduckt</b> benutzt: Blockiermodus, Herstellung sperren, Anzeige im
     * Pattern-Zugriffsterminal und die Priorität. Sie stehen mit dem übrigen Zustand als JSON in der
     * Block-Komponente ({@code Data}), die Priorität zusätzlich in {@code Priority}.
     *
     * <p>Die Sperrmodi „bei Signal“, „ohne Signal“ und „bis zum Signalimpuls“ hören statt auf Redstone auf das
     * AE2-Signal ({@link de.fellurion.ae2.hytale.signal.Signals}): angesteuert ist der Provider, wenn ein Nachbarblock
     * sendet, etwa ein Level Emitter. „Bis das Ergebnis zurück ist“ braucht kein Signal.
     */
    public static final class PatternProvider implements Device, ConfigHost {
        /** Namen der Einstellungen wie in AE2 ({@code appeng.api.config.Settings}), für die Memory Card. */
        public static final String SETTING_BLOCKING_MODE = "blocking_mode";
        public static final String SETTING_PATTERN_ACCESS_TERMINAL = "pattern_access_terminal";
        public static final String SETTING_LOCK_CRAFTING_MODE = "lock_crafting_mode";

        private final PatternProviderLogic logic = new PatternProviderLogic();
        /** Stand von {@code Signals.version()}, zu dem das Signal zuletzt abgefragt wurde. */
        private long signalVersion = -1;
        private final Watchers watchers = new Watchers();
        @Nullable
        private ItemContainer container;
        private long patternsSnapshot = Long.MIN_VALUE;
        @Nullable
        private ItemStack[] lastStacks;
        @Nullable
        private AEItemKey[] lastKeys;
        private boolean saveRequested;
        private WorldNetworks nets;
        private WorldNetworks.Node node;

        public PatternProviderLogic logic() {
            return logic;
        }

        @Override
        public Object owner() {
            return logic;
        }

        @Override
        public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
            this.nets = nets;
            this.node = node;
            container = BlockAccess.container(nets.world(), node.x(), node.y(), node.z());
            if (container != null) {
                for (short s = 0; s < container.getCapacity(); s++) {
                    container.setSlotFilter(FilterActionType.ADD, s, (a, c, slot, stack) ->
                            stack == null || stack.isEmpty() || PatternDetailsHelper.isEncodedPattern(stack.getItemId()));
                }
            }
            if (node.comp.data() != null) {
                try {
                    logic.fromJson(node.comp.data());
                } catch (RuntimeException ignored) {
                    // kaputter Zustand
                }
            }
            logic.setPriority(node.comp.priority());
            logic.setChangeListener(() -> saveRequested = true);
            logic.setTargetSupplier(this::targets);
            // Sperrmodi mit Signal: das AE2-Signal eines Nachbarblocks (Level Emitter ...)
            de.fellurion.ae2.hytale.signal.Signals signals = de.fellurion.ae2.hytale.signal.Signals.of(nets.world());
            logic.setRedstoneSupplier(() -> signals.isPowered(node.x(), node.y(), node.z()));
            signalVersion = signals.version();
            logic.updateRedstoneState();
            syncPatterns();
        }

        @Override
        public void tick(WorldNetworks nets, WorldNetworks.Node node) {
            long version = de.fellurion.ae2.hytale.signal.Signals.of(nets.world()).version();
            if (version != signalVersion) {
                signalVersion = version;
                logic.updateRedstoneState();
            }
            syncPatterns();
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
            node.comp.setData(logic.toJson());
            node.markDirty();
            watchers.run(); // offene Seiten merken, dass es den Provider nicht mehr gibt, und schließen sich
            if (broken) {
                // Abgebaut: Was noch unterwegs war, fällt heraus (AE2: PatternProviderLogic.addDrops) – Zutaten, die
                // noch nicht ins Ziel gepasst haben, und Ergebnisse, die noch nicht im Netz sind. Die Patterns
                // selbst liegen im Container und fallen mit ihm.
                Drops.at(nets.world(), node.x(), node.y(), node.z(), pendingDrops());
            }
        }

        /** Sendeliste und Rückgabepuffer als Stapel (höchstens Stapelgröße je Stapel). */
        private List<ItemStack> pendingDrops() {
            List<GenericStack> pending = new ArrayList<>(logic.getSendList());
            pending.addAll(logic.getReturnInventory().getStacks());
            List<ItemStack> out = new ArrayList<>();
            for (GenericStack gs : pending) {
                if (!(gs.what() instanceof AEItemKey key)) {
                    continue;
                }
                try {
                    int max = ItemKeys.maxStack(key.itemId());
                    for (long left = gs.amount(); left > 0; left -= max) {
                        out.add(ItemKeys.toStack(key, (int) Math.min(left, max)));
                    }
                } catch (RuntimeException ignored) {
                    // Gegenstand lässt sich nicht als Hytale-Stapel anlegen (kaputte Metadaten)
                }
            }
            return out;
        }

        /**
         * Gedreht: Der Part schiebt nur nach vorn, und vorn steht jetzt ein anderer Block. Die Ziele liest
         * {@link #targets()} ohnehin bei jedem Auftrag neu; nur was von einem laufenden Auftrag noch nicht ins alte
         * Ziel gepasst hat, hinge sonst an diesem – es geht ans neue Ziel bzw. wartet, bis vorn eines steht.
         */
        @Override
        public void onRotated(WorldNetworks nets, WorldNetworks.Node node) {
            logic.onTargetsChanged();
        }

        // -------------------------------------------------------------- Konfigurationsseite (ConfigHost)

        /** Benutzen öffnet die Pattern-Plätze; die Einstellungen gibt es geduckt. */
        @Override
        public boolean opensOnCrouch() {
            return true;
        }

        /** AE2: die drei Knöpfe links im Fenster des Pattern Providers, in derselben Reihenfolge. */
        @Override
        public List<Setting> settings(Context ctx) {
            return List.of(
                    Setting.toggle(ctx, "ae2bus.setting.blocking.", logic.isBlocking(), logic::setBlocking),
                    Setting.cycle(ctx, "ae2bus.setting.lock.", logic.getLockCraftingMode(), LockCraftingMode.values(),
                            logic::setLockCraftingMode),
                    Setting.toggle(ctx, "ae2bus.setting.accessTerminal.", logic.isShownInAccessTerminal(),
                            logic::setShownInAccessTerminal));
        }

        /**
         * Einstellungen unter AE2s Namen, für die Memory Card (AE2: der Config-Manager von
         * {@code PatternProviderLogic}); Block und Kabel-Part teilen sie sich. Abweichung von AE2: Die Patterns
         * selbst kopiert die Karte nicht (AE2 kodiert sie aus leeren Patterns des Spielers nach), und eine
         * Schieberichtung, die AE2 beim Block mitkopiert, hat der Block hier nicht.
         */
        @Override
        public Map<String, String> exportSettings() {
            Map<String, String> out = new LinkedHashMap<>();
            out.put(SETTING_BLOCKING_MODE, MemoryCardSettings.yesNo(logic.isBlocking()));
            out.put(SETTING_PATTERN_ACCESS_TERMINAL, MemoryCardSettings.yesNo(logic.isShownInAccessTerminal()));
            out.put(SETTING_LOCK_CRAFTING_MODE, logic.getLockCraftingMode().name());
            return out;
        }

        @Override
        public boolean importSettings(Map<String, String> in) {
            boolean any = MemoryCardSettings.importYesNo(in, SETTING_BLOCKING_MODE, logic::setBlocking);
            any |= MemoryCardSettings.importYesNo(in, SETTING_PATTERN_ACCESS_TERMINAL, logic::setShownInAccessTerminal);
            any |= MemoryCardSettings.importEnum(in, SETTING_LOCK_CRAFTING_MODE, LockCraftingMode.class,
                    logic::setLockCraftingMode);
            return any;
        }

        /** „Online“ bzw. „Offline …“, dahinter eine wirksame Sperre (AE2: PatternProviderLockReason). */
        @Override
        public String info(Context ctx) {
            String state = ctx.tr(online(ctx.node()) ? "ae2bus.info.online" : "ae2bus.info.offline");
            LockCraftingMode reason = logic.getCraftingLockedReason();
            if (reason == LockCraftingMode.NONE) {
                return state;
            }
            GenericStack waiting = logic.getUnlockStack();
            if (reason == LockCraftingMode.LOCK_UNTIL_RESULT && waiting != null && waiting.what() instanceof AEItemKey key) {
                return state + " · " + ctx.tr("ae2bus.info.lockedResult", "amount", Long.toString(waiting.amount()),
                        "item", TerminalTexts.itemName(ctx.lang(), key.itemId()));
            }
            return state + " · " + ctx.tr("ae2bus.info.locked");
        }

        /** Sperrmodi, die ein Signal brauchen: Hinweis, woher es kommt. */
        @Override
        @Nullable
        public String note(Context ctx) {
            return switch (logic.getLockCraftingMode()) {
                case LOCK_UNTIL_PULSE, LOCK_WHILE_HIGH, LOCK_WHILE_LOW -> ctx.tr("ae2bus.hint.lockSignal");
                default -> null;
            };
        }

        @Override
        public boolean hasPriority() {
            return true;
        }

        @Override
        public int priority() {
            return logic.getPriority();
        }

        /** Priorität setzen (Kern und Block-Komponente); AE2: Patterns des Providers mit der höchsten zuerst. */
        @Override
        public void setPriority(WorldNetworks.Node node, int priority) {
            logic.setPriority(priority);
            node.comp.setPriority(priority);
            node.markDirty();
        }

        @Override
        public Watchers watchers() {
            return watchers;
        }

        /** Patterns aus dem Container übernehmen, wenn sich etwas geändert hat. */
        private void syncPatterns() {
            if (container == null) {
                return;
            }
            int cap = container.getCapacity();
            if (lastStacks == null || lastStacks.length != cap) {
                lastStacks = new ItemStack[cap];
                lastKeys = new AEItemKey[cap];
                patternsSnapshot = Long.MIN_VALUE;
            }
            // ItemStacks sind unveränderlich: gleiches Objekt im Slot = nichts geändert
            boolean same = true;
            for (short s = 0; s < cap; s++) {
                ItemStack st = container.getItemStack(s);
                if (st != lastStacks[s]) {
                    lastStacks[s] = st;
                    lastKeys[s] = ItemKeys.of(st);
                    same = false;
                }
            }
            if (same && patternsSnapshot != Long.MIN_VALUE) {
                return;
            }
            long snap = 1;
            List<AEItemKey> patterns = new ArrayList<>();
            for (short s = 0; s < cap; s++) {
                AEItemKey key = lastKeys[s];
                snap = snap * 31 + (key == null ? 0 : key.hashCode());
                if (key != null && PatternDetailsHelper.isEncodedPattern(key.itemId())) {
                    patterns.add(key);
                }
            }
            if (snap != patternsSnapshot) {
                patternsSnapshot = snap;
                logic.setPatterns(patterns);
            }
        }

        /** Ziele: Nachbarn in allen Richtungen (Block) bzw. nur vorne (Part). */
        private List<PatternProviderTarget> targets() {
            List<PatternProviderTarget> out = new ArrayList<>();
            if (nets == null || node == null) {
                return out;
            }
            boolean part = node.item() != null && node.item().part();
            for (Dir d : Dir.values()) {
                if (part && d != node.facing()) {
                    continue;
                }
                int x = node.x() + d.dx, y = node.y() + d.dy, z = node.z() + d.dz;
                WorldNetworks.Node n = nets.node(x, y, z);
                if (n != null && n.device() instanceof MolecularAssembler ma) {
                    out.add(ma.logic());
                    continue;
                }
                if (n != null && NO_PUSH.contains(n.kind())) {
                    continue;
                }
                // Item-P2P-Tunnel, der dem Provider zugewandt ist: die Zutaten gehen ans andere Tunnelende
                var tunnel = de.fellurion.ae2.hytale.p2p.ItemTunnel.facing(node, n);
                if (tunnel != null) {
                    out.add(PatternProviderTarget.of(tunnel,
                            de.fellurion.ae2.core.storage.IActionSource.ofMachine(logic)));
                    continue;
                }
                // Maschinen mit eigenen Regeln für Automation (Inscriber) nehmen die Zutaten über ihre Sicht an
                SlotAccess view = n != null && n.device() instanceof AutomationAccess a ? a.automationSlots(d.opposite())
                        : null;
                if (view != null) {
                    out.add(PatternProviderTarget.of(view));
                    continue;
                }
                ItemContainer c = BlockAccess.container(nets.world(), x, y, z);
                if (c != null) {
                    out.add(PatternProviderTarget.of(new HytaleSlots(c)));
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ Molecular Assembler

    /**
     * Molecular Assembler: führt Crafting-Patterns aus (10 Ticks je Rezept ohne Beschleunigungskarten).
     *
     * <p>Bis zu 5 Beschleunigungskarten (AE2: MolecularAssemblerMenu) baut der Spieler auf
     * {@code hytale.ui.DeviceConfigPage} ein, die sich öffnet, wenn er den Block <b>geduckt</b> benutzt. Die Karten
     * stehen in der Block-Komponente unter {@code Config.upgrades} – getrennt vom laufenden Auftrag in {@code Data},
     * der nur über das Entladen des Chunks hinweg gilt. Abgebaut lässt der Block die Karten fallen.
     */
    public static final class MolecularAssembler implements Device, ConfigHost {
        /** Schlüssel in {@code AE2BlockComponent.config()}: Item-Ids der Karten je Platz, durch Komma getrennt. */
        static final String UPGRADES_KEY = "upgrades";

        private final MolecularAssemblerLogic logic = new MolecularAssemblerLogic();
        private final Watchers watchers = new Watchers();

        public MolecularAssemblerLogic logic() {
            return logic;
        }

        @Override
        public Object owner() {
            return logic;
        }

        @Override
        public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
            if (node.comp.data() != null) {
                try {
                    logic.loadState(node.comp.data());
                } catch (RuntimeException ignored) {
                    // kaputter Zustand
                }
                node.comp.setData(null);
                node.markDirty();
            }
            String cards = node.comp.config().get(UPGRADES_KEY);
            if (cards != null) {
                logic.getUpgrades().fromItemIds(Arrays.asList(cards.split(",", -1)));
            }
        }

        @Override
        public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
            watchers.tick(nets.tickCount());
        }

        // -------------------------------------------------------------- Konfigurationsseite (ConfigHost)

        /** Benutzen öffnet das Slot-Fenster; die Karten gibt es geduckt. */
        @Override
        public boolean opensOnCrouch() {
            return true;
        }

        @Override
        public UpgradeInventory upgradeCards() {
            return logic.getUpgrades();
        }

        @Override
        public Watchers watchers() {
            return watchers;
        }

        /** Schreibt die eingebauten Karten in die Block-Komponente. */
        @Override
        public void configChanged(WorldNetworks.Node node) {
            String cards = cards(logic.getUpgrades());
            String saved = node.comp.config().get(UPGRADES_KEY);
            if (cards.isEmpty() ? saved != null : !cards.equals(saved)) {
                if (cards.isEmpty()) {
                    node.comp.config().remove(UPGRADES_KEY);
                } else {
                    node.comp.config().put(UPGRADES_KEY, cards);
                }
                node.markDirty();
            }
        }

        /** „AE2_Speed_Card,,AE2_Speed_Card,,“ – je Platz die Item-Id oder nichts; leer, wenn keine Karte eingebaut ist. */
        static String cards(UpgradeInventory upgrades) {
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            for (int i = 0; i < upgrades.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                if (upgrades.get(i) != null) {
                    sb.append(upgrades.get(i).itemId);
                    any = true;
                }
            }
            return any ? sb.toString() : "";
        }

        @Override
        public void tick(WorldNetworks nets, WorldNetworks.Node node) {
            // Nach dem Neuladen: Ergebnis wieder an einen angrenzenden Pattern Provider liefern
            if (logic.needsReturnTarget() && nets.tickCount() % 20 == 0) {
                for (Dir d : Dir.values()) {
                    WorldNetworks.Node n = nets.node(node.x() + d.dx, node.y() + d.dy, node.z() + d.dz);
                    if (n != null && n.device() instanceof PatternProvider pp) {
                        logic.setReturnTarget(pp.logic());
                        break;
                    }
                }
            }
        }

        @Override
        public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
            watchers.run(); // offene Seiten merken, dass es den Assembler nicht mehr gibt, und schließen sich
            if (!broken) {
                // Chunk wird inaktiv: laufenden Auftrag speichern (AE2 speichert ihn im Block-Entity)
                node.comp.setData(logic.saveState());
                node.markDirty();
                return;
            }
            // Abgebaut: Karten fallen heraus (AE2: addAdditionalDrops) ...
            Drops.upgrades(nets.world(), node.x(), node.y(), node.z(), logic.getUpgrades());
            // ... gehaltene Zutaten/Ergebnisse in den eigenen Container (fallen mit heraus)
            List<GenericStack> contents = logic.getContents();
            ItemContainer c = BlockAccess.container(nets.world(), node.x(), node.y(), node.z());
            if (c == null || contents.isEmpty()) {
                return;
            }
            HytaleSlots slots = new HytaleSlots(c);
            for (GenericStack gs : contents) {
                if (gs.what() instanceof AEItemKey key) {
                    SlotInventories.insert(slots, key, gs.amount(), false);
                }
            }
        }

        @Override
        public String visualState(WorldNetworks nets, WorldNetworks.Node node) {
            return logic.isWorking() ? "Working" : "default";
        }
    }

    // ------------------------------------------------------------------ Crafting-CPU

    /** Crafting Unit / Storage / Accelerator / Monitor: bilden zusammen (Quader) eine Crafting-CPU. */
    public static final class CraftingUnit implements Device {
        private final CraftingUnitLogic logic;
        private boolean saveRequested;

        public CraftingUnit(String kind, int bytes) {
            this.logic = CraftingUnitLogic.forKind(kind, bytes);
        }

        public CraftingUnitLogic logic() {
            return logic;
        }

        @Override
        public Object owner() {
            return logic;
        }

        @Override
        public void onAttached(WorldNetworks nets, WorldNetworks.Node node) {
            if (node.comp.data() != null) {
                try {
                    logic.setSavedClusterState(node.comp.data());
                } catch (RuntimeException ignored) {
                    // kaputter Zustand
                }
            }
            logic.setChangeListener(() -> saveRequested = true);
        }

        @Override
        public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
            if (saveRequested && nets.tickCount() % 20 == 0) {
                saveRequested = false;
                String state = logic.getClusterStateToSave();
                if (!Objects.equals(state, node.comp.data())) {
                    node.comp.setData(state);
                    node.markDirty();
                }
            }
        }

        @Override
        public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
            if (broken) {
                logic.onBlockBroken(); // Job abbrechen, Items ins Netz zurück (AE2)
            } else {
                node.comp.setData(logic.getClusterStateToSave());
                node.markDirty();
            }
        }
    }
}
