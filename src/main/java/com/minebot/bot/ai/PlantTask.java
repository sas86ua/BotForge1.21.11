package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.Item;
import java.util.function.Predicate;

/**
 * Plants the saplings the bot carries (the ones it didn't burn) so new trees
 * grow: on grass or dirt under the open sky, with room for the tree, a few
 * blocks apart and away from its house.
 */
public class PlantTask extends Task {
    private static final int RADIUS = 12;
    /** Around a felled tree: plant in its place, more or less. */
    private static final int TREE_RADIUS = 6;
    /** At most this many saplings where one tree stood. */
    private static final int PER_TREE = 2;
    /** No other tree or sapling this close (so they're 4+ blocks apart and have room to grow). */
    private static final int SPACING = 3;
    /** Clear air above a sapling; trees are taller, but leaves can grow around things. */
    private static final int HEADROOM = 5;
    /** Trees don't belong right by the house. */
    private static final int HOME_DISTANCE = 20;

    /** Saplings that grow on their own (dark and pale oak need four in a square). */
    private static final Predicate<ItemStack> SAPLING = stack -> stack.is(ItemTags.SAPLINGS)
        && !stack.is(Items.DARK_OAK_SAPLING) && !stack.is(Items.PALE_OAK_SAPLING)
        && stack.getItem() instanceof BlockItem;

    /** Plant around this felled tree only (or null: around the trees felled lately, or right here). */
    private final @Nullable BlockPos tree;
    private @Nullable BlockPos spot;
    private int planted;
    private int failures;

    public PlantTask(BotPlayer bot) {
        this(bot, null);
    }

    public PlantTask(BotPlayer bot, @Nullable BlockPos tree) {
        super(bot);
        this.tree = tree;
    }

    /** Bots that found no room to plant: not again before this (game time). */
    private static final java.util.Map<java.util.UUID, Long> NO_ROOM = new java.util.HashMap<>();

    public static void forget(java.util.UUID bot) {
        NO_ROOM.remove(bot);
    }

    /** No room to plant around here for now: the saplings it has go in the chest. */
    public static boolean paused(BotPlayer bot) {
        return bot.level().getGameTime() < NO_ROOM.getOrDefault(bot.getUUID(), 0L);
    }

    public static boolean wanted(BotPlayer bot) {
        return Inv.count(bot, SAPLING) > 0 && !Home.isNight(bot)
            && bot.level().getGameTime() >= NO_ROOM.getOrDefault(bot.getUUID(), 0L);
    }

    @Override
    public Status tick() {
        int slot = Inv.findSlot(bot, SAPLING);
        if (slot < 0) {
            return Status.SUCCESS;
        }
        Block sapling = ((BlockItem) bot.getInventory().getItem(slot).getItem()).getBlock();
        ServerLevel level = bot.level();
        if (tree != null && planted >= PER_TREE) {
            return Status.SUCCESS;
        }
        if (spot == null || !canPlant(level, spot, sapling)) {
            spot = findSpot(level, sapling);
            if (spot == null) {
                bot.debug("planted {} saplings, no more room around here", planted);
                NO_ROOM.put(bot.getUUID(), level.getGameTime() + 20 * 60 * 5); // (the rest go in the chest)
                return planted > 0 ? Status.SUCCESS : Status.FAILURE;
            }
        }
        if (!bot.canUse(spot.below())) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.reachVisible(bot.level(), spot.below()));
            }
            if (bot.navigator().tick().ended()) {
                spot = null;
                return ++failures > 3 ? Status.FAILURE : Status.RUNNING;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        Item item = sapling.asItem();
        if (BlockPlacer.place(bot, spot, stack -> stack.is(item), Direction.DOWN, null)) {
            bot.debug("planted {} at {}", item, spot.toShortString());
            planted++;
            spot = null;
        } else if (++failures > 10) {
            return Status.FAILURE;
        }
        return Status.RUNNING;
    }

    /** Where a tree was felled (this one, or the ones felled lately), else around the bot. */
    private @Nullable BlockPos findSpot(ServerLevel level, Block sapling) {
        if (tree != null) {
            return findSpotAround(level, sapling, tree, TREE_RADIUS);
        }
        for (BlockPos felled : bot.choppedTrees()) {
            if (felled.closerThan(bot.blockPosition(), 48)) {
                BlockPos spot = findSpotAround(level, sapling, felled, TREE_RADIUS);
                if (spot != null) {
                    return spot;
                }
            }
        }
        return findSpotAround(level, sapling, bot.blockPosition(), RADIUS);
    }

    private @Nullable BlockPos findSpotAround(ServerLevel level, Block sapling, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                    continue;
                }
                BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                double distance = pos.distSqr(center);
                if ((distance >= 4 || !center.equals(bot.blockPosition())) && distance < bestDistance && canPlant(level, pos, sapling)) {
                    best = pos;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private boolean canPlant(ServerLevel level, BlockPos pos, Block sapling) {
        BlockState here = level.getBlockState(pos);
        if (!here.canBeReplaced() || !here.getFluidState().isEmpty() || !level.getBlockState(pos.below()).is(BlockTags.DIRT)
            || !sapling.defaultBlockState().canSurvive(level, pos) || !level.canSeeSky(pos)) {
            return false;
        }
        if (!bot.memory().inZone(level.dimension(), pos) || ProtectedAreas.isProtected(level, pos)) {
            return false;
        }
        var home = bot.memory().home();
        if (home != null && home.dimension() == level.dimension() && home.pos().closerThan(pos, HOME_DISTANCE)) {
            return false; // not in front of the door
        }
        BlockPos workshop = bot.memory().workshop();
        if (workshop != null && home != null && home.dimension() == level.dimension() && workshop.closerThan(pos, HOME_DISTANCE)) {
            return false; // nor by the old hut
        }
        for (int dy = 1; dy <= HEADROOM; dy++) {
            if (!level.getBlockState(pos.above(dy)).isAir()) {
                return false;
            }
        }
        // Room to grow: no other tree, sapling or building block right next to it
        return BlockPos.betweenClosedStream(pos.offset(-SPACING, -1, -SPACING), pos.offset(SPACING, 2, SPACING))
            .noneMatch(near -> {
                BlockState state = level.getBlockState(near);
                return state.is(BlockTags.SAPLINGS) || state.is(BlockTags.LOGS)
                    || !near.equals(pos.below()) && near.getY() >= pos.getY() && state.isCollisionShapeFullBlock(level, near);
            });
    }

    @Override
    public void stop() {
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return "planting saplings";
    }
}
