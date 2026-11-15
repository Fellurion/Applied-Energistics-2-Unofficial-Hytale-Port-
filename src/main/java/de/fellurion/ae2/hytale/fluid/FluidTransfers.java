/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.AEBaseMenu#handleFillingHeldItem / #handleEmptyHeldItem).
 */
package de.fellurion.ae2.hytale.fluid;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import de.fellurion.ae2.core.stacks.AEFluidKey;

import java.util.function.Consumer;

/**
 * Umfüllen zwischen einem Behälter-Item in einem Inventarfeld und einem Fluidlager (ME-Netz, Tank) – AE2s Regel für
 * den gehaltenen Behälter, übertragen auf ein Feld.
 *
 * <p><b>Nichts wird verdoppelt, nichts geht verloren:</b> Ein Behälter wird immer ganz umgefüllt. Erst wird geprüft,
 * ob das Lager die ganze Menge nimmt bzw. hergibt; dann verlässt genau ein Behälter das Feld, die Flüssigkeit wird
 * bewegt, und der Behälter kehrt im neuen Zustand zurück. Bewegt das Lager wider Erwarten weniger als geprüft, wird
 * die Teilmenge zurückgenommen und der Behälter unverändert zurückgelegt; eine Menge gibt es dabei nie doppelt.
 */
public final class FluidTransfers {

    private FluidTransfers() {
    }

    /** Ein Fluidlager: Mengen in mB, {@code simulate} prüft nur. */
    public interface Endpoint {

        /** @return die angenommene Menge */
        long insert(AEFluidKey what, long amount, boolean simulate);

        /** @return die entnommene Menge */
        long extract(AEFluidKey what, long amount, boolean simulate);
    }

    /** Ausgang eines Umfüllens. */
    public enum Result {
        /** umgefüllt */
        DONE,
        /** im Feld liegt kein passender Behälter */
        NO_CONTAINER,
        /** das Lager nimmt den Inhalt nicht (ganz) an */
        NO_ROOM,
        /** das Lager hat keinen ganzen Behälter voll */
        NOT_ENOUGH,
        /** das Feld hat sich geändert oder das Lager hat anders geantwortet als geprüft – nichts ist passiert */
        FAILED
    }

    /**
     * Leert den gefüllten Behälter in {@code slot} in das Lager.
     *
     * @param overflow bekommt den leeren Behälter, wenn er nicht zurück ins Feld passt (Stapel gefüllter Behälter)
     */
    public static Result empty(ItemContainer container, short slot, Endpoint target, Consumer<ItemStack> overflow) {
        if (slot < 0 || slot >= container.getCapacity()) {
            return Result.NO_CONTAINER;
        }
        ItemStack stack = container.getItemStack(slot);
        FluidContainers.Content content = FluidContainers.contentOf(stack);
        if (content == null) {
            return Result.NO_CONTAINER;
        }
        long amount = content.amount();
        if (target.insert(content.fluid(), amount, true) < amount) {
            return Result.NO_ROOM;
        }
        ItemStackSlotTransaction tx = container.removeItemStackFromSlot(slot, 1, true, false);
        if (!tx.succeeded()) {
            return Result.FAILED;
        }
        ItemStack taken = ItemStack.isEmpty(tx.getOutput()) ? stack.withQuantity(1) : tx.getOutput();
        long inserted = target.insert(content.fluid(), amount, false);
        if (inserted != amount) {
            // Teilmenge zurückholen; gelingt auch das nicht ganz, bleibt sie im Lager und der Behälter ist leer
            // (lieber ein Rest verloren als eine Menge doppelt)
            long back = inserted > 0 ? target.extract(content.fluid(), inserted, false) : 0;
            putBack(container, slot, back == inserted ? taken : FluidContainers.empty(content.spec()), overflow);
            return Result.FAILED;
        }
        putBack(container, slot, FluidContainers.empty(content.spec()), overflow);
        return Result.DONE;
    }

    /**
     * Füllt den leeren Behälter in {@code slot} mit {@code what} aus dem Lager.
     *
     * @param overflow bekommt den gefüllten Behälter, wenn er nicht zurück ins Feld passt (Stapel leerer Behälter)
     */
    public static Result fill(ItemContainer container, short slot, AEFluidKey what, Endpoint source,
                              Consumer<ItemStack> overflow) {
        if (slot < 0 || slot >= container.getCapacity()) {
            return Result.NO_CONTAINER;
        }
        ItemStack stack = container.getItemStack(slot);
        FluidConfig.ContainerSpec spec = FluidContainers.emptyFor(stack, what.fluidId());
        if (spec == null) {
            return Result.NO_CONTAINER;
        }
        long amount = spec.amount();
        if (source.extract(what, amount, true) < amount) {
            return Result.NOT_ENOUGH;
        }
        ItemStackSlotTransaction tx = container.removeItemStackFromSlot(slot, 1, true, false);
        if (!tx.succeeded()) {
            return Result.FAILED;
        }
        ItemStack taken = ItemStack.isEmpty(tx.getOutput()) ? stack.withQuantity(1) : tx.getOutput();
        long got = source.extract(what, amount, false);
        if (got != amount) {
            // Teilmenge zurückgeben; der Behälter bleibt in jedem Fall leer (nie eine Menge doppelt)
            if (got > 0) {
                source.insert(what, got, false);
            }
            putBack(container, slot, taken, overflow);
            return Result.FAILED;
        }
        putBack(container, slot, FluidContainers.filled(spec), overflow);
        return Result.DONE;
    }

    /** Zurück ins Feld; was dort nicht (mehr) hineinpasst, geht an {@code overflow}. */
    private static void putBack(ItemContainer container, short slot, ItemStack stack, Consumer<ItemStack> overflow) {
        ItemStack rest = stack;
        try {
            rest = container.addItemStackToSlot(slot, stack).getRemainder();
        } catch (RuntimeException e) {
            // Feld nimmt nichts an: alles an overflow
        }
        if (!ItemStack.isEmpty(rest)) {
            overflow.accept(rest);
        }
    }

    /** Erstes Feld von {@code container} mit einem leeren Behälter für {@code fluidId}, sonst -1. */
    public static short findEmptyContainer(ItemContainer container, String fluidId) {
        for (short s = 0; s < container.getCapacity(); s++) {
            if (FluidContainers.emptyFor(container.getItemStack(s), fluidId) != null) {
                return s;
            }
        }
        return -1;
    }
}
