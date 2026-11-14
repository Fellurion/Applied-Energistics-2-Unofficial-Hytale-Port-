/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * A processing pattern: arbitrary inputs are pushed into a machine, arbitrary outputs come back. Port of
 * {@code appeng.crafting.pattern.AEProcessingPattern}.
 * <p>
 * Encoding: the definition is the item key {@value #ITEM_ID} with metadata
 * {@code {"in":[stack|null,...],"out":[stack|null,...]}} where a stack is {@code {"t":"i","id":"...","a":n}}. Inputs
 * equal keys are condensed into one {@link IInput} with the summed amount as multiplier; they are pushed in the
 * original (sparse) order.
 */
public final class ProcessingPattern implements IPatternDetails {
    public static final String ITEM_ID = "AE2_Processing_Pattern";
    public static final int MAX_INPUT_SLOTS = 9 * 9;
    public static final int MAX_OUTPUT_SLOTS = 3 * 9;

    private final AEItemKey definition;
    private final List<GenericStack> sparseInputs;
    private final List<GenericStack> sparseOutputs;
    private final Input[] inputs;
    private final List<GenericStack> condensedOutputs;

    public ProcessingPattern(AEItemKey definition) {
        this.definition = Objects.requireNonNull(definition);
        if (!ITEM_ID.equals(definition.itemId()) || definition.nbt() == null) {
            throw new IllegalArgumentException("Given item does not encode a processing pattern: " + definition);
        }
        var json = MiniJson.parseObject(definition.nbt());
        this.sparseInputs = Collections.unmodifiableList(PatternHelper.stacksFromJson(MiniJson.getList(json, "in")));
        this.sparseOutputs = Collections
                .unmodifiableList(PatternHelper.stacksFromJson(MiniJson.getList(json, "out")));
        if (sparseOutputs.isEmpty() || sparseOutputs.getFirst() == null) {
            throw new IllegalArgumentException("The first (primary) output must be non-null.");
        }
        var condensedInputs = PatternHelper.condenseStacks(sparseInputs);
        this.inputs = new Input[condensedInputs.size()];
        for (int i = 0; i < inputs.length; ++i) {
            inputs[i] = new Input(condensedInputs.get(i));
        }
        this.condensedOutputs = Collections.unmodifiableList(PatternHelper.condenseStacks(sparseOutputs));
    }

    /**
     * Encodes a processing pattern definition (AE2: {@code AEProcessingPattern.encode}).
     *
     * @param sparseInputs  inputs (nulls allowed, at least one non-null)
     * @param sparseOutputs outputs (first = primary output, must be non-null)
     */
    public static AEItemKey encode(List<GenericStack> sparseInputs, List<GenericStack> sparseOutputs) {
        if (sparseInputs.stream().noneMatch(Objects::nonNull)) {
            throw new IllegalArgumentException("At least one input must be non-null.");
        }
        Objects.requireNonNull(sparseOutputs.getFirst(), "The first (primary) output must be non-null.");
        var map = new LinkedHashMap<String, Object>();
        map.put("in", PatternHelper.stacksToJson(sparseInputs));
        map.put("out", PatternHelper.stacksToJson(sparseOutputs));
        return new AEItemKey(ITEM_ID, MiniJson.write(map));
    }

    /** Convenience: encodes and decodes. */
    public static ProcessingPattern of(List<GenericStack> sparseInputs, List<GenericStack> sparseOutputs) {
        return new ProcessingPattern(encode(sparseInputs, sparseOutputs));
    }

    @Override
    public AEItemKey getDefinition() {
        return definition;
    }

    @Override
    public IInput[] getInputs() {
        return inputs;
    }

    @Override
    public List<GenericStack> getOutputs() {
        return condensedOutputs;
    }

    public List<GenericStack> getSparseInputs() {
        return sparseInputs;
    }

    public List<GenericStack> getSparseOutputs() {
        return sparseOutputs;
    }

    @Override
    public void pushInputsToExternalInventory(KeyCounter[] inputHolder, PatternInputSink inputSink) {
        if (sparseInputs.size() == inputs.length) {
            IPatternDetails.super.pushInputsToExternalInventory(inputHolder, inputSink);
            return;
        }
        var allInputs = new KeyCounter();
        for (var counter : inputHolder) {
            allInputs.addAll(counter);
        }
        // Push according to sparse input order
        for (var sparseInput : sparseInputs) {
            if (sparseInput == null) {
                continue;
            }
            var key = sparseInput.what();
            var amount = sparseInput.amount();
            long available = allInputs.get(key);
            if (available < amount) {
                throw new IllegalStateException("Expected at least %d of %s when pushing pattern, but only %d available"
                        .formatted(amount, key, available));
            }
            inputSink.pushInput(key, amount);
            allInputs.remove(key, amount);
        }
    }

    @Override
    public int hashCode() {
        return definition.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ProcessingPattern p && p.definition.equals(definition);
    }

    @Override
    public String toString() {
        var ins = new ArrayList<String>();
        for (var i : inputs) {
            ins.add(i.getMultiplier() + "x" + i.getPossibleInputs()[0].what());
        }
        return "ProcessingPattern" + ins + "->" + condensedOutputs;
    }

    private static final class Input implements IInput {
        private final GenericStack[] template;
        private final long multiplier;

        private Input(GenericStack stack) {
            this.template = new GenericStack[] { new GenericStack(stack.what(), 1) };
            this.multiplier = stack.amount();
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return template;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input) {
            return input.matches(template[0]);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
