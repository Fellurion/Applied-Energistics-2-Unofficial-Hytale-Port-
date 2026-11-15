/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale.device;

import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import de.fellurion.ae2.core.cells.StorageCells;
import de.fellurion.ae2.core.devices.CellHostLogic;
import de.fellurion.ae2.core.energy.CreativeEnergyCellLogic;
import de.fellurion.ae2.core.energy.EnergyCellLogic;
import de.fellurion.ae2.core.machines.CrystalResonanceGeneratorLogic;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Erzeugt je {@code Kind} den passenden Geräte-Adapter. */
public final class Devices {

    /** Erzeugt das Gerät eines Blocks; null = dieser Block bekommt kein Gerät. */
    @FunctionalInterface
    public interface Factory {
        @Nullable
        Device create(WorldNetworks nets, WorldNetworks.Node node);
    }

    private static final Map<String, Factory> FACTORIES = new ConcurrentHashMap<>();

    private Devices() {
    }

    /**
     * Meldet das Gerät einer Block-Art an (einmal beim Plugin-Start, aus dem {@code setup} des jeweiligen Bereichs).
     * Angemeldete Arten haben Vorrang vor den fest eingebauten.
     */
    public static void register(String kind, Factory factory) {
        if (FACTORIES.putIfAbsent(kind, factory) != null) {
            throw new IllegalStateException("AE2: device for kind '" + kind + "' registered twice");
        }
    }

    /** Lädt Rezeptdaten für Maschinen (einmal beim Plugin-Start). */
    public static void init() {
        MachineDevice.loadRecipes();
    }

    @Nullable
    public static Device create(WorldNetworks nets, WorldNetworks.Node node) {
        String kind = node.kind();
        Factory registered = FACTORIES.get(kind);
        if (registered != null) {
            return registered.create(nets, node);
        }
        return switch (kind) {
            case "drive" -> new CellHostDevice(CellHostLogic.drive());
            case "me_chest" -> new CellHostDevice(CellHostLogic.meChest());
            case "energy_cell" -> new EnergyCellDevice(EnergyCellLogic.energyCell());
            case "dense_energy_cell" -> new EnergyCellDevice(EnergyCellLogic.denseEnergyCell());
            case "creative_energy_cell" -> owner(new CreativeEnergyCellLogic());
            case "crystal_resonance_generator" -> owner(new CrystalResonanceGeneratorLogic());
            case "inscriber", "charger", "vibration_chamber", "condenser" -> new MachineDevice(kind).prepare(nets, node);
            case "import_bus", "export_bus", "storage_bus" -> new BusDevice(kind);
            case "interface" -> new InterfaceDevice(node.item() != null && node.item().part());
            case GlassDevice.KIND -> new GlassDevice();
            case "cell_workbench" -> cellsOnly(nets, node);
            case "crank" -> new CrankDevice();
            case "pattern_provider" -> new CraftingDevices.PatternProvider();
            case "molecular_assembler" -> new CraftingDevices.MolecularAssembler();
            case "crafting_unit", "crafting_accelerator", "crafting_storage", "crafting_monitor" ->
                    new CraftingDevices.CraftingUnit(kind, node.comp.bytes());
            case "budding_quartz" -> new GrowthDevices.Budding(node.comp.grade());
            case "growth_accelerator" -> new GrowthDevices.GrowthAccelerator();
            default -> null;
        };
    }

    /** Block, in dessen Slot nur Speicherzellen passen (Zellen-Werkbank). */
    private static Device cellsOnly(WorldNetworks nets, WorldNetworks.Node node) {
        return new Device() {
            @Override
            public void onAttached(WorldNetworks n, WorldNetworks.Node self) {
                var c = BlockAccess.container(n.world(), self.x(), self.y(), self.z());
                if (c != null) {
                    for (short s = 0; s < c.getCapacity(); s++) {
                        c.setSlotFilter(FilterActionType.ADD, s, (action, container, slot, stack) ->
                                stack == null || stack.isEmpty() || StorageCells.isCellItem(stack.getItemId()));
                    }
                }
            }
        };
    }

    /** Gerät ohne eigene Hytale-Logik, nur mit Kern-Owner. */
    private static Device owner(Object owner) {
        return new Device() {
            @Override
            public Object owner() {
                return owner;
            }
        };
    }
}
