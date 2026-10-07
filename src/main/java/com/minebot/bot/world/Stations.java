package com.minebot.bot.world;

import com.minebot.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Finds work blocks anyone may use (crafting tables, furnaces, campfires,
 * chests): the bot's own, other bots', villages'... Never inside a player's
 * protected area, never outside the bot's zone.
 */
public final class Stations {
    private Stations() {
    }

    public static @Nullable BlockPos nearest(BotPlayer bot, Predicate<BlockState> kind, int radius) {
        return nearest(bot, kind, radius, (pos, state) -> true);
    }

    public static @Nullable BlockPos nearest(BotPlayer bot, Predicate<BlockState> kind, int radius,
                                             BiPredicate<BlockPos, BlockState> usable) {
        List<BlockPos> found = all(bot, kind, radius, usable, 1);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Usable blocks of a kind around the bot, nearest first. */
    public static List<BlockPos> all(BotPlayer bot, Predicate<BlockState> kind, int radius,
                                     BiPredicate<BlockPos, BlockState> usable, int limit) {
        ServerLevel level = bot.level();
        BlockPos center = bot.blockPosition();
        return BlockSearch.find(level, center, radius, center.getY() - radius / 2, center.getY() + radius / 2, kind,
            (pos, state) -> bot.memory().inZone(level.dimension(), pos)
                && !ProtectedAreas.isProtected(level, pos)
                && usable.test(pos, state),
            limit);
    }
}
