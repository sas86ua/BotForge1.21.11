package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import com.minebot.bot.world.HomeFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Sleeps through the night: in the bot's own bed if it's within reasonable
 * walking distance, otherwise in any free bed close by. Sleeping in a bed
 * moves a player's respawn point there, so after a night in a borrowed bed the
 * bot puts its respawn point back on its own bed.
 */
public class SleepTask extends Task {
    /** Walk back to the home bed from at most this far. */
    private static final int HOME_BED_RANGE = 96;
    /** Otherwise borrow a free bed this close. */
    private static final int NEARBY_BED_RANGE = 32;

    private final @Nullable BlockPos bed;
    private final boolean borrowed;
    private final ServerPlayer.@Nullable RespawnConfig ownRespawn;
    private int attempts;
    private boolean slept;

    public SleepTask(BotPlayer bot) {
        super(bot);
        BlockPos home = homeBed(bot);
        this.bed = home != null ? home : HomeFinder.findBedForTheNight(bot, NEARBY_BED_RANGE);
        this.borrowed = home == null;
        this.ownRespawn = bot.getRespawnConfig();
    }

    /** Is it night and is there a bed to sleep in? */
    public static boolean wanted(BotPlayer bot) {
        return Home.isNight(bot) && !JourneyTask.isTravelling(bot) && !GreatBuildTask.isAway(bot)
            && !GreatBuildTask.sessionOn(bot) // (time to set off for the Great Build: no going to bed)
            && (homeBed(bot) != null || HomeFinder.findBedForTheNight(bot, NEARBY_BED_RANGE) != null);
    }

    private static @Nullable BlockPos homeBed(BotPlayer bot) {
        BlockPos bed = bot.memory().bed();
        return bed != null && Home.levelIfHere(bot) != null && bed.closerThan(bot.blockPosition(), HOME_BED_RANGE)
            ? bed : null;
    }

    @Override
    public Status tick() {
        if (bed == null || !HomeFinder.isBed(bot.level(), bed)) {
            return Status.FAILURE;
        }
        if (bot.isSleeping()) {
            slept = true;
            bot.controller().releaseInputs();
            if (GreatBuildTask.sessionOn(bot)) {
                // (time to set off for the Great Build, a long way to go: up, and off)
                bot.debug("up: time to set off for the great build");
                bot.stopSleepInBed(true, true);
                return Status.SUCCESS;
            }
            return Status.RUNNING; // wakes up by itself in the morning
        }
        if (slept || !Home.isNight(bot)) {
            return Status.SUCCESS;
        }
        // Beds only work from up close (3 blocks), closer than normal reach
        if (bot.position().distanceTo(Vec3.atBottomCenterOf(bed)) > 2.5 || !bot.canUse(bed)) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.inSight(bot.level(), Goal.near(bed, 1.5), bed));
            }
            if (bot.navigator().tick() == Navigator.Status.FAILED && ++attempts > 3) {
                return Status.FAILURE;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().lookAt(Vec3.atCenterOf(bed));
        var result = bot.startSleepInBed(bed);
        if (result.left().isPresent() && ++attempts > 20) {
            bot.debug("can't sleep: {}", result.left().get());
            return Status.FAILURE; // monsters nearby, bed occupied...
        }
        if (result.right().isPresent()) {
            bot.debug("going to sleep in {} bed at {}", borrowed ? "a borrowed" : "my", bed.toShortString());
        }
        return Status.RUNNING;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        if (bot.isSleeping()) {
            bot.stopSleepInBed(true, true); // (something more urgent: out of bed for it - it lay there till morning)
        }
        if (borrowed && slept) {
            // Sleeping in someone else's bed moved our respawn point there; put it back
            bot.setRespawnPosition(ownRespawn, false);
        }
    }

    @Override
    public String describe() {
        if (bot.isSleeping()) {
            return borrowed ? "sleeping in a borrowed bed" : "sleeping";
        }
        return "going to bed";
    }
}
