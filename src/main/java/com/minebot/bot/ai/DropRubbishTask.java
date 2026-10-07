package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.path.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;

/**
 * Rubbish and no lava to burn it in: carried off a little way from the house and
 * left on the ground, where it disappears after a few minutes (dropped by the
 * bot, so no bot picks it up again).
 */
public class DropRubbishTask extends Task {
    private static final int DISTANCE = 12;
    private static final int MAX_TICKS = 20 * 60;

    private final BlockPos spot;
    private int ticks;

    public DropRubbishTask(BotPlayer bot) {
        super(bot);
        this.spot = pickSpot(bot);
    }

    /** Dry open ground 6-12 blocks from the house (not water, not a roof), or the bot's own spot. */
    private static BlockPos pickSpot(BotPlayer bot) {
        BlockPos home = bot.memory().bed() != null ? bot.memory().bed() : bot.blockPosition();
        net.minecraft.server.level.ServerLevel level = bot.level();
        float start = bot.getRandom().nextFloat() * Mth.TWO_PI;
        for (int distance : new int[] {DISTANCE, 9, 6}) {
            for (int i = 0; i < 8; i++) {
                float angle = start + i * Mth.TWO_PI / 8;
                int x = home.getX() + (int) (Mth.cos(angle) * distance);
                int z = home.getZ() + (int) (Mth.sin(angle) * distance);
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                    continue;
                }
                BlockPos spot = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                net.minecraft.world.level.block.state.BlockState ground = level.getBlockState(spot.below());
                if (ground.getFluidState().isEmpty() && !com.minebot.bot.world.BlockRules.isBuilt(ground)
                    && Math.abs(spot.getY() - home.getY()) <= 6 && !com.minebot.bot.world.ProtectedAreas.isProtected(level, spot)) {
                    return spot;
                }
            }
        }
        return bot.blockPosition();
    }

    @Override
    public Status tick() {
        if (Stash.disposable(bot).isEmpty()) {
            return Status.SUCCESS;
        }
        boolean there = bot.blockPosition().closerThan(spot, 3);
        if (!there && ++ticks < MAX_TICKS) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.near(spot, 2.0));
            }
            if (!bot.navigator().tick().ended()) {
                return Status.RUNNING;
            }
            // (couldn't get there: anywhere a fair way from the house does)
            BlockPos home = bot.memory().bed();
            if (home != null && bot.blockPosition().closerThan(home, DISTANCE / 2.0)) {
                return Status.FAILURE;
            }
        }
        bot.navigator().stop();
        for (Map.Entry<Integer, Integer> entry : Stash.disposable(bot).entrySet()) {
            ItemStack stack = bot.getInventory().getItem(entry.getKey()).split(entry.getValue());
            bot.drop(stack, false, true);
        }
        bot.debug("left the rubbish at {}", bot.blockPosition().toShortString());
        return Status.SUCCESS;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return "taking rubbish away from the house";
    }
}
