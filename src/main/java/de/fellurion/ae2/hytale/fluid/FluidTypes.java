/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.api.stacks.AEFluidKey – dort steckt die Flüssigkeit des Spiels im Schlüssel).
 */
package de.fellurion.ae2.hytale.fluid;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.fluid.DefaultFluidTicker;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.PlaceFluidInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import de.fellurion.ae2.core.stacks.AEFluidKey;
import de.fellurion.ae2.hytale.ui.TerminalTexts;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Flüssigkeiten, die das ME-Netz lagert: Kennung, Schlüssel und das Item, mit dem das Terminal sie zeigt.
 *
 * <p>Ein Fluid-Schlüssel ist {@code AEFluidKey(<Kennung der Quell-Flüssigkeit>)}, etwa {@code Water_Source}. Ein
 * Quell-Block entspricht 1000 mB (AE2: ein Eimer). Die Liste entsteht aus den Flüssigkeiten des Spiels – auch aus
 * denen anderer Mods – nach {@link FluidRules}; {@link FluidConfig} kann sie erweitern und einschränken. Sie wird beim
 * ersten Gebrauch gebaut und neu, wenn das Spiel Flüssigkeiten nachlädt.
 *
 * <p>Für die Anzeige braucht der Client ein Item: das Spiel hat zu jeder seiner Flüssigkeiten eines ({@code Fluid_Water}
 * …, es setzt beim Benutzen genau diese Flüssigkeit). Findet sich keines, steht das Gehäuse der Fluid-Zellen dafür.
 */
public final class FluidTypes {

    /** Anzeige-Item für Flüssigkeiten, zu denen das Spiel kein eigenes hat. */
    static final String FALLBACK_ICON = "AE2_Fluid_Cell_Housing";
    private static final String NAME_KEY_PREFIX = "ae2fluid.fluid.";

    /**
     * Eine lagerbare Flüssigkeit.
     *
     * @param id       Asset-Kennung der Quelle
     * @param key      Schlüssel im ME-Netz
     * @param index    Nummer der Flüssigkeit in den Chunks des Spiels
     * @param iconItem Item, mit dem sie angezeigt wird
     */
    public record Info(String id, AEFluidKey key, int index, String iconItem) {
    }

    private record Snapshot(int assetCount, List<Info> list, Map<String, Info> byId, Map<Integer, Info> byIndex) {
    }

    @Nullable
    private static volatile Snapshot snapshot;

    private FluidTypes() {
    }

    /** Vergisst die Liste (nach geänderter Konfiguration); sie entsteht beim nächsten Gebrauch neu. */
    static void reload() {
        snapshot = null;
    }

    private static Snapshot snapshot() {
        int count;
        try {
            count = Fluid.getAssetMap().getAssetCount();
        } catch (RuntimeException e) {
            return new Snapshot(-1, List.of(), Map.of(), Map.of()); // Assets noch nicht geladen
        }
        Snapshot s = snapshot;
        if (s != null && s.assetCount() == count) {
            return s;
        }
        s = build(count);
        snapshot = s;
        return s;
    }

    private static Snapshot build(int count) {
        Map<String, Fluid> assets = Fluid.getAssetMap().getAssetMap();
        List<FluidRules.Candidate> candidates = new ArrayList<>(assets.size());
        List<String> ids = new ArrayList<>(assets.keySet());
        ids.sort(null); // feste Reihenfolge, unabhängig von der Ladereihenfolge der Mods
        for (String id : ids) {
            Fluid f = assets.get(id);
            if (f != null) {
                candidates.add(new FluidRules.Candidate(id, isSource(f), f.isUnknown()));
            }
        }
        Map<String, String> iconItems = iconItems();
        List<Info> list = new ArrayList<>();
        Map<String, Info> byId = new HashMap<>();
        Map<Integer, Info> byIndex = new HashMap<>();
        for (String id : FluidRules.storable(candidates, FluidConfig.allow(), FluidConfig.deny())) {
            int index = Fluid.getAssetMap().getIndexOrDefault(id, Integer.MIN_VALUE);
            if (index <= 0) {
                continue;
            }
            Info info = new Info(id, AEFluidKey.of(id), index, resolveIcon(id, iconItems));
            list.add(info);
            byId.put(id, info);
            byIndex.put(index, info);
        }
        return new Snapshot(count, List.copyOf(list), byId, byIndex);
    }

    /**
     * Quelle: verbreitet sich wie jede Flüssigkeit, baut sich aber nicht von selbst ab ({@code CanDemote: false}).
     * Feuer und endliches Wasser haben einen eigenen Takt und zählen nicht.
     */
    private static boolean isSource(Fluid fluid) {
        return fluid.getTicker() != null && fluid.getTicker().getClass() == DefaultFluidTicker.class
                && !fluid.getTicker().canDemote();
    }

    /**
     * Items, die beim Benutzen eine Flüssigkeit setzen (Flüssigkeit → Item). Zustände anderer Items (der gefüllte
     * Eimer) zählen nicht; bei mehreren gewinnt das Item mit dem üblichen Namen.
     */
    private static Map<String, String> iconItems() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (Item item : Item.getAssetMap().getAssetMap().values()) {
                if (item == null || item.getId() == null || item.isState()) {
                    continue;
                }
                String fluid = placedFluid(item);
                if (fluid == null) {
                    continue;
                }
                String known = out.get(fluid);
                if (known == null || item.getId().equals(FluidRules.conventionalIconItem(fluid))) {
                    out.put(fluid, item.getId());
                }
            }
        } catch (RuntimeException e) {
            // Interaktionen nicht lesbar: es bleibt bei den üblichen Namen
        }
        return out;
    }

    /** Die Flüssigkeit, die ein Item mit seiner Zweitaktion setzt, oder null. */
    @Nullable
    private static String placedFluid(Item item) {
        String rootId = item.getInteractions() == null ? null : item.getInteractions().get(InteractionType.Secondary);
        RootInteraction root = rootId == null ? null : RootInteraction.getAssetMap().getAsset(rootId);
        if (root == null || root.getInteractionIds() == null) {
            return null;
        }
        for (String id : root.getInteractionIds()) {
            Interaction interaction = id == null ? null : Interaction.getAssetMap().getAsset(id);
            if (interaction instanceof PlaceFluidInteraction place && place.getFluidKey() != null) {
                return place.getFluidKey();
            }
        }
        return null;
    }

    private static String resolveIcon(String fluidId, Map<String, String> iconItems) {
        String configured = FluidConfig.icon(fluidId);
        if (configured != null && Item.getAssetMap().getAsset(configured) != null) {
            return configured;
        }
        String placing = iconItems.get(fluidId);
        if (placing != null) {
            return placing;
        }
        String conventional = FluidRules.conventionalIconItem(fluidId);
        return Item.getAssetMap().getAsset(conventional) != null ? conventional : FALLBACK_ICON;
    }

    // ------------------------------------------------------------------ Abfragen

    /** Alle lagerbaren Flüssigkeiten in fester Reihenfolge (nach Kennung). */
    public static List<Info> storable() {
        return snapshot().list();
    }

    /** Die lagerbare Flüssigkeit mit dieser Kennung, oder null. */
    @Nullable
    public static Info get(@Nullable String fluidId) {
        return fluidId == null ? null : snapshot().byId().get(fluidId);
    }

    public static boolean isStorable(@Nullable String fluidId) {
        return get(fluidId) != null;
    }

    /** Der Schlüssel einer lagerbaren Flüssigkeit, oder null. */
    @Nullable
    public static AEFluidKey key(@Nullable String fluidId) {
        Info info = get(fluidId);
        return info == null ? null : info.key();
    }

    /**
     * Die lagerbare Quell-Flüssigkeit in einer Zelle der Welt, oder null (leer, fließend, nicht lagerbar, Chunk
     * nicht geladen). Lädt nichts nach.
     */
    @Nullable
    public static Info sourceAt(World world, int x, int y, int z) {
        if (y < 0 || y >= 320) {
            return null;
        }
        ChunkStore chunks = world.getChunkStore();
        Ref<ChunkStore> section = chunks.getChunkSectionReferenceAtBlock(x, y, z);
        if (section == null || !section.isValid()) {
            return null;
        }
        FluidSection fluids = chunks.getStore().getComponent(section, FluidSection.getComponentType());
        return byIndex(fluids == null ? 0 : fluids.getFluidId(x, y, z));
    }

    /** Die lagerbare Quell-Flüssigkeit mit dieser Nummer in den Chunks des Spiels, oder null (auch für fließende). */
    @Nullable
    public static Info byIndex(int fluidIndex) {
        return fluidIndex <= 0 ? null : snapshot().byIndex().get(fluidIndex);
    }

    // ------------------------------------------------------------------ Anzeige

    /**
     * Item, mit dem die Flüssigkeit gezeigt wird – auch für Kennungen, die es nicht mehr gibt (Mod entfernt): dann
     * das übliche Item, wenn es existiert, sonst das Zellengehäuse.
     */
    public static String iconItem(String fluidId) {
        Info info = get(fluidId);
        if (info != null) {
            return info.iconItem();
        }
        try {
            String conventional = FluidRules.conventionalIconItem(fluidId);
            return Item.getAssetMap().getAsset(conventional) != null ? conventional : FALLBACK_ICON;
        } catch (RuntimeException e) {
            return FALLBACK_ICON;
        }
    }

    /** Ein Stück des Anzeige-Items (reine Anzeige, nie ein echtes Item des Spielers). */
    public static ItemStack displayStack(String fluidId) {
        return new ItemStack(iconItem(fluidId), 1);
    }

    /** Name der Flüssigkeit in {@code locale}: eigener Text, sonst der des Anzeige-Items, sonst aus der Kennung. */
    public static String name(String locale, String fluidId) {
        try {
            I18nModule i18n = I18nModule.get();
            String own = i18n == null ? null : i18n.getMessage(locale, NAME_KEY_PREFIX + fluidId);
            if (own != null && !own.isBlank()) {
                return own;
            }
        } catch (RuntimeException ignored) {
            // I18n nicht bereit
        }
        String icon = iconItem(fluidId);
        return FALLBACK_ICON.equals(icon) ? FluidRules.fallbackName(fluidId) : TerminalTexts.itemName(locale, icon);
    }

    /** Name der Flüssigkeit als Nachricht, die der Client in seiner Sprache auflöst (Tooltips von Items). */
    public static Message nameMessage(String fluidId) {
        try {
            I18nModule i18n = I18nModule.get();
            if (i18n != null && i18n.getMessage("en-US", NAME_KEY_PREFIX + fluidId) != null) {
                return Message.translation(NAME_KEY_PREFIX + fluidId);
            }
            String icon = iconItem(fluidId);
            Item item = FALLBACK_ICON.equals(icon) ? null : Item.getAssetMap().getAsset(icon);
            if (item != null) {
                return item.getTranslationMessage();
            }
        } catch (RuntimeException ignored) {
            // ohne Übersetzung
        }
        return Message.raw(FluidRules.fallbackName(fluidId));
    }
}
