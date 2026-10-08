package com.minebot.command;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotLog;
import com.minebot.bot.BotManager;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.BotRegistry;
import com.minebot.bot.BotSpawnPoints;
import com.minebot.bot.world.NoGoAreas;
import com.minebot.bot.ai.CompanionTask;
import com.minebot.bot.ai.GoToTask;
import com.minebot.bot.ai.Home;
import com.minebot.bot.ai.Needs;
import com.minebot.bot.ai.ObtainTask;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * /bot spawn|remove|list|goto|travel|come|stop|usebed|spawnpoint|remove|list|goto|come|stop|spawnpoint
 */
public final class BotCommand {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SimpleCommandExceptionType NO_SUCH_BOT =
        new SimpleCommandExceptionType(Component.literal("No bot with that name"));
    /** How far around the workshop spot its chests are looked for (a hut, chests stacked two high). */
    private static final int WORKSHOP_RANGE = 6;

    private BotCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(Commands.literal("bot")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("spawn")
                .executes(ctx -> spawn(ctx, BotManager.nextFreeName(ctx.getSource().getServer())))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("remove")
                .then(botArgument().executes(ctx -> {
                    BotPlayer bot = getBot(ctx);
                    BotManager.remove(bot);
                    return 1;
                })))
            .then(Commands.literal("list")
                .executes(BotCommand::list))
            .then(Commands.literal("goto")
                .then(botArgument()
                    .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(ctx -> {
                            BotPlayer bot = getBot(ctx);
                            Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
                            bot.brain().setCommand(new GoToTask(bot, Goal.near(BlockPos.containing(pos), 1.0)));
                            reply(ctx, bot.getPlainTextName() + " is heading to "
                                + String.format(Locale.ROOT, "%.1f %.1f %.1f", pos.x, pos.y, pos.z));
                            return 1;
                        }))))
            .then(Commands.literal("travel")
                .then(botArgument()
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(ctx -> {
                                BotPlayer bot = getBot(ctx);
                                int x = IntegerArgumentType.getInteger(ctx, "x");
                                int z = IntegerArgumentType.getInteger(ctx, "z");
                                bot.brain().setCommand(new GoToTask(bot, Goal.column(x, z, 2.0)));
                                reply(ctx, bot.getPlainTextName() + " is travelling to " + x + " " + z);
                                return 1;
                            })))))
            .then(Commands.literal("get")
                .then(botArgument()
                    .then(Commands.argument("item", ItemArgument.item(buildContext))
                        .executes(ctx -> obtain(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                            .executes(ctx -> obtain(ctx, IntegerArgumentType.getInteger(ctx, "count")))))))
            .then(Commands.literal("do")
                .then(botArgument()
                    .then(Commands.argument("need", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                            Needs.ALL.stream().map(Needs.Need::id), builder))
                        .executes(ctx -> {
                            BotPlayer bot = getBot(ctx);
                            String id = StringArgumentType.getString(ctx, "need");
                            Needs.Need need = Needs.ALL.stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
                            if (need == null) {
                                ctx.getSource().sendFailure(Component.literal("Unknown need: " + id));
                                return 0;
                            }
                            bot.brain().setCommand(need.task().apply(bot));
                            reply(ctx, bot.getPlainTextName() + " will do: " + id);
                            return 1;
                        }))))
            .then(Commands.literal("info")
                .then(botArgument().executes(BotCommand::info)))
            .then(Commands.literal("log")
                .then(botArgument()
                    .executes(ctx -> showLog(ctx, 10))
                    .then(Commands.argument("lines", IntegerArgumentType.integer(1, 50))
                        .executes(ctx -> showLog(ctx, IntegerArgumentType.getInteger(ctx, "lines"))))))
            .then(Commands.literal("anchor")
                .then(botArgument().executes(ctx -> {
                    BotPlayer bot = getBot(ctx);
                    bot.setAnchor(GlobalPos.of(bot.level().dimension(), bot.blockPosition()));
                    reply(ctx, bot.getPlainTextName() + " now lives within " + BotMemory.ZONE_RADIUS + " blocks of "
                        + bot.blockPosition().toShortString());
                    return 1;
                })))
            .then(Commands.literal("workshop")
                .then(botArgument()
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(BotCommand::setWorkshop))))
            .then(Commands.literal("come")
                .then(botArgument().executes(ctx -> {
                    BotPlayer bot = getBot(ctx);
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    bot.brain().setCommand(new CompanionTask(bot, player));
                    reply(ctx, bot.getPlainTextName() + " is following you");
                    return 1;
                })))
            .then(Commands.literal("follow")
                .then(botArgument()
                    .then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                        .executes(ctx -> {
                            BotPlayer bot = getBot(ctx);
                            ServerPlayer player = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player");
                            if (player == bot) {
                                ctx.getSource().sendFailure(Component.literal("A bot can't follow itself"));
                                return 0;
                            }
                            bot.brain().setCommand(new CompanionTask(bot, player));
                            reply(ctx, bot.getPlainTextName() + " is following " + player.getPlainTextName());
                            return 1;
                        }))))
            .then(Commands.literal("kill")
                .then(botArgument()
                    .then(Commands.argument("target", net.minecraft.commands.arguments.EntityArgument.player())
                        .executes(ctx -> {
                            BotPlayer bot = getBot(ctx);
                            ServerPlayer target = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "target");
                            if (target == bot) {
                                ctx.getSource().sendFailure(Component.literal("A bot can't be sent after itself"));
                                return 0;
                            }
                            bot.brain().setCommand(new com.minebot.bot.ai.KillTask(bot, target));
                            reply(ctx, bot.getPlainTextName() + " is going after " + target.getPlainTextName());
                            return 1;
                        }))))
            .then(Commands.literal("usebed")
                .then(botArgument().executes(ctx -> {
                    BotPlayer bot = getBot(ctx);
                    if (!bot.useNearestBed()) {
                        ctx.getSource().sendFailure(Component.literal("No bed within reach of " + bot.getPlainTextName()));
                        return 0;
                    }
                    reply(ctx, bot.getRespawnConfig() != null
                        ? bot.getPlainTextName() + " will respawn at this bed"
                        : bot.getPlainTextName() + " could not use the bed");
                    return 1;
                })))
            .then(Commands.literal("debug")
                .then(botArgument().executes(ctx -> {
                    BotPlayer bot = getBot(ctx);
                    bot.setDebug(!bot.isDebug());
                    reply(ctx, "Debug log for " + bot.getPlainTextName() + (bot.isDebug() ? " on" : " off"));
                    return 1;
                })))
            .then(Commands.literal("auto")
                .then(botArgument()
                    .then(Commands.literal("on").executes(ctx -> setAuto(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> setAuto(ctx, false)))))
            .then(Commands.literal("stop")
                .then(botArgument().executes(ctx -> {
                    BotPlayer bot = getBot(ctx);
                    bot.brain().setCommand(null);
                    reply(ctx, bot.getPlainTextName() + " stopped");
                    return 1;
                })))
            .then(Commands.literal("nogo")
                .then(Commands.literal("add")
                    .then(Commands.argument("x1", IntegerArgumentType.integer())
                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                            .then(Commands.argument("x2", IntegerArgumentType.integer())
                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                    .executes(ctx -> addNoGo(ctx, ""))
                                    .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> addNoGo(ctx, StringArgumentType.getString(ctx, "name")))))))))
                .then(Commands.literal("list").executes(BotCommand::listNoGo))
                .then(Commands.literal("remove")
                    .then(Commands.argument("number", IntegerArgumentType.integer(1))
                        .executes(BotCommand::removeNoGo))))
            .then(BuildCommand.node())
            .then(Commands.literal("stats").executes(ctx -> {
                com.minebot.stats.ServerStats.post(ctx.getSource().getServer()); // (the hourly summary, now)
                return 1;
            }))
            .then(Commands.literal("house").then(Commands.literal("designs").executes(BotCommand::houseDesigns)))
            .then(Commands.literal("spawnpoint")
                .then(Commands.literal("add")
                    .executes(ctx -> addSpawnPoint(ctx, BlockPos.containing(ctx.getSource().getPosition())))
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(ctx -> addSpawnPoint(ctx, BlockPosArgument.getBlockPos(ctx, "pos")))))
                .then(Commands.literal("remove")
                    .then(Commands.argument("number", IntegerArgumentType.integer(1, BotSpawnPoints.MAX))
                        .executes(BotCommand::removeSpawnPoint)))
                .then(Commands.literal("list")
                    .executes(BotCommand::listSpawnPoints))));
    }

    /** The schematic houses the bots build: size, ground, front, bed and table, and what they take. */
    private static int houseDesigns(CommandContext<CommandSourceStack> ctx) {
        var designs = com.minebot.bot.build.HouseSchematics.all();
        StringBuilder text = new StringBuilder(designs.size() + " house designs:");
        for (int i = 0; i < designs.size(); i++) {
            var design = designs.get(i);
            var plan = com.minebot.bot.build.HousePlans.create(com.minebot.bot.build.HousePlans.SCHEMATIC_BASE + i, BlockPos.ZERO,
                net.minecraft.core.Direction.NORTH);
            java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
            java.util.Map<String, Integer> items = new java.util.HashMap<>();
            for (var cell : plan.cells()) {
                kinds.merge(String.valueOf(cell.kind()), 1, Integer::sum);
                if (cell.state() != null && cell.kind() == 'X') {
                    items.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(cell.state().getBlock().asItem()).getPath(), 1, Integer::sum);
                }
            }
            var s = design.schematic();
            text.append("\n ").append(design.name()).append(": ").append(s.width()).append("x").append(s.height()).append("x").append(s.length())
                .append(", ground layer ").append(design.groundLayer()).append(", front ").append(design.front())
                .append(", cells ").append(kinds);
            items.entrySet().stream().sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> text.append(" ").append(e.getKey()).append(" ").append(e.getValue()).append(","));
        }
        reply(ctx, text.toString());
        return designs.size();
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BotPlayer bot = getBot(ctx);
        BotMemory memory = bot.memory();
        StringBuilder text = new StringBuilder(bot.getPlainTextName()).append(": ").append(bot.brain().describe());
        text.append("\n  anchor ").append(describe(memory.anchor()))
            .append(", autonomous ").append(memory.autonomous());
        text.append("\n  home ").append(memory.home() == null ? "none" : describe(memory.home()))
            .append(memory.builtHome() ? " (built)" : "");
        text.append("\n  bed ").append(pos(memory.bed())).append(", chests ")
            .append(memory.chests().stream().map(BotCommand::pos).toList())
            .append(", table ").append(pos(memory.craftingTable()))
            .append(", furnace ").append(pos(memory.furnace()))
            .append(", campfire ").append(pos(memory.campfire()))
            .append(", workshop ").append(pos(memory.workshop()));
        text.append(String.format(Locale.ROOT, "\n  hp %.0f, food %d", bot.getHealth(), bot.getFoodData().getFoodLevel()));
        reply(ctx, text.toString());
        return 1;
    }

    /**
     * Gives a bot living in its house its old hut back as the workshop (pos: where the hut's bed
     * was), with the chests there, and the furnace and campfire if it has none.
     */
    private static int setWorkshop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BotPlayer bot = getBot(ctx);
        BlockPos workshop = BlockPosArgument.getBlockPos(ctx, "pos");
        BotMemory memory = bot.memory();
        ServerLevel level = Home.levelIfHere(bot);
        if (level == null || !level.isLoaded(workshop)) {
            ctx.getSource().sendFailure(Component.literal(bot.getPlainTextName() + " is away from home, or that spot isn't loaded"));
            return 0;
        }
        memory.setWorkshop(workshop);
        int chests = 0;
        for (BlockPos pos : BlockPos.betweenClosed(workshop.offset(-WORKSHOP_RANGE, -2, -WORKSHOP_RANGE),
                workshop.offset(WORKSHOP_RANGE, 2, WORKSHOP_RANGE))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof ChestBlock) {
                // (a double chest is one entry)
                BlockPos other = state.getValue(ChestBlock.TYPE) == ChestType.SINGLE ? pos
                    : pos.relative(ChestBlock.getConnectedDirection(state));
                if (!isBotChest(level, pos) && !isBotChest(level, other)) {
                    memory.addChest(pos);
                    chests++;
                }
            } else if (state.getBlock() instanceof FurnaceBlock && memory.furnace() == null) {
                memory.setFurnace(pos.immutable());
            } else if (state.getBlock() instanceof CampfireBlock && memory.campfire() == null) {
                memory.setCampfire(pos.immutable());
            }
        }
        reply(ctx, bot.getPlainTextName() + "'s workshop is at " + workshop.toShortString() + " (" + chests + " chests added)");
        return 1;
    }

    private static boolean isBotChest(ServerLevel level, BlockPos pos) {
        for (BotMemory memory : BotRegistry.get(level.getServer()).all()) {
            if (memory.chests().contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private static String pos(@Nullable BlockPos pos) {
        return pos == null ? "-" : pos.toShortString();
    }

    private static String describe(GlobalPos pos) {
        return pos.pos().toShortString() + " " + pos.dimension().identifier();
    }

    private static int showLog(CommandContext<CommandSourceStack> ctx, int lines) {
        String name = StringArgumentType.getString(ctx, "bot");
        List<String> tail = BotLog.tail(name, lines);
        if (tail.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No log for " + name));
            return 0;
        }
        reply(ctx, "logs/minebot/" + name.toLowerCase(Locale.ROOT) + ".log:\n" + String.join("\n", tail));
        return tail.size();
    }

    private static int setAuto(CommandContext<CommandSourceStack> ctx, boolean on) throws CommandSyntaxException {
        BotPlayer bot = getBot(ctx);
        bot.memory().setAutonomous(on);
        reply(ctx, bot.getPlainTextName() + (on ? " lives on its own now" : " only follows commands now"));
        return 1;
    }

    private static int obtain(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
        BotPlayer bot = getBot(ctx);
        Item item = ItemArgument.getItem(ctx, "item").getItem();
        bot.brain().setCommand(new ObtainTask(bot, Target.of(item, count), 0).digValuables());
        reply(ctx, bot.getPlainTextName() + " will get " + count + "x " + item.getName().getString());
        return 1;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> botArgument() {
        return Commands.argument("bot", StringArgumentType.word())
            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                BotManager.all().stream().map(BotPlayer::getPlainTextName), builder));
    }

    private static BotPlayer getBot(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BotPlayer bot = BotManager.get(StringArgumentType.getString(ctx, "bot"));
        if (bot == null) {
            throw NO_SUCH_BOT.create();
        }
        return bot;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        if (name.length() > 16 || !name.matches("[A-Za-z0-9_]+")) {
            source.sendFailure(Component.literal("Name must be 1-16 characters: letters, digits, _"));
            return 0;
        }
        // Without spawn points, a player running the command spawns the bot next to them
        BotSpawnPoints.Location fallback = source.getEntity() != null
            ? new BotSpawnPoints.Location(source.getLevel(), source.getPosition(), source.getRotation().y)
            : null;
        try {
            BotPlayer bot = BotManager.spawn(source.getServer(), name, fallback);
            reply(ctx, String.format(Locale.ROOT, "Spawned %s at %d %d %d (%d/%d bots)", bot.getPlainTextName(),
                bot.getBlockX(), bot.getBlockY(), bot.getBlockZ(), BotManager.all().size(), BotManager.MAX_BOTS));
        } catch (IllegalStateException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        } catch (RuntimeException e) {
            LOGGER.error("Failed to spawn bot {}", name, e);
            source.sendFailure(Component.literal("Failed to spawn bot: " + e));
            return 0;
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        if (BotManager.all().isEmpty()) {
            reply(ctx, "No bots");
            return 0;
        }
        String text = BotManager.all().stream()
            .map(bot -> {
                LivingEntity target = bot.combat().target();
                String state = bot.isDeadOrDying() ? "DEAD"
                    : target != null ? "FIGHTING " + target.getPlainTextName()
                    : bot.brain().describe();
                return String.format(Locale.ROOT, "%s [%s] hp %.0f at %d %d %d %s", bot.getPlainTextName(), state,
                    bot.getHealth(), bot.getBlockX(), bot.getBlockY(), bot.getBlockZ(),
                    bot.level().dimension().identifier());
            })
            .collect(Collectors.joining("\n"));
        reply(ctx, text);
        return BotManager.all().size();
    }

    private static int addSpawnPoint(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        CommandSourceStack source = ctx.getSource();
        BotSpawnPoints points = BotSpawnPoints.get(source.getServer());
        BotSpawnPoints.Point point = new BotSpawnPoints.Point(
            GlobalPos.of(source.getLevel().dimension(), pos), source.getRotation().y);
        if (!points.add(point)) {
            source.sendFailure(Component.literal("Already " + BotSpawnPoints.MAX
                + " spawn points; remove one first (/bot spawnpoint remove <number>)"));
            return 0;
        }
        reply(ctx, "Added spawn point #" + points.points().size() + ": " + describe(point));
        return 1;
    }

    private static int addNoGo(CommandContext<CommandSourceStack> ctx, String name) {
        NoGoAreas.Area area = NoGoAreas.Area.of(ctx.getSource().getLevel().dimension(),
            IntegerArgumentType.getInteger(ctx, "x1"), IntegerArgumentType.getInteger(ctx, "z1"),
            IntegerArgumentType.getInteger(ctx, "x2"), IntegerArgumentType.getInteger(ctx, "z2"), name);
        NoGoAreas areas = NoGoAreas.get(ctx.getSource().getServer());
        areas.add(area);
        reply(ctx, "Added no-go area #" + areas.areas().size() + ": " + area.describe());
        return 1;
    }

    private static int listNoGo(CommandContext<CommandSourceStack> ctx) {
        List<NoGoAreas.Area> areas = NoGoAreas.get(ctx.getSource().getServer()).areas();
        if (areas.isEmpty()) {
            reply(ctx, "No no-go areas. Add one with /bot nogo add <x1> <z1> <x2> <z2> [name]");
            return 0;
        }
        StringBuilder text = new StringBuilder("No-go areas:");
        for (int i = 0; i < areas.size(); i++) {
            text.append("\n#").append(i + 1).append(": ").append(areas.get(i).describe());
        }
        reply(ctx, text.toString());
        return areas.size();
    }

    private static int removeNoGo(CommandContext<CommandSourceStack> ctx) {
        int number = IntegerArgumentType.getInteger(ctx, "number");
        if (!NoGoAreas.get(ctx.getSource().getServer()).remove(number - 1)) {
            ctx.getSource().sendFailure(Component.literal("No no-go area #" + number));
            return 0;
        }
        reply(ctx, "Removed no-go area #" + number);
        return 1;
    }

    private static int removeSpawnPoint(CommandContext<CommandSourceStack> ctx) {
        int number = IntegerArgumentType.getInteger(ctx, "number");
        if (!BotSpawnPoints.get(ctx.getSource().getServer()).remove(number - 1)) {
            ctx.getSource().sendFailure(Component.literal("No spawn point #" + number));
            return 0;
        }
        reply(ctx, "Removed spawn point #" + number);
        return 1;
    }

    private static int listSpawnPoints(CommandContext<CommandSourceStack> ctx) {
        List<BotSpawnPoints.Point> points = BotSpawnPoints.get(ctx.getSource().getServer()).points();
        if (points.isEmpty()) {
            reply(ctx, "No spawn points. Add one with /bot spawnpoint add");
            return 0;
        }
        StringBuilder text = new StringBuilder("Spawn points:");
        for (int i = 0; i < points.size(); i++) {
            text.append("\n#").append(i + 1).append(": ").append(describe(points.get(i)));
        }
        reply(ctx, text.toString());
        return points.size();
    }

    private static String describe(BotSpawnPoints.Point point) {
        BlockPos pos = point.pos().pos();
        return String.format(Locale.ROOT, "%d %d %d %s", pos.getX(), pos.getY(), pos.getZ(),
            point.pos().dimension().identifier());
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String text) {
        // Sent straight to the player: data packs like NAuth turn the send_command_feedback
        // game rule off, which would hide every /bot answer
        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal(text));
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        }
    }
}
