package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Trapped underground (a flooded cave, a pit it can't find a way out of): gets
 * back to the surface. In water it swims up, breaks through a rock ceiling, and
 * at the top climbs onto the nearest ledge, cutting one into the rock or
 * putting a block into the water to stand on. On dry ground it digs and
 * pillars its way up, digging next to water too if it has to.
 */
public class EscapeTask extends Task {
    private static final int MAX_TICKS = 20 * 180;
    /** Deeper than this below the surface counts as underground. */
    private static final int DEPTH = 4;

    private final BlockBreaker breaker;
    private int ticks;
    private int failures;
    private int climbTicks;

    public EscapeTask(BotPlayer bot) {
        super(bot);
        this.breaker = new BlockBreaker(bot);
    }

    /** Underground and in trouble: running out of air, or no way found to wherever it was going. */
    public static boolean wanted(BotPlayer bot) {
        if (!underground(bot)) {
            return false;
        }
        boolean drowning = bot.isUnderWater() && bot.getAirSupply() < bot.getMaxAirSupply() / 2;
        // (paths failing now and then is normal down a mine: stuck means going nowhere for a minute too)
        // (or on its way somewhere far, yet still in the same few blocks after minutes: Bedrock swam to and fro in a
        // flooded cave for 20 hours, its ways never "failing", just never getting it out)
        BlockPos feet = bot.navigator().feet();
        var goal = bot.navigator().goal();
        boolean goingNowhere = bot.areaSeconds() >= 180 && goal != null && goal.distance(feet.getX(), feet.getY(), feet.getZ()) > 16;
        return drowning || bot.recentNavFailures(20 * 60) >= 3 && bot.stillSeconds() >= 60 || goingNowhere;
    }

    public static boolean underground(BotPlayer bot) {
        ServerLevel level = bot.level();
        BlockPos feet = bot.blockPosition();
        return !level.canSeeSky(feet.above())
            && feet.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()) - DEPTH;
    }

    @Override
    public Status tick() {
        if (!underground(bot)) {
            bot.debug("out of the cave");
            bot.clearNavFailures();
            return Status.SUCCESS;
        }
        if (++ticks > MAX_TICKS) {
            return Status.FAILURE;
        }
        if (breaker.target() != null) {
            // (at the surface it keeps afloat while breaking; under water it stays on the bottom, where digging is quicker)
            bot.setJumping(bot.isInWater() && !bot.isUnderWater());
            if (breaker.tick() == BlockBreaker.Result.RUNNING) {
                return Status.RUNNING;
            }
        }
        if (bot.isInWater()) {
            bot.navigator().stop();
            return swim();
        }
        if (digOut) {
            return digOut();
        }
        if (!bot.navigator().isActive()) {
            bot.navigator().navigateEscape(Goal.surface(bot.level()));
        }
        if (bot.navigator().tick().ended() && underground(bot) && ++failures > 3) {
            // No way out at all (Makena sat two hours in a pocket of rock under her own house): it digs itself out
            bot.debug("no way out of here; digging my way up");
            bot.navigator().stop();
            digOut = true;
            failures = 0;
        }
        return Status.RUNNING;
    }

    private boolean digOut;
    private @org.jetbrains.annotations.Nullable Direction away;

    /**
     * Digging out by hand, as a player would: under its own house (or near it) first a tunnel away from
     * it, not up through its floor; then straight up - the block over its head broken, a block put under
     * its feet as it jumps - till the sky is over it.
     */
    private Status digOut() {
        ServerLevel level = bot.level();
        BlockPos feet = bot.blockPosition();
        BlockPos home = bot.memory().home() != null && bot.memory().home().dimension() == level.dimension() ? bot.memory().home().pos() : null;
        boolean nearHome = home != null && Math.abs(feet.getX() - home.getX()) <= 10 && Math.abs(feet.getZ() - home.getZ()) <= 10;
        if (nearHome) {
            if (away == null) {
                int dx = feet.getX() - home.getX();
                int dz = feet.getZ() - home.getZ();
                away = dx == 0 && dz == 0 ? Direction.NORTH
                    : Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
            }
            BlockPos ahead = feet.relative(away);
            for (BlockPos pos : new BlockPos[] {ahead.above(), ahead}) {
                if (!isOpen(level, pos)) {
                    if (!canBreak(level, pos) && !(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock)) {
                        away = away.getClockWise(); // (something it mustn't break: another way)
                        return ++failures > 12 ? Status.FAILURE : Status.RUNNING;
                    }
                    bot.controller().lookAt(Vec3.atCenterOf(pos));
                    breaker.start(pos);
                    return Status.RUNNING;
                }
            }
            if (isOpen(level, ahead.below())) {
                Inv.select(bot, Inv::isScaffold); // (a floor to walk on over a gap)
            }
            bot.controller().moveTowards(Vec3.atBottomCenterOf(ahead), false, false);
            return Status.RUNNING;
        }
        BlockPos overHead = feet.above(2);
        if (!isOpen(level, overHead)) {
            if (!canBreak(level, overHead)) {
                away = away == null ? Direction.NORTH : away.getClockWise();
                return ++failures > 12 ? Status.FAILURE : Status.RUNNING;
            }
            bot.controller().lookAt(Vec3.atCenterOf(overHead));
            breaker.start(overHead);
            return Status.RUNNING;
        }
        // Pillar up: jump, and a block under its feet at the top of the jump
        bot.controller().hold(Vec3.atBottomCenterOf(feet));
        bot.setXRot(90.0F);
        bot.setJumping(true);
        if (bot.getY() > feet.getY() + 0.9 && level.getBlockState(feet).canBeReplaced()) {
            if (!com.minebot.bot.action.BlockPlacer.place(bot, feet, Inv::isScaffold) && Inv.count(bot, Inv::isScaffold) == 0) {
                return Status.FAILURE; // (nothing to stand on: not likely, it has dug enough)
            }
        }
        return Status.RUNNING;
    }

    /** In the water: up to the top, then out onto a ledge. */
    private Status swim() {
        ServerLevel level = bot.level();
        BlockPos feet = bot.blockPosition();
        BlockPos eyes = BlockPos.containing(bot.getEyePosition());
        if (isWater(level, eyes)) {
            // Under water: the rock ceiling above gets broken, standing on the bottom if it's within
            // reach (digging while afloat is five times slower); the hole fills with air to breathe
            BlockPos ceiling = eyes.above();
            while (isWater(level, ceiling) && ceiling.getY() - eyes.getY() < 6) {
                ceiling = ceiling.above();
            }
            bot.controller().releaseInputs();
            if (!isWater(level, ceiling) && !isOpen(level, ceiling) && bot.isWithinBlockInteractionRange(ceiling, 0.0)) {
                bot.setJumping(false);
                return breakOrGiveUp(ceiling);
            }
            bot.setJumping(true);
            return Status.RUNNING;
        }
        // Head above the water: climb onto a neighbouring block. A step beside it needs a floor
        // (rock, or a block put into the water) and room above (cut out of the rock if need be)
        boolean haveBlocks = Inv.count(bot, Inv::isScaffold) > 0;
        Direction best = null;
        int bestWork = Integer.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            BlockState floor = level.getBlockState(side);
            boolean solid = !floor.getCollisionShape(level, side).isEmpty() && floor.getFluidState().isEmpty();
            boolean water = isWater(level, side);
            if (!solid && !(water && haveBlocks)) {
                continue;
            }
            int work = water ? 1 : 0;
            boolean possible = true;
            for (BlockPos room : new BlockPos[] {side.above(), side.above(2)}) {
                if (!isOpen(level, room)) {
                    work += 2;
                    possible &= canBreak(level, room);
                }
            }
            if (possible && work < bestWork) {
                best = direction;
                bestWork = work;
            }
        }
        if (best != null) {
            BlockPos side = feet.relative(best);
            if (!isOpen(level, side.above())) {
                return breakOrGiveUp(side.above());
            }
            if (!isOpen(level, side.above(2))) {
                return breakOrGiveUp(side.above(2));
            }
            if (isWater(level, side)) {
                // A block into the water beside it, to climb onto
                BlockPlacer.place(bot, side, Inv::isScaffold);
                bot.setJumping(true);
                return Status.RUNNING;
            }
            if (!isOpen(level, feet.above(2))) {
                // (the hop up onto the step needs headroom over the water too)
                return breakOrGiveUp(feet.above(2));
            }
            bot.controller().moveTowards(Vec3.atBottomCenterOf(side.above()), false, true);
            // Floating a little low, the bot may not quite clear the edge (a player mashes jump):
            // pressed against the free step for over a second, it steps up onto it
            double dx = side.getX() + 0.5 - bot.getX();
            double dz = side.getZ() + 0.5 - bot.getZ();
            if (bot.horizontalCollision && dx * dx + dz * dz < 1.7 && ++climbTicks > 20) {
                climbTicks = 0;
                bot.teleportTo(side.getX() + 0.5, side.getY() + 1, side.getZ() + 0.5);
            }
            return Status.RUNNING;
        }
        // Nothing to climb onto here: up through the ceiling, and look again from there
        BlockPos above = feet.above(2);
        if (!isOpen(level, above)) {
            return breakOrGiveUp(above);
        }
        bot.setJumping(true);
        return Status.RUNNING;
    }

    private Status breakOrGiveUp(BlockPos pos) {
        if (!canBreak(bot.level(), pos)) {
            return ++failures > 20 ? Status.FAILURE : Status.RUNNING;
        }
        bot.controller().lookAt(Vec3.atCenterOf(pos));
        breaker.start(pos);
        return Status.RUNNING;
    }

    private static boolean isWater(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER);
    }

    private static boolean isOpen(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /** Rock it may break to get out (not bedrock, nothing built, nothing protected). */
    private static boolean canBreak(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty() && !state.hasBlockEntity()
            && state.getDestroySpeed(level, pos) >= 0 && !BlockRules.isBuilt(state) && !ProtectedAreas.isProtected(level, pos);
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        breaker.cancel();
        bot.controller().releaseInputs();
    }

    @Override
    public String describe() {
        return "getting out of the cave";
    }
}
