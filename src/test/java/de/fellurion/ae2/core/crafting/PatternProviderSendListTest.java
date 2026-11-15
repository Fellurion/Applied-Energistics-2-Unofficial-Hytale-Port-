/*
 * Derived from Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; port (c) Fellurion
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version. It is distributed WITHOUT ANY WARRANTY; see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */
package de.fellurion.ae2.core.crafting;

import de.fellurion.ae2.core.config.AEConfig;
import de.fellurion.ae2.core.config.Actionable;
import de.fellurion.ae2.core.stacks.AEKey;
import de.fellurion.ae2.core.stacks.GenericStack;
import de.fellurion.ae2.core.stacks.KeyCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.ORE;
import static de.fellurion.ae2.core.crafting.CraftingTestNetwork.SMELT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The send list of a pattern provider (inputs that did not fit into the target): it stays with the target it was
 * meant for, moves on when the targets changed ({@link PatternProviderLogic#onTargetsChanged()}, a re-oriented
 * provider) and finds a target again after a reload. Nothing is lost or sent twice on the way.
 */
class PatternProviderSendListTest {

    /** A container with room for a limited number of items. */
    private static final class Box implements PatternProviderTarget {
        final KeyCounter received = new KeyCounter();
        long capacity;
        long stored;

        Box(long capacity) {
            this.capacity = capacity;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable type) {
            long n = Math.max(0, Math.min(amount, capacity - stored));
            if (type == Actionable.MODULATE && n > 0) {
                received.add(what, n);
                stored += n;
            }
            return n;
        }

        @Override
        public boolean containsPatternInput(Set<AEKey> patternInputs) {
            return false;
        }
    }

    private CraftingTestNetwork net;
    private PatternProviderLogic provider;
    private Box front;

    @BeforeEach
    void setUp() {
        AEConfig.reset();
        net = new CraftingTestNetwork(List.of(SMELT));
        provider = net.provider;
        provider.setTargetSupplier(() -> List.of(front));
    }

    private boolean push(long ore) {
        var inputs = new KeyCounter[] { new KeyCounter() };
        inputs[0].add(ORE, ore);
        return provider.pushPattern(SMELT, inputs);
    }

    @Test
    void whatDoesNotFitWaitsForTheSameTarget() {
        Box first = front = new Box(1);
        assertTrue(push(3));
        assertEquals(1, first.received.get(ORE));
        assertEquals(List.of(new GenericStack(ORE, 2)), provider.getSendList());
        assertTrue(provider.isBusy());

        // another container appears where the provider pushes: the rest still belongs to the first one
        Box second = front = new Box(10);
        net.world.tick(40);
        assertEquals(0, second.received.get(ORE));
        assertTrue(provider.isBusy());
        assertFalse(push(1), "busy providers take no further pattern");

        first.capacity = 10;
        net.world.tick(40);
        assertEquals(3, first.received.get(ORE));
        assertEquals(0, second.received.get(ORE));
        assertFalse(provider.isBusy());
    }

    @Test
    void afterTheTargetsChangedTheRestGoesToTheNewTarget() {
        Box first = front = new Box(1);
        assertTrue(push(3));
        net.world.tick(5);
        assertTrue(provider.isBusy());

        // re-oriented: the provider now faces nothing, the send list waits
        front = null;
        provider.setTargetSupplier(List::of);
        provider.onTargetsChanged();
        first.capacity = 10;
        net.world.tick(40);
        assertEquals(1, first.received.get(ORE), "nothing more goes into the block it no longer faces");
        assertEquals(List.of(new GenericStack(ORE, 2)), provider.getSendList());

        // re-oriented again, towards another container
        Box second = front = new Box(10);
        provider.setTargetSupplier(() -> List.of(front));
        provider.onTargetsChanged();
        net.world.tick(40);
        assertEquals(2, second.received.get(ORE));
        assertEquals(1, first.received.get(ORE));
        assertFalse(provider.isBusy());
        assertTrue(provider.getSendList().isEmpty());
    }

    @Test
    void theRestIsNeverSentToAMachineThatTakesPlans() {
        front = new Box(1);
        assertTrue(push(2));
        Box box = new Box(10);
        // the molecular assembler takes whole plans, not loose items
        provider.setTargetSupplier(() -> List.of(net.assembler, box));
        provider.onTargetsChanged();
        net.world.tick(40);
        assertEquals(1, box.received.get(ORE));
        assertFalse(provider.isBusy());
    }

    @Test
    void aLoadedSendListFindsItsTargetWhenTheSupplierIsSetAfterLoading() {
        front = new Box(1);
        assertTrue(push(3));
        String saved = provider.toJson();

        // as the integration does it: the node exists, then the state is loaded, then the targets are wired
        var reloaded = new CraftingTestNetwork(List.of(SMELT));
        reloaded.provider.setTargetSupplier(List::of);
        reloaded.world.tick(40);
        reloaded.provider.fromJson(saved);
        assertTrue(reloaded.provider.isBusy());
        assertEquals(List.of(new GenericStack(ORE, 2)), reloaded.provider.getSendList());
        Box box = new Box(10);
        reloaded.provider.setTargetSupplier(() -> List.of(box));
        reloaded.world.tick(40);
        assertEquals(2, box.received.get(ORE));
        assertFalse(reloaded.provider.isBusy());
    }
}
