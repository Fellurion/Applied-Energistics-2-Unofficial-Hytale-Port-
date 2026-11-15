/*
 * Applied Energistics 2 für Hytale.
 * Portierung (c) Fellurion, LGPL-3.0.
 */
package de.fellurion.ae2.hytale.meteorite;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.worldgen.chunk.ChunkGenerator;
import de.fellurion.ae2.core.worldgen.MeteoriteLocator;
import de.fellurion.ae2.core.worldgen.MeteoriteSite;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

/**
 * {@code /ae2 meteorites …}: Meteoriten je Welt einstellen (Admin, {@code ae2.admin}). Alle Unterbefehle wirken auf
 * die Welt des Spielers oder auf {@code --world <name>}.
 * <ul>
 *   <li>{@code status} – Einstellungen und bekannte Meteoriten der Welt</li>
 *   <li>{@code worldgen on|off} – Meteoriten bei der Weltgenerierung</li>
 *   <li>{@code impacts on|off} – Einschlag-Ereignis</li>
 *   <li>{@code interval <minuten>} – mittlerer Abstand zwischen zwei Einschlägen</li>
 *   <li>{@code protection normal|strict} – Bauschutz-Stufe</li>
 *   <li>{@code spawn} – jetzt einen Einschlag in der Nähe auslösen (der Bauschutz gilt trotzdem)</li>
 *   <li>{@code nearest} – nächster Meteorit</li>
 * </ul>
 */
public final class MeteoriteCommands extends AbstractCommandCollection {

    public MeteoriteCommands() {
        super("meteorites", "ae2cmd.meteorites.desc");
        addAliases("meteorite", "meteoriten");
        requirePermission("ae2.admin");
        addSubCommand(new Status());
        addSubCommand(new Toggle("worldgen", "ae2cmd.meteorites.worldgen.desc", true));
        addSubCommand(new Toggle("impacts", "ae2cmd.meteorites.impacts.desc", false));
        addSubCommand(new Interval());
        addSubCommand(new ProtectionLevel());
        addSubCommand(new Spawn());
        addSubCommand(new Allow());
        addSubCommand(new Nearest());
    }

    private static Message onOff(boolean on) {
        return Message.translation(on ? "ae2cmd.on" : "ae2cmd.off");
    }

    /** Position des Aufrufers (Spieler), sonst der Ursprung der Welt. */
    private static Vector3d origin(CommandContext context) {
        if (context.isPlayer() && context.sender() instanceof PlayerRef player && player.getTransform() != null) {
            return new Vector3d(player.getTransform().getPosition());
        }
        return new Vector3d(0, 0, 0);
    }

    private abstract static class Sub extends AbstractWorldCommand {
        Sub(String name, String description) {
            super(name, description);
            requirePermission("ae2.admin");
        }
    }

    /** Unterbefehl mit optionaler Position ({@code --x <x> --z <z>}); ohne Angabe die Position des Spielers. */
    private abstract static class Positioned extends Sub {
        private final OptionalArg<Integer> x;
        private final OptionalArg<Integer> z;

        Positioned(String name, String description) {
            super(name, description);
            x = withOptionalArg("x", "ae2cmd.arg.x", ArgTypes.INTEGER);
            z = withOptionalArg("z", "ae2cmd.arg.z", ArgTypes.INTEGER);
        }

        Vector3d position(CommandContext context) {
            if (x.provided(context) && z.provided(context)) {
                return new Vector3d(context.get(x), 0, context.get(z));
            }
            return origin(context);
        }
    }

    private static final class Status extends Sub {
        Status() {
            super("status", "ae2cmd.meteorites.status.desc");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            MeteoriteConfig.Settings s = MeteoriteConfig.of(world.getName());
            MeteoriteRegistry registry = MeteoriteRegistry.of(world.getName());
            boolean standard = world.getChunkStore().getGenerator() instanceof ChunkGenerator;
            context.sendMessage(Message.translation("ae2cmd.meteorites.status.world").param("world", world.getName())
                    .param("generator", Message.translation(standard ? "ae2cmd.meteorites.generator.standard" : "ae2cmd.meteorites.generator.other")));
            context.sendMessage(Message.translation("ae2cmd.meteorites.status.worldgen")
                    .param("state", onOff(s.worldgen() && (standard || s.worldgenExplicit()))));
            context.sendMessage(Message.translation("ae2cmd.meteorites.status.impacts").param("state", onOff(s.impacts()))
                    .param("minutes", s.intervalMinutes())
                    .param("protection", s.protection().name().toLowerCase(Locale.ROOT)));
            context.sendMessage(Message.translation("ae2cmd.meteorites.status.known").param("count", registry.size())
                    .param("touched", registry.touchedCount()));
            java.util.Set<String> allowed = MeteoriteConfig.global().naturalBlocks();
            if (!allowed.isEmpty()) {
                context.sendMessage(Message.translation("ae2cmd.meteorites.status.allowed")
                        .param("blocks", String.join(", ", allowed)));
            }
        }
    }

    private static final class Toggle extends Sub {
        private final boolean worldgen;
        private final RequiredArg<String> state;

        Toggle(String name, String description, boolean worldgen) {
            super(name, description);
            this.worldgen = worldgen;
            this.state = withRequiredArg("on|off", "ae2cmd.arg.state", ArgTypes.STRING);
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Boolean on = parse(context.get(state));
            if (on == null) {
                context.sendMessage(Message.translation("ae2cmd.meteorites.onOffExpected"));
                return;
            }
            if (worldgen) {
                MeteoriteConfig.setWorldgen(world.getName(), on);
                context.sendMessage(Message.translation("ae2cmd.meteorites.worldgen.set").param("world", world.getName())
                        .param("state", onOff(on)));
                if (on && !(world.getChunkStore().getGenerator() instanceof ChunkGenerator)) {
                    context.sendMessage(Message.translation("ae2cmd.meteorites.worldgen.otherGenerator"));
                }
            } else {
                MeteoriteConfig.setImpacts(world.getName(), on);
                MeteoriteImpacts.settingsChanged(world);
                context.sendMessage(Message.translation("ae2cmd.meteorites.impacts.set").param("world", world.getName())
                        .param("state", onOff(on)));
            }
        }

        @Nullable
        private static Boolean parse(String s) {
            return switch (s == null ? "" : s.toLowerCase(Locale.ROOT)) {
                case "on", "an", "true", "ein", "1", "yes", "ja" -> true;
                case "off", "aus", "false", "0", "no", "nein" -> false;
                default -> null;
            };
        }
    }

    private static final class Interval extends Sub {
        private final RequiredArg<Integer> minutes;

        Interval() {
            super("interval", "ae2cmd.meteorites.interval.desc");
            minutes = withRequiredArg("minutes", "ae2cmd.arg.minutes", ArgTypes.INTEGER);
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            int m = Math.max(1, context.get(minutes));
            MeteoriteConfig.setInterval(world.getName(), m);
            MeteoriteImpacts.settingsChanged(world);
            context.sendMessage(Message.translation("ae2cmd.meteorites.interval.set").param("world", world.getName())
                    .param("minutes", m));
        }
    }

    private static final class ProtectionLevel extends Sub {
        private final RequiredArg<String> level;

        ProtectionLevel() {
            super("protection", "ae2cmd.meteorites.protection.desc");
            level = withRequiredArg("normal|strict", "ae2cmd.arg.protection", ArgTypes.STRING);
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            MeteoriteConfig.Protection p;
            try {
                p = MeteoriteConfig.Protection.valueOf(context.get(level).toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                context.sendMessage(Message.translation("ae2cmd.meteorites.protection.unknown"));
                return;
            }
            MeteoriteConfig.setProtection(world.getName(), p);
            context.sendMessage(Message.translation("ae2cmd.meteorites.protection.set." + p.name().toLowerCase(Locale.ROOT))
                    .param("world", world.getName()));
        }
    }

    private static final class Spawn extends Positioned {
        Spawn() {
            super("spawn", "ae2cmd.meteorites.spawn.desc");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3d at = position(context);
            MeteoriteImpacts.SpawnResult result = MeteoriteImpacts.spawnNear(world, at.x, at.z);
            if (result.site() != null) {
                double dx = result.site().x() - at.x, dz = result.site().z() - at.z;
                context.sendMessage(Message.translation("ae2cmd.meteorites.spawn.started").param("x", result.site().x())
                        .param("z", result.site().z()).param("distance", (int) Math.sqrt(dx * dx + dz * dz))
                        .param("direction", Message.translation("ae2cmd.direction." + direction(dx, dz))));
                return;
            }
            context.sendMessage(Message.translation("ae2cmd.meteorites.spawn.failed")
                    .param("reason", Message.translation(result.rejected())));
            if ("ae2meteor.reject.building".equals(result.rejected()) && BuildProtection.lastBuilding != null) {
                // dem Admin zeigen, welcher Block den Bauschutz ausgelöst hat – und wie er ihn freigeben kann
                String block = BuildProtection.lastBuilding;
                context.sendMessage(Message.translation("ae2cmd.meteorites.spawn.block").param("block", block));
                if (BuildProtection.lastBuildingUnknown) {
                    context.sendMessage(Message.translation("ae2cmd.meteorites.spawn.allowHint")
                            .param("block", block.substring(0, Math.max(0, block.indexOf(" @ ")))));
                }
            }
        }

        /** Himmelsrichtung (Hytale: Norden = −Z, Osten = +X). */
        private static String direction(double dx, double dz) {
            String[] names = {"e", "se", "s", "sw", "w", "nw", "n", "ne"};
            double angle = Math.toDegrees(Math.atan2(dz, dx));
            return names[(int) Math.floorMod(Math.round(angle / 45.0), 8)];
        }
    }

    /** {@code /ae2 meteorites allow <Block>}: Block einer anderen Mod als natürliches Gelände freigeben. */
    private static final class Allow extends Sub {
        private final RequiredArg<String> block;

        Allow() {
            super("allow", "ae2cmd.meteorites.allow.desc");
            block = withRequiredArg("block", "ae2cmd.arg.block", ArgTypes.STRING);
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            String pattern = context.get(block).trim();
            if (pattern.isEmpty() || pattern.equals("*")) {
                context.sendMessage(Message.translation("ae2cmd.meteorites.allow.invalid"));
                return;
            }
            boolean added = MeteoriteConfig.allowNatural(pattern);
            context.sendMessage(Message.translation(added ? "ae2cmd.meteorites.allow.added" : "ae2cmd.meteorites.allow.known")
                    .param("block", pattern));
        }
    }

    private static final class Nearest extends Positioned {
        Nearest() {
            super("nearest", "ae2cmd.meteorites.nearest.desc");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
            Vector3d at = position(context);
            MeteoriteRegistry.Entry known = MeteoriteRegistry.of(world.getName()).nearest(at.x, at.z, false, 100_000);
            if (known != null) {
                context.sendMessage(Message.translation("ae2cmd.meteorites.nearest.known").param("x", known.x())
                        .param("y", known.y()).param("z", known.z())
                        .param("distance", (int) Math.sqrt(known.distanceSquared(at.x, at.z)))
                        .param("cube", Message.translation(known.cube() ? "ae2cmd.meteorites.cube.yes" : "ae2cmd.meteorites.cube.no")));
            }
            // Im Standard-Gelände steht schon vor dem Generieren fest, wo Meteoriten entstehen werden
            MeteoriteSite planned = nearestPlanned(world, at.x, at.z);
            if (planned != null) {
                double dx = planned.x() - at.x, dz = planned.z() - at.z;
                context.sendMessage(Message.translation("ae2cmd.meteorites.nearest.planned").param("x", planned.x())
                        .param("y", planned.y()).param("z", planned.z()).param("distance", (int) Math.sqrt(dx * dx + dz * dz)));
            }
            if (known == null && planned == null) {
                context.sendMessage(Message.translation("ae2cmd.meteorites.nearest.none"));
            }
        }

        /**
         * Nächster Meteorit, den die Weltgenerierung noch bauen wird: Gelände, das schon existiert (gespeichert oder
         * geladen), bekommt nachträglich keinen mehr und zählt deshalb nicht.
         */
        @Nullable
        private static MeteoriteSite nearestPlanned(World world, double x, double z) {
            if (!(world.getChunkStore().getGenerator() instanceof ChunkGenerator terrain)
                    || !MeteoriteConfig.of(world.getName()).worldgen()) {
                return null;
            }
            LongOpenHashSet existing = new LongOpenHashSet(world.getChunkStore().getChunkIndexes());
            try {
                if (world.getChunkStore().getLoader() != null) {
                    existing.addAll(world.getChunkStore().getLoader().getIndexes());
                }
            } catch (java.io.IOException | RuntimeException ignored) {
                // ohne Liste der gespeicherten Chunks: nur geladene berücksichtigen
            }
            long seed = world.getWorldConfig().getSeed();
            int rx = MeteoriteLocator.region((int) x), rz = MeteoriteLocator.region((int) z);
            MeteoriteSite best = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    MeteoriteSite s = MeteoriteWorldgen.plan(world.getName(), terrain, seed, rx + dx, rz + dz);
                    if (s == null || existing.contains(ChunkUtil.indexChunkFromBlock(s.x(), s.z()))) {
                        continue;
                    }
                    double d = (s.x() - x) * (s.x() - x) + (s.z() - z) * (s.z() - z);
                    if (d < bestD) {
                        bestD = d;
                        best = s;
                    }
                }
            }
            return best;
        }
    }
}
