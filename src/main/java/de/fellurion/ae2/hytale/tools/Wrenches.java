/*
 * Applied Energistics 2 für Hytale.
 * Abgeleitet von Applied Energistics 2 (c) 2013-2020 AlgorithmX2 et al., LGPL-3.0; Portierung (c) Fellurion
 * (Vorbild: appeng.hooks.WrenchHook, appeng.blockentity.AEBaseBlockEntity#disassembleWithWrench,
 * appeng.items.tools.quartz.QuartzWrenchItem).
 */
package de.fellurion.ae2.hytale.tools;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.VariantRotation;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EnvironmentSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.hytale.AE2BlockComponent;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.ItemCatalog;
import de.fellurion.ae2.hytale.WorldNetworks;
import de.fellurion.ae2.hytale.device.Drops;
import org.joml.Vector3i;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Was der Schraubenschlüssel mit einem AE2-Block tut: drehen und abbauen (AE2: Rechtsklick dreht, geduckt +
 * Rechtsklick demontiert). Hier steht nur die Block-Seite – ohne Spieler aufrufbar, damit auch die Testbefehle
 * ({@link ToolCommands}) sie benutzen können; Zugriffsprüfung und Meldungen macht {@link ToolItems}.
 *
 * <p><b>Drehen</b> schaltet zur nächsten Ausrichtung, die der Block laut seiner {@code VariantRotation} haben darf
 * (NESW: vier Himmelsrichtungen, DoublePipe: alle sechs Seiten). Der Blocktyp bleibt, nur die Rotation ändert sich;
 * Block-Entity (Container, Einstellungen) und Netzknoten bleiben dieselben, {@link WorldNetworks#nodeRotated} zieht
 * Verbindungen und Geräte nach.
 *
 * <p><b>Abbauen</b> läuft über Hytales Weg für das Abbauen durch einen Spieler
 * ({@link BlockHarvestUtils#performBlockBreak}): {@code BreakBlockEvent} wird ausgelöst und kann von Schutz-Plugins
 * abgelehnt werden, die Block-Entity wird wie bei jedem Abbau entfernt – Container-Inhalt und eingebaute Karten
 * fallen also genau einmal, auf demselben Weg wie sonst. Nur der Block selbst fällt nicht, sondern geht ins Inventar.
 *
 * <p>Abweichungen von AE2: AE2 dreht um die angeklickte Seite; hier wird reihum geschaltet (die Seite kennt der
 * Klick mit einem Item nicht). AE2 legt auch den Inhalt ins Inventar; hier fällt er wie beim normalen Abbau am Block.
 * Gedreht und abgebaut werden nur Blöcke mit der Komponente {@code AE2_Block}.
 */
public final class Wrenches {

    /**
     * Wie {@code BlockOperations.setBlockInteractionState}: Block-Entity behalten, keine Partikel, Änderung an die
     * Clients schicken.
     */
    private static final int ROTATE_SETTINGS = SetBlockSettings.NO_UPDATE_STATE | SetBlockSettings.NO_SEND_PARTICLES
            | SetBlockSettings.FORCE_CHANGED | SetBlockSettings.NO_UPDATE_NEIGHBOR_CONNECTIONS;

    private Wrenches() {
    }

    /** Ergebnis von {@link #rotate}. */
    public enum Rotated {
        /** Der Block zeigt jetzt in die nächste Richtung. */
        DONE,
        /** Kein AE2-Block mit Ausrichtung (oder nur eine erlaubte Ausrichtung). */
        NOT_ROTATABLE,
        /** Chunk nicht geladen bzw. Block nicht lesbar. */
        UNAVAILABLE
    }

    /** Ergebnis von {@link #dismantle}. */
    public enum Dismantled {
        /** Block entfernt; {@link Dismantling#drops} wurde übergeben bzw. fallen gelassen. */
        DONE,
        /** Kein AE2-Block an der Stelle. */
        NOT_AE2,
        /** Die Welt bzw. Umgebung erlaubt hier kein Abbauen, oder der Block ist nicht abbaubar. */
        NOT_ALLOWED,
        /** {@code BreakBlockEvent} wurde abgelehnt (Schutz-Plugin): der Block steht noch. */
        VETOED
    }

    /** @param drops was der Block selbst ergeben hat (leer, wenn nichts abgebaut wurde) */
    public record Dismantling(Dismantled result, List<ItemStack> drops) {
        static Dismantling of(Dismantled result) {
            return new Dismantling(result, List.of());
        }
    }

    /** Steht an der Stelle ein Block mit der Komponente {@code AE2_Block}? */
    public static boolean isAe2Block(World world, int x, int y, int z) {
        return BlockAccess.component(AE2BlockComponent.getComponentType(), world, x, y, z) != null;
    }

    // ------------------------------------------------------------------ Drehen

    /**
     * Nächste erlaubte Rotation nach {@code current} für eine {@code VariantRotation} (Reihenfolge: unrotiert, dann
     * die Rotationen der Variante), oder -1, wenn es nur eine gibt.
     */
    static int nextRotation(VariantRotation variant, int current) {
        RotationTuple[] extra = variant == null ? RotationTuple.EMPTY_ARRAY : variant.getRotations();
        if (extra.length == 0) {
            return -1;
        }
        int at = current == RotationTuple.NONE_INDEX ? 0 : -1;
        for (int i = 0; i < extra.length && at < 0; i++) {
            if (extra[i].index() == current) {
                at = i + 1;
            }
        }
        // eine Rotation, die die Variante gar nicht vorsieht (alter Bestand): bei der ersten wieder einsteigen
        int next = at < 0 ? 0 : (at + 1) % (extra.length + 1);
        return next == 0 ? RotationTuple.NONE_INDEX : extra[next - 1].index();
    }

    /** Darf der Schraubenschlüssel diesen Block drehen? Nur AE2-Blöcke, die laut {@code items.json} ausrichtbar sind. */
    public static boolean isRotatable(World world, int x, int y, int z) {
        BlockType type = BlockAccess.blockType(world, x, y, z);
        return type != null && isAe2Block(world, x, y, z) && rotatable(type);
    }

    private static boolean rotatable(BlockType type) {
        ItemCatalog.Info info = type.getItem() == null ? null : ItemCatalog.get(type.getItem().getId());
        return info != null && info.rotation() != null && !"None".equals(info.rotation())
                && nextRotation(type.getVariantRotation(), RotationTuple.NONE_INDEX) >= 0;
    }

    /**
     * Dreht den AE2-Block um eine Stufe weiter. Der Blocktyp (samt Zustand wie {@code Online}) und die Block-Entity
     * bleiben, das Netz folgt sofort.
     */
    public static Rotated rotate(World world, int x, int y, int z) {
        BlockType type = BlockAccess.blockType(world, x, y, z);
        String key = BlockAccess.blockKey(world, x, y, z);
        if (type == null || key == null) {
            return Rotated.UNAVAILABLE;
        }
        if (!isAe2Block(world, x, y, z) || !rotatable(type)) {
            return Rotated.NOT_ROTATABLE;
        }
        int current = BlockAccess.rotationIndex(world, x, y, z);
        int next = nextRotation(type.getVariantRotation(), current);
        if (next < 0 || next == current) {
            return Rotated.NOT_ROTATABLE;
        }
        if (!BlockAccess.setBlock(world, x, y, z, key, next, ROTATE_SETTINGS)) {
            return Rotated.UNAVAILABLE;
        }
        WorldNetworks.of(world).nodeRotated(x, y, z);
        return Rotated.DONE;
    }

    // ------------------------------------------------------------------ Abbauen

    /**
     * Baut den AE2-Block ab und gibt ihn {@code breaker} ins Inventar; was nicht hineinpasst (oder alles, wenn es
     * keinen Spieler gibt), fällt am Block. Der Inhalt des Blocks fällt wie bei jedem Abbau am Block.
     *
     * @param breaker  der Spieler, in dessen Namen abgebaut wird ({@code BreakBlockEvent}); null = niemand
     *                 (Testbefehl), dann gilt der Abbau als Ereignis der Umgebung
     * @param tool     das Werkzeug in der Hand, für das Ereignis
     * @param entities Zugriff auf den EntityStore der Welt (im Klick: der CommandBuffer der Interaktion)
     */
    public static Dismantling dismantle(World world, int x, int y, int z, @Nullable Ref<EntityStore> breaker,
                                        @Nullable ItemStack tool, ComponentAccessor<EntityStore> entities) {
        if (!isAe2Block(world, x, y, z)) {
            return Dismantling.of(Dismantled.NOT_AE2);
        }
        ChunkStore chunks = world.getChunkStore();
        Ref<ChunkStore> section = chunks.getChunkSectionReferenceAtBlock(x, y, z);
        BlockType type = BlockAccess.blockType(world, x, y, z);
        if (section == null || !section.isValid() || type == null) {
            return Dismantling.of(Dismantled.NOT_AE2);
        }
        Store<ChunkStore> store = chunks.getStore();
        BlockGathering gathering = type.getGathering();
        if (gathering == null || !world.getGameplayConfig().getWorldConfig().isBlockBreakingAllowed(type)
                || !environmentAllows(store, section, x, y, z)) {
            return Dismantling.of(Dismantled.NOT_ALLOWED);
        }
        // Was der Block beim normalen Abbau ergibt – vorher bestimmen, danach gibt es den Block nicht mehr
        BlockBreakingDropType breaking = gathering.getBreaking();
        List<ItemStack> drops = new ArrayList<>(BlockHarvestUtils.getDrops(type,
                breaking == null ? 1 : Math.max(1, breaking.getQuantity()),
                breaking == null ? null : breaking.getItemId(), breaking == null ? null : breaking.getDropListId()));
        String before = type.getItem() == null ? type.getId() : type.getItem().getId();

        // Hytales Abbau durch einen Spieler, ohne Block-Drop (Menge 0): löst BreakBlockEvent aus und entfernt
        // Block und Block-Entity auf dem üblichen Weg
        BlockHarvestUtils.performBlockBreak(breaker, tool, new Vector3i(x, y, z), 0, section, entities, store);

        BlockType after = BlockAccess.blockType(world, x, y, z);
        if (after != null && before.equals(after.getItem() == null ? after.getId() : after.getItem().getId())) {
            return Dismantling.of(Dismantled.VETOED); // abgelehnt: nichts ist passiert, nichts wird vergeben
        }
        give(world, x, y, z, breaker, entities, drops);
        return new Dismantling(Dismantled.DONE, List.copyOf(drops));
    }

    /** Wie beim Abbauen von Hand: Umgebungen (Environment) können Blockänderungen verbieten. */
    private static boolean environmentAllows(Store<ChunkStore> store, Ref<ChunkStore> section, int x, int y, int z) {
        try {
            EnvironmentSection environments = store.getComponent(section, EnvironmentSection.getComponentType());
            if (environments == null) {
                return true;
            }
            Environment environment = Environment.getAssetMap().getAsset(environments.get(x, y, z));
            return environment == null || environment.isBlockModificationAllowed();
        } catch (RuntimeException e) {
            return true; // Umgebung nicht lesbar: dann entscheidet das Ereignis
        }
    }

    /** Jeder Stapel landet genau einmal: im Inventar des Spielers, der Rest (oder ohne Spieler alles) am Block. */
    private static void give(World world, int x, int y, int z, @Nullable Ref<EntityStore> breaker,
                             ComponentAccessor<EntityStore> entities, List<ItemStack> drops) {
        List<ItemStack> rest = new ArrayList<>();
        boolean player = breaker != null && breaker.isValid()
                && entities.getComponent(breaker, Player.getComponentType()) != null;
        for (ItemStack drop : drops) {
            if (ItemStack.isEmpty(drop)) {
                continue;
            }
            ItemStack left = drop;
            if (player) {
                try {
                    left = Player.giveItem(drop, breaker, entities).getRemainder();
                } catch (RuntimeException e) {
                    left = drop; // Inventar nicht erreichbar: der Stapel fällt am Block
                }
            }
            if (!ItemStack.isEmpty(left)) {
                rest.add(left);
            }
        }
        Drops.at(world, x, y, z, rest);
    }
}
