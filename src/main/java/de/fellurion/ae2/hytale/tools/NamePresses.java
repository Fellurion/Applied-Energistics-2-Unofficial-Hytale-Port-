/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.materials.NamePressItem, appeng.blockentity.misc.InscriberRecipes#makeNamePressRecipe).
 */
package de.fellurion.ae2.hytale.tools;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import de.fellurion.ae2.core.inv.SimpleStack;
import de.fellurion.ae2.core.machines.InscriberRecipes;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.tools.NamePressNames;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.items.ItemTooltips;

import javax.annotation.Nullable;

/**
 * Die Namenspresse ({@value #ITEM_ID}): trägt einen Namen in ihren Metadaten ({@link #KEY}); im Inscriber oben
 * und/oder unten eingelegt, benennt sie das Item in der Mitte um. Der Kern entscheidet, wann umbenannt wird
 * ({@link InscriberRecipes#findNamePressRecipe}); hier steht, wie eine Presse ihren Namen trägt und wie ein Item zu
 * einem eigenen Namen kommt – dem Anzeigenamen in {@link ItemDisplayMetadata}, alles andere am Item bleibt.
 */
public final class NamePresses implements InscriberRecipes.NameHandler {

    public static final String ITEM_ID = InscriberRecipes.NAME_PRESS_ITEM_ID;
    /** Metadaten-Schlüssel: der eingravierte Name. */
    public static final String KEY = "AE2PressName";

    static final NamePresses INSTANCE = new NamePresses();

    private NamePresses() {
    }

    public static boolean isPress(@Nullable ItemStack stack) {
        return !ItemStack.isEmpty(stack) && ITEM_ID.equals(stack.getItemId());
    }

    /** Der Name auf der Presse, oder null für eine unbeschriftete (bzw. alles, was keine Presse ist). */
    @Nullable
    public static String name(@Nullable ItemStack press) {
        if (!isPress(press)) {
            return null;
        }
        return NamePressNames.clean(press.getFromMetadataOrNull(KEY, Codec.STRING));
    }

    /**
     * Eine Namenspresse mit diesem Namen und passendem Tooltip.
     *
     * @param name schon bereinigter Name ({@link NamePressNames#clean}); null ergibt eine unbeschriftete Presse
     */
    public static ItemStack create(@Nullable String name) {
        return ItemTooltips.refresh(new ItemStack(ITEM_ID, 1).withMetadata(KEY, Codec.STRING, name));
    }

    /**
     * Der Stack mit dem eigenen Namen {@code name} ({@code null} entfernt ihn); Beschreibung und alle übrigen
     * Metadaten bleiben.
     */
    public static ItemStack rename(ItemStack stack, @Nullable String name) {
        ItemDisplayMetadata old = stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
        Message description = old == null ? null : old.getDescription();
        if (name == null && description == null) {
            return old == null ? stack : stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC, null);
        }
        return stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC,
                new ItemDisplayMetadata(name == null ? null : Message.raw(name), description));
    }

    /** Der eigene Name eines Stacks, soweit er als einfacher Text gesetzt wurde (wie von der Namenspresse); sonst null. */
    @Nullable
    public static String customName(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return null;
        }
        ItemDisplayMetadata display = stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
        Message name = display == null ? null : display.getName();
        return name == null ? null : name.getRawText();
    }

    // ------------------------------------------------------------------ für den Kern (InscriberRecipes.NameHandler)

    @Override
    public String nameOf(SimpleStack press) {
        try {
            return name(ItemKeys.toStack(press.key(), 1));
        } catch (RuntimeException e) {
            return null; // unlesbare Metadaten: wie eine unbeschriftete Presse
        }
    }

    /**
     * Ein Stück des Items mit dem neuen Namen. Lässt sich das Item nicht als Hytale-Stack lesen, bleibt es, wie es
     * ist – der Inscriber reicht es dann unverändert durch, statt Metadaten zu verlieren.
     */
    @Override
    public SimpleStack rename(SimpleStack item, String name) {
        try {
            AEItemKey renamed = ItemKeys.of(rename(ItemKeys.toStack(item.key(), 1), name));
            return renamed == null ? item.withCount(1) : SimpleStack.of(renamed, 1);
        } catch (RuntimeException e) {
            return item.withCount(1);
        }
    }

    // ------------------------------------------------------------------ Tooltip

    /** Zusatzzeile der Namenspresse: der eingravierte Name (AE2: NamePressItem#appendHoverText). */
    @Nullable
    static Message tooltip(ItemStack stack) {
        String name = name(stack);
        return name == null ? null : Message.translation("ae2tools.press.tooltip.name").param("name", name);
    }
}
