/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.recipes.transform.TransformLogic, appeng.mixins.ItemEntityMixin).
 */
package de.fellurion.ae2.hytale.transform;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventItemMerging;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.ItemCatalog;
import de.fellurion.ae2.hytale.ItemKeys;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * In-Welt-Umwandlungen wie in AE2: Wer einen geladenen Certusquarzkristall zusammen mit roten und weißen
 * Kristallsplittern ins Wasser wirft, bekommt Fluix-Kristalle; mit einem Certusquarzblock bzw. beschädigtem
 * Budding-Quarz wird Budding-Quarz repariert (Rezepte: {@code ae2data/transform.json}, {@link TransformRecipes}).
 *
 * <p><b>Ablauf (AE2: {@code ItemEntityMixin.handleEntityTransform}):</b> Ein am Boden liegender Gegenstand, der die
 * erste Zutat eines Rezepts ist, muss {@value TransformSystem#DWELL_SECONDS} s (AE2: 60 Ticks) in der Flüssigkeit
 * liegen. Dann werden die Gegenstände im Kasten ±1 Block um ihn geprüft; liegen alle Zutaten da, wird je eine
 * verbraucht und das Ergebnis erscheint an seiner Stelle. Fehlt etwas, beginnt die Wartezeit neu.
 *
 * <p><b>Kosten:</b> Das System läuft alle {@value TransformSystem#INTERVAL_SECONDS} s über die Gegenstände am Boden
 * und prüft je Gegenstand nur, ob sein Item ein Auslöser ist (ein Set-Lookup). Flüssigkeit und Nachbarn werden erst
 * für Auslöser gelesen – ohne geladenen Certusquarz am Boden passiert also nichts weiter.
 *
 * <p>Umwandlungen durch Explosionen (verschränkte Singularität) übernimmt {@link ExplosionTransforms}.
 *
 * <p>Abweichung: AE2s Blitz-Partikel fehlen.
 */
public final class InWorldTransforms {

    private static volatile boolean installed;
    @Nullable
    private static volatile TransformRecipes recipes;

    private InWorldTransforms() {
    }

    /** Einmal beim Plugin-Start aufrufen (HytaleBridge.setup, nach {@code ItemCatalog.load()}). */
    public static synchronized void setup(JavaPlugin plugin) {
        if (installed) {
            return;
        }
        installed = true;
        HytaleLogger log = plugin.getLogger();
        TransformRecipes loaded;
        try {
            loaded = TransformRecipes.fromJson(ItemCatalog.resource("transform.json"));
        } catch (RuntimeException e) {
            log.at(Level.WARNING).withCause(e).log("AE2: transform.json is not readable, in-world transforms are off");
            return;
        }
        recipes = loaded;
        ExplosionTransforms.setup(plugin, loaded);
        try {
            plugin.getEntityStoreRegistry().registerSystem(new TransformSystem(loaded, log));
        } catch (RuntimeException | LinkageError e) {
            log.at(Level.WARNING).withCause(e).log("AE2: in-world transforms are not available");
            return;
        }
        long fluid = loaded.recipes().stream().filter(TransformRecipes.Recipe::isFluid).count();
        long explosion = loaded.recipes().stream().filter(TransformRecipes.Recipe::isExplosion).count();
        log.at(Level.INFO).log("AE2: in-world transforms enabled (%d recipes in water, %d by explosion)", fluid, explosion);
    }

    /** Die geladenen Rezepte; null, solange {@link #setup} nicht gelaufen ist. */
    @Nullable
    public static TransformRecipes recipes() {
        return recipes;
    }

    // ------------------------------------------------------------------ System

    /** Wartezeiten der Auslöser einer Welt (nicht gespeichert: nach einem Neustart beginnt die Wartezeit neu). */
    private static final class Dwell implements Resource<EntityStore> {
        /** Ref ist je Entity ein festes Objekt und vergleicht nach Identität. */
        private final Map<Ref<EntityStore>, Float> seconds = new IdentityHashMap<>();
        private float sinceSweep;

        @Nonnull
        @Override
        public Resource<EntityStore> clone() {
            Dwell copy = new Dwell();
            copy.seconds.putAll(seconds);
            copy.sinceSweep = sinceSweep;
            return copy;
        }
    }

    /** Ein Gegenstand in Reichweite. */
    private record Nearby(Ref<EntityStore> ref, ItemComponent item, ItemStack stack) {
    }

    static final class TransformSystem extends DelayedEntitySystem<EntityStore> {

        static final float INTERVAL_SECONDS = 0.5f;
        /** AE2: mehr als 60 Ticks in der Flüssigkeit. */
        static final float DWELL_SECONDS = 3.0f;
        /** AE2 wandelt nach der Wartezeit einmal je Tick um; hier höchstens so viele je Durchlauf (0,5 s = 10 Ticks). */
        static final int MAX_PER_RUN = 10;
        /** AE2: Kasten ±1 Block um den Auslöser. */
        static final double RANGE = 1.0;
        private static final float SWEEP_SECONDS = 10.0f;

        private final TransformRecipes recipes;
        private final HytaleLogger logger;
        private final ResourceType<EntityStore, Dwell> dwellType = registerResource(Dwell.class, Dwell::new);
        /** Gegenstände am Boden wie beim Zusammenlegen ({@code ItemMergeSystem}): keine Aufheb-Animationen, keine Auslagen. */
        private final Query<EntityStore> query = Query.and(ItemComponent.getComponentType(),
                TransformComponent.getComponentType(), Query.not(Interactable.getComponentType()),
                Query.not(PreventItemMerging.getComponentType()));
        private long lastErrorLog;

        TransformSystem(TransformRecipes recipes, HytaleLogger logger) {
            super(INTERVAL_SECONDS);
            this.recipes = recipes;
            this.logger = logger;
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        /** Greift auf Nachbar-Entities zu: nie parallel. */
        @Override
        public boolean isParallel(int archetypeChunkSize, int taskCount) {
            return false;
        }

        @Override
        public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
            super.tick(dt, systemIndex, store);
            // Wartezeiten verschwundener Gegenstände (aufgehoben, verbraucht, entladen) wegräumen
            Dwell dwell = store.getResource(dwellType);
            if (dwell != null && !dwell.seconds.isEmpty() && (dwell.sinceSweep += dt) >= SWEEP_SECONDS) {
                dwell.sinceSweep = 0;
                dwell.seconds.keySet().removeIf(r -> !r.isValid());
            }
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                         @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            ItemComponent item = chunk.getComponent(index, ItemComponent.getComponentType());
            ItemStack stack = item == null ? null : item.getItemStack();
            // Billige Vorprüfung: nur Auslöser (erste Zutat eines Rezepts) gehen weiter
            if (ItemStack.isEmpty(stack) || !recipes.isFluidTrigger(stack.getItemId())) {
                return;
            }
            try {
                process(dt, chunk.getReferenceTo(index), chunk.getComponent(index, TransformComponent.getComponentType()),
                        stack, store, commandBuffer);
            } catch (RuntimeException e) {
                // Eine Umwandlung darf den Welt-Tick nie stören
                long now = System.currentTimeMillis();
                if (now - lastErrorLog > 60_000L) {
                    lastErrorLog = now;
                    logger.at(Level.WARNING).withCause(e).log("AE2: in-world transform failed");
                }
            }
        }

        private void process(float dt, Ref<EntityStore> self, @Nullable TransformComponent transform, ItemStack stack,
                             Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
            Dwell dwell = store.getResource(dwellType);
            World world = store.getExternalData().getWorld();
            if (transform == null || dwell == null || world == null) {
                return;
            }
            Vector3d pos = transform.getPosition();
            String fluid = fluidAt(world, pos);
            if (!recipes.canTransformIn(stack.getItemId(), fluid)) {
                dwell.seconds.remove(self); // nicht (mehr) in der Flüssigkeit: Wartezeit beginnt neu
                return;
            }
            float waited = dwell.seconds.getOrDefault(self, 0f) + dt;
            dwell.seconds.put(self, waited);
            if (waited < DWELL_SECONDS) {
                return;
            }

            // Gegenstände im Kasten um den Auslöser; er selbst steht vorn und wird bevorzugt verbraucht
            List<Nearby> nearby = nearby(self, pos, store);
            int[] left = new int[nearby.size()];
            for (int i = 0; i < left.length; i++) {
                left[i] = nearby.get(i).stack().getQuantity();
            }
            // Erst rechnen (auf den Mengen), dann die Entities anfassen
            Map<String, Integer> produced = new LinkedHashMap<>();
            int rounds = 0;
            while (rounds < MAX_PER_RUN) {
                List<TransformRecipes.Pile> piles = new ArrayList<>(left.length);
                for (int i = 0; i < left.length; i++) {
                    piles.add(new TransformRecipes.Pile(nearby.get(i).stack().getItemId(), left[i]));
                }
                if (piles.getFirst().count() <= 0) {
                    break; // Auslöser aufgebraucht
                }
                TransformRecipes.Match match = recipes.match(fluid, piles);
                if (match == null) {
                    break;
                }
                for (int i = 0; i < left.length; i++) {
                    left[i] -= match.consumed()[i];
                }
                produced.merge(match.recipe().output().itemId(), match.recipe().output().quantity(), Integer::sum);
                rounds++;
            }
            if (rounds == 0) {
                dwell.seconds.put(self, 0f); // AE2: Zähler zurück, nächster Versuch nach der Wartezeit
                return;
            }

            for (int i = 0; i < left.length; i++) {
                Nearby n = nearby.get(i);
                if (left[i] == n.stack().getQuantity()) {
                    continue;
                }
                if (left[i] <= 0) {
                    // wie ItemMergeSystem: Entity entfernen und den Stapel sofort leeren, damit ihn in diesem
                    // Durchlauf niemand ein zweites Mal verbraucht
                    commandBuffer.removeEntity(n.ref(), RemoveReason.REMOVE);
                    n.item().setItemStack(null);
                    dwell.seconds.remove(n.ref());
                } else {
                    n.item().setItemStack(n.stack().withQuantity(left[i]));
                }
            }
            spawn(produced, pos, store, commandBuffer);
        }

        /** Gegenstände im Kasten ±{@link #RANGE} um {@code pos}; der Auslöser steht an erster Stelle. */
        private List<Nearby> nearby(Ref<EntityStore> self, Vector3d pos, Store<EntityStore> store) {
            List<Nearby> out = new ArrayList<>();
            ItemComponent own = store.getComponent(self, ItemComponent.getComponentType());
            if (own != null && !ItemStack.isEmpty(own.getItemStack())) {
                out.add(new Nearby(self, own, own.getItemStack()));
            }
            SpatialResource<Ref<EntityStore>, EntityStore> spatial =
                    store.getResource(EntityModule.get().getItemSpatialResourceType());
            if (spatial == null) {
                return out;
            }
            List<Ref<EntityStore>> refs = SpatialResource.getThreadLocalReferenceList();
            spatial.getSpatialStructure().collectBox(new Vector3d(pos.x - RANGE, pos.y - RANGE, pos.z - RANGE),
                    new Vector3d(pos.x + RANGE, pos.y + RANGE, pos.z + RANGE), refs);
            for (Ref<EntityStore> ref : refs) {
                if (ref == self || !ref.isValid()) {
                    continue;
                }
                ItemComponent other = store.getComponent(ref, ItemComponent.getComponentType());
                // Stapel null = in diesem Durchlauf schon verbraucht
                if (other == null || ItemStack.isEmpty(other.getItemStack())
                        || store.getArchetype(ref).contains(Interactable.getComponentType())) {
                    continue;
                }
                out.add(new Nearby(ref, other, other.getItemStack()));
            }
            return out;
        }

        /** Legt die Ergebnisse als Gegenstände an die Stelle des Auslösers (AE2: im Block des Auslösers, leichter Schwung). */
        private static void spawn(Map<String, Integer> produced, Vector3d pos, Store<EntityStore> store,
                                  CommandBuffer<EntityStore> commandBuffer) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (Map.Entry<String, Integer> e : produced.entrySet()) {
                int max = ItemKeys.maxStack(e.getKey());
                int remaining = e.getValue();
                while (remaining > 0) {
                    int n = Math.min(remaining, max);
                    remaining -= n;
                    Vector3d at = new Vector3d(pos.x + random.nextDouble(-0.2, 0.2), pos.y + 0.25,
                            pos.z + random.nextDouble(-0.2, 0.2));
                    Holder<EntityStore> holder = ItemComponent.generateItemDrop(store, new ItemStack(e.getKey(), n), at,
                            Rotation3f.IDENTITY, (float) random.nextDouble(-0.75, 0.75), 2.0f,
                            (float) random.nextDouble(-0.75, 0.75));
                    if (holder != null) {
                        commandBuffer.addEntity(holder, AddReason.SPAWN);
                    }
                }
            }
        }

        /**
         * Flüssigkeit, in der ein Gegenstand bei {@code pos} liegt (Asset-Schlüssel), oder null. Geprüft wird knapp
         * über seiner Unterkante, etwas höher und knapp darunter – er kann am Grund liegen oder an der Oberfläche
         * treiben.
         */
        @Nullable
        private static String fluidAt(World world, Vector3d pos) {
            ChunkStore chunks = world.getChunkStore();
            int x = (int) Math.floor(pos.x);
            int z = (int) Math.floor(pos.z);
            int last = Integer.MIN_VALUE;
            for (double dy : new double[]{0.1, 0.4, -0.2}) {
                int y = (int) Math.floor(pos.y + dy);
                if (y == last) {
                    continue;
                }
                last = y;
                int id = WorldUtil.getFluidIdAtPosition(chunks, x, y, z);
                if (id != 0) {
                    Fluid f = Fluid.getAssetMap().getAsset(id);
                    if (f != null) {
                        return f.getId();
                    }
                }
            }
            return null;
        }
    }
}
