package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.build.Villages;
import com.minebot.bot.path.Goal;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A finished building of the village: out to it and round it, the pillars, ramps, ladders and stray blocks round and
 * in it taken down (see {@link Home#tidyVillage}). The bots never went back to a building once it was done, and the
 * scaffolding stood for good.
 */
public class VillageTidyTask extends Task {
    /** When this bot last tidied that building (game time); a building is looked over by each bot once a day. */
    private static final Map<UUID, Map<BlockPos, Long>> DONE = new ConcurrentHashMap<>();
    private static final long EVERY = 24000L;
    private static final int RANGE = 300;

    private final Villages.Project project;
    private @Nullable Task child;
    private boolean scanned;
    private int ticks;

    public VillageTidyTask(BotPlayer bot) {
        super(bot);
        this.project = next(bot);
    }

    private static @Nullable Villages.Project next(BotPlayer bot) {
        Villages.Project best = null;
        double bestDistance = Double.MAX_VALUE;
        long now = bot.level().getGameTime();
        Map<BlockPos, Long> done = DONE.getOrDefault(bot.getUUID(), Map.of());
        for (Villages.Project project : Villages.get(bot.level().getServer()).projects()) {
            double distance = project.origin().distSqr(bot.blockPosition());
            if (project.done() && distance < RANGE * RANGE && now - done.getOrDefault(project.origin(), -EVERY) >= EVERY
                && distance < bestDistance) {
                best = project;
                bestDistance = distance;
            }
        }
        return best;
    }

    public static boolean wanted(BotPlayer bot) {
        return bot.memory().autonomous() && !Home.isNight(bot) && !GreatBuildTask.isAway(bot) && !GreatBuildTask.sessionOn(bot)
            && next(bot) != null; // (looked at each time - cheap; kept a minute, it was started and done at once over and over)
    }

    @Override
    public Status tick() {
        if (project == null) {
            return Status.SUCCESS;
        }
        if (++ticks > 20 * 60 * 6) {
            return finish();
        }
        if (child != null) {
            Status status = child.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            child.stop();
            child = null;
            if (scanned) {
                return finish();
            }
        }
        BlockPos at = Villages.plan(project).center();
        if (!scanned && bot.blockPosition().distSqr(at) > 20 * 20) {
            child = new GoToTask(bot, Goal.column(at.getX(), at.getZ(), 8));
            return Status.RUNNING;
        }
        if (!scanned) {
            scanned = true;
            Home.tidyVillage(bot, bot.level());
        }
        if (DismantleTask.wanted(bot)) {
            child = new DismantleTask(bot);
            return Status.RUNNING;
        }
        return finish();
    }

    private Status finish() {
        if (project != null) {
            DONE.computeIfAbsent(bot.getUUID(), u -> new ConcurrentHashMap<>()).put(project.origin(), bot.level().getGameTime());
        }
        return Status.SUCCESS;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        if (child != null) {
            child.stop();
        }
    }

    @Override
    public String describe() {
        return "tidying the village's building" + (child != null ? ": " + child.describe() : "");
    }
}
