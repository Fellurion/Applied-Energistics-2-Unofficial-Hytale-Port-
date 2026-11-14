/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.stacks.GenericStack;

/**
 * Result of submitting a crafting plan. Port of {@code appeng.crafting.execution.CraftingSubmitResult} (+
 * {@code ICraftingSubmitResult}).
 *
 * @param link        the requester's link on success (null for standalone jobs)
 * @param errorCode   null on success
 * @param errorDetail {@link UnsuitableCpus} or the missing {@link GenericStack}, depending on the error
 */
public record CraftingSubmitResult(ICraftingLink link, CraftingSubmitErrorCode errorCode, Object errorDetail) {
    public static final CraftingSubmitResult NO_CPU_FOUND = simpleError(CraftingSubmitErrorCode.NO_CPU_FOUND);
    public static final CraftingSubmitResult INCOMPLETE_PLAN = simpleError(CraftingSubmitErrorCode.INCOMPLETE_PLAN);
    public static final CraftingSubmitResult CPU_BUSY = simpleError(CraftingSubmitErrorCode.CPU_BUSY);
    public static final CraftingSubmitResult CPU_OFFLINE = simpleError(CraftingSubmitErrorCode.CPU_OFFLINE);
    public static final CraftingSubmitResult CPU_TOO_SMALL = simpleError(CraftingSubmitErrorCode.CPU_TOO_SMALL);

    public boolean successful() {
        return errorCode == null;
    }

    public static CraftingSubmitResult successful(ICraftingLink link) {
        return new CraftingSubmitResult(link, null, null);
    }

    public static CraftingSubmitResult simpleError(CraftingSubmitErrorCode code) {
        return new CraftingSubmitResult(null, code, null);
    }

    public static CraftingSubmitResult missingIngredient(GenericStack missingIngredient) {
        return new CraftingSubmitResult(null, CraftingSubmitErrorCode.MISSING_INGREDIENT, missingIngredient);
    }

    public static CraftingSubmitResult noSuitableCpu(UnsuitableCpus unsuitableCpus) {
        return new CraftingSubmitResult(null, CraftingSubmitErrorCode.NO_SUITABLE_CPU_FOUND, unsuitableCpus);
    }
}
