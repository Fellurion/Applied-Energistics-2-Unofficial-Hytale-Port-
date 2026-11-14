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
import de.fellurion.ae2.core.util.MiniJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A crafting-table pattern (3×3 grid, one ingredient per slot, single output). Port of
 * {@code appeng.crafting.pattern.AECraftingPattern}.
 * <p>
 * Encoding: definition key {@value #ITEM_ID} with metadata
 * {@code {"in":[key|null x9],"out":stack,"sub":bool,"alt":[[key,...]|null x9]}}. Deviations (the core does not know
 * Hytale's recipes/tags): the encoder must provide the substitute candidates per slot ({@code alt}) instead of AE2
 * reading them from the recipe ingredient, and container remainders (AE2: {@code getRemainingItems}) come from the
 * global {@link #setRemainderResolver} hook (default: no remainders). Fluid substitution is not supported.
 * <p>
 * Like AE2, crafting patterns cannot be pushed to generic inventories: they need a crafting machine (see
 * {@link MolecularAssemblerLogic}).
 */
public final class CraftingPattern implements IPatternDetails {
    public static final String ITEM_ID = "AE2_Crafting_Pattern";

    private static volatile Function<AEItemKey, AEKey> remainderResolver = key -> null;

    private final AEItemKey definition;
    private final List<AEItemKey> sparseInputs;
    private final List<List<AEItemKey>> substitutes;
    private final boolean canSubstitute;
    private final GenericStack output;
    private final Input[] inputs;

    public CraftingPattern(AEItemKey definition) {
        this.definition = Objects.requireNonNull(definition);
        if (!ITEM_ID.equals(definition.itemId()) || definition.nbt() == null) {
            throw new IllegalArgumentException("Given item does not encode a crafting pattern: " + definition);
        }
        var json = MiniJson.parseObject(definition.nbt());
        var in = new ArrayList<AEItemKey>();
        for (var o : MiniJson.getList(json, "in")) {
            in.add(o instanceof Map<?, ?> m && AEKey.fromJsonMap(m) instanceof AEItemKey k ? k : null);
        }
        this.sparseInputs = Collections.unmodifiableList(in);
        var alt = new ArrayList<List<AEItemKey>>();
        var altJson = MiniJson.getList(json, "alt");
        for (int i = 0; i < sparseInputs.size(); i++) {
            var list = new ArrayList<AEItemKey>();
            if (i < altJson.size() && altJson.get(i) instanceof List<?> l) {
                for (var o : l) {
                    if (o instanceof Map<?, ?> m && AEKey.fromJsonMap(m) instanceof AEItemKey k) {
                        list.add(k);
                    }
                }
            }
            alt.add(Collections.unmodifiableList(list));
        }
        this.substitutes = Collections.unmodifiableList(alt);
        this.canSubstitute = MiniJson.getBool(json, "sub", false);
        this.output = PatternHelper.stackFromJson(json.get("out"));
        if (output == null) {
            throw new IllegalArgumentException("Crafting pattern without output");
        }

        // Condense equal inputs, the input refers to the first slot with that key
        var sparseStacks = new ArrayList<GenericStack>();
        for (var k : sparseInputs) {
            sparseStacks.add(k == null ? null : new GenericStack(k, 1));
        }
        var condensed = PatternHelper.condenseStacks(sparseStacks);
        this.inputs = new Input[condensed.size()];
        for (int j = 0; j < condensed.size(); j++) {
            var c = condensed.get(j);
            for (int i = 0; i < sparseInputs.size(); i++) {
                if (c.what().equals(sparseInputs.get(i))) {
                    inputs[j] = new Input(i, c);
                    break;
                }
            }
        }
    }

    /**
     * Encodes a crafting pattern.
     *
     * @param grid          the 9 (or fewer) slots, row major, nulls for empty slots
     * @param output        the crafting result
     * @param canSubstitute allow substitutes
     * @param substitutes   per slot alternative items (may be null)
     */
    public static AEItemKey encode(List<AEItemKey> grid, GenericStack output, boolean canSubstitute,
            List<List<AEItemKey>> substitutes) {
        if (grid.stream().noneMatch(Objects::nonNull)) {
            throw new IllegalArgumentException("At least one input must be non-null.");
        }
        Objects.requireNonNull(output, "output");
        var map = new LinkedHashMap<String, Object>();
        var in = new ArrayList<Object>();
        for (var k : grid) {
            in.add(k == null ? null : k.toJsonMap());
        }
        map.put("in", in);
        map.put("out", PatternHelper.stackToJson(output));
        if (canSubstitute) {
            map.put("sub", true);
        }
        if (substitutes != null && substitutes.stream().anyMatch(l -> l != null && !l.isEmpty())) {
            var alt = new ArrayList<Object>();
            for (var l : substitutes) {
                if (l == null || l.isEmpty()) {
                    alt.add(null);
                } else {
                    var keys = new ArrayList<Object>();
                    for (var k : l) {
                        keys.add(k.toJsonMap());
                    }
                    alt.add(keys);
                }
            }
            map.put("alt", alt);
        }
        return new AEItemKey(ITEM_ID, MiniJson.write(map));
    }

    public static CraftingPattern of(List<AEItemKey> grid, GenericStack output) {
        return new CraftingPattern(encode(grid, output, false, null));
    }

    /** Hook for container remainders (item key → remaining key, or null). */
    public static void setRemainderResolver(Function<AEItemKey, AEKey> resolver) {
        remainderResolver = resolver == null ? key -> null : resolver;
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
        return List.of(output);
    }

    @Override
    public boolean supportsPushInputsToExternalInventory() {
        return false;
    }

    public List<AEItemKey> getSparseInputs() {
        return sparseInputs;
    }

    public boolean canSubstitute() {
        return canSubstitute;
    }

    /** The remainder of each input slot after crafting with the given grid content (for the assembler). */
    public AEKey getRemainder(AEItemKey usedItem) {
        return remainderResolver.apply(usedItem);
    }

    @Override
    public int hashCode() {
        return definition.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CraftingPattern p && p.definition.equals(definition);
    }

    @Override
    public String toString() {
        return "CraftingPattern" + sparseInputs + "->" + output;
    }

    private final class Input implements IInput {
        private final int slot;
        private final GenericStack[] possibleInputs;
        private final long multiplier;

        private Input(int slot, GenericStack condensedInput) {
            this.slot = slot;
            this.multiplier = condensedInput.amount();
            var primary = new GenericStack(sparseInputs.get(slot), 1);
            if (!canSubstitute) {
                this.possibleInputs = new GenericStack[] { primary };
            } else {
                var subs = substitutes.get(slot);
                this.possibleInputs = new GenericStack[subs.size() + 1];
                possibleInputs[0] = primary;
                for (int i = 0; i < subs.size(); i++) {
                    possibleInputs[i + 1] = new GenericStack(subs.get(i), 1);
                }
            }
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input) {
            if (input.matches(possibleInputs[0])) {
                return true;
            }
            return canSubstitute && input instanceof AEItemKey itemKey && substitutes.get(slot).contains(itemKey);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            if (template instanceof AEItemKey itemKey) {
                return remainderResolver.apply(itemKey);
            }
            return null;
        }
    }
}
