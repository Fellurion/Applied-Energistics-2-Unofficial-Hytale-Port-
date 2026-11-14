/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;

import java.util.List;

/**
 * Information about a pattern for use by the autocrafting system. Port of {@code appeng.api.crafting.IPatternDetails}.
 * <p>
 * <strong>Implementations must implement equals/hashCode</strong> (by {@link #getDefinition()}) so that crafting jobs
 * can be resumed after reloads. Deviation: the {@code Level} parameter of {@link IInput#isValid} is dropped.
 */
public interface IPatternDetails {
    /**
     * The key of the encoded pattern item (e.g. {@code AE2_Processing_Pattern} with the pattern JSON as metadata). It
     * contains all data needed to decode the pattern again, see {@link PatternDetailsHelper#decodePattern}.
     */
    AEItemKey getDefinition();

    /** The inputs of this pattern. <b>The returned array must never be edited</b>. */
    IInput[] getInputs();

    /** The primary output; the pattern is only used to craft this, other outputs are byproducts. */
    default GenericStack getPrimaryOutput() {
        return getOutputs().getFirst();
    }

    /** All outputs of this pattern. */
    List<GenericStack> getOutputs();

    /**
     * True if the inputs may be pushed into generic external inventories (processing patterns); false if a crafting
     * machine (molecular assembler) is needed (crafting patterns).
     */
    default boolean supportsPushInputsToExternalInventory() {
        return true;
    }

    /** Lets the pattern reorder its inputs when pushing to external inventories. */
    default void pushInputsToExternalInventory(KeyCounter[] inputHolder, PatternInputSink inputSink) {
        for (var inputList : inputHolder) {
            for (var input : inputList) {
                inputSink.pushInput(input.getKey(), input.getLongValue());
            }
        }
    }

    interface IInput {
        /**
         * Possible inputs: the first is the primary input, others are substitutes that are used if available but
         * not autocrafted. <b>Never edit the returned array.</b>
         */
        GenericStack[] getPossibleInputs();

        /** How many of the possible inputs are needed per craft. */
        long getMultiplier();

        /** Whether the key is a valid input for this slot. */
        boolean isValid(AEKey input);

        /** Container item left after crafting with {@code template} (e.g. an empty bucket), or null. */
        AEKey getRemainingKey(AEKey template);
    }

    @FunctionalInterface
    interface PatternInputSink {
        void pushInput(AEKey key, long amount);
    }
}
