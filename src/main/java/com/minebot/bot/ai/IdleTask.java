package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/** Nothing to do: potter about near home (or the spawn point) for a while. */
public class IdleTask extends Task {
    private static final int DURATION = 20 * 45;
    private static final int WANDER_RADIUS = 18;

    private int ticks;
    private boolean walking;

    public IdleTask(BotPlayer bot) {
        super(bot);
    }

    @Override
    public Status tick() {
        if (++ticks > DURATION) {
            return Status.SUCCESS;
        }
        if (walking) {
            switch (bot.navigator().tick()) {
                case SUCCESS, FAILED, IDLE -> walking = false;
                default -> {
                }
            }
            return Status.RUNNING;
        }
        BlockPos home = center();
        if (!walking && home != null && bot.blockPosition().distSqr(home) > 64 * 64 && ticks % 100 == 1) {
            // Far from home with nothing to do (its home's ground isn't even loaded to potter about on): home first
            bot.navigator().navigate(Goal.column(home.getX(), home.getZ(), 8.0));
            walking = true;
            return Status.RUNNING;
        }
        bot.controller().releaseInputs();
        if (bot.getRandom().nextInt(60) == 0) {
            bot.controller().glance(); // (looks about now and then, head up)
        }
        if (bot.getRandom().nextInt(200) == 0) {
            BlockPos spot = safeSpot(center());
            if (spot != null) {
                bot.navigator().navigate(Goal.near(spot, 2.0));
                walking = true;
            }
        }
        return Status.RUNNING;
    }

    /**
     * A random spot on open ground near home: on the surface, not much higher or
     * lower than home (no ravines), nothing hot near it. Null if the pick was bad.
     */
    private @Nullable BlockPos safeSpot(BlockPos center) {
        ServerLevel level = bot.level();
        int x = center.getX() + bot.getRandom().nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS;
        int z = center.getZ() + bot.getRandom().nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS;
        if (!level.isLoaded(new BlockPos(x, center.getY(), z))) {
            return null;
        }
        BlockPos spot = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        if (Math.abs(spot.getY() - center.getY()) > 6 || !level.getFluidState(spot.below()).isEmpty()
            || BlockRules.isBuilt(level.getBlockState(spot.below()))) {
            return null; // (water, or a roof or wall top: it walks about on the ground)
        }
        for (BlockPos near : BlockPos.betweenClosed(spot.offset(-2, -3, -2), spot.offset(2, 1, 2))) {
            BlockState state = level.getBlockState(near);
            if (BlockRules.isLava(state) || BlockRules.isDangerous(state)) {
                return null;
            }
        }
        return spot;
    }

    private BlockPos center() {
        GlobalPos home = bot.memory().home();
        if (home != null && home.dimension() == bot.level().dimension()) {
            return home.pos();
        }
        return bot.memory().anchor().pos();
    }

    @Override
    public void stop() {
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return "idling";
    }
}
