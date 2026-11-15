/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.items.tools.powered.ChargedStaffItem#hurtEnemy, EntropyManipulatorItem#hurtEnemy).
 */
package de.fellurion.ae2.hytale.gadgets;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCalculatorSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.items.PoweredItems;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Treffer mit einem geladenen AE2-Werkzeug in der Hand (AE2: {@code hurtEnemy}):
 * <ul>
 *   <li><b>Charged Staff</b>: {@value #STAFF_DAMAGE} Schaden zusätzlich für {@value #STAFF_ENERGY} AE je Treffer.</li>
 *   <li><b>Entropy Manipulator</b>: setzt das Ziel {@value #BURN_SECONDS} Sekunden in Brand, für
 *       {@value EntropyManipulator#ENERGY_PER_USE} AE.</li>
 * </ul>
 *
 * <p>Der Schlag selbst bleibt Hytales normaler Angriff der Waffe (Animation, Kombo, Grundschaden). Dieses System
 * hängt sich an den Schaden, den er auslöst: nach allen Filtern (Rüstung, Blocken, PvP- und Schutzregeln), vor dem
 * Abziehen der Lebenspunkte. Der Zusatzschaden umgeht damit die Rüstung – wie AE2s Magieschaden – und entfällt, wenn
 * der Treffer geblockt oder verboten wurde.
 *
 * <p>Abweichung: Der Stab ist in Hytale eine Waffe mit eigenem (kleinem) Grundschaden. Ohne genug Ladung trifft er
 * nur damit; der Spieler bekommt dann einen Hinweis, aber nicht bei jedem Schlag.
 */
final class MeleeHits extends DamageEventSystem {

    static final String STAFF = "AE2_Charged_Staff";
    /** AE2: {@code extractAEPower(item, 300)}. */
    static final int STAFF_ENERGY = 300;
    /** AE2: {@code target.hurt(magic, 6)}. */
    static final float STAFF_DAMAGE = 6;
    /** AE2: {@code target.igniteForSeconds(8)}. */
    static final float BURN_SECONDS = 8;
    private static final String BURN_EFFECT = "Burn";

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemGroupDependency<>(Order.AFTER, DamageModule.get().getGatherDamageGroup()),
            new SystemGroupDependency<>(Order.AFTER, DamageModule.get().getFilterDamageGroup()),
            new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class));

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    /** Alles, was Lebenspunkte hat. */
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return EntityStatMap.getComponentType();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
        // Nur der Nahkampftreffer selbst: Quelle ist das Wesen (kein Geschoss, kein Effekt), der Schaden kommt aus
        // einer Angriffs-Interaktion (die hängt genau einem Schaden je Treffer die Schlagfolge an) und kam durch
        if (damage.getAmount() <= 0 || damage.getSource().getClass() != Damage.EntitySource.class
                || damage.getIfPresentMetaObject(DamageCalculatorSystems.DAMAGE_SEQUENCE) == null
                || Boolean.TRUE.equals(damage.getIfPresentMetaObject(Damage.BLOCKED))) {
            return;
        }
        Ref<EntityStore> attacker = ((Damage.EntitySource) damage.getSource()).getRef();
        if (!attacker.isValid()) {
            return;
        }
        InventoryComponent.Hotbar hotbar = commandBuffer.getComponent(attacker, InventoryComponent.Hotbar.getComponentType());
        ItemStack held = hotbar == null ? null : hotbar.getActiveItem();
        if (ItemStack.isEmpty(held)) {
            return;
        }
        String id = held.getItemId();
        if (STAFF.equals(id)) {
            // AE2: getAECurrentPower(item) > 300
            if (!(PoweredItems.stored(held) > STAFF_ENERGY)) {
                tellEmpty(commandBuffer, attacker, STAFF_ENERGY);
            } else if (spend(hotbar, held, STAFF_ENERGY)) {
                damage.setAmount(damage.getAmount() + STAFF_DAMAGE);
                spark(store, chunk, index);
            }
        } else if (EntropyManipulator.ITEM.equals(id)) {
            // AE2: getAECurrentPower(item) > ENERGY_PER_USE
            if (!(PoweredItems.stored(held) > EntropyManipulator.ENERGY_PER_USE)) {
                tellEmpty(commandBuffer, attacker, EntropyManipulator.ENERGY_PER_USE);
            } else if (burn(chunk.getReferenceTo(index), attacker, commandBuffer)) {
                spend(hotbar, held, EntropyManipulator.ENERGY_PER_USE);
            }
        }
    }

    /** Der Akku ist leer: der Schlag trifft nur mit dem Grundschaden; gesagt wird das nicht bei jedem Schlag. */
    private static void tellEmpty(CommandBuffer<EntityStore> commandBuffer, Ref<EntityStore> attacker, double needed) {
        PlayerRef player = commandBuffer.getComponent(attacker, PlayerRef.getComponentType());
        if (player != null) {
            Feedback.noPowerOnceInAWhile(player, needed);
        }
    }

    /** Zieht die Energie ab und schreibt das Werkzeug an seinen Platz zurück. */
    private static boolean spend(InventoryComponent.Hotbar hotbar, ItemStack held, double ae) {
        ItemStack used = PoweredItems.use(held, ae);
        ItemContainer container = hotbar.getInventory();
        byte slot = hotbar.getActiveSlot();
        if (used == null || container == null || slot < 0 || slot >= container.getCapacity()) {
            return false;
        }
        return container.setItemStackForSlot(slot, used).succeeded();
    }

    private static boolean burn(Ref<EntityStore> target, Ref<EntityStore> attacker, CommandBuffer<EntityStore> commandBuffer) {
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(BURN_EFFECT);
        EffectControllerComponent effects = commandBuffer.getComponent(target, EffectControllerComponent.getComponentType());
        return effect != null && effects != null
                && effects.addEffect(target, effect, BURN_SECONDS, OverlapBehavior.OVERWRITE, commandBuffer, attacker);
    }

    /** AE2: zwei Blitz-Partikel am Ziel. */
    private static void spark(Store<EntityStore> store, ArchetypeChunk<EntityStore> chunk, int index) {
        TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
        World world = store.getExternalData().getWorld();
        if (transform == null || world == null) {
            return;
        }
        Vector3d p = transform.getPosition();
        Feedback.particle(world, "Lightning", p.x, p.y + 1.0, p.z);
        Feedback.sound(world, "SFX_Emit_Goblin_Electric_Fence_Zap", p.x, p.y + 1.0, p.z);
    }
}
