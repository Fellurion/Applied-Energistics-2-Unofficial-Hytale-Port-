/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.recipes.transform.TransformLogic#tryTransform, appeng.mixins.ItemEntityMixin#handleExplosion,
 * appeng.recipes.transform.TransformRecipe#createResult).
 */
package de.fellurion.ae2.hytale.transform;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.ExplodeInteraction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.ItemKeys;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Umwandlungen durch Explosionen wie in AE2: Liegen eine Singularität und Ender Dust beieinander und werden von
 * einer Explosion erfasst, entstehen daraus zwei verschränkte Singularitäten mit gemeinsamer Frequenz (Rezepte mit
 * {@code "circumstance": "explosion"} in {@code ae2data/transform.json}).
 *
 * <p><b>Ablauf (AE2: {@code ItemEntityMixin.handleExplosion}):</b> Trifft eine Explosion einen am Boden liegenden
 * Gegenstand, der Zutat eines Explosions-Rezepts ist, werden die Gegenstände im Kasten ±1 Block um ihn geprüft;
 * liegen alle Zutaten da, wird je eine verbraucht und das Ergebnis erscheint an seiner Stelle.
 *
 * <p>Zwei Auslöser: Tiny TNT ruft {@link #transformAround} für alles in seiner Reichweite auf. Für jede andere
 * Explosion des Spiels (Fässer, Bomben) meldet Hytale den Schaden je getroffenem Gegenstand als {@link Damage}; das
 * fängt {@link ItemBlast} ab. Hytale trifft dabei nur Gegenstände, die auf einer seiner Sichtlinien vom Zentrum
 * liegen – bei fremden Explosionen kann ein Gegenstand also verschont bleiben.
 *
 * <p>Abweichung: Die Frequenz steht in den Item-Metadaten ({@link EntangledSingularity#KEY}); ein Stapel mit zwei
 * Singularitäten ist ein Paar.
 */
public final class ExplosionTransforms {

    /** AE2: Kasten ±1 Block um den getroffenen Gegenstand. */
    static final double RANGE = 1.0;

    @Nullable
    private static volatile TransformRecipes recipes;

    private ExplosionTransforms() {
    }

    /** Einmal beim Plugin-Start, mit den gelesenen Rezepten. */
    static void setup(JavaPlugin plugin, TransformRecipes loaded) {
        recipes = loaded;
        EntangledSingularity.setup();
        try {
            plugin.getEntityStoreRegistry().registerSystem(new ItemBlast(plugin.getLogger()));
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().at(Level.WARNING).withCause(e)
                    .log("AE2: explosion transforms only work with Tiny TNT (damage hook is not available)");
        }
    }

    /** Gesetzt, solange eine Explosion läuft, deren Gegenstände schon mit {@link #transformAround} behandelt sind. */
    private static final ThreadLocal<Boolean> HANDLED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Führt eine Explosion aus, deren Gegenstände der Aufrufer schon selbst umgewandelt hat ({@link #transformAround}):
     * der Schaden, den sie an Gegenständen meldet, löst dann keine zweite Umwandlung aus (AE2: je Explosion und
     * Gegenstand höchstens eine).
     */
    public static void alreadyHandled(Runnable explosion) {
        HANDLED.set(Boolean.TRUE);
        try {
            explosion.run();
        } finally {
            HANDLED.set(Boolean.FALSE);
        }
    }

    /** Wie die Änderungen an den Gegenständen ausgeführt werden (direkt im Store oder über einen CommandBuffer). */
    private interface Changes {
        void remove(Ref<EntityStore> ref);

        void add(Holder<EntityStore> holder);
    }

    /** Ein Gegenstand in Reichweite. */
    private record Nearby(Ref<EntityStore> ref, ItemComponent item, ItemStack stack) {
    }

    /**
     * Wandelt alles um, was im Kasten ±{@code range} um {@code center} liegt und zu einem Explosions-Rezept passt.
     * Nur zwischen zwei Ticks aufrufen ({@code world.execute}), nicht aus einem System heraus.
     *
     * @return wie viele Umwandlungen stattgefunden haben
     */
    public static int transformAround(World world, Vector3d center, double range) {
        TransformRecipes r = recipes;
        if (r == null) {
            return 0;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Changes direct = new Changes() {
            @Override
            public void remove(Ref<EntityStore> ref) {
                store.removeEntity(ref, RemoveReason.REMOVE);
            }

            @Override
            public void add(Holder<EntityStore> holder) {
                store.addEntity(holder, AddReason.SPAWN);
            }
        };
        int done = 0;
        // Kopie: die Liste des Spatial-Index gehört dem Thread und wird beim Suchen der Nachbarn wiederverwendet
        for (Ref<EntityStore> ref : new ArrayList<>(itemsInBox(store, center, range))) {
            // AE2: jeder getroffene Gegenstand löst höchstens eine Umwandlung aus – eine Explosion macht aus zwei
            // Stapeln also höchstens zwei Paare
            if (ref.isValid() && tryTransform(r, store, ref, direct)) {
                done++;
            }
        }
        return done;
    }

    /**
     * Versucht eine Umwandlung mit {@code trigger} als getroffenem Gegenstand.
     *
     * @return true, wenn ein Rezept gepasst hat (Zutaten verbraucht, Ergebnis erzeugt)
     */
    private static boolean tryTransform(TransformRecipes recipes, Store<EntityStore> store, Ref<EntityStore> trigger, Changes changes) {
        ItemComponent own = store.getComponent(trigger, ItemComponent.getComponentType());
        TransformComponent transform = store.getComponent(trigger, TransformComponent.getComponentType());
        ItemStack stack = own == null ? null : own.getItemStack();
        // Billige Vorprüfung wie AE2 (canTransformInExplosion): nur Zutaten gehen weiter
        if (transform == null || ItemStack.isEmpty(stack) || !recipes.isExplosionTrigger(stack.getItemId())
                || store.getArchetype(trigger).contains(Interactable.getComponentType())) {
            return false;
        }
        Vector3d pos = new Vector3d(transform.getPosition());
        List<Nearby> nearby = new ArrayList<>();
        nearby.add(new Nearby(trigger, own, stack));
        for (Ref<EntityStore> ref : itemsInBox(store, pos, RANGE)) {
            if (ref == trigger || !ref.isValid()) {
                continue;
            }
            ItemComponent other = store.getComponent(ref, ItemComponent.getComponentType());
            // Stapel null = in diesem Durchlauf schon verbraucht
            if (other == null || ItemStack.isEmpty(other.getItemStack())
                    || store.getArchetype(ref).contains(Interactable.getComponentType())) {
                continue;
            }
            nearby.add(new Nearby(ref, other, other.getItemStack()));
        }
        List<TransformRecipes.Pile> piles = new ArrayList<>(nearby.size());
        for (Nearby n : nearby) {
            piles.add(new TransformRecipes.Pile(n.stack().getItemId(), n.stack().getQuantity()));
        }
        TransformRecipes.Match match = recipes.matchExplosion(piles);
        if (match == null) {
            return false;
        }
        for (int i = 0; i < nearby.size(); i++) {
            int used = match.consumed()[i];
            if (used <= 0) {
                continue;
            }
            Nearby n = nearby.get(i);
            int left = n.stack().getQuantity() - used;
            if (left <= 0) {
                // erst den Stapel leeren, dann die Entity entfernen: niemand verbraucht ihn ein zweites Mal
                n.item().setItemStack(null);
                changes.remove(n.ref());
            } else {
                n.item().setItemStack(n.stack().withQuantity(left));
            }
        }
        spawn(store, result(match.recipe()), pos, changes);
        return true;
    }

    /** Das Ergebnis eines Rezepts; verschränkte Singularitäten bekommen ihre gemeinsame Frequenz (AE2: createResult). */
    static ItemStack result(TransformRecipes.Recipe recipe) {
        ItemStack out = new ItemStack(recipe.output().itemId(), recipe.output().quantity());
        if (EntangledSingularity.ITEM.equals(out.getItemId()) && out.getQuantity() > 1) {
            out = EntangledSingularity.entangle(out);
        }
        return out;
    }

    /** AE2: das Ergebnis erscheint im Block des getroffenen Gegenstands, mit leichtem Schwung. */
    private static void spawn(Store<EntityStore> store, ItemStack result, Vector3d pos, Changes changes) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int max = Math.max(1, ItemKeys.maxStack(result.getItemId()));
        int remaining = result.getQuantity();
        while (remaining > 0) {
            int n = Math.min(remaining, max);
            remaining -= n;
            Vector3d at = new Vector3d(Math.floor(pos.x) + 0.25 + random.nextDouble() * 0.5, pos.y + 0.25,
                    Math.floor(pos.z) + 0.25 + random.nextDouble() * 0.5);
            Holder<EntityStore> holder = ItemComponent.generateItemDrop(store, result.withQuantity(n), at, Rotation3f.IDENTITY,
                    (float) random.nextDouble(-0.5, 0.5), 2.0f, (float) random.nextDouble(-0.5, 0.5));
            if (holder != null) {
                changes.add(holder);
            }
        }
    }

    /** Gegenstände am Boden im Kasten ±{@code range} um {@code center} (die Liste gehört dem Thread). */
    private static List<Ref<EntityStore>> itemsInBox(Store<EntityStore> store, Vector3d center, double range) {
        List<Ref<EntityStore>> refs = SpatialResource.getThreadLocalReferenceList();
        SpatialResource<Ref<EntityStore>, EntityStore> spatial =
                store.getResource(EntityModule.get().getItemSpatialResourceType());
        if (spatial != null) {
            spatial.getSpatialStructure().collectBox(new Vector3d(center.x - range, center.y - range, center.z - range),
                    new Vector3d(center.x + range, center.y + range, center.z + range), refs);
        }
        return refs;
    }

    // ------------------------------------------------------------------ Explosionen des Spiels

    /** Schaden einer Explosion an einem Gegenstand am Boden: umwandeln statt beschädigen. */
    static final class ItemBlast extends DamageEventSystem {

        private final HytaleLogger logger;
        private final Query<EntityStore> query = Query.and(ItemComponent.getComponentType(), TransformComponent.getComponentType());
        private long lastErrorLog;

        ItemBlast(HytaleLogger logger) {
            this.logger = logger;
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
            TransformRecipes r = recipes;
            // Explosionen von Blöcken melden sich mit Hytales Explosions-Quelle, die von Geschossen als Geschoss
            if (r == null || HANDLED.get() || !(damage.getSource() == ExplodeInteraction.DAMAGE_SOURCE_EXPLOSION
                    || damage.getSource() instanceof Damage.ProjectileSource)) {
                return;
            }
            ItemComponent item = chunk.getComponent(index, ItemComponent.getComponentType());
            ItemStack stack = item == null ? null : item.getItemStack();
            if (ItemStack.isEmpty(stack) || !r.isExplosionTrigger(stack.getItemId())) {
                return;
            }
            try {
                boolean changed = tryTransform(r, store, chunk.getReferenceTo(index), new Changes() {
                    @Override
                    public void remove(Ref<EntityStore> ref) {
                        commandBuffer.removeEntity(ref, RemoveReason.REMOVE);
                    }

                    @Override
                    public void add(Holder<EntityStore> holder) {
                        commandBuffer.addEntity(holder, AddReason.SPAWN);
                    }
                });
                if (changed) {
                    damage.setCancelled(true); // AE2: der Gegenstand nimmt keinen Schaden
                }
            } catch (RuntimeException e) {
                // Eine Umwandlung darf die Explosion nie stören
                long now = System.currentTimeMillis();
                if (now - lastErrorLog > 60_000L) {
                    lastErrorLog = now;
                    logger.at(Level.WARNING).withCause(e).log("AE2: explosion transform failed");
                }
            }
        }
    }
}
