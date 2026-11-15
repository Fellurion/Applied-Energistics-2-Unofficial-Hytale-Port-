/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.locator.ItemMenuHostLocator).
 */
package de.fellurion.ae2.hytale.wireless;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.BlockAccess;

import javax.annotation.Nullable;

/**
 * Wo das Item liegt, in dem ein Terminal steckt (AE2: {@code ItemMenuHostLocator}): die Behälter, in denen es liegen
 * kann, nach Abschnittsnummer. Im Spiel ist das das Inventar des Spielers; für Tests ohne Spieler der Behälter eines
 * Blocks.
 */
interface ItemPlace {

    /** Behälter des Abschnitts, oder null (Abschnitt fehlt, Spieler weg, falscher Thread). */
    @Nullable
    ItemContainer container(int section);

    /** Abschnitte, in denen ein verschobenes Item gesucht wird. */
    int[] sections();

    /** Inventar eines Spielers: Schnellleiste, Zweithand, Lager, Rucksack, Werkzeuge. */
    static ItemPlace of(PlayerRef playerRef) {
        return new ItemPlace() {
            private static final int[] SECTIONS = {InventoryComponent.HOTBAR_SECTION_ID,
                    InventoryComponent.UTILITY_SECTION_ID, InventoryComponent.STORAGE_SECTION_ID,
                    InventoryComponent.BACKPACK_SECTION_ID, InventoryComponent.TOOLS_SECTION_ID};

            @Override
            @Nullable
            public ItemContainer container(int section) {
                Ref<EntityStore> ref = playerRef.getReference();
                if (section >= 0 || ref == null || !ref.isValid()) {
                    // Nummern ab 0 wären offene Fenster (fremde Behälter) – ein Terminal liegt nur im eigenen Inventar
                    return null;
                }
                Store<EntityStore> store = ref.getStore();
                World world = store.getExternalData().getWorld();
                if (world == null || !world.isInThread()) {
                    // Inventar nur auf dem Welt-Thread des Spielers anfassen
                    return null;
                }
                try {
                    return InventoryUtils.getSectionById(ref, section, store);
                } catch (RuntimeException e) {
                    return null;
                }
            }

            @Override
            public int[] sections() {
                return SECTIONS;
            }
        };
    }

    /** Behälter eines Blocks (Abschnitt 0) – für Tests über die Konsole. */
    static ItemPlace of(World world, int x, int y, int z) {
        return new ItemPlace() {
            private static final int[] SECTIONS = {0};

            @Override
            @Nullable
            public ItemContainer container(int section) {
                return section == 0 ? BlockAccess.container(world, x, y, z) : null;
            }

            @Override
            public int[] sections() {
                return SECTIONS;
            }
        };
    }
}
