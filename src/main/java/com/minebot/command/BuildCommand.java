package com.minebot.command;

import com.minebot.bot.BotManager;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.build.GreatBuild;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * /bot build: the Great Build ({@link GreatBuild}).
 * start &lt;file&gt; &lt;pos&gt; | stop | status | orders | now | end
 */
final class BuildCommand {
    private BuildCommand() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("build")
            .then(Commands.literal("start")
                .then(Commands.argument("file", StringArgumentType.string())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(files(ctx.getSource().getServer()), builder))
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(BuildCommand::start))))
            .then(Commands.literal("stop").executes(ctx -> {
                GreatBuild.get(ctx.getSource().getServer()).stop(ctx.getSource().getServer());
                reply(ctx, "The Great Build is stopped");
                return 1;
            }))
            .then(Commands.literal("status").executes(BuildCommand::status))
            .then(Commands.literal("orders").executes(BuildCommand::orders))
            .then(Commands.literal("materials").executes(BuildCommand::materials))
            .then(Commands.literal("prep").executes(BuildCommand::prep))
            .then(Commands.literal("go")
                .executes(ctx -> sendEarly(ctx, null))
                .then(Commands.argument("bot", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                        BotManager.all().stream().map(BotPlayer::getPlainTextName), builder))
                    .executes(ctx -> sendEarly(ctx, StringArgumentType.getString(ctx, "bot")))))
            .then(Commands.literal("writeoff").executes(ctx -> {
                GreatBuild build = existing(ctx);
                if (build == null) {
                    return 0;
                }
                build.allowWriteOffNow();
                reply(ctx, "Great Build: layers the bots can't get at may be written off from now on (no waiting for the sessions)");
                return 1;
            }))
            .then(Commands.literal("retry").executes(ctx -> {
                GreatBuild build = existing(ctx);
                if (build == null) {
                    return 0;
                }
                build.clearFailures(); // (the orders stay: the bots have been getting them ready)
                reply(ctx, "Great Build: what the bots couldn't get is forgotten; they'll try it again");
                return 1;
            }))
            .then(Commands.literal("now").executes(ctx -> {
                GreatBuild build = existing(ctx);
                if (build == null) {
                    return 0;
                }
                build.startSession(ctx.getSource().getServer());
                return 1;
            }))
            .then(Commands.literal("end").executes(ctx -> {
                GreatBuild build = existing(ctx);
                if (build == null || !build.inSession(ctx.getSource().getServer())) {
                    reply(ctx, "No session going on");
                    return 0;
                }
                build.endSession(ctx.getSource().getServer());
                return 1;
            }));
    }

    /** Off to the site now, ahead of the session (it doesn't start it): all the bots on the server, or one. */
    private static int sendEarly(CommandContext<CommandSourceStack> ctx, @org.jetbrains.annotations.Nullable String name) {
        GreatBuild build = existing(ctx);
        if (build == null) {
            return 0;
        }
        List<BotPlayer> bots = new ArrayList<>();
        for (BotPlayer bot : BotManager.all()) {
            if (name == null || bot.getPlainTextName().equalsIgnoreCase(name)) {
                bots.add(bot);
            }
        }
        if (bots.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(name == null ? "No bots on the server" : "No bot " + name + " on the server"));
            return 0;
        }
        int sent = build.sendEarly(bots);
        reply(ctx, sent + " bot(s) set off for the Great Build now; they'll work there and wait for the start (day "
            + build.nextDay() + ")");
        if (sent > 0) {
            GreatBuild.announce(ctx.getSource().getServer(), (name == null ? "Боты выходят" : name + " выходит")
                + " на стройку раньше срока! Координаты: " + build.centre().getX() + " " + build.centre().getY() + " " + build.centre().getZ());
        }
        return sent;
    }

    private static List<String> files(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        try (Stream<java.nio.file.Path> list = Files.list(GreatBuild.schematicsDir(server))) {
            list.forEach(path -> names.add("\"" + path.getFileName() + "\""));
        } catch (IOException e) {
            // (no folder yet)
        }
        return names;
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String file = StringArgumentType.getString(ctx, "file");
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        try {
            String summary = GreatBuild.get(server).start(server, file, pos);
            reply(ctx, "Great Build started: " + file + " at " + pos.toShortString() + " (" + summary + ")");
            return 1;
        } catch (IOException | RuntimeException e) {
            ctx.getSource().sendFailure(Component.literal("Can't start: " + e.getMessage()
                + " (schematics go in " + GreatBuild.schematicsDir(server) + ")"));
            return 0;
        }
    }

    private static GreatBuild existing(CommandContext<CommandSourceStack> ctx) {
        GreatBuild build = GreatBuild.get(ctx.getSource().getServer());
        if (!build.exists() || build.plan(ctx.getSource().getServer()) == null) {
            ctx.getSource().sendFailure(Component.literal(build.exists()
                ? "The schematic can't be read: " + build.planError() : "There's no Great Build; /bot build start <file> <pos>"));
            return null;
        }
        return build;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        GreatBuild build = existing(ctx);
        if (build == null) {
            return 0;
        }
        long day = server.overworld().getDayTime() / GreatBuild.DAY;
        StringBuilder text = new StringBuilder("Great Build: " + build.file() + " at " + build.centre().toShortString()
            + ", done " + build.progressText() + " (as far as seen), placed " + build.placed() + ", dug " + build.dug()
            + ", sessions " + build.sessions());
        if (build.inSession(server)) {
            text.append("\n session on, ends in ").append((build.sessionEnd() - server.overworld().getDayTime()) / 1200).append(" min");
        } else {
            text.append("\n next session in ").append(Math.max(0, build.nextDay() - day)).append(" days (day ").append(build.nextDay()).append(")");
        }
        if (!build.failures().isEmpty()) {
            text.append("\n bots couldn't get: ").append(build.failures());
        }
        text.append("\n ").append(build.writeOffText());
        reply(ctx, text.toString());
        return 1;
    }

    private static int orders(CommandContext<CommandSourceStack> ctx) {
        GreatBuild build = existing(ctx);
        if (build == null) {
            return 0;
        }
        StringBuilder text = new StringBuilder("Great Build orders:");
        for (Map.Entry<UUID, Map<String, Integer>> entry : build.orders().entrySet()) {
            String name = entry.getKey().toString();
            for (BotPlayer bot : BotManager.all()) {
                if (bot.getUUID().equals(entry.getKey())) {
                    name = bot.getPlainTextName();
                }
            }
            text.append("\n ").append(name).append(": ");
            for (Map.Entry<Item, Integer> order : build.orderOf(entry.getKey()).entrySet()) {
                text.append(GreatBuild.key(order.getKey()).replace("minecraft:", "")).append(" ").append(order.getValue()).append(", ");
            }
        }
        reply(ctx, text.toString());
        return 1;
    }

    /** Every block the plan needs, and whether a bot knows a way to make or get it (not counting chests). */
    private static int materials(CommandContext<CommandSourceStack> ctx) {
        GreatBuild build = existing(ctx);
        if (build == null) {
            return 0;
        }
        BotPlayer bot = BotManager.all().stream().findFirst().orElse(null);
        if (bot == null) {
            ctx.getSource().sendFailure(Component.literal("No bot on the server to ask"));
            return 0;
        }
        StringBuilder can = new StringBuilder();
        StringBuilder cannot = new StringBuilder();
        for (Map.Entry<Item, Integer> entry : build.materialTotals().entrySet()) {
            Item item = entry.getKey();
            boolean way = new com.minebot.bot.craft.Planner(bot).options(com.minebot.bot.craft.Target.of(item, 1)).stream()
                .anyMatch(option -> !(option.strategy() instanceof com.minebot.bot.craft.Planner.StoredStrategy));
            (way ? can : cannot).append(GreatBuild.key(item).replace("minecraft:", "")).append(" ").append(entry.getValue()).append(", ");
        }
        reply(ctx, "Great Build materials (asked " + bot.getPlainTextName() + "):\n can make: " + can + "\n no way: " + cannot);
        return 1;
    }

    /** How far each bot is with its order for the next session: what it has (on it and in its chests) of what it was asked for. */
    private static int prep(CommandContext<CommandSourceStack> ctx) {
        GreatBuild build = existing(ctx);
        if (build == null) {
            return 0;
        }
        StringBuilder text = new StringBuilder("Great Build, orders for the next session (have/asked):");
        int haveTotal = 0;
        int askedTotal = 0;
        for (BotPlayer bot : BotManager.all()) {
            Map<Item, Integer> order = build.orderOf(bot.getUUID());
            if (order.isEmpty()) {
                continue;
            }
            net.minecraft.server.level.ServerLevel level = bot.level();
            List<net.minecraft.world.Container> boxes = new ArrayList<>();
            java.util.Set<BlockPos> seen = new java.util.HashSet<>();
            for (BlockPos pos : bot.memory().chests()) {
                if (seen.add(pos) && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof net.minecraft.world.Container box) {
                    boxes.add(box);
                }
            }
            text.append("\n ").append(bot.getPlainTextName()).append(": ");
            boolean first = true;
            for (Map.Entry<Item, Integer> entry : order.entrySet()) {
                Item item = entry.getKey();
                int have = com.minebot.bot.action.Inv.count(bot, stack -> stack.is(item));
                for (net.minecraft.world.Container box : boxes) {
                    have += com.minebot.bot.ai.Stash.count(box, stack -> stack.is(item));
                }
                int asked = entry.getValue();
                haveTotal += Math.min(have, asked);
                askedTotal += asked;
                text.append(first ? "" : ", ").append(GreatBuild.key(item).replace("minecraft:", "")).append(" ")
                    .append(Math.min(have, asked)).append("/").append(asked);
                first = false;
            }
        }
        text.append("\n total ").append(haveTotal).append("/").append(askedTotal).append(" (")
            .append(askedTotal == 0 ? 100 : haveTotal * 100 / askedTotal).append("%)");
        reply(ctx, text.toString());
        return 1;
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String text) {
        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal(text));
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        }
    }
}
