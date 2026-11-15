/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.gadgets;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeIntPosition;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.fellurion.ae2.core.grid.AEColor;
import de.fellurion.ae2.core.tools.EntropyRecipes;
import de.fellurion.ae2.core.tools.MatterCannonAmmo;
import de.fellurion.ae2.core.tools.PaintStorage;
import de.fellurion.ae2.hytale.BlockAccess;
import de.fellurion.ae2.hytale.items.PoweredItems;
import de.fellurion.ae2.hytale.signal.Signals;
import de.fellurion.ae2.hytale.transform.EntangledSingularity;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /ae2 gadget …}: Test- und Hilfsbefehle für Werkzeuge und Tiny TNT (Admin, {@code ae2.admin}). Sie rufen
 * dieselbe Logik auf wie ein Spieler mit dem Werkzeug in der Hand; Änderungen an der Welt laufen dabei ohne Spieler
 * ({@link WorldEdits#forConsole}).
 * <ul>
 *   <li>{@code ignite x y z} – Tiny TNT zünden</li>
 *   <li>{@code blockdamage tnt|cannon on|off} – Blockschaden für diesen Serverlauf schalten</li>
 *   <li>{@code drop x y z itemId amount} – Gegenstand auf den Boden legen</li>
 *   <li>{@code items x y z radius} – Gegenstände am Boden auflisten (Singularitäten mit Frequenz)</li>
 *   <li>{@code charge x y z slot} – Ladung des Items in einem Block-Slot</li>
 *   <li>{@code entropy x y z heat|cool} – erhitzen/kühlen wie der Entropy Manipulator</li>
 *   <li>{@code recolor x y z farbe} – Kabel umfärben wie der Color Applicator</li>
 *   <li>{@code shoot x y z richtung itemId} – Schuss der Matter Cannon aus der Mitte der Zelle</li>
 *   <li>{@code fill} – das Werkzeug in der Hand voll laden (nur Spieler)</li>
 *   <li>{@code fluid x y z fluidId|none|get} – Flüssigkeit einer Zelle setzen, entfernen oder nur anzeigen</li>
 *   <li>{@code signal x y z on|off} – an der Stelle ein AE2-Signal senden (zündet Tiny TNT daneben)</li>
 * </ul>
 */
final class GadgetCommands extends AbstractCommandCollection {

    GadgetCommands() {
        super("gadget", "ae2gadget.cmd.desc");
        requirePermission("ae2.admin");
        addSubCommand(new Ignite());
        addSubCommand(new BlockDamage());
        addSubCommand(new Drop());
        addSubCommand(new Items());
        addSubCommand(new Charge());
        addSubCommand(new Entropy());
        addSubCommand(new Recolor());
        addSubCommand(new Shoot());
        addSubCommand(new Fill());
        addSubCommand(new SetFluid());
        addSubCommand(new Signal());
    }

    private static Message msg(String key) {
        return Message.translation("ae2gadget.cmd." + key);
    }

    /** Unterbefehl, der mit einer Blockposition beginnt. */
    private abstract static class At extends AbstractWorldCommand {
        private final RequiredArg<RelativeIntPosition> pos;

        At(String name) {
            super(name, "ae2gadget.cmd." + name + ".desc");
            requirePermission("ae2.admin");
            this.pos = withRequiredArg("x y z", "ae2cmd.arg.position", ArgTypes.RELATIVE_BLOCK_POSITION);
        }

        @Override
        protected final void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3i p = ctx.get(pos).getBlockPosition(ctx, store);
            ctx.sendMessage(run(ctx, world, store, p.x, p.y, p.z));
        }

        abstract Message run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z);
    }

    private static final class Ignite extends At {
        Ignite() {
            super("ignite");
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            TinyTnt.TntDevice tnt = TinyTnt.at(world, x, y, z);
            if (tnt == null) {
                return msg("ignite.none");
            }
            boolean lit = !tnt.isLit() && TinyTnt.ignite(world, x, y, z);
            return msg(lit ? "ignite.done" : "ignite.already").param("ticks", Integer.toString(tnt.fuse()));
        }
    }

    private static final class BlockDamage extends AbstractWorldCommand {
        private final RequiredArg<String> what;
        private final RequiredArg<String> state;

        BlockDamage() {
            super("blockdamage", "ae2gadget.cmd.blockdamage.desc");
            requirePermission("ae2.admin");
            what = withRequiredArg("tnt|cannon", "ae2gadget.cmd.arg.what", ArgTypes.STRING);
            state = withRequiredArg("on|off", "ae2cmd.arg.state", ArgTypes.STRING);
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            boolean on = "on".equalsIgnoreCase(ctx.get(state));
            boolean tnt = "tnt".equalsIgnoreCase(ctx.get(what));
            GadgetConfig.override(tnt ? null : on, tnt ? on : null);
            ctx.sendMessage(msg("blockdamage.set").param("tnt", Boolean.toString(GadgetConfig.tinyTntBlockDamage()))
                    .param("cannon", Boolean.toString(GadgetConfig.matterCannonBlockDamage())));
        }
    }

    private static final class Drop extends At {
        private final RequiredArg<String> item;
        private final RequiredArg<Integer> amount;

        Drop() {
            super("drop");
            item = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
            amount = withRequiredArg("amount", "ae2cmd.arg.amount", ArgTypes.INTEGER);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            String id = ctx.get(item);
            int n = Math.max(1, ctx.get(amount));
            if (Item.getAssetMap().getAsset(id) == null) {
                return msg("drop.unknown").param("item", id);
            }
            Holder<EntityStore> holder = ItemComponent.generateItemDrop(store, new ItemStack(id, n),
                    new Vector3d(x + 0.5, y + 0.1, z + 0.5), Rotation3f.IDENTITY, 0f, 0f, 0f);
            if (holder == null) {
                return msg("drop.unknown").param("item", id);
            }
            store.addEntity(holder, AddReason.SPAWN);
            return msg("drop.done").param("amount", Integer.toString(n)).param("item", id);
        }
    }

    private static final class Items extends At {
        private final RequiredArg<Integer> radius;

        Items() {
            super("items");
            radius = withRequiredArg("radius", "ae2gadget.cmd.arg.radius", ArgTypes.INTEGER);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            int r = Math.max(1, ctx.get(radius));
            SpatialResource<Ref<EntityStore>, EntityStore> spatial = store.getResource(EntityModule.get().getItemSpatialResourceType());
            List<Ref<EntityStore>> refs = new ArrayList<>();
            if (spatial != null) {
                List<Ref<EntityStore>> found = SpatialResource.getThreadLocalReferenceList();
                spatial.getSpatialStructure().collectBox(new Vector3d(x - r, y - r, z - r),
                        new Vector3d(x + 1 + r, y + 1 + r, z + 1 + r), found);
                refs.addAll(found);
            }
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Ref<EntityStore> ref : refs) {
                ItemComponent item = ref.isValid() ? store.getComponent(ref, ItemComponent.getComponentType()) : null;
                TransformComponent at = item == null ? null : store.getComponent(ref, TransformComponent.getComponentType());
                ItemStack stack = item == null ? null : item.getItemStack();
                if (at == null || ItemStack.isEmpty(stack)) {
                    continue;
                }
                count++;
                Vector3d p = at.getPosition();
                sb.append(sb.isEmpty() ? "" : "; ").append(stack.getQuantity()).append(' ').append(stack.getItemId())
                        .append(String.format(Locale.ROOT, " @%.1f,%.1f,%.1f", p.x, p.y, p.z));
                long frequency = EntangledSingularity.frequency(stack);
                if (frequency != 0) {
                    sb.append(" frequency=").append(frequency);
                }
            }
            return msg("items.list").param("count", Integer.toString(count)).param("items", sb.toString());
        }
    }

    private static final class Charge extends At {
        private final RequiredArg<Integer> slot;

        Charge() {
            super("charge");
            slot = withRequiredArg("slot", "ae2cmd.arg.slot", ArgTypes.INTEGER);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            ItemContainer c = BlockAccess.container(world, x, y, z);
            int s = ctx.get(slot);
            ItemStack stack = c == null || s < 0 || s >= c.getCapacity() ? null : c.getItemStack((short) s);
            if (ItemStack.isEmpty(stack)) {
                return msg("charge.empty");
            }
            return msg("charge.value").param("item", stack.getItemId())
                    .param("stored", String.format(Locale.ROOT, "%.0f", PoweredItems.stored(stack)))
                    .param("max", String.format(Locale.ROOT, "%.0f", PoweredItems.capacity(stack)));
        }
    }

    private static final class Entropy extends At {
        private final RequiredArg<String> mode;

        Entropy() {
            super("entropy");
            mode = withRequiredArg("heat|cool", "ae2gadget.cmd.arg.mode", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            EntropyRecipes.Mode m = EntropyRecipes.Mode.parse(ctx.get(mode));
            if (m == null) {
                return msg("entropy.mode");
            }
            String before = describe(world, x, y, z);
            // wie ein Klick von oben auf die Zelle
            EntropyManipulator.Outcome outcome = EntropyManipulator.apply(WorldEdits.forConsole(world),
                    new EntropyManipulator.Target(x, y, z, new Vector3i(x, y + 1, z)), m);
            return msg("entropy.result").param("outcome", outcome.name()).param("before", before)
                    .param("after", describe(world, x, y, z)).param("above", describe(world, x, y + 1, z));
        }
    }

    /** „Block + Flüssigkeit“ einer Zelle. */
    private static String describe(World world, int x, int y, int z) {
        String block = BlockAccess.blockKey(world, x, y, z);
        String fluid = WorldEdits.fluidKey(world, x, y, z);
        return (block == null ? "?" : block) + (fluid == null ? "" : "+" + fluid);
    }

    private static final class Recolor extends At {
        private final RequiredArg<String> color;

        Recolor() {
            super("recolor");
            color = withRequiredArg("color", "ae2gadget.cmd.arg.color", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            AEColor c = PaintStorage.colorById(ctx.get(color).toLowerCase(Locale.ROOT));
            if (c == null) {
                return msg("recolor.color");
            }
            String before = describe(world, x, y, z);
            boolean done = ColorApplicator.recolor(world, WorldEdits.forConsole(world), null, x, y, z, c);
            return msg(done ? "recolor.done" : "recolor.none").param("before", before).param("after", describe(world, x, y, z));
        }
    }

    private static final class Shoot extends At {
        private final RequiredArg<String> direction;
        private final RequiredArg<String> item;

        Shoot() {
            super("shoot");
            direction = withRequiredArg("direction", "ae2gadget.cmd.arg.direction", ArgTypes.STRING);
            item = withRequiredArg("itemId", "ae2cmd.arg.item", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            BlockAccess.Dir dir;
            try {
                dir = BlockAccess.Dir.valueOf(ctx.get(direction).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return msg("shoot.direction");
            }
            MatterCannonAmmo ammo = MatterCannon.ammo();
            float penetration = ammo == null ? 0 : ammo.penetration(ctx.get(item));
            if (penetration <= 0) {
                return msg("shoot.noAmmo").param("item", ctx.get(item));
            }
            int destroyed = MatterCannon.standardAmmo(world, store, null, WorldEdits.forConsole(world), penetration,
                    new Vector3d(x + 0.5, y + 0.5, z + 0.5), new Vector3d(dir.dx, dir.dy, dir.dz));
            return msg("shoot.result").param("penetration", String.format(Locale.ROOT, "%.2f", penetration))
                    .param("destroyed", Integer.toString(destroyed));
        }
    }

    private static final class SetFluid extends At {
        private final RequiredArg<String> fluid;

        SetFluid() {
            super("fluid");
            fluid = withRequiredArg("fluidId|none|get", "ae2gadget.cmd.arg.fluid", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            String id = ctx.get(fluid);
            if (!"get".equalsIgnoreCase(id)) {
                WorldEdits.forConsole(world).setFluid(x, y, z, "none".equalsIgnoreCase(id) ? null : id);
            }
            return msg("fluid.now").param("cell", describe(world, x, y, z));
        }
    }

    private static final class Signal extends At {
        private final RequiredArg<String> state;

        Signal() {
            super("signal");
            state = withRequiredArg("on|off", "ae2cmd.arg.state", ArgTypes.STRING);
        }

        @Override
        Message run(CommandContext ctx, World world, Store<EntityStore> store, int x, int y, int z) {
            boolean on = "on".equalsIgnoreCase(ctx.get(state));
            Signals.of(world).setEmitting(x, y, z, on);
            return msg("signal.set").param("state", Boolean.toString(on));
        }
    }

    /** {@code /ae2 gadget fill}: lädt das Werkzeug in der Hand voll (zum Ausprobieren ohne Charger). */
    private static final class Fill extends AbstractPlayerCommand {
        Fill() {
            super("fill", "ae2gadget.cmd.fill.desc");
            requirePermission("ae2.admin");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef, @Nonnull World world) {
            InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
            ItemStack held = hotbar == null ? null : hotbar.getActiveItem();
            if (ItemStack.isEmpty(held) || !PoweredItems.isPowered(held.getItemId()) || hotbar.getActiveSlot() < 0) {
                context.sendMessage(msg("fill.none"));
                return;
            }
            ItemStack full = PoweredItems.withStored(held, PoweredItems.capacity(held));
            hotbar.getInventory().setItemStackForSlot(hotbar.getActiveSlot(), full);
            context.sendMessage(msg("fill.done").param("stored", String.format(Locale.ROOT, "%.0f", PoweredItems.stored(full))));
        }
    }
}
