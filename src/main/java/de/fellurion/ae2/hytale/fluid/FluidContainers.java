/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.api.behaviors.ContainerItemStrategies / FluidContainerItemStrategy).
 */
package de.fellurion.ae2.hytale.fluid;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.hytale.ItemKeys;

import javax.annotation.Nullable;

/**
 * Behälter-Items für Flüssigkeiten (AE2: {@code ContainerItemStrategy}). Das Spiel kennt genau einen: den Holzeimer
 * {@code Container_Bucket}, der als Zustand {@code Filled_Water} einen Quell-Block Wasser (1000 mB) fasst. Weitere
 * lassen sich in {@link FluidConfig} eintragen.
 *
 * <p>Ein Behälter ist immer ganz leer oder ganz voll; umgefüllt wird sein ganzer Inhalt oder nichts. Nur Items ohne
 * eigene Metadaten gelten als Behälter – ein umbenannter oder sonstwie besonderer Eimer bleibt ein gewöhnliches Item.
 */
public final class FluidContainers {

    private FluidContainers() {
    }

    /**
     * Inhalt eines gefüllten Behälters.
     *
     * @param spec  der Behälter
     * @param fluid die Flüssigkeit darin
     */
    public record Content(FluidConfig.ContainerSpec spec, AEFluidKey fluid) {

        /** Inhalt in mB. */
        public long amount() {
            return spec.amount();
        }
    }

    /** Item-Id des gefüllten Behälters, oder null, wenn es das Item oder seinen Zustand nicht gibt. */
    @Nullable
    public static String filledItemId(FluidConfig.ContainerSpec spec) {
        try {
            Item empty = Item.getAssetMap().getAsset(spec.emptyItem());
            return empty == null ? null : empty.getItemIdForState(spec.filledState());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean plain(ItemStack stack) {
        AEItemKey key = ItemKeys.of(stack);
        return key != null && key.nbt() == null;
    }

    /**
     * Was in einem gefüllten Behälter steckt; null, wenn {@code stack} kein gefüllter Behälter ist oder seine
     * Flüssigkeit nicht lagerbar ist.
     */
    @Nullable
    public static Content contentOf(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return null;
        }
        for (FluidConfig.ContainerSpec spec : FluidConfig.containers()) {
            if (stack.getItemId().equals(filledItemId(spec))) {
                AEFluidKey key = FluidTypes.key(spec.fluid());
                return key != null && plain(stack) ? new Content(spec, key) : null;
            }
        }
        return null;
    }

    /** Der Behälter, als der das leere Item {@code stack} die Flüssigkeit {@code fluidId} aufnimmt, oder null. */
    @Nullable
    public static FluidConfig.ContainerSpec emptyFor(@Nullable ItemStack stack, String fluidId) {
        if (ItemStack.isEmpty(stack)) {
            return null;
        }
        for (FluidConfig.ContainerSpec spec : FluidConfig.containers()) {
            if (spec.fluid().equals(fluidId) && spec.emptyItem().equals(stack.getItemId())) {
                return filledItemId(spec) != null && FluidTypes.isStorable(fluidId) && plain(stack) ? spec : null;
            }
        }
        return null;
    }

    /** Der erste Behälter, den es für diese Flüssigkeit gibt, oder null (dann geht sie nur über Tanks und Planes). */
    @Nullable
    public static FluidConfig.ContainerSpec forFluid(String fluidId) {
        for (FluidConfig.ContainerSpec spec : FluidConfig.containers()) {
            if (spec.fluid().equals(fluidId) && filledItemId(spec) != null && FluidTypes.isStorable(fluidId)) {
                return spec;
            }
        }
        return null;
    }

    /** Ist das Item ein leerer oder gefüllter Behälter (für irgendeine Flüssigkeit)? */
    public static boolean isContainerItem(@Nullable String itemId) {
        if (itemId == null) {
            return false;
        }
        for (FluidConfig.ContainerSpec spec : FluidConfig.containers()) {
            if (itemId.equals(spec.emptyItem()) || itemId.equals(filledItemId(spec))) {
                return true;
            }
        }
        return false;
    }

    /** Ein gefüllter Behälter. */
    public static ItemStack filled(FluidConfig.ContainerSpec spec) {
        String id = filledItemId(spec);
        if (id == null) {
            throw new IllegalStateException("AE2: no filled item for " + spec.emptyItem() + "/" + spec.filledState());
        }
        return new ItemStack(id, 1);
    }

    /** Ein leerer Behälter. */
    public static ItemStack empty(FluidConfig.ContainerSpec spec) {
        return new ItemStack(spec.emptyItem(), 1);
    }
}
