/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion.
 */
package de.fellurion.ae2.hytale;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.codec.Codec;
import de.fellurion.ae2.core.crafting.PatternDetailsHelper;
import de.fellurion.ae2.core.stacks.AEItemKey;
import de.fellurion.ae2.core.stacks.FuzzySupport;
import org.bson.BsonDocument;
import org.bson.BsonDouble;

import javax.annotation.Nullable;

/**
 * Umwandlung Hytale-{@link ItemStack} ↔ AE2-{@link AEItemKey}.
 *
 * <p>Der {@code nbt}-Teil des Schlüssels ist ein kanonischer JSON-String mit allem, was zwei Stacks
 * unterscheidet: {@code {"d": Haltbarkeit, "md": max. Haltbarkeit, "m": Metadaten}} – nur nicht-Standardfelder.
 * Unbeschädigte Items ohne Metadaten haben {@code nbt == null} und stapeln sich damit wie in AE2.
 */
public final class ItemKeys {

    private ItemKeys() {
    }

    /** Metadaten-Schlüssel kodierter Patterns: der Pattern-JSON des Kerns (CraftingPattern/ProcessingPattern). */
    public static final String PATTERN_KEY = "AE2Pattern";

    @Nullable
    public static AEItemKey of(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (PatternDetailsHelper.isEncodedPattern(stack.getItemId())) {
            // Pattern: der Schlüssel des Kerns IST der Pattern-JSON (siehe CraftingPattern.encode)
            return AEItemKey.of(stack.getItemId(), stack.getFromMetadataOrNull(PATTERN_KEY, Codec.STRING));
        }
        double max = stack.getMaxDurability();
        Item item = stack.getItem();
        double defMax = item == null ? max : item.getMaxDurability();
        BsonDocument meta = metadataView(stack);
        boolean hasMeta = meta != null && !meta.isEmpty();
        if (max == defMax && stack.getDurability() == max && !hasMeta) {
            // häufigster Fall: neues Item ohne Metadaten – kein JSON nötig
            return AEItemKey.of(stack.getItemId(), null);
        }
        BsonDocument doc = new BsonDocument();
        if (max != defMax) {
            doc.put("md", new BsonDouble(max));
        }
        if (stack.getDurability() != max) {
            doc.put("d", new BsonDouble(stack.getDurability()));
        }
        if (hasMeta) {
            doc.put("m", meta); // nur gelesen (toJson), nicht verändert
        }
        return AEItemKey.of(stack.getItemId(), doc.toJson());
    }

    /** Lesezugriff auf die Metadaten ohne Kopie ({@link ItemStack#getMetadata()} klont das ganze Dokument). */
    @Nullable
    private static final java.lang.invoke.VarHandle METADATA = findMetadataHandle();

    @Nullable
    private static java.lang.invoke.VarHandle findMetadataHandle() {
        try {
            java.lang.reflect.Field f = ItemStack.class.getDeclaredField("metadata");
            f.setAccessible(true);
            return java.lang.invoke.MethodHandles.privateLookupIn(ItemStack.class, java.lang.invoke.MethodHandles.lookup())
                    .unreflectVarHandle(f);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Metadaten des Stacks OHNE Kopie – nur lesen, nie verändern (sonst ändert sich der Stack mit). */
    @Nullable
    @SuppressWarnings("deprecation")
    public static BsonDocument metadataView(ItemStack stack) {
        if (METADATA != null) {
            try {
                return (BsonDocument) METADATA.get(stack);
            } catch (RuntimeException ignored) {
                // Fallback unten
            }
        }
        return stack.getMetadata();
    }

    /** Erzeugt einen Stack mit {@code amount} (1..Integer.MAX_VALUE) Items dieses Schlüssels. */
    public static ItemStack toStack(AEItemKey key, int amount) {
        if (PatternDetailsHelper.isEncodedPattern(key.itemId())) {
            ItemStack p = new ItemStack(key.itemId(), amount);
            return key.nbt() == null ? p : p.withMetadata(PATTERN_KEY, Codec.STRING, key.nbt());
        }
        if (key.nbt() == null) {
            return new ItemStack(key.itemId(), amount);
        }
        BsonDocument doc = BsonDocument.parse(key.nbt());
        BsonDocument meta = doc.containsKey("m") ? doc.getDocument("m") : null;
        ItemStack base = new ItemStack(key.itemId(), amount, meta);
        double max = doc.containsKey("md") ? doc.getDouble("md").getValue() : base.getMaxDurability();
        double dur = doc.containsKey("d") ? doc.getDouble("d").getValue() : max;
        if (max != base.getMaxDurability() || dur != base.getDurability()) {
            return new ItemStack(key.itemId(), amount, dur, max, meta);
        }
        return base;
    }

    /** Maximale Stapelgröße des Items (mind. 1). */
    public static int maxStack(String itemId) {
        Item item = Item.getAssetMap().getAsset(itemId);
        return item == null ? 64 : Math.max(1, item.getMaxStack());
    }

    /** Liefert dem Kern Beschädigung/Max-Haltbarkeit für die Fuzzy-Karte. */
    public static void installDurabilityResolver() {
        FuzzySupport.setDurabilityResolver(new FuzzySupport.DurabilityResolver() {
            @Override
            public int damage(AEItemKey key) {
                ItemStack s = toStack(key, 1);
                return (int) Math.round(s.getMaxDurability() - s.getDurability());
            }

            @Override
            public int maxDamage(AEItemKey key) {
                Item item = Item.getAssetMap().getAsset(key.itemId());
                return item == null ? 0 : (int) Math.round(item.getMaxDurability());
            }
        });
    }
}
