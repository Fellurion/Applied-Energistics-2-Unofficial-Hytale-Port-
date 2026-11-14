/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.helpers.patternprovider.PatternProviderLogic, appeng.blockentity.crafting.*).
 */
package de.fellurion.ae2.hytale.device;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import de.fellurion.ae2.core.crafting.CraftingUnitLogic;
import de.fellurion.ae2.core.crafting.MolecularAssemblerLogic;
import de.fellurion.ae2.core.crafting.PatternDetailsHelper;
import de.fellurion.ae2.core.crafting.PatternProviderLogic;
import de.fellurion.ae2.core.crafting.PatternProviderTarget;
import de.fellurion.ae2.core.inv.SlotInventories;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.BlockAccess.Dir;
import de.fellurion.ae2.hytale.HytaleSlots;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
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
     */
    public static final class PatternProvider implements Device {
        private final PatternProviderLogic logic = new PatternProviderLogic();
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
            syncPatterns();
        }

        @Override
        public void tick(WorldNetworks nets, WorldNetworks.Node node) {
            syncPatterns();
        }

        @Override
        public void afterCoreTick(WorldNetworks nets, WorldNetworks.Node node) {
            if (saveRequested) {
                saveRequested = false;
                node.comp.setData(logic.toJson());
                node.markDirty();
            }
        }

        @Override
        public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
            node.comp.setData(logic.toJson());
            node.markDirty();
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
                ItemContainer c = BlockAccess.container(nets.world(), x, y, z);
                if (c != null) {
                    out.add(PatternProviderTarget.of(new HytaleSlots(c)));
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ Molecular Assembler

    /** Molecular Assembler: führt Crafting-Patterns aus (10 Ticks je Rezept ohne Beschleunigungskarten). */
    public static final class MolecularAssembler implements Device {
        private final MolecularAssemblerLogic logic = new MolecularAssemblerLogic();

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
            if (!broken) {
                // Chunk wird inaktiv: laufenden Auftrag speichern (AE2 speichert ihn im Block-Entity)
                node.comp.setData(logic.saveState());
                node.markDirty();
                return;
            }
            // Abgebaut: gehaltene Zutaten/Ergebnisse in den eigenen Container (fallen mit heraus)
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
