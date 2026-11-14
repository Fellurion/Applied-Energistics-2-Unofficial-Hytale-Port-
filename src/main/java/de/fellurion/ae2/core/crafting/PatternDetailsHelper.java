/*
 * Derived from the Applied Energistics 2 API (c) 2013-2020 AlgorithmX2 et al., MIT License (see LICENSE-API.md);
 * port to Hytale (c) Fellurion
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.stacks.AEItemKey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decodes encoded pattern items. Port of {@code appeng.api.crafting.PatternDetailsHelper} (with a small LRU cache like
 * AE2's decoder cache).
 */
public final class PatternDetailsHelper {
    private static final int CACHE_SIZE = 512;
    private static final Map<AEItemKey, IPatternDetails> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<AEItemKey, IPatternDetails> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private PatternDetailsHelper() {
    }

    public static boolean isEncodedPattern(String itemId) {
        return ProcessingPattern.ITEM_ID.equals(itemId) || CraftingPattern.ITEM_ID.equals(itemId);
    }

    /** @return the decoded pattern, or null if the key is no (valid) encoded pattern */
    public static synchronized IPatternDetails decodePattern(AEItemKey definition) {
        if (definition == null || !isEncodedPattern(definition.itemId()) || definition.nbt() == null) {
            return null;
        }
        var cached = CACHE.get(definition);
        if (cached != null) {
            return cached;
        }
        IPatternDetails details;
        try {
            details = ProcessingPattern.ITEM_ID.equals(definition.itemId()) ? new ProcessingPattern(definition)
                    : new CraftingPattern(definition);
        } catch (RuntimeException e) {
            return null;
        }
        CACHE.put(definition, details);
        return details;
    }
}
