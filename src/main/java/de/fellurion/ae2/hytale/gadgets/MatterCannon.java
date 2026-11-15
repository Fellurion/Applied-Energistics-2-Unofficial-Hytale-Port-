/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.tools.powered.MatterCannonItem).
 */
package de.fellurion.ae2.hytale.gadgets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemToolSpec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.tools.MatterCannonAmmo;
import de.fellurion.ae2.core.tools.MatterCannonLogic;
import de.fellurion.ae2.core.tools.PaintItems;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.items.ItemTooltips;
import de.fellurion.ae2.hytale.items.ItemUpgrades;
import de.fellurion.ae2.hytale.items.ItemUseContext;
import de.fellurion.ae2.hytale.items.PoweredItems;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Matter Cannon: Rechtsklick feuert ein Stück der geladenen Munition entlang der Blickrichtung
 * ({@value MatterCannonLogic#ENERGY_PER_SHOT} AE je Schuss). Der Schuss trifft das erste Wesen oder den ersten
 * festen Block; mit dem „Gewicht“ der Munition (Rezepte {@code ae2data/matter_cannon.json}) durchschlägt er Blöcke
 * und getötete Wesen, bis seine Durchschlagskraft verbraucht ist. Farbkugeln als Munition färben, was sie treffen.
 *
 * <p>Geduckt Rechtsklick lädt passende Munition aus dem Inventar (höchstens {@value MatterCannonLogic#AMMO_CAPACITY}
 * Stück einer Sorte); liegt keine im Inventar, wird die Kanone ins Inventar entladen.
 *
 * <p>Blöcke werden über Hytales Abbauen für den Spieler zerstört ({@link WorldEdits}), Schaden läuft über Hytales
 * Schadenssystem mit dem Spieler als Quelle. Der Schalter {@code matterCannonBlockDamage} ({@link GadgetConfig})
 * verbietet Blockschaden.
 *
 * <p>Abweichungen von AE2: Die Kanone ist keine Speicherzelle – die Munition steht in den Item-Metadaten und wird
 * aus dem Inventar geladen statt über eine ME Chest. Die Härte eines Blocks ergibt sich aus seiner Abbau-Gruppe
 * ({@link MatterCannonLogic#hardnessFromGatherPower}). Farbkugeln färben nur AE2-Kabel (es gibt keinen
 * Farbklecks-Block); auf Wesen haben sie keine Wirkung. Der Lichtstrahl ist ein Partikel am Einschlag.
 */
final class MatterCannon {

    static final String ITEM = "AE2_Matter_Cannon";
    /** Metadaten: Item-Id der geladenen Munition. */
    static final String AMMO_KEY = "AE2CannonAmmo";
    /** Metadaten: Stückzahl der geladenen Munition. */
    static final String COUNT_KEY = "AE2CannonAmmoCount";
    private static final String SPEED_CARD = "AE2_Speed_Card";
    private static final String DAMAGE_CAUSE = "Projectile";
    /** Obergrenze der Durchschläge je Schuss (die Durchschlagskraft endet weit vorher). */
    private static final int MAX_STEPS = 48;

    @Nullable
    private static volatile MatterCannonAmmo ammo;

    private MatterCannon() {
    }

    static void setup(MatterCannonAmmo loaded) {
        ammo = loaded;
        ItemTooltips.addSection(MatterCannon::tooltip);
    }

    @Nullable
    static MatterCannonAmmo ammo() {
        return ammo;
    }

    // ------------------------------------------------------------------ Munition im Item

    @Nullable
    static String ammoItem(ItemStack cannon) {
        return ammoCount(cannon) <= 0 ? null : cannon.getFromMetadataOrNull(AMMO_KEY, Codec.STRING);
    }

    static int ammoCount(ItemStack cannon) {
        Integer n = cannon.getFromMetadataOrNull(COUNT_KEY, Codec.INTEGER);
        return n == null || cannon.getFromMetadataOrNull(AMMO_KEY, Codec.STRING) == null ? 0 : Math.max(0, n);
    }

    static ItemStack withAmmo(ItemStack cannon, @Nullable String itemId, int count) {
        boolean none = itemId == null || count <= 0;
        ItemStack out = cannon.withMetadata(AMMO_KEY, Codec.STRING, none ? null : itemId)
                .withMetadata(COUNT_KEY, Codec.INTEGER, none ? null : count);
        return ItemTooltips.refresh(out);
    }

    /** AE2: {@code isBlackListed} – Munition mit Gewicht oder eine Farbkugel. */
    static boolean isAmmo(String itemId) {
        MatterCannonAmmo a = ammo;
        return (a != null && a.penetration(itemId) > 0) || PaintItems.paintBallColor(itemId) != null;
    }

    @Nullable
    private static Message tooltip(ItemStack stack) {
        if (!ITEM.equals(stack.getItemId())) {
            return null;
        }
        String id = ammoItem(stack);
        Item item = id == null ? null : Item.getAssetMap().getAsset(id);
        if (item == null) {
            return Message.translation("ae2gadget.cannon.tooltip.empty");
        }
        return Message.translation("ae2gadget.cannon.tooltip.ammo").param("count", Integer.toString(ammoCount(stack)))
                .param("item", item.getTranslationMessage());
    }

    // ------------------------------------------------------------------ Klick

    static void use(ItemUseContext ctx) {
        if (ctx.primary()) {
            return;
        }
        if (ctx.crouching()) {
            reload(ctx);
        } else {
            fire(ctx);
        }
    }

    /** Geduckt: Munition aus dem Inventar laden; ist dort keine, die Kanone entladen. */
    private static void reload(ItemUseContext ctx) {
        ItemStack held = ctx.held();
        String loaded = ammoItem(held);
        int count = ammoCount(held);
        ItemContainer heldContainer = ctx.interaction().getHeldItemContainer();
        short heldSlot = ctx.interaction().getHeldItemSlot();
        // die erste Munition im Inventar bestimmt die Sorte einer leeren Kanone
        String[] type = {loaded};
        int[] room = {MatterCannonLogic.AMMO_CAPACITY - count};
        boolean[] found = {false};
        List<ItemStack> taken = PlayerItems.take(PlayerItems.containers(ctx.commandBuffer(), ctx.playerEntity()), heldContainer,
                heldSlot, stack -> {
                    if (type[0] == null ? !isAmmo(stack.getItemId()) : !type[0].equals(stack.getItemId())) {
                        return 0;
                    }
                    type[0] = stack.getItemId();
                    found[0] = true;
                    int n = Math.min(room[0], stack.getQuantity());
                    room[0] -= n;
                    return n;
                });
        int moved = 0;
        for (ItemStack s : taken) {
            moved += s.getQuantity();
        }
        if (moved > 0) {
            if (!ctx.setHeld(withAmmo(held, type[0], count + moved))) {
                PlayerItems.give(ctx.commandBuffer(), ctx.playerEntity(), type[0], moved); // Kanone ist weg: zurück damit
                return;
            }
            Feedback.tell(ctx.playerRef(), Message.translation("ae2gadget.cannon.loaded").param("count", Integer.toString(moved))
                    .param("total", Integer.toString(count + moved)).param("item", name(type[0])), NotificationStyle.Success);
        } else if (found[0]) {
            Feedback.tell(ctx.playerRef(), "ae2gadget.cannon.full");
        } else if (loaded != null && count > 0) {
            // nichts Passendes im Inventar: entladen – erst aus der Kanone, dann ins Inventar
            if (ctx.setHeld(withAmmo(held, null, 0))) {
                PlayerItems.give(ctx.commandBuffer(), ctx.playerEntity(), loaded, count);
                Feedback.tell(ctx.playerRef(), Message.translation("ae2gadget.cannon.unloaded").param("count", Integer.toString(count))
                        .param("item", name(loaded)), NotificationStyle.Default);
            }
        } else {
            Feedback.tell(ctx.playerRef(), "ae2gadget.cannon.noAmmoInInventory");
        }
    }

    private static Message name(String itemId) {
        Item item = Item.getAssetMap().getAsset(itemId);
        return item == null ? Message.raw(itemId) : item.getTranslationMessage();
    }

    /** AE2: {@code fireCannon}. */
    private static void fire(ItemUseContext ctx) {
        ItemStack held = ctx.held();
        String ammoId = ammoItem(held);
        int count = ammoCount(held);
        MatterCannonAmmo weights = ammo;
        if (ammoId == null || count <= 0 || weights == null) {
            Feedback.tell(ctx.playerRef(), "ae2gadget.cannon.noAmmo"); // AE2: PlayerMessages.AmmoDepleted
            return;
        }
        int shotPower = MatterCannonLogic.shotPower(ItemUpgrades.count(held, SPEED_CARD), count, PoweredItems.stored(held));
        if (shotPower <= 0) {
            Feedback.noPower(ctx.playerRef(), MatterCannonLogic.ENERGY_PER_SHOT);
            return;
        }
        Transform look = Aim.look(ctx);
        if (look == null) {
            return;
        }
        // erst bezahlen (Energie und ein Stück Munition), dann schießen
        ItemStack paid = PoweredItems.use(held, MatterCannonLogic.energyFor(shotPower));
        if (paid == null || !ctx.setHeld(withAmmo(paid, ammoId, count - 1))) {
            return;
        }
        Vector3d from = new Vector3d(look.getPosition());
        Vector3d direction = new Vector3d(look.getDirection()).normalize();
        Feedback.sound(ctx.world(), "SFX_Rifle_Fire", from.x, from.y, from.z);
        WorldEdits edits = WorldEdits.forPlayer(ctx.world(), ctx.commandBuffer(), ctx.playerEntity(), held,
                ctx.interaction().getHeldItemContainer(), ctx.interaction().getHeldItemSlot());
        float penetration = weights.penetration(ammoId) * shotPower;
        if (penetration <= 0) {
            AEColor color = PaintItems.paintBallColor(ammoId);
            if (color != null) {
                shootPaint(ctx, edits, color, from, direction);
            }
            return;
        }
        standardAmmo(ctx.world(), ctx.commandBuffer(), ctx.playerEntity(), edits, penetration, from, direction);
    }

    // ------------------------------------------------------------------ Schuss

    /** Was der Schuss als Erstes trifft. */
    private record Impact(@Nullable Ref<EntityStore> entity, @Nullable WorldRay.Hit block, double distance) {
    }

    /**
     * AE2: {@code standardAmmo} – solange etwas zerstört wurde und Durchschlagskraft übrig ist, geht der Schuss weiter.
     *
     * @param shooter wer schießt (Quelle des Schadens), null für Testbefehle der Konsole
     * @return wie viele Blöcke zerstört wurden
     */
    static int standardAmmo(World world, ComponentAccessor<EntityStore> entities, @Nullable Ref<EntityStore> shooter,
                            WorldEdits edits, float penetration, Vector3d from, Vector3d direction) {
        int destroyed = 0;
        boolean hasDestroyed = true;
        int steps = 0;
        while (penetration > 0 && hasDestroyed && steps++ < MAX_STEPS) {
            hasDestroyed = false;
            Impact impact = trace(world, entities, shooter, from, direction);
            if (impact == null) {
                break;
            }
            Vector3d at = new Vector3d(direction).mul(impact.distance()).add(from);
            Feedback.particle(world, "RifleShooting_Impact", at.x, at.y, at.z);
            if (impact.entity() != null) {
                Ref<EntityStore> target = impact.entity();
                int damage = MatterCannonLogic.damageFromPenetration(penetration);
                penetration = MatterCannonLogic.afterEntity(penetration);
                hasDestroyed = hurt(entities, shooter, target, damage);
            } else if (impact.block() != null) {
                if (!GadgetConfig.matterCannonBlockDamage()) {
                    break; // AE2: penetration = 0
                }
                WorldRay.Hit b = impact.block();
                float hardness = hardness(BlockAccess.blockType(world, b.x(), b.y(), b.z()));
                if (MatterCannonLogic.breaks(penetration, hardness) && edits.breakBlock(b.x(), b.y(), b.z(), true)) {
                    hasDestroyed = true;
                    destroyed++;
                    penetration = MatterCannonLogic.afterBlock(penetration, hardness);
                }
            }
        }
        return destroyed;
    }

    /** Das Nächste auf dem Strahl: ein Wesen (Kasten wie AE2 um 0,3 erweitert) oder ein fester Block. */
    @Nullable
    private static Impact trace(World world, ComponentAccessor<EntityStore> entities, @Nullable Ref<EntityStore> shooter,
                                Vector3d from, Vector3d direction) {
        WorldRay.Hit block = WorldRay.cast(world, from, direction, MatterCannonLogic.RANGE, (id, fluid) -> isSolid(id));
        double reach = block == null ? MatterCannonLogic.RANGE : block.distance();
        Ref<EntityStore> nearest = null;
        double nearestDistance = reach;
        // Kugel um die Mitte des Strahls; die Liste gehört dem Thread, deshalb erst kopieren
        Vector3d mid = new Vector3d(direction).mul(reach / 2).add(from);
        List<Ref<EntityStore>> candidates = new ArrayList<>(TargetUtil.getAllEntitiesInSphere(mid, reach / 2 + 4, entities));
        double m = MatterCannonLogic.ENTITY_MARGIN;
        for (Ref<EntityStore> ref : candidates) {
            if (ref == null || !ref.isValid() || ref.equals(shooter)) {
                continue;
            }
            BoundingBox bounds = entities.getComponent(ref, BoundingBox.getComponentType());
            TransformComponent transform = entities.getComponent(ref, TransformComponent.getComponentType());
            if (bounds == null || transform == null || !isAlive(entities, ref)) {
                continue;
            }
            Box box = bounds.getBoundingBox();
            Vector3d p = transform.getPosition();
            double minX = p.x + box.getMin().x - m, minY = p.y + box.getMin().y - m, minZ = p.z + box.getMin().z - m;
            double maxX = p.x + box.getMax().x + m, maxY = p.y + box.getMax().y + m, maxZ = p.z + box.getMax().z + m;
            if (from.x >= minX && from.x <= maxX && from.y >= minY && from.y <= maxY && from.z >= minZ && from.z <= maxZ) {
                continue; // der Schütze steckt darin (Reittier): AE2 „do not shoot your horse“
            }
            double d = MatterCannonLogic.rayBox(from.x, from.y, from.z, direction.x, direction.y, direction.z, minX, minY, minZ,
                    maxX, maxY, maxZ, nearestDistance);
            if (d >= 0 && d < nearestDistance) {
                nearestDistance = d;
                nearest = ref;
            }
        }
        if (nearest != null) {
            return new Impact(nearest, null, nearestDistance);
        }
        return block == null ? null : new Impact(null, block, block.distance());
    }

    /** AE2 prüft den Kollisionskörper: nur feste Blöcke halten den Schuss auf, Gras und Blumen nicht. */
    private static boolean isSolid(int blockId) {
        if (blockId <= 1) {
            return false; // Luft oder unbekannt
        }
        BlockType type = BlockType.getAssetMap().getAsset(blockId);
        return type != null && type.getMaterial() == BlockMaterial.Solid;
    }

    private static boolean isAlive(ComponentAccessor<EntityStore> entities, Ref<EntityStore> ref) {
        EntityStatMap stats = entities.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return false; // kein Lebewesen (AE2 trifft nur, was Schaden nehmen kann)
        }
        EntityStatValue health = stats.get(DefaultEntityStatTypes.getHealth());
        return health != null && health.get() > health.getMin();
    }

    /**
     * Schaden mit dem Schützen als Quelle – Hytales Regeln (PvP, Schutz, Rüstung) entscheiden, was ankommt.
     *
     * @return true, wenn das Ziel danach tot ist (dann fliegt der Schuss weiter)
     */
    private static boolean hurt(ComponentAccessor<EntityStore> entities, @Nullable Ref<EntityStore> shooter, Ref<EntityStore> target,
                                int amount) {
        DamageCause cause = DamageCause.getAssetMap().getAsset(DAMAGE_CAUSE);
        if (cause == null || amount <= 0) {
            return false;
        }
        Damage damage = new Damage(shooter == null ? Damage.NULL_SOURCE : new Damage.EntitySource(shooter), cause, amount);
        DamageSystems.executeDamage(target, entities, damage);
        return !damage.isCancelled() && !isAlive(entities, target);
    }

    /**
     * Härte wie in Minecraft, abgeleitet aus der Abbau-Gruppe des Blocks: wie viel ein Schlag mit der bloßen Hand von
     * seiner Haltbarkeit nimmt. Ohne Abbau-Angaben ist der Block unzerstörbar.
     */
    static float hardness(@Nullable BlockType type) {
        BlockGathering gathering = type == null ? null : type.getGathering();
        if (gathering == null) {
            return -1;
        }
        BlockBreakingDropType breaking = gathering.getBreaking();
        if (breaking == null || breaking.getGatherType() == null) {
            return gathering.isSoft() ? 0 : -1;
        }
        ItemToolSpec hand = ItemToolSpec.getAssetMap().getAsset(breaking.getGatherType());
        return hand == null ? -1 : MatterCannonLogic.hardnessFromGatherPower(hand.getPower());
    }

    /** AE2: {@code shootPaintBalls} – hier: ein getroffenes Kabel bekommt die Farbe der Kugel. */
    private static void shootPaint(ItemUseContext ctx, WorldEdits edits, AEColor color, Vector3d from, Vector3d direction) {
        Impact impact = trace(ctx.world(), ctx.commandBuffer(), ctx.playerEntity(), from, direction);
        if (impact == null) {
            return;
        }
        Vector3d at = new Vector3d(direction).mul(impact.distance()).add(from);
        Feedback.particle(ctx.world(), "RifleShooting_Impact", at.x, at.y, at.z);
        if (impact.block() != null) {
            WorldRay.Hit b = impact.block();
            ColorApplicator.recolor(ctx.world(), edits, ctx.playerRef(), b.x(), b.y(), b.z(), color);
        }
    }
}
