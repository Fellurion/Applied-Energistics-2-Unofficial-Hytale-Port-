/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import de.fellurion.ae2.core.storage.IActionSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper functions used by the CPU and the calculation. Port of {@code appeng.crafting.execution.CraftingCpuHelper}.
 */
public final class CraftingCpuHelper {
    private CraftingCpuHelper() {
    }

    /**
     * Tries to extract all ingredients defined by the plan into the CPU inventory.
     *
     * @return null on success, otherwise what is missing (everything extracted is re-inserted)
     */
    public static GenericStack tryExtractInitialItems(CraftingPlan plan, Grid grid, ListCraftingInventory cpuInventory,
            IActionSource src) {
        var storage = grid.getStorageService().getInventory();
        for (var entry : plan.usedItems()) {
            var what = entry.getKey();
            var toExtract = entry.getLongValue();
            var extracted = storage.extract(what, toExtract, Actionable.MODULATE, src);
            cpuInventory.insert(what, extracted, Actionable.MODULATE);
            if (extracted < toExtract) {
                // Failed to extract everything, reinject and hope for the best.
                for (var stored : cpuInventory.list) {
                    storage.insert(stored.getKey(), stored.getLongValue(), Actionable.MODULATE, src);
                }
                cpuInventory.clear();
                return new GenericStack(what, toExtract - extracted);
            }
        }
        return null;
    }

    /** Power needed to push a pattern: sum of amount / amountPerOperation over all inputs. */
    public static double calculatePatternPower(KeyCounter[] craftingContainer) {
        double sum = 0;
        for (var itemHolder : craftingContainer) {
            for (var anInput : itemHolder) {
                sum += ((double) anInput.getLongValue()) / ((double) anInput.getKey().getAmountPerOperation());
            }
        }
        return sum;
    }

    /**
     * Extracts the inputs of one pattern execution from the source inventory.
     *
     * @return the inputs per pattern input, or null (nothing extracted) if not all inputs are available
     */
    public static KeyCounter[] extractPatternInputs(IPatternDetails details, ICraftingInventory sourceInv,
            KeyCounter expectedOutputs, KeyCounter expectedContainerItems) {
        var inputs = details.getInputs();
        KeyCounter[] inputHolder = new KeyCounter[inputs.length];
        boolean found = true;
        for (int x = 0; x < inputs.length; x++) {
            var list = inputHolder[x] = new KeyCounter();
            long remainingMultiplier = inputs[x].getMultiplier();
            for (var template : getValidItemTemplates(sourceInv, inputs[x])) {
                long extracted = extractTemplates(sourceInv, template, remainingMultiplier);
                list.add(template.key(), extracted * template.amount());
                var containerItem = inputs[x].getRemainingKey(template.key());
                if (containerItem != null) {
                    expectedContainerItems.add(containerItem, extracted);
                }
                remainingMultiplier -= extracted;
                if (remainingMultiplier == 0) {
                    break;
                }
            }
            if (remainingMultiplier > 0) {
                found = false;
                break;
            }
        }
        if (!found) {
            reinjectPatternInputs(sourceInv, inputHolder);
            return null;
        }
        for (var output : details.getOutputs()) {
            expectedOutputs.add(output.what(), output.amount());
        }
        return inputHolder;
    }

    public static void reinjectPatternInputs(ICraftingInventory sourceInv, KeyCounter[] inputHolder) {
        for (var list : inputHolder) {
            if (list != null) {
                for (var entry : list) {
                    sourceInv.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
                }
            }
        }
    }

    /** All available templates that count as "1" ingredient for the given pattern input. */
    public static List<InputTemplate> getValidItemTemplates(ICraftingInventory inv, IPatternDetails.IInput input) {
        var possibleInputs = input.getPossibleInputs();
        var substitutes = new ArrayList<InputTemplate>(possibleInputs.length);
        for (var stack : possibleInputs) {
            for (var fuzz : inv.findFuzzyTemplates(stack.what())) {
                substitutes.add(new InputTemplate(fuzz, stack.amount()));
            }
        }
        substitutes.removeIf(t -> !input.isValid(t.key()));
        return substitutes;
    }

    /** Extracts a whole number of templates, and returns how many were extracted. */
    public static long extractTemplates(ICraftingInventory inv, InputTemplate template, long multiplier) {
        long maxTotal = template.amount() * multiplier;
        var extracted = inv.extract(template.key(), maxTotal, Actionable.SIMULATE);
        if (extracted == 0) {
            return 0;
        }
        multiplier = extracted / template.amount();
        maxTotal = template.amount() * multiplier;
        if (maxTotal == 0) {
            return 0;
        }
        extracted = inv.extract(template.key(), maxTotal, Actionable.MODULATE);
        if (extracted == 0 || extracted != maxTotal) {
            throw new IllegalStateException("Failed to correctly extract whole number. Invalid simulation!");
        }
        return multiplier;
    }
}
