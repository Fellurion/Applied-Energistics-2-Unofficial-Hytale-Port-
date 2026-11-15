/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.meteorite;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.buildertool.config.BlockTypeListAsset;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.worldgen.MeteoriteSite;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Bauschutz für das Einschlag-Ereignis: Ein Meteorit darf nur in unberührte Natur fallen, nie in etwas, das Spieler
 * gebaut haben. Ein Einschlagort wird abgelehnt, wenn
 * <ul>
 *   <li>in einem der betroffenen Chunks (plus ein Chunk Rand) je ein Spieler einen Block gesetzt oder abgebaut hat
 *       (wird seit der Installation mitgeschrieben),</li>
 *   <li>im Krater oder daneben irgendein Block steht, der nicht natürlich vorkommt (Bretter, Ziegel, Möbel, Truhen,
 *       Werkbänke, Fackeln, Felder, Wege, Blöcke anderer Mods …),</li>
 *   <li>ein Claim-Plugin dort ein Gebiet kennt (ClaimPro, OrbisGuard, CoreSys) oder ein anderes Plugin widerspricht
 *       ({@link #addVeto}),</li>
 *   <li>ein Bett/Zuhause, der Welt-Spawn, ein Spieler oder ein anderer Meteorit zu nah ist,</li>
 *   <li>in der Stufe „strict“: das Gelände nicht mit AE2 generiert wurde (dann ist unbekannt, was dort früher war).</li>
 * </ul>
 */
public final class BuildProtection {

    /** Einspruch eines anderen Plugins gegen einen Einschlag in diesem Bereich (Blockkoordinaten, inklusive). */
    @FunctionalInterface
    public interface Veto {
        boolean blocks(World world, int minX, int minZ, int maxX, int maxZ);
    }

    private static final List<Veto> VETOS = new CopyOnWriteArrayList<>();
    /** Rand um den veränderten Bereich, der ebenfalls rein natürlich sein muss. */
    static final int MARGIN = 8;
    /** So nah darf kein anderer bekannter Meteorit liegen. */
    private static final int MIN_METEORITE_DISTANCE = 96;

    @Nullable
    private static volatile BitSet natural;
    @Nullable
    private static volatile JavaPlugin plugin;
    private static volatile boolean claimProbeFailed;
    /** Welcher Block zuletzt als „gebaut“ erkannt wurde (für {@code /ae2 meteorites spawn}). */
    @Nullable
    static volatile String lastBuilding;
    /** Ob dieser Block nur „unbekannt“ war – dann kann ihn der Admin freigeben. */
    static volatile boolean lastBuildingUnknown;

    private BuildProtection() {
    }

    /** Für andere Plugins: eigene Schutzgebiete anmelden. */
    public static void addVeto(Veto veto) {
        VETOS.add(veto);
    }

    static void setup(JavaPlugin p) {
        plugin = p;
        p.getEntityStoreRegistry().registerSystem(new TouchOnPlace());
        p.getEntityStoreRegistry().registerSystem(new TouchOnBreak());
    }

    // ------------------------------------------------------------------ Prüfung eines Einschlagorts

    /**
     * Prüft einen geplanten Einschlag.
     *
     * @param terrain die Welt samt der vorab berechneten Änderungen
     * @param event   true für das Einschlag-Ereignis (Abstand zu Spielern), false für nachgeholte Weltgenerierung
     * @return null, wenn der Einschlag erlaubt ist, sonst der Übersetzungsschlüssel des Grundes
     */
    @Nullable
    static String check(World world, MeteoriteSite site, WorldTerrain terrain, MeteoriteConfig.Settings settings, boolean event) {
        if (terrain.missingChunks()) {
            return "ae2meteor.reject.unloaded";
        }
        MeteoriteRegistry registry = MeteoriteRegistry.of(world.getName());
        int reach = MeteoriteSite.REACH + MARGIN;
        int minX = site.x() - reach, maxX = site.x() + reach, minZ = site.z() - reach, maxZ = site.z() + reach;
        // 1. Chunks, in denen Spieler gebaut oder abgebaut haben (mit einem Chunk Rand)
        for (int cx = (minX >> 5) - 1; cx <= (maxX >> 5) + 1; cx++) {
            for (int cz = (minZ >> 5) - 1; cz <= (maxZ >> 5) + 1; cz++) {
                if (registry.isTouched(cx, cz)) {
                    return "ae2meteor.reject.touched";
                }
            }
        }
        if (settings.protection() == MeteoriteConfig.Protection.STRICT) {
            for (int cx = minX >> 5; cx <= maxX >> 5; cx++) {
                for (int cz = minZ >> 5; cz <= maxZ >> 5; cz++) {
                    if (!ChunkInfo.isGenerated(world, ChunkUtil.indexChunk(cx, cz))) {
                        return "ae2meteor.reject.oldTerrain";
                    }
                }
            }
        }
        // 2. Abstände: Spawn, Spieler, andere Meteoriten
        MeteoriteConfig.Global global = MeteoriteConfig.global();
        try {
            if (world.getWorldConfig().getSpawnProvider() != null && world.getWorldConfig().getSpawnProvider()
                    .isWithinSpawnDistance(new Vector3d(site.x(), site.y(), site.z()), global.minDistanceFromSpawn())) {
                return "ae2meteor.reject.spawn";
            }
        } catch (RuntimeException ignored) {
            // Spawn-Anbieter ohne Abstandsprüfung
        }
        if (event) {
            Collection<PlayerRef> players = world.getPlayerRefs();
            for (PlayerRef player : players) {
                if (player.getTransform() == null) {
                    continue;
                }
                Vector3d p = player.getTransform().getPosition();
                double dx = p.x - site.x(), dz = p.z - site.z();
                if (dx * dx + dz * dz < (double) global.minDistanceFromPlayers() * global.minDistanceFromPlayers()) {
                    return "ae2meteor.reject.players";
                }
            }
        }
        if (registry.nearest(site.x(), site.z(), false, MIN_METEORITE_DISTANCE) != null) {
            return "ae2meteor.reject.meteorite";
        }
        // 3. Nur natürliche Blöcke: alles, was ersetzt würde, und alles im Zylinder um den Krater
        if (!terrain.changesOnlyNatural() || !terrain.cylinderIsNatural(site.x(), site.z(), craterRadius(site) + MARGIN,
                site.y() - 12)) {
            lastBuilding = terrain.unnaturalBlock();
            lastBuildingUnknown = terrain.unnaturalIsUnknown();
            return "ae2meteor.reject.building";
        }
        // 4. Betten/Zuhause (Hytales Wildnis-Erkennung), Claims, Einsprüche anderer Plugins
        if (nearHome(world, site)) {
            return "ae2meteor.reject.home";
        }
        if (global.respectClaimPlugins() && claimed(world, minX, minZ, maxX, maxZ)) {
            return "ae2meteor.reject.claim";
        }
        for (Veto veto : VETOS) {
            try {
                if (veto.blocks(world, minX, minZ, maxX, maxZ)) {
                    return "ae2meteor.reject.claim";
                }
            } catch (RuntimeException e) {
                return "ae2meteor.reject.claim"; // im Zweifel für den Schutz
            }
        }
        return null;
    }

    /** Radius der Kratermulde an der Oberfläche (die Mitte liegt {@code ceil(r) + 1} darunter), plus Trümmerring. */
    static int craterRadius(MeteoriteSite site) {
        double depth = site.radius() + Math.ceil(site.radius()) + 4;
        return (int) Math.min(MeteoriteSite.REACH, Math.ceil(Math.max(Math.sqrt(50 * depth), 1.6 * (site.radius() * 2 + 5))));
    }

    private static boolean nearHome(World world, MeteoriteSite site) {
        try {
            var tracker = com.hypixel.hytale.builtin.adventure.wilderness.resource.WildernessTracker.getTracker(world);
            if (tracker == null || !tracker.isEnabled()) {
                return false;
            }
            int r = MeteoriteSite.REACH;
            for (int dx = -r; dx <= r; dx += 16) {
                for (int dz = -r; dz <= r; dz += 16) {
                    if (!tracker.isWilderness(site.x() + dx, site.y(), site.z() + dz)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Wildnis-Modul nicht vorhanden
        }
        return false;
    }

    // ------------------------------------------------------------------ natürlich, gebaut oder unbekannt

    /** Einstufung eines Blocks für den Bauschutz. */
    enum Kind {
        /** Kommt so im Gelände vor: Erde, Stein, Sand, Schnee, Erze, Pflanzen, Bäume. */
        NATURAL,
        /** Eindeutig gebaut oder hergestellt: Ziegel, Bretter, Möbel, Truhen, Werkbänke, Lampen, AE2-Blöcke … */
        BUILT,
        /**
         * Weder noch – typisch für Blöcke anderer Mods. Steckt so ein Block rundum in festem Gestein, ist er ein Erz;
         * liegt er offen, wird er wie Gebautes behandelt (siehe {@link WorldTerrain}).
         */
        UNKNOWN
    }

    private static final String[] NATURAL_LISTS = {"Soils", "Rock", "Gravel", "Snow", "Ores", "PlantsAndTrees", "AllScatter"};
    /** Natürliche Blöcke des Grundspiels, die in keiner der Listen stehen. */
    private static final Pattern NATURAL_IDS = Pattern.compile(
            "^(Soil_(Ash|Mud|Mud_Dry|Sand_White|Sand_Red|Sand_Ashen|Needles|Hive|Pebbles(_Frozen)?|Dirt_(Wet|Lush|Crystal)|Roots_Poisoned|Seaweed_Block)"
                    + "|Soil_Clay(_[A-Z][A-Za-z]*)?(_Stalactite_(Small|Large))?"
                    + "|Rock_(Ice|Ice_Permafrost|Ice_Icicles|Magma_Cooled|Volcanic_Cracked_Lava|Outcrops)"
                    + "|Rock_[A-Za-z]+_Stalactite_(Small|Large)|Rock_Crystal_.*|Ore_.*|Rubble_.*"
                    + "|Plant_Crop_.*|Plant_Sapling_.*|Plant_Seeds_.*|Plant_Leaves_.*)$");
    /**
     * Namen, die Gebautes verraten – auch wenn der Block in einer Natur-Liste steht oder als „Rock“/„Soil“ markiert ist
     * (Bau-Mods markieren Pflaster und Mauersteine so). Felder erkennt der Bauschutz am gepflügten Boden („Tilled“):
     * Getreide und Gemüse wachsen in Hytale auch wild.
     */
    private static final Set<String> BUILT_WORDS = Set.of("Brick", "Bricks", "Cobble", "Plank", "Planks", "Stairs", "Roof", "Fence",
            "Beam", "Path", "Paths", "Pathway", "Road", "Tilled", "Plaster", "Smooth", "Polished", "Chiseled", "Carved", "Tile", "Tiles",
            "Slab", "Pillar", "Column", "Arch", "Battlement", "Corbel", "Grooved", "Trim", "Window", "Door", "Gate",
            "Ladder", "Lamp", "Light", "Lightsource", "Lantern", "Torch", "Brazier", "Chest", "Crate", "Barrel", "Frame", "Carpet",
            "Glass", "Anchor", "Sign", "Banner");

    /**
     * Ob ein Wort des Block-Namens (durch {@code _} getrennt) Gebautes verrät. „Corner“ und „Floor“ fehlen bewusst:
     * so heißen in Hytale auch Äste ({@code Wood_Oak_Branch_Corner}) und Laub am Boden ({@code Plant_Leaves_Autumn_Floor}).
     */
    static boolean builtName(String key) {
        for (String word : key.split("_")) {
            if (BUILT_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> CRAFTED_TYPES = Set.of("Furniture", "Bench", "Deco", "Cloth", "Metal", "Portal", "Rail",
            "Container", "Trap", "Explosive", "Editor", "Debug");
    /** Markierungen, mit denen Blöcke anderer Mods als Gelände gelten (wenn ihr Name nichts Gebautes verrät). */
    private static final Set<String> TERRAIN_TYPES = Set.of("Rock", "Soil", "Plant", "Ore", "Tree", "Rubble");

    @Nullable
    private static volatile BitSet builtSet;
    @Nullable
    private static volatile Set<String> appliedWhitelist;

    /** Ob dieser Block natürlich im Gelände vorkommt oder Luft ist. */
    static boolean natural(int blockId) {
        return kind(blockId) == Kind.NATURAL;
    }

    static Kind kind(int blockId) {
        if (blockId == 0) {
            return Kind.NATURAL;
        }
        Set<String> whitelist = MeteoriteConfig.global().naturalBlocks();
        BitSet nat = natural;
        BitSet built = builtSet;
        if (nat == null || built == null || appliedWhitelist != whitelist) {
            BitSet[] sets = classifyAll(whitelist);
            nat = sets[0];
            built = sets[1];
            natural = nat;
            builtSet = built;
            appliedWhitelist = whitelist;
        }
        if (blockId < 0) {
            return Kind.BUILT;
        }
        return nat.get(blockId) ? Kind.NATURAL : built.get(blockId) ? Kind.BUILT : Kind.UNKNOWN;
    }

    private static BitSet[] classifyAll(Set<String> whitelist) {
        var blocks = BlockType.getAssetMap();
        Set<String> listed = new java.util.HashSet<>();
        for (String list : NATURAL_LISTS) {
            try {
                BlockTypeListAsset asset = BlockTypeListAsset.getAssetMap().getAsset(list);
                if (asset != null && asset.getBlockTypeKeys() != null) {
                    listed.addAll(asset.getBlockTypeKeys());
                }
            } catch (RuntimeException ignored) {
                // Liste fehlt in dieser Spielversion
            }
        }
        String basePack = pack("Rock_Stone");
        BitSet nat = new BitSet();
        BitSet built = new BitSet();
        for (String key : blocks.getAssetMap().keySet()) {
            int id = blocks.getIndexOrDefault(key, Integer.MIN_VALUE);
            BlockType type = id <= 0 ? null : blocks.getAsset(id);
            if (type == null) {
                continue;
            }
            // Zustands-Varianten („*<Block>_State_Definitions_<Zustand>“) zählen wie ihr Grundblock
            int marker = key.indexOf("_State_Definitions_");
            String base = marker > 1 && key.charAt(0) == '*' ? key.substring(1, marker) : key;
            if (matches(whitelist, base)) {
                nat.set(id);
                continue;
            }
            if (base.startsWith("AE2_") || type.isUnknown()) {
                built.set(id);
                continue;
            }
            Set<String> types = typeTags(type);
            boolean plant = base.startsWith("Plant_") || types.contains("Plant");
            boolean crafted = builtName(base) || type.getBench() != null || type.isDoor()
                    || (type.getBlockEntity() != null && !plant) || !java.util.Collections.disjoint(types, CRAFTED_TYPES);
            if (crafted) {
                built.set(id);
            } else if (listed.contains(base) || NATURAL_IDS.matcher(base).matches()
                    || (!java.util.Objects.equals(basePack, pack(base)) && !java.util.Collections.disjoint(types, TERRAIN_TYPES))) {
                // Grundspiel: Listen und Namen; andere Mods: als Gelände markierte Blöcke (Erze, Pflanzen, Gestein)
                nat.set(id);
            }
        }
        return new BitSet[]{nat, built};
    }

    @Nullable
    private static String pack(String key) {
        try {
            return BlockType.getAssetMap().getAssetPack(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Set<String> typeTags(BlockType type) {
        try {
            Map<String, String[]> tags = type.getData() == null ? null : type.getData().getRawTags();
            String[] types = tags == null ? null : tags.get("Type");
            return types == null ? Set.of() : Set.of(types);
        } catch (RuntimeException e) {
            return Set.of(); // ohne Tags (oder doppelte Einträge): Name und Listen entscheiden
        }
    }

    /** Ob {@code key} auf einen Eintrag der Freigabe-Liste passt ({@code *} am Ende = beliebige Fortsetzung). */
    static boolean matches(Set<String> patterns, String key) {
        for (String p : patterns) {
            if (p.endsWith("*") ? key.startsWith(p.substring(0, p.length() - 1)) : p.equals(key)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Claim-Plugins (nur wenn installiert)

    /**
     * Darf ein selbsttätig arbeitendes AE2-Gerät (Annihilation/Formation Plane, Spatial IO, Tiny TNT ...) an der
     * Stelle {@code targetX/targetZ} Blöcke verändern?
     *
     * <p>Geräte haben keinen Spieler, den ein Claim-Plugin fragen könnte. Deshalb gilt: außerhalb von Claims ja; in
     * einem Claim nur, wenn auch das Gerät selbst in einem Claim steht (wer es dort aufstellen durfte, darf dort
     * bauen). Ein Gerät vor der Grundstücksgrenze kann so nicht in ein fremdes Grundstück hineinwirken. Ohne
     * Claim-Plugin ist immer alles erlaubt. Aktionen eines Spielers (Werkzeuge) gehen dagegen über Hytales normales
     * Abbauen/Platzieren, damit die Plugins selbst entscheiden.
     */
    public static boolean deviceMayModify(World world, int deviceX, int deviceZ, int targetX, int targetZ) {
        if (!MeteoriteConfig.global().respectClaimPlugins()) {
            return true;
        }
        if (!claimed(world, targetX, targetZ, targetX, targetZ)) {
            return true;
        }
        return claimed(world, deviceX, deviceZ, deviceX, deviceZ);
    }

    /** Wie {@link #deviceMayModify(World, int, int, int, int)} für ein ganzes Gebiet (alle Spalten von min bis max). */
    public static boolean deviceMayModify(World world, int deviceX, int deviceZ, int minX, int minZ, int maxX, int maxZ) {
        if (!MeteoriteConfig.global().respectClaimPlugins()) {
            return true;
        }
        if (!claimed(world, minX, minZ, maxX, maxZ)) {
            return true;
        }
        return claimed(world, deviceX, deviceZ, deviceX, deviceZ);
    }

    private static boolean claimed(World world, int minX, int minZ, int maxX, int maxZ) {
        if (claimProbeFailed) {
            return true;
        }
        try {
            for (int x = minX; x <= maxX + 15; x += 16) {
                for (int z = minZ; z <= maxZ + 15; z += 16) {
                    int px = Math.min(x, maxX), pz = Math.min(z, maxZ);
                    if (claimPro(world.getName(), px, pz) || orbisGuard(world.getName(), px, pz) || coreSys(world.getName(), px, pz)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Schnittstelle eines Claim-Plugins passt nicht mehr: lieber keine Einschläge als einer in einem Claim
            claimProbeFailed = true;
            JavaPlugin p = plugin;
            if (p != null) {
                p.getLogger().at(Level.WARNING).withCause(e).log("AE2: could not query a claim plugin – meteorite impacts are "
                        + "blocked until restart. Set \"respectClaimPlugins\": false in meteorites.json to ignore claim plugins.");
            }
            return true;
        }
    }

    @Nullable
    private static Class<?> find(String name) {
        try {
            return Class.forName(name, true, pluginLoader(name));
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** Klassen anderer Plugins liegen in deren Classloader. */
    private static ClassLoader pluginLoader(String className) {
        try {
            for (PluginBase other : PluginManager.get().getPlugins()) {
                ClassLoader loader = other.getClass().getClassLoader();
                try {
                    Class.forName(className, false, loader);
                    return loader;
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // nicht in diesem Plugin
                }
            }
        } catch (RuntimeException ignored) {
            // Plugin-Verwaltung nicht erreichbar
        }
        return BuildProtection.class.getClassLoader();
    }

    private static volatile Optional<Object[]> claimProApi;

    private static boolean claimPro(String world, int x, int z) throws ReflectiveOperationException {
        Optional<Object[]> api = claimProApi;
        if (api == null) {
            Class<?> c = find("me.tsumori.claimpro.api.ClaimProAPI");
            api = c == null ? Optional.empty() : Optional.of(new Object[]{c.getMethod("get"),
                    c.getMethod("getClaimAtBlock", String.class, double.class, double.class)});
            claimProApi = api;
        }
        if (api.isEmpty()) {
            return false;
        }
        Object instance = ((Method) api.get()[0]).invoke(null);
        return instance != null && ((Method) api.get()[1]).invoke(instance, world, (double) x, (double) z) != null;
    }

    private static volatile Optional<Object[]> orbisGuardApi;

    private static boolean orbisGuard(String world, int x, int z) throws ReflectiveOperationException {
        Optional<Object[]> api = orbisGuardApi;
        if (api == null) {
            Class<?> c = find("com.orbisguard.api.OrbisGuardAPI");
            Class<?> container = find("com.orbisguard.api.region.IRegionContainer");
            api = c == null || container == null ? Optional.empty() : Optional.of(new Object[]{c.getMethod("getInstance"),
                    c.getMethod("getRegionContainer"),
                    container.getMethod("getRegionsAt", String.class, int.class, int.class, int.class)});
            orbisGuardApi = api;
        }
        if (api.isEmpty()) {
            return false;
        }
        Object instance = ((Method) api.get()[0]).invoke(null);
        Object container = instance == null ? null : ((Method) api.get()[1]).invoke(instance);
        if (container == null) {
            return false;
        }
        // Regionen sind Quader: an mehreren Höhen prüfen
        for (int y = 16; y < 320; y += 48) {
            Object regions = ((Method) api.get()[2]).invoke(container, world, x, y, z);
            if (regions instanceof Collection<?> set && !set.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static volatile Optional<Object[]> coreSysApi;

    private static boolean coreSys(String world, int x, int z) throws ReflectiveOperationException {
        Optional<Object[]> api = coreSysApi;
        if (api == null) {
            api = Optional.empty();
            Class<?> pluginClass = find("de.fellurion.LevelPlugin");
            if (pluginClass != null) {
                for (PluginBase other : PluginManager.get().getPlugins()) {
                    if (pluginClass.isInstance(other)) {
                        Object service = pluginClass.getMethod("getClaimService").invoke(other);
                        if (service != null) {
                            api = Optional.of(new Object[]{service,
                                    service.getClass().getMethod("findClaimAt", String.class, int.class, int.class)});
                        }
                        break;
                    }
                }
            }
            coreSysApi = api;
        }
        if (api.isEmpty()) {
            return false;
        }
        Object claim = ((Method) api.get()[1]).invoke(api.get()[0], world, x, z);
        return claim instanceof Optional<?> o && o.isPresent();
    }

    // ------------------------------------------------------------------ von Spielern veränderte Chunks merken

    private static void touch(Store<EntityStore> store, @Nullable Vector3i block) {
        World world = store.getExternalData().getWorld();
        if (world != null && block != null) {
            MeteoriteRegistry.of(world.getName()).touch(block.x >> 5, block.z >> 5);
        }
    }

    private static final class TouchOnPlace extends EntityEventSystem<EntityStore, PlaceBlockEvent> {
        TouchOnPlace() {
            super(PlaceBlockEvent.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull PlaceBlockEvent event) {
            touch(store, event.getTargetBlock());
        }
    }

    private static final class TouchOnBreak extends EntityEventSystem<EntityStore, BreakBlockEvent> {
        TouchOnBreak() {
            super(BreakBlockEvent.class);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull BreakBlockEvent event) {
            touch(store, event.getTargetBlock());
            // Mysterious Cube außerhalb des Abenteuer-Modus: Hytale lässt nichts fallen, AE2 holt das nach
            Vector3i at = event.getTargetBlock();
            BlockType type = event.getBlockType();
            Player player = chunk.getComponent(index, Player.getComponentType());
            World world = store.getExternalData().getWorld();
            if (at != null && type != null && world != null && player != null && CubeDrops.BLOCK.equals(type.getId())
                    && player.getGameMode() != com.hypixel.hytale.protocol.GameMode.Adventure) {
                CubeDrops.brokenWithoutDrops(world, at.x, at.y, at.z);
            }
        }
    }
}
