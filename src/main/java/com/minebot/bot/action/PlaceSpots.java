package com.minebot.bot.action;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Optional;

/** Finds free spots next to the bot to put down a crafting table, furnace, chest... */
public final class PlaceSpots {
    /**
     * Where to put the block. If {@code clearFirst}, the spot holds a natural
     * block (bot is boxed in, e.g. in a mine shaft) that must be mined first.
     */
    public record Spot(BlockPos pos, boolean clearFirst) {
    }

    private PlaceSpots() {
    }

    public static @Nullable Spot find(BotPlayer bot) {
        ServerLevel level = bot.level();
        BlockPos feet = bot.blockPosition();
        AABB body = bot.getBoundingBox();
        // Best: on the ground next to the bot
        Optional<BlockPos> ground = candidates(bot, feet)
            .filter(pos -> !new AABB(pos).intersects(body) && isFreeGroundSpot(level, pos))
            .min(Comparator.comparingDouble(pos -> pos.distToCenterSqr(bot.position())));
        if (ground.isPresent()) {
            return new Spot(ground.get(), false);
        }
        // Else anywhere it can attach to (a wall, the ceiling of a tunnel)
        Optional<BlockPos> attached = candidates(bot, feet)
            .filter(pos -> !new AABB(pos).intersects(body) && level.getFluidState(pos).isEmpty()
                && BlockPlacer.canPlaceAt(level, pos))
            .min(Comparator.comparingDouble(pos -> pos.distToCenterSqr(bot.position())));
        if (attached.isPresent()) {
            return new Spot(attached.get(), false);
        }
        // Boxed in: dig a niche into the wall at foot level
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos wall = feet.relative(direction);
            if (BlockRules.canBreak(level, wall, level.getBlockState(wall))) {
                return new Spot(wall, true);
            }
        }
        return null;
    }

    private static java.util.stream.Stream<BlockPos> candidates(BotPlayer bot, BlockPos feet) {
        ServerLevel level = bot.level();
        return BlockPos.betweenClosedStream(feet.offset(-2, -1, -2), feet.offset(2, 2, 2))
            .map(BlockPos::immutable)
            .filter(pos -> bot.isWithinBlockInteractionRange(pos, 0.0))
            .filter(pos -> !ProtectedAreas.isProtected(level, pos));
    }

    /** Air (or grass) with something solid underneath. */
    public static boolean isFreeGroundSpot(ServerLevel level, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState below = level.getBlockState(pos.below());
        return level.getBlockState(pos).canBeReplaced()
            && level.getFluidState(pos).isEmpty()
            && BlockRules.isStandable(level, pos.below(), below)
            // (not on a piece of furniture: a table on the bed, a furnace on a chest)
            && !below.is(net.minecraft.tags.BlockTags.BEDS) && !below.hasBlockEntity()
            && !below.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
            && !besideBed(level, pos); // (nor right beside a bed: a furnace at its foot, a block by its side)
    }

    private static boolean besideBed(ServerLevel level, BlockPos pos) {
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(direction)).is(net.minecraft.tags.BlockTags.BEDS)) {
                return true;
            }
        }
        return false;
    }
}
