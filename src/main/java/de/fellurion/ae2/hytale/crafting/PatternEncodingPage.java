/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.menu.me.items.PatternEncodingTermMenu).
 */
package de.fellurion.ae2.hytale.crafting;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.grid.Grid;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.storage.IActionSource;
import de.fellurion.ae2.core.storage.StorageHelper;
import de.fellurion.ae2.hytale.ItemKeys;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.lang.AE2Language;
import de.fellurion.ae2.hytale.ui.TerminalTexts;
import de.fellurion.ae2.hytale.ui.TerminalView;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pattern-Kodierterminal: Item suchen (oder gehaltenes Item), Hytale-Rezept wählen, „Kodieren“ macht aus
 * einem leeren Pattern (aus dem Inventar oder dem ME-Netz) ein Crafting- bzw. Processing-Pattern.
 */
public final class PatternEncodingPage extends InteractiveCustomUIPage<PatternEncodingPage.Payload> {

    private static final String PAGE = "Pages/AE2/PatternEncoding.ui";
    private static final String ENTRY = "Pages/AE2/TerminalEntry.ui";
    private static final String ROW = "Pages/AE2/RecipeRow.ui";
    private static final String INGREDIENT = "Pages/AE2/IngredientEntry.ui";
    private static final String BLANK = "AE2_Blank_Pattern";
    private static final int MAX_RESULTS = 60;
    private static final int MAX_RECIPES = 12;

    private final WorldNetworks nets;
    private final int x, y, z;
    private String search = "";
    @Nullable
    private String selected;
    private List<String> results = List.of();
    private List<HytaleRecipes.Option> recipes = List.of();
    private Message status = Message.raw("");

    public PatternEncodingPage(PlayerRef playerRef, WorldNetworks nets, int x, int y, int z) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Payload.CODEC);
        this.nets = nets;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt,
                      @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE);
        cmd.set("#PageTitle.Text", Message.translation("ae2.items.AE2_Pattern_Encoding_Terminal.name"));
        cmd.set("#UseHeld.Text", Message.translation("ae2craft.encode.useHeld"));
        evt.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput",
                EventData.of("@Search", "#SearchInput.Value"), false);
        evt.addEventBinding(CustomUIEventBindingType.Activating, "#UseHeld", EventData.of("Action", "UseHeld"), false);
        fill(cmd, evt, store, ref);
    }

    private void fill(UICommandBuilder cmd, UIEventBuilder evt, Store<EntityStore> store, Ref<EntityStore> ref) {
        // Suchergebnisse
        results = search(search, AE2Language.of(playerRef));
        cmd.set("#ResultsHeader.Text", Message.translation("ae2craft.encode.results").param("count", Integer.toString(results.size())));
        cmd.clear("#Results");
        for (int i = 0; i < results.size(); i++) {
            String sel = "#Results[" + i + "]";
            cmd.append("#Results", ENTRY);
            cmd.set(sel + " #Icon.ItemId", results.get(i));
            cmd.set(sel + " #Quantity.Text", "");
            evt.addEventBinding(CustomUIEventBindingType.Activating, sel + " #Button",
                    new EventData().append("Action", "Select").append("Index", Integer.toString(i)), false);
        }

        // Gewähltes Item + Rezepte
        cmd.clear("#Recipes");
        cmd.set("#TargetIcon.Visible", selected != null);
        if (selected == null) {
            cmd.set("#TargetName.Text", Message.translation("ae2craft.encode.chooseItem"));
            cmd.set("#BlankInfo.Text", blankInfo(store, ref));
            cmd.set("#Status.TextSpans", status); // Meldung: TextSpans (eine Meldung in .Text bringt den Client zum Absturz)
            return;
        }
        cmd.set("#TargetIcon.ItemId", selected);
        cmd.set("#TargetName.Text", Message.translation(itemNameKey(selected)));
        cmd.set("#BlankInfo.Text", blankInfo(store, ref));
        recipes = HytaleRecipes.recipesFor(selected);
        if (recipes.size() > MAX_RECIPES) {
            recipes = recipes.subList(0, MAX_RECIPES);
        }
        for (int i = 0; i < recipes.size(); i++) {
            HytaleRecipes.Option o = recipes.get(i);
            String row = "#Recipes[" + i + "]";
            cmd.append("#Recipes", ROW);
            MaterialQuantity out = o.recipe().getPrimaryOutput();
            cmd.set(row + " #Output.ItemId", out.getItemId());
            cmd.set(row + " #OutputQty.Text", out.getQuantity() > 1 ? Integer.toString(out.getQuantity()) : "");
            cmd.set(row + " #Kind.Text", Message.translation(o.processing() ? "ae2craft.encode.kindProcessing" : "ae2craft.encode.kindCrafting")
                    .param("bench", o.benchId()));
            cmd.set(row + " #Encode.Text", Message.translation("ae2craft.encode.encode"));
            MaterialQuantity[] inputs = o.recipe().getInput();
            int j = 0;
            for (MaterialQuantity mq : inputs == null ? new MaterialQuantity[0] : inputs) {
                String item = HytaleRecipes.displayItem(mq);
                if (item == null) {
                    continue;
                }
                String in = row + " #Inputs[" + j + "]";
                cmd.append(row + " #Inputs", INGREDIENT);
                cmd.set(in + " #Icon.ItemId", item);
                cmd.set(in + " #Quantity.Text", mq.getQuantity() > 1 ? Integer.toString(mq.getQuantity()) : "");
                j++;
            }
            evt.addEventBinding(CustomUIEventBindingType.Activating, row + " #Encode",
                    new EventData().append("Action", "Encode").append("Index", Integer.toString(i)), false);
        }
        if (recipes.isEmpty()) {
            status = Message.translation("ae2craft.encode.noRecipe");
        }
        cmd.set("#Status.TextSpans", status); // Meldung: TextSpans (eine Meldung in .Text bringt den Client zum Absturz)
    }

    private static String itemNameKey(String itemId) {
        var item = com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAsset(itemId);
        if (item != null && item.getTranslationKey() != null) {
            return item.getTranslationKey();
        }
        return itemId;
    }

    /**
     * Items mit Rezept, die zum Suchtext passen: nach dem Namen in der AE2-Sprache des Spielers oder der Item-Id
     * (wie im ME-Terminal; vorher fand die Suche nur Ids, deutsche Namen also gar nicht).
     */
    private static List<String> search(String query, String locale) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (q.length() < 2) {
            return out;
        }
        for (String id : HytaleRecipes.craftableOutputs()) {
            if (TerminalView.matches(q, id, TerminalTexts.itemName(locale, id))) {
                out.add(id);
                if (out.size() >= MAX_RESULTS) {
                    break;
                }
            }
        }
        return out;
    }

    private Message blankInfo(Store<EntityStore> store, Ref<EntityStore> ref) {
        long inv = countInInventory(store, ref, BLANK);
        long net = 0;
        Grid grid = CraftingAccess.grid(nets, x, y, z);
        if (grid != null && CraftingAccess.active(nets, x, y, z)) {
            net = grid.getStorageService().getCachedInventory().get(AEItemKey.of(BLANK));
        }
        return Message.translation("ae2craft.encode.blanks").param("inventory", Long.toString(inv)).param("network", Long.toString(net));
    }

    // ------------------------------------------------------------------ Ereignisse

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Payload p) {
        if (!de.fellurion.ae2.hytale.security.AE2Security.checkUse(nets, x, y, z, playerRef)) {
            close(); // Zugriff entzogen, während die Seite offen war
            return;
        }
        if (p.search != null && p.action == null) {
            search = p.search;
        } else if ("Select".equals(p.action)) {
            int i = parse(p.index);
            if (i >= 0 && i < results.size()) {
                selected = results.get(i);
                status = Message.raw("");
            }
        } else if ("UseHeld".equals(p.action)) {
            ItemStack held = InventoryComponent.getItemInHand(store, ref);
            if (held != null && !held.isEmpty()) {
                selected = held.getItemId();
                status = Message.raw("");
            }
        } else if ("Encode".equals(p.action)) {
            int i = parse(p.index);
            if (i >= 0 && i < recipes.size()) {
                encode(ref, store, recipes.get(i));
            }
        }
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder evt = new UIEventBuilder();
        fill(cmd, evt, store, ref);
        sendUpdate(cmd, evt, false);
    }

    private void encode(Ref<EntityStore> ref, Store<EntityStore> store, HytaleRecipes.Option option) {
        AEItemKey pattern = HytaleRecipes.encode(option);
        if (pattern == null) {
            status = Message.translation("ae2craft.encode.unsupported");
            return;
        }
        if (!takeBlank(ref, store)) {
            status = Message.translation("ae2craft.encode.noBlank");
            return;
        }
        ItemStack stack = ItemKeys.toStack(pattern, 1);
        SimpleItemContainer.addOrDropItemStack(store, ref,
                InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST), stack);
        status = Message.translation("ae2craft.encode.done");
    }

    /** Ein leeres Pattern verbrauchen: erst aus dem Inventar, sonst aus dem ME-Netz. */
    private boolean takeBlank(Ref<EntityStore> ref, Store<EntityStore> store) {
        for (InventoryComponent inv : inventories(store, ref)) {
            ItemContainer c = inv.getInventory();
            for (short s = 0; s < c.getCapacity(); s++) {
                ItemStack st = c.getItemStack(s);
                // Nur wenn das leere Pattern wirklich entnommen wurde (gesperrte Felder liefern sonst ein Gratis-Pattern)
                if (st != null && !st.isEmpty() && BLANK.equals(st.getItemId())
                        && c.removeItemStackFromSlot(s, 1).succeeded()) {
                    return true;
                }
            }
        }
        Grid grid = CraftingAccess.grid(nets, x, y, z);
        if (grid != null && CraftingAccess.active(nets, x, y, z)) {
            boolean taken = StorageHelper.poweredExtraction(grid.getEnergyService(),
                    grid.getStorageService().getInventory(), AEItemKey.of(BLANK), 1,
                    IActionSource.ofPlayer(playerRef.getUuid())) == 1;
            if (taken) {
                // sonst zeigte „Leere Patterns: … im Netz“ bis zum nächsten Tick noch die alte Zahl
                grid.getStorageService().invalidateCache();
            }
            return taken;
        }
        return false;
    }

    /** Schnellleiste, Lager und Rucksack (leere Patterns im Rucksack zählen und werden verbraucht wie die übrigen). */
    private static List<InventoryComponent> inventories(Store<EntityStore> store, Ref<EntityStore> ref) {
        List<InventoryComponent> out = new ArrayList<>();
        InventoryComponent.Hotbar h = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        InventoryComponent.Storage s = store.getComponent(ref, InventoryComponent.Storage.getComponentType());
        InventoryComponent.Backpack b = store.getComponent(ref, InventoryComponent.Backpack.getComponentType());
        for (InventoryComponent c : new InventoryComponent[]{h, s, b}) {
            if (c != null && c.getInventory() != null) {
                out.add(c);
            }
        }
        return out;
    }

    private static long countInInventory(Store<EntityStore> store, Ref<EntityStore> ref, String itemId) {
        long n = 0;
        for (InventoryComponent inv : inventories(store, ref)) {
            ItemContainer c = inv.getInventory();
            for (short s = 0; s < c.getCapacity(); s++) {
                ItemStack st = c.getItemStack(s);
                if (st != null && !st.isEmpty() && itemId.equals(st.getItemId())) {
                    n += st.getQuantity();
                }
            }
        }
        return n;
    }

    private static int parse(@Nullable String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static final class Payload {
        String action;
        String index;
        String search;

        public static final BuilderCodec<Payload> CODEC = BuilderCodec.builder(Payload.class, Payload::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (o, v) -> o.action = v, o -> o.action).add()
                .append(new KeyedCodec<>("Index", Codec.STRING), (o, v) -> o.index = v, o -> o.index).add()
                .append(new KeyedCodec<>("@Search", Codec.STRING), (o, v) -> o.search = v, o -> o.search).add()
                .build();
    }
}
