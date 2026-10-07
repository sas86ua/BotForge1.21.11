package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * Walks some distance in a random direction (staying inside the bot's zone)
 * to find resources that aren't in the loaded area around it.
 */
public class ExploreTask extends Task {
    private final int distance;
    private int targetX;
    private int targetZ;
    private boolean started;

    public ExploreTask(BotPlayer bot, int distance) {
        super(bot);
        this.distance = distance;
    }

    @Override
    public Status tick() {
        if (!started) {
            started = true;
            pickTarget();
            bot.navigator().navigate(Goal.column(targetX, targetZ, 6.0));
        }
        Navigator.Status status = bot.navigator().tick();
        // Even a failed trip usually moved us somewhere new
        return switch (status) {
            case SUCCESS, FAILED, IDLE -> Status.SUCCESS;
            default -> Status.RUNNING;
        };
    }

    private void pickTarget() {
        BlockPos anchor = bot.memory().anchor().pos();
        net.minecraft.server.level.ServerLevel level = bot.level();
        // Land: animals, trees and ore aren't out at sea. Where it can see, it skips water; where it
        // can't (not loaded), it may be land; open water only if nothing else turns up
        int[] unseen = null;
        int[] water = null;
        for (int attempt = 0; attempt < 24; attempt++) {
            float angle = bot.getRandom().nextFloat() * Mth.TWO_PI;
            double reach = distance * (0.6 + 0.4 * bot.getRandom().nextDouble());
            int x = bot.getBlockX() + (int) (Mth.cos(angle) * reach);
            int z = bot.getBlockZ() + (int) (Mth.sin(angle) * reach);
            long dx = x - anchor.getX();
            long dz = z - anchor.getZ();
            if (dx * dx + dz * dz > (long) (BotMemory.ZONE_RADIUS - 50) * (BotMemory.ZONE_RADIUS - 50)) {
                continue;
            }
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                if (unseen == null) {
                    unseen = new int[] {x, z};
                }
                continue;
            }
            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            if (level.getFluidState(new BlockPos(x, top, z)).isEmpty()) {
                targetX = x;
                targetZ = z;
                return;
            }
            if (water == null) {
                water = new int[] {x, z};
            }
        }
        int[] pick = unseen != null ? unseen : water;
        if (pick != null) {
            targetX = pick[0];
            targetZ = pick[1];
            return;
        }
        // Near the edge of the zone: head back towards the middle, but no further than
        // a normal trip (a target hundreds of blocks away is never reached in one go)
        double dx = anchor.getX() - bot.getX();
        double dz = anchor.getZ() - bot.getZ();
        double length = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
        double step = Math.min(distance, length / 2);
        targetX = bot.getBlockX() + (int) (dx / length * step);
        targetZ = bot.getBlockZ() + (int) (dz / length * step);
    }

    @Override
    public void stop() {
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return started ? "exploring towards " + targetX + " " + targetZ : "exploring";
    }
}
