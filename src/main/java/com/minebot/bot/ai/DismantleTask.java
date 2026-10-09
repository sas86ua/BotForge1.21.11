package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.Inv;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * Takes down the pillars the bot put up (to reach an enemy on a tower, the top
 * of a tall tree), top block first, so it doesn't leave columns of dirt and
 * cobblestone all over the place.
 */
public class DismantleTask extends Task {
    /** Pillars further away than this are left standing. */
    private static final int MAX_DISTANCE = 32;

    private final BlockBreaker breaker;
    /** The blocks to take down: the bot's list of pillars, or one task's own (a tree tower). */
    private final List<BlockPos> blocks;
    private @Nullable BlockPos current;
    private @Nullable Task collect;
    private int failures;

    public DismantleTask(BotPlayer bot) {
        this(bot, bot.pillars());
    }

    public DismantleTask(BotPlayer bot, List<BlockPos> blocks) {
        super(bot);
        this.breaker = new BlockBreaker(bot);
        this.blocks = blocks;
    }

    public static boolean wanted(BotPlayer bot) {
        // (at the Great Build, or time to set off for it: no time for that; it's done once back home)
        if (GreatBuildTask.isAway(bot) || GreatBuildTask.sessionOn(bot) || !anyLeft(bot, bot.pillars())) {
            return false;
        }
        // Not while it's still using it: a minute after it went up, and once it has moved off it
        for (BlockPos pos : bot.pillars()) {
            double dx = pos.getX() + 0.5 - bot.getX();
            double dz = pos.getZ() + 0.5 - bot.getZ();
            if (near(bot, pos) && bot.pillarAge(pos) >= 20 * 60 && dx * dx + dz * dz >= 9) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyLeft(BotPlayer bot, List<BlockPos> pillars) {
        // Gone, or to stay (filling a shaft, by water: no diving to take a block down); those far off or
        // not loaded wait for it to come back (the list is kept)
        // (a stray block in its house goes whatever it stands next to: a bed and a wall aren't a shaft)
        pillars.removeIf(pos -> com.minebot.bot.build.GreatBuild.nearSite(pos) || inHousePlan(bot, pos) || bot.level().isLoaded(pos) && (!isOurBlock(bot, pos)
            || !Home.isClutter(bot, pos) && (!bot.level().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.LADDER)
                && (inShaft(bot, pos, pillars) || inWall(bot, pos)) || touchesWater(bot, pos) || underground(bot, pos))));
        return pillars.stream().anyMatch(pos -> near(bot, pos));
    }

    /**
     * A cell its house plan has a block in (ground, a wall, a floor): what stands there is the house's, not scaffolding -
     * taken down, the house put it back, and so on round (Makena, at a corner of hers).
     */
    private static boolean inHousePlan(BotPlayer bot, BlockPos pos) {
        var memory = bot.memory();
        if (memory.houseOrigin() == null) {
            return false;
        }
        var plan = com.minebot.bot.build.HousePlans.of(memory);
        if (plan == null || !plan.inFootprint(pos) || Home.isClutter(bot, pos)) {
            return false;
        }
        for (var cell : plan.cells()) {
            if (cell.pos().equals(pos)) {
                return cell.kind() != '.';
            }
        }
        return false;
    }

    /**
     * Well under the ground (a cave, a mine): nobody sees it there, and it's the way back up. Taking it down
     * meant climbing out again on a new one, to be taken down again: down and up the shaft over and over.
     */
    private static boolean underground(BotPlayer bot, BlockPos pos) {
        return pos.getY() < bot.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            pos.getX(), pos.getZ()) - UNDERGROUND_DEPTH;
    }

    /** Blocks down from the surface where scaffolding is left as it is. */
    private static final int UNDERGROUND_DEPTH = 6;

    private static boolean near(BotPlayer bot, BlockPos pos) {
        return bot.level().isLoaded(pos) && pos.closerThan(bot.blockPosition(), MAX_DISTANCE);
    }

    /** Still the scaffold block we put there (not dug away, replaced...). */
    /**
     * Rock or earth on two sides or more: a block put down climbing out of a pit or up a shaft.
     * That just fills the hole (and is how it got out); only towers out in the open come down.
     */
    private static boolean inShaft(BotPlayer bot, BlockPos pos, List<BlockPos> pillars) {
        int walls = 0;
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            net.minecraft.world.level.block.state.BlockState state = bot.level().getBlockState(side);
            // (natural rock and earth only: furniture or a house wall beside a block put down indoors is no pit)
            if (!pillars.contains(side) && (state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
                || state.is(net.minecraft.tags.BlockTags.DIRT) || state.is(net.minecraft.tags.BlockTags.SAND)
                || state.is(net.minecraft.world.level.block.Blocks.GRAVEL) || state.is(net.minecraft.world.level.block.Blocks.CLAY))) {
                walls++;
            }
        }
        return walls >= 2;
    }

    /** Built blocks (not rock or earth, not furniture) on two sides: part of a wall built there since. */
    private static boolean inWall(BotPlayer bot, BlockPos pos) {
        int built = 0;
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            net.minecraft.world.level.block.state.BlockState state = bot.level().getBlockState(side);
            if (state.isCollisionShapeFullBlock(bot.level(), side) && !state.hasBlockEntity()
                && !state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) && !state.is(net.minecraft.tags.BlockTags.DIRT)
                && !state.is(net.minecraft.tags.BlockTags.SAND) && !state.is(net.minecraft.tags.BlockTags.LEAVES)
                && !state.is(net.minecraft.tags.BlockTags.LOGS)) {
                built++;
            }
        }
        return built >= 2;
    }

    private static boolean touchesWater(BotPlayer bot, BlockPos pos) {
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            if (bot.level().getFluidState(pos.relative(direction)).is(net.minecraft.tags.FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOurBlock(BotPlayer bot, BlockPos pos) {
        return bot.level().isLoaded(pos) && (Inv.isScaffold(new ItemStack(bot.level().getBlockState(pos).getBlock().asItem()))
            || bot.level().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.LADDER)); // (put up to climb a wall)
    }

    @Override
    public Status tick() {
        if (collect != null) {
            Status status = collect.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            collect.stop();
            collect = null;
        }
        if (current == null) {
            if (!anyLeft(bot, blocks)) {
                return Status.SUCCESS;
            }
            // Top first: standing on the pillar, the bot digs itself down block by block
            current = blocks.stream().filter(pos -> near(bot, pos)).max(Comparator.comparingInt(BlockPos::getY)).orElseThrow();
            breaker.cancel();
        }
        if (!isOurBlock(bot, current)) {
            blocks.remove(current);
            current = null;
            return Status.RUNNING;
        }
        if (!breaker.isBreaking(current)) {
            if (!bot.isWithinBlockInteractionRange(current, 0.0)) {
                if (!bot.navigator().isActive()) {
                    bot.navigator().navigate(Goal.reach(current));
                }
                if (bot.navigator().tick() == Navigator.Status.FAILED && ++failures > 3) {
                    blocks.remove(current); // can't get at it: leave it
                    current = null;
                }
                return Status.RUNNING;
            }
            bot.navigator().stop();
            breaker.start(current);
        }
        BlockBreaker.Result result = breaker.tick();
        if (result == BlockBreaker.Result.RUNNING) {
            return Status.RUNNING;
        }
        BlockPos done = current;
        blocks.remove(done);
        current = null;
        if (result == BlockBreaker.Result.SUCCESS) {
            collect = new CollectItemsTask(bot, Vec3.atCenterOf(done), 3.0, 40);
        }
        return Status.RUNNING;
    }

    @Override
    public void stop() {
        breaker.cancel();
        bot.navigator().stop();
        if (collect != null) {
            collect.stop();
        }
    }

    @Override
    public String describe() {
        return "taking down a pillar";
    }
}
