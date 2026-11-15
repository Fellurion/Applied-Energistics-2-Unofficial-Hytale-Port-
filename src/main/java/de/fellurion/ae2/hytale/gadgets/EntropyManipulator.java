/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.tools.powered.EntropyManipulatorItem).
 */
package de.fellurion.ae2.hytale.gadgets;

import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.World;
import de.fellurion.ae2.core.machines.Ingredient;
import de.fellurion.ae2.core.tools.EntropyRecipes;
import de.fellurion.ae2.core.tools.EntropyRecipes.Mode;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.device.Drops;
import de.fellurion.ae2.hytale.items.ItemUseContext;
import de.fellurion.ae2.hytale.items.PoweredItems;
import org.joml.Vector3i;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Entropy Manipulator: Rechtsklick erhitzt, geduckt Rechtsklick kühlt den anvisierten Block oder die anvisierte
 * Flüssigkeit – je {@value #ENERGY_PER_USE} AE. Rezepte aus {@code ae2data/entropy.json} ({@link EntropyRecipes}).
 *
 * <p>Erhitzen wie in AE2 der Reihe nach: Sprengladungen zünden (ohne Energie) – Rezept – den Block an Ort und Stelle
 * schmelzen, wenn alles, was er fallen lässt, ein Ofen-Rezept hat – sonst Feuer an der angeklickten Seite legen.
 * Ein Treffer auf ein Wesen setzt es 8 Sekunden in Brand ({@link MeleeHits}).
 *
 * <p>Jede Änderung läuft über {@link WorldEdits}, also über Hytales Abbauen/Setzen für den Spieler.
 *
 * <p>Abweichungen von AE2: „Schmelzen“ nutzt die Rezepte von Hytales Ofen mit genau einer Zutat ({@link
 * #smeltingResult}); weil Hytales Erze auch ihr Gestein fallen lassen, zählt dafür zusätzlich das eigene
 * Erhitzen-Rezept (Bruchstein → Stein). Für Flüssigkeiten kennt Hytale kein Ereignis, deshalb wird dort vorab mit
 * einem Platzier-Ereignis gefragt. Blockeigenschaften (AE2: {@code properties}) gibt es nicht.
 */
final class EntropyManipulator {

    static final String ITEM = "AE2_Entropy_Manipulator";
    /** AE2: {@code ENERGY_PER_USE}. */
    static final int ENERGY_PER_USE = 1600;
    private static final String FURNACE = "Furnace";
    private static final String FIRE = "Fire";

    @Nullable
    private static volatile EntropyRecipes recipes;

    private EntropyManipulator() {
    }

    static void setRecipes(EntropyRecipes loaded) {
        recipes = loaded;
    }

    @Nullable
    static EntropyRecipes recipes() {
        return recipes;
    }

    /** Was eine Anwendung bewirkt hat. */
    enum Outcome {
        /** Hier gibt es nichts zu erhitzen/kühlen, oder die Änderung wurde nicht erlaubt. */
        NOTHING,
        /** Gewirkt, kostet Energie. */
        DONE,
        /** Gewirkt, kostet nichts (AE2: Sprengladung gezündet). */
        FREE
    }

    /**
     * Die anvisierte Zelle.
     *
     * @param side die Zelle, aus der der Blick kam (dort entsteht Feuer), oder null
     */
    record Target(int x, int y, int z, @Nullable Vector3i side) {
    }

    // ------------------------------------------------------------------ Klick

    static void use(ItemUseContext ctx) {
        if (ctx.primary()) {
            return;
        }
        ItemStack held = ctx.held();
        Target target = target(ctx);
        if (target == null) {
            return; // AE2: ins Leere geklickt
        }
        // AE2: getAECurrentPower(item) > ENERGY_PER_USE
        if (!(PoweredItems.stored(held) > ENERGY_PER_USE)) {
            Feedback.noPower(ctx.playerRef(), ENERGY_PER_USE);
            return;
        }
        Mode mode = ctx.crouching() ? Mode.COOL : Mode.HEAT;
        WorldEdits edits = WorldEdits.forPlayer(ctx.world(), ctx.commandBuffer(), ctx.playerEntity(), held,
                ctx.interaction().getHeldItemContainer(), ctx.interaction().getHeldItemSlot());
        if (apply(edits, target, mode) == Outcome.DONE) {
            ItemStack used = PoweredItems.use(held, ENERGY_PER_USE);
            if (used != null) {
                ctx.setHeld(used);
            }
        }
    }

    /**
     * Wohin der Spieler zielt: Flüssigkeiten zählen mit (AE2: {@code getPlayerPOVHitResult(..., Fluid.ANY)}), das
     * sieht nur der Strahl des Servers. Für Blöcke gilt die Zelle, die der Client anvisiert hat.
     */
    @Nullable
    private static Target target(ItemUseContext ctx) {
        WorldRay.Hit hit = Aim.firstCell(ctx, true);
        Vector3i client = ctx.targetBlock();
        if (hit != null && (hit.fluidId() != 0 || client == null)) {
            return new Target(hit.x(), hit.y(), hit.z(), hit.hasPrev() ? new Vector3i(hit.prevX(), hit.prevY(), hit.prevZ()) : null);
        }
        if (client == null) {
            return null;
        }
        boolean same = hit != null && hit.x() == client.x && hit.y() == client.y && hit.z() == client.z && hit.hasPrev();
        return new Target(client.x, client.y, client.z, same ? new Vector3i(hit.prevX(), hit.prevY(), hit.prevZ()) : null);
    }

    // ------------------------------------------------------------------ Wirkung (AE2: tryApplyEffect)

    static Outcome apply(WorldEdits edits, Target t, Mode mode) {
        EntropyRecipes all = recipes;
        World world = edits.world;
        int x = t.x(), y = t.y(), z = t.z();
        BlockType type = BlockAccess.blockType(world, x, y, z);
        String fluid = WorldEdits.fluidKey(world, x, y, z);
        if (all == null || (type == null && fluid == null)) {
            return Outcome.NOTHING;
        }
        if (mode == Mode.COOL) {
            EntropyRecipes.Recipe cool = find(all, Mode.COOL, type, fluid);
            return cool != null && applyRecipe(edits, cool, x, y, z) ? Outcome.DONE : Outcome.NOTHING;
        }
        // Sprengladungen (AE2: TntBlock, TinyTNTBlock) – zünden kostet nichts
        if (type != null && TinyTnt.at(world, x, y, z) != null) {
            return edits.mayModify(x, y, z) && TinyTnt.ignite(world, x, y, z) ? Outcome.FREE : Outcome.NOTHING;
        }
        if (type != null && type.getExplosionReactionState() != null) {
            BlockType lit = type.getBlockForState(type.getExplosionReactionState());
            if (lit != null && !lit.getId().equals(type.getId())) {
                return edits.placeBlock(x, y, z, lit.getId(), BlockAccess.rotationIndex(world, x, y, z)) ? Outcome.FREE : Outcome.NOTHING;
            }
        }
        EntropyRecipes.Recipe heat = find(all, Mode.HEAT, type, fluid);
        if (heat != null) {
            return applyRecipe(edits, heat, x, y, z) ? Outcome.DONE : Outcome.NOTHING;
        }
        if (type != null && smelt(edits, all, type, x, y, z)) {
            return Outcome.DONE;
        }
        return lightFire(edits, t) ? Outcome.DONE : Outcome.NOTHING;
    }

    /** Rezept für Block und Flüssigkeit der Zelle; ein Block in einem Zustand zählt wie sein Grundblock. */
    @Nullable
    private static EntropyRecipes.Recipe find(EntropyRecipes all, Mode mode, @Nullable BlockType type, @Nullable String fluid) {
        if (type == null) {
            return all.find(mode, EntropyRecipes.EMPTY, fluid);
        }
        EntropyRecipes.Recipe r = all.find(mode, type.getId(), fluid);
        if (r == null && type.getItem() != null && !type.getItem().getId().equals(type.getId())) {
            r = all.find(mode, type.getItem().getId(), fluid);
        }
        return r;
    }

    /** AE2: {@code applyRecipe}. Erst die Welt ändern (das kann abgelehnt werden), dann die Beute. */
    private static boolean applyRecipe(WorldEdits edits, EntropyRecipes.Recipe r, int x, int y, int z) {
        String placed = r.placedBlock();
        if (placed != null) {
            // Block gegen Block (Stein → Bruchstein) oder Block in die Flüssigkeit (Wasser → Eis)
            if (!edits.placeBlock(x, y, z, placed, 0)) {
                return false;
            }
        } else if (r.removesBlock()) {
            // Block wird zu Flüssigkeit oder Luft (Eis → Wasser)
            if (!edits.breakBlock(x, y, z, false)) {
                return false;
            }
        } else if (!edits.mayModify(x, y, z)) {
            // nur die Flüssigkeit ändert sich (Wasser verdampft)
            return false;
        }
        if (r.outputFluid() != null) {
            edits.setFluid(x, y, z, r.outputFluid());
        } else if (r.removesFluid()) {
            edits.setFluid(x, y, z, null);
        }
        if (!r.drops().isEmpty()) {
            List<ItemStack> drops = new ArrayList<>();
            for (Ingredient d : r.drops()) {
                drops.add(new ItemStack(d.itemId(), d.quantity()));
            }
            Drops.at(edits.world, x, y, z, drops);
        }
        if (r.mode() == Mode.HEAT) {
            // AE2: Zischen und Rauch wie ein Wassereimer im Nether
            Feedback.sound(edits.world, "SFX_Flame_Break", x + 0.5, y + 0.5, z + 0.5);
            Feedback.particle(edits.world, "Impact_Fire", x + 0.5, y + 0.5, z + 0.5);
        } else {
            Feedback.sound(edits.world, "SFX_Ice_Build", x + 0.5, y + 0.5, z + 0.5);
            Feedback.particle(edits.world, "Impact_Ice", x + 0.5, y + 0.5, z + 0.5);
        }
        return true;
    }

    /**
     * AE2: {@code performInWorldSmelting} – der Block wird an Ort und Stelle geschmolzen, wenn alles, was er fallen
     * lässt, geschmolzen werden kann. Das erste Ergebnis, das selbst ein Block ist, ersetzt den Block; der Rest fällt.
     * Ein Erz im Gestein wird so zu Gestein, und der Barren fällt heraus.
     */
    private static boolean smelt(WorldEdits edits, EntropyRecipes all, BlockType type, int x, int y, int z) {
        BlockGathering gathering = type.getGathering();
        BlockBreakingDropType breaking = gathering == null ? null : gathering.getBreaking();
        if (breaking == null) {
            return false;
        }
        List<ItemStack> drops = BlockHarvestUtils.getDrops(type, Math.max(1, breaking.getQuantity()), breaking.getItemId(),
                breaking.getDropListId());
        String smeltedBlock = null;
        List<ItemStack> smelted = new ArrayList<>();
        for (ItemStack drop : drops) {
            if (ItemStack.isEmpty(drop)) {
                continue;
            }
            ItemStack result = smeltingResult(all, drop.getItemId());
            if (result == null) {
                return false;
            }
            String block = blockOf(result.getItemId());
            if (block != null) {
                if (block.equals(type.getId())) {
                    return false; // AE2: nichts schmelzen, was den Block gar nicht ändert
                }
                if (smeltedBlock == null) {
                    smeltedBlock = block;
                    continue;
                }
            }
            smelted.add(result);
        }
        if (smeltedBlock == null && smelted.isEmpty()) {
            return false; // der Block lässt nichts fallen
        }
        boolean changed = smeltedBlock == null ? edits.breakBlock(x, y, z, false) : edits.placeBlock(x, y, z, smeltedBlock, 0);
        if (!changed) {
            return false;
        }
        Feedback.sound(edits.world, "SFX_Flame_Ignite", x + 0.5, y + 0.5, z + 0.5);
        Feedback.particle(edits.world, "Impact_Fire", x + 0.5, y + 0.5, z + 0.5);
        Drops.at(edits.world, x, y, z, smelted);
        return true;
    }

    /** Block-Id, die ein Item setzt, oder null, wenn es kein Block ist. */
    @Nullable
    private static String blockOf(String itemId) {
        Item item = Item.getAssetMap().getAsset(itemId);
        return item != null && item.hasBlockType() ? item.getBlockId() : null;
    }

    /**
     * Was aus einem Stück dieses Items beim Schmelzen wird, oder null. AE2 fragt Minecrafts Schmelzrezepte und nimmt je
     * fallen gelassenem Stapel ein Ergebnis ({@code recipe.assemble}). Hier, der Reihe nach:
     * <ol>
     *   <li>ein Block, den der Manipulator selbst erhitzen kann (Bruchstein → Stein), wird zu dessen Ergebnis;</li>
     *   <li>ein Rezept von Hytales Ofen mit genau einer Zutat: Menge 1 (Erz → Barren), oder – wenn dabei ein Block
     *       entsteht – auch eine größere Menge (Hytales Ofen brennt Gestein 2:1; an Ort und Stelle wird wie in AE2
     *       aus einem Block ein Block).</li>
     * </ol>
     */
    @Nullable
    static ItemStack smeltingResult(@Nullable EntropyRecipes all, String itemId) {
        String block = blockOf(itemId);
        if (all != null && block != null) {
            EntropyRecipes.Recipe heat = all.find(Mode.HEAT, block, null);
            String heated = heat == null || heat.inputBlock() == null ? null : heat.placedBlock();
            BlockType heatedType = heated == null ? null : BlockType.getAssetMap().getAsset(heated);
            Item heatedItem = heatedType == null ? null : heatedType.getItem();
            if (heatedItem != null) {
                return new ItemStack(heatedItem.getId(), 1);
            }
        }
        Item item = Item.getAssetMap().getAsset(itemId);
        for (CraftingRecipe r : CraftingRecipe.getAssetMap().getAssetMap().values()) {
            MaterialQuantity[] inputs = r.getInput();
            MaterialQuantity output = r.getPrimaryOutput();
            if (inputs == null || inputs.length != 1 || output == null || output.getItemId() == null || !isFurnace(r)
                    || !accepts(inputs[0], itemId, item)) {
                continue;
            }
            if (blockOf(output.getItemId()) != null) {
                return new ItemStack(output.getItemId(), 1);
            }
            if (inputs[0].getQuantity() == 1) {
                return new ItemStack(output.getItemId(), Math.max(1, output.getQuantity()));
            }
        }
        return null;
    }

    /** Nimmt die Zutat dieses Item (direkt oder über seinen Ressourcentyp)? */
    private static boolean accepts(MaterialQuantity input, String itemId, @Nullable Item item) {
        if (input.getItemId() != null) {
            return input.getItemId().equals(itemId);
        }
        if (input.getResourceTypeId() == null || item == null || item.getResourceTypes() == null || input.isItemExcluded(itemId)) {
            return false;
        }
        for (ItemResourceType t : item.getResourceTypes()) {
            if (t != null && input.getResourceTypeId().equals(t.id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFurnace(CraftingRecipe r) {
        BenchRequirement[] benches = r.getBenchRequirement();
        if (benches == null) {
            return false;
        }
        for (BenchRequirement bench : benches) {
            if (bench != null && bench.type == BenchType.Processing && FURNACE.equals(bench.id)) {
                return true;
            }
        }
        return false;
    }

    /** AE2: {@code applyFlintAndSteelEffect} – Feuer in der freien Zelle vor der angeklickten Seite. */
    private static boolean lightFire(WorldEdits edits, Target t) {
        Vector3i at = t.side();
        if (at == null || Math.abs(at.x - t.x()) + Math.abs(at.y - t.y()) + Math.abs(at.z - t.z()) != 1) {
            return false;
        }
        World world = edits.world;
        if (BlockAccess.blockType(world, at.x, at.y, at.z) != null || WorldEdits.fluidKey(world, at.x, at.y, at.z) != null
                || WorldEdits.section(world, at.x, at.y, at.z) == null || !edits.mayModify(at.x, at.y, at.z)) {
            return false;
        }
        if (!edits.setFluid(at.x, at.y, at.z, FIRE)) {
            return false;
        }
        Feedback.sound(world, "SFX_Flame_Ignite", at.x + 0.5, at.y + 0.5, at.z + 0.5);
        return true;
    }
}
