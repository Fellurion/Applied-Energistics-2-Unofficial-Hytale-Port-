/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.stacks;

import de.fellurion.ae2.core.config.Enums.FuzzyMode;
import de.fellurion.ae2.core.util.MiniJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StacksTest {
    @AfterEach
    void tearDown() {
        FuzzySupport.setDurabilityResolver(null);
    }

    @Test
    void keyTypeConstantsMatchAE2() {
        assertEquals(8, AEKeyType.ITEMS.getAmountPerByte());
        assertEquals(8000, AEKeyType.FLUIDS.getAmountPerByte());
        assertEquals(1, AEKeyType.ITEMS.getAmountPerOperation());
        assertEquals(125, AEKeyType.FLUIDS.getAmountPerOperation());
        assertEquals(1000, AEKeyType.FLUIDS.getAmountPerUnit());
    }

    @Test
    void keysHaveValueSemantics() {
        assertEquals(AEItemKey.of("A"), new AEItemKey("A", ""));
        assertNotEquals(AEItemKey.of("A"), AEItemKey.of("A", "{x}"));
        assertEquals(AEItemKey.of("A"), AEItemKey.of("A", "{x}").dropSecondary());
        assertNotEquals(AEItemKey.of("Water").getPrimaryKey(), AEFluidKey.of("Water").getPrimaryKey());
        var json = AEItemKey.of("A", "{\"q\":\"x\\\"y\"}").toJson();
        assertEquals(AEItemKey.of("A", "{\"q\":\"x\\\"y\"}"), AEKey.fromJson(json));
        assertEquals(AEFluidKey.of("Lava"), AEKey.fromJson(AEFluidKey.of("Lava").toJson()));
    }

    @Test
    void keyCounterKeepsZerosUntilRemoved() {
        var c = new KeyCounter();
        var a = AEItemKey.of("A");
        c.add(a, 5);
        c.remove(a, 5);
        assertEquals(0, c.get(a));
        assertFalse(c.isEmpty(), "AE2 keeps zero entries until removeZeros()");
        assertEquals(1, c.size());
        c.removeZeros();
        assertTrue(c.isEmpty());

        c.add(AEItemKey.of("A", "{1}"), 3);
        c.add(AEItemKey.of("A", "{2}"), 4);
        c.add(AEItemKey.of("B"), 7);
        assertEquals(2, c.findFuzzy(AEItemKey.of("A"), FuzzyMode.IGNORE_ALL).size());
        var other = new KeyCounter();
        other.add(AEItemKey.of("B"), 2);
        c.removeAll(other);
        assertEquals(5, c.get(AEItemKey.of("B")));
    }

    @Test
    void fuzzyPercentModesUseDurabilityResolver() {
        FuzzySupport.setDurabilityResolver(new FuzzySupport.DurabilityResolver() {
            @Override
            public int damage(AEItemKey key) {
                return key.nbt() == null ? 0 : Integer.parseInt(key.nbt());
            }

            @Override
            public int maxDamage(AEItemKey key) {
                return 100;
            }
        });
        var fresh = AEItemKey.of("Sword");
        var worn = AEItemKey.of("Sword", "30");
        var broken = AEItemKey.of("Sword", "80");
        assertTrue(fresh.fuzzyEquals(broken, FuzzyMode.IGNORE_ALL));
        assertFalse(fresh.fuzzyEquals(worn, FuzzyMode.PERCENT_99));
        assertTrue(worn.fuzzyEquals(broken, FuzzyMode.PERCENT_99));
        // 50% breakpoint: 30% damaged is on the "<=50" side with fresh, 80% is not
        assertTrue(fresh.fuzzyEquals(worn, FuzzyMode.PERCENT_50));
        assertFalse(worn.fuzzyEquals(broken, FuzzyMode.PERCENT_50));
        assertFalse(fresh.fuzzyEquals(AEItemKey.of("Axe"), FuzzyMode.IGNORE_ALL));
    }

    @Test
    void miniJsonRoundTrip() {
        var json = "{\"a\":1,\"b\":[true,false,null,\"x\\n\\u0041\"],\"c\":{\"d\":-2.5},\"e\":3.0}";
        var parsed = MiniJson.parseObject(json);
        assertEquals(1L, parsed.get("a"));
        assertEquals(List.of(true, false), ((List<?>) parsed.get("b")).subList(0, 2));
        assertEquals("x\nA", ((List<?>) parsed.get("b")).get(3));
        assertEquals(-2.5, ((Map<?, ?>) parsed.get("c")).get("d"));
        assertEquals(3.0, parsed.get("e"));
        assertEquals("{\"a\":1,\"b\":[true,false,null,\"x\\nA\"],\"c\":{\"d\":-2.5},\"e\":3.0}", MiniJson.write(parsed));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":}"));
    }
}
