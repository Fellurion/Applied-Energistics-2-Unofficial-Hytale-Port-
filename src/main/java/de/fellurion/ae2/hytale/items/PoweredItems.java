/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.tools.powered.powersink.AEBasePoweredItem, appeng.api.implementations.items.IAEItemPowerStorage).
 */
package de.fellurion.ae2.hytale.items;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.machines.IChargeableItem;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Akku der AE2-Items (Wireless Terminal, tragbare Zellen, Entropy Manipulator, Matter Cannon, Charged Staff, Color
 * Applicator): Ladung in AE in den Item-Metadaten unter {@link #KEY}, Größe und Laderate wie in AE2.
 *
 * <p>Geladen wird im Charger ({@link #CHARGEABLE}). Energiekarten ({@link ItemUpgrades}) vergrößern den Akku
 * ({@code Grundwert · (1 + Faktor · Karten)}) und erhöhen die Laderate ({@code Grundwert · (1 + Karten)}).
 * {@link ItemStack} ist unveränderlich: alle Methoden liefern einen neuen Stack, den der Aufrufer an seinen Platz
 * zurückschreibt. Ein Item, das laufend Energie verbraucht (offenes Terminal), sollte nicht jeden Tick, sondern
 * höchstens etwa einmal je Sekunde zurückgeschrieben werden – jede Änderung geht als Inventar-Update an den Client.
 */
public final class PoweredItems {

    /** Metadaten-Schlüssel: gespeicherte Energie in AE. */
    public static final String KEY = "AE2Power";

    /**
     * @param capacity      Akkugröße in AE ohne Energiekarten
     * @param chargeRate    AE je Tick im Charger ohne Energiekarten
     * @param cardCapacity  Faktor je Energiekarte auf die Akkugröße (AE2: 8, Wireless Terminal 1, ohne Kartenplätze 0)
     * @param cardChargeRate true, wenn Energiekarten auch die Laderate erhöhen
     */
    public record Spec(double capacity, double chargeRate, int cardCapacity, boolean cardChargeRate) {
    }

    private static final Map<String, Spec> SPECS = new ConcurrentHashMap<>();

    static {
        // Werte aus appeng.core.AEConfig (battery) und den getChargeRate-Methoden der Items
        Spec terminal = new Spec(1_600_000, 800, 1, true);
        SPECS.put("AE2_Wireless_Terminal", terminal);
        SPECS.put("AE2_Wireless_Crafting_Terminal", terminal);
        SPECS.put("AE2_Entropy_Manipulator", new Spec(200_000, 800, 0, false));
        SPECS.put("AE2_Matter_Cannon", new Spec(200_000, 800, 8, true));
        SPECS.put("AE2_Charged_Staff", new Spec(8_000, 32, 0, false));
        SPECS.put("AE2_Color_Applicator", new Spec(20_000, 80, 8, true));
        Spec portable = new Spec(20_000, 80, 8, true);
        for (String size : new String[]{"1k", "4k", "16k", "64k", "256k"}) {
            SPECS.put("AE2_Portable_Item_Cell_" + size, portable);
            SPECS.put("AE2_Portable_Fluid_Cell_" + size, portable);
        }
        ItemTooltips.addSection(PoweredItems::tooltip);
    }

    private PoweredItems() {
    }

    public static boolean isPowered(String itemId) {
        return SPECS.containsKey(itemId);
    }

    @Nullable
    public static Spec spec(String itemId) {
        return SPECS.get(itemId);
    }

    /** Akkugröße dieses Items in AE (mit seinen Energiekarten); 0, wenn es keinen Akku hat. */
    public static double capacity(ItemStack stack) {
        Spec spec = ItemStack.isEmpty(stack) ? null : SPECS.get(stack.getItemId());
        if (spec == null) {
            return 0;
        }
        int cards = spec.cardCapacity() == 0 ? 0 : ItemUpgrades.count(stack, ItemUpgrades.ENERGY_CARD);
        return spec.capacity() * (1 + (double) spec.cardCapacity() * cards);
    }

    /** AE je Tick, die der Charger in dieses Item lädt. */
    public static double chargeRate(ItemStack stack) {
        Spec spec = ItemStack.isEmpty(stack) ? null : SPECS.get(stack.getItemId());
        if (spec == null) {
            return 0;
        }
        int cards = spec.cardChargeRate() ? ItemUpgrades.count(stack, ItemUpgrades.ENERGY_CARD) : 0;
        return spec.chargeRate() * (1 + cards);
    }

    /** Gespeicherte Energie in AE. */
    public static double stored(ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return 0;
        }
        Double v = stack.getFromMetadataOrNull(KEY, Codec.DOUBLE);
        return v == null ? 0 : Math.max(0, v);
    }

    /** Stack mit dieser Ladung (auf 0..Akkugröße begrenzt) und passendem Tooltip. */
    public static ItemStack withStored(ItemStack stack, double ae) {
        double value = Math.max(0, Math.min(capacity(stack), ae));
        ItemStack out = stack.withMetadata(KEY, Codec.DOUBLE, value <= 0 ? null : value);
        return ItemTooltips.refresh(out);
    }

    /** Reicht die Ladung für {@code ae}? */
    public static boolean has(ItemStack stack, double ae) {
        return stored(stack) >= ae - 1e-6;
    }

    /**
     * Verbraucht {@code ae} aus dem Akku.
     *
     * @return der Stack mit weniger Ladung, oder null, wenn die Ladung nicht reicht (dann ändert sich nichts)
     */
    @Nullable
    public static ItemStack use(ItemStack stack, double ae) {
        if (ae <= 0) {
            return stack;
        }
        double stored = stored(stack);
        if (stored < ae - 1e-6) {
            return null;
        }
        return withStored(stack, stored - ae);
    }

    /**
     * Lädt bis zu {@code ae} in den Akku.
     *
     * @param leftover nimmt in {@code [0]} auf, was nicht mehr hineinpasste (darf null sein)
     */
    public static ItemStack charge(ItemStack stack, double ae, @Nullable double[] leftover) {
        double stored = stored(stack);
        double room = Math.max(0, capacity(stack) - stored);
        double added = Math.max(0, Math.min(room, ae));
        if (leftover != null) {
            leftover[0] = Math.max(0, ae - added);
        }
        return added <= 0 ? stack : withStored(stack, stored + added);
    }

    /** Tooltip-Zeile „Gespeicherte Energie“, oder null für Items ohne Akku. */
    @Nullable
    private static Message tooltip(ItemStack stack) {
        if (!SPECS.containsKey(stack.getItemId())) {
            return null;
        }
        double max = capacity(stack);
        double stored = Math.min(max, stored(stack));
        return Message.translation("ae2ui.power.stored")
                .param("stored", TerminalTexts.formatAmount((long) stored))
                .param("max", TerminalTexts.formatAmount((long) max))
                .param("percent", Integer.toString(max <= 0 ? 0 : (int) Math.floor(stored * 100 / max)));
    }

    /** Anbindung an den Charger des Kerns. */
    public static final IChargeableItem CHARGEABLE = new IChargeableItem() {
        @Override
        public boolean isChargeable(SimpleStack stack) {
            return stack != null && SPECS.containsKey(stack.itemId());
        }

        @Override
        public double getAECurrentPower(SimpleStack stack) {
            return stored(toStack(stack));
        }

        @Override
        public double getAEMaxPower(SimpleStack stack) {
            return capacity(toStack(stack));
        }

        @Override
        public double getChargeRate(SimpleStack stack) {
            return chargeRate(toStack(stack));
        }

        @Override
        public Charged injectAEPower(SimpleStack stack, double amount) {
            double[] leftover = new double[1];
            ItemStack charged = charge(toStack(stack), amount, leftover);
            AEItemKey key = ItemKeys.of(charged);
            return new Charged(key == null ? stack : new SimpleStack(key.itemId(), key.nbt(), stack.count()), leftover[0]);
        }
    };

    private static ItemStack toStack(SimpleStack stack) {
        return ItemKeys.toStack(stack.key(), stack.count());
    }
}
