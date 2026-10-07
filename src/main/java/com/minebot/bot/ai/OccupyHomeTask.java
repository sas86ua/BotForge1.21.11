package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import com.minebot.bot.world.HomeFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Moves into a free bed in an existing building (village house, ruin...). */
public class OccupyHomeTask extends Task {
    private static final int SEARCH_RADIUS = 256;

    private @Nullable BlockPos bed;
    private int attempts;

    public OccupyHomeTask(BotPlayer bot) {
        super(bot);
    }

    @Override
    public Status tick() {
        if (bed == null) {
            bed = HomeFinder.claimFreeBed(bot, SEARCH_RADIUS);
            if (bed == null) {
                return Status.FAILURE;
            }
            bot.debug("found a free bed at {}", bed.toShortString());
            // Right up to it: a bed only sets the respawn point from within 3 blocks
            bot.navigator().navigate(Goal.inSight(bot.level(), Goal.near(bed, 1.5), bed));
        }
        if (!HomeFinder.isBed(bot.level(), bed)) {
            bot.debug("the bed at {} is gone", bed.toShortString());
            return giveUp();
        }
        Navigator.Status status = bot.navigator().tick();
        if (status == Navigator.Status.FAILED || status == Navigator.Status.IDLE && ++attempts > 3) {
            bot.debug("can't reach the bed at {} ({})", bed.toShortString(), status);
            return giveUp();
        }
        if (status != Navigator.Status.SUCCESS && bot.position().distanceTo(Vec3.atBottomCenterOf(bed)) > 2.5 || !bot.canUse(bed)) {
            return Status.RUNNING;
        }
        bot.memory().setHome(GlobalPos.of(bot.level().dimension(), bed), false);
        bot.memory().setBed(bed);
        bot.useBed(bed); // respawn point
        bot.debug("moved into the house with the bed at {}", bed.toShortString());
        return Status.SUCCESS;
    }

    private Status giveUp() {
        HomeFinder.release(bot.level(), bed);
        bed = null;
        return Status.FAILURE;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        if (bed != null && !bed.equals(bot.memory().bed())) {
            // Interrupted on the way: free the reservation, or nobody could ever take the bed
            HomeFinder.release(bot.level(), bed);
        }
    }

    @Override
    public String describe() {
        return bed == null ? "looking for a house" : "moving into the house at " + bed.toShortString();
    }
}
