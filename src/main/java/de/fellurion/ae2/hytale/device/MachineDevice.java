/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.blockentity.misc.InscriberBlockEntity, ChargerBlockEntity, VibrationChamberBlockEntity,
 * CondenserBlockEntity).
 */
package de.fellurion.ae2.hytale.device;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.machines.ChargerLogic;
import de.fellurion.ae2.core.machines.ChargerRecipes;
import de.fellurion.ae2.core.machines.CondenserLogic;
import de.fellurion.ae2.core.machines.IChargeableItem;
import de.fellurion.ae2.core.machines.ICrankable;
import de.fellurion.ae2.core.machines.InscriberLogic;
import de.fellurion.ae2.core.machines.InscriberRecipes;
import de.fellurion.ae2.core.machines.VibrationChamberLogic;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.HytaleSlots;
import de.fellurion.ae2.hytale.ItemCatalog;
import de.fellurion.ae2.hytale.WorldNetworks;

import javax.annotation.Nullable;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Maschinen mit Slots (Inscriber, Charger, Vibration Chamber, Condenser). Die Slots sind der
 * Hytale-Container des Blocks (öffnet sich beim Benutzen), die Logik kommt aus dem Kern.
 */
public final class MachineDevice implements Device {

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

    public MachineDevice(String kind) {
        this.kind = kind;
    }

    @Override
    public Object owner() {
        return machine == null ? null : machine.owner();
    }

    /** Wird vor {@code addNode} gebraucht, damit der Kern den Owner kennt. */
    public MachineDevice prepare(WorldNetworks nets, WorldNetworks.Node node) {
        container = BlockAccess.container(nets.world(), node.x(), node.y(), node.z());
        if (container == null) {
            return this;
        }
        HytaleSlots slots = new HytaleSlots(container);
        machine = switch (kind) {
            case "inscriber" -> wrap(new InscriberLogic(slots, inscriberRecipes));
            case "charger" -> wrap(new ChargerLogic(slots, chargerRecipes, IChargeableItem.NONE, new Random()));
            case "vibration_chamber" -> wrap(new VibrationChamberLogic(slots, MachineDevice::burnTicks, s -> null));
            case "condenser" -> wrap(new CondenserLogic(slots));
            default -> null;
        };
        if (machine != null && node.comp.data() != null) {
            try {
                machine.load(node.comp.data());
            } catch (RuntimeException ignored) {
                // alter/kaputter Zustand: neu starten
            }
        }
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
            String json = machine.save();
            if (json != null && !json.equals(node.comp.data())) {
                node.comp.setData(json);
                node.markDirty();
            }
        }
    }

    @Override
    public void onRemoved(WorldNetworks nets, WorldNetworks.Node node, boolean broken) {
        if (machine != null && !broken) {
            node.comp.setData(machine.save());
            node.markDirty();
        }
    }

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
        l.setStateListener(() -> stateChanged = true);
        return machine(l, l::onInventoryChanged, l::isWorking, l::saveState, l::loadState, null);
    }

    private Machine wrap(ChargerLogic l) {
        l.setStateListener(() -> stateChanged = true);
        return machine(l, l::onInventoryChanged, l::isWorking, l::saveState, l::loadState, null);
    }

    private Machine wrap(VibrationChamberLogic l) {
        l.setStateListener(() -> stateChanged = true);
        return machine(l, l::onInventoryChanged, l::isOn, l::saveState, l::loadState, null);
    }

    private Machine wrap(CondenserLogic l) {
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
