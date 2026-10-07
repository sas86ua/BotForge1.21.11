package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Target;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides what the bot does when it isn't fighting:
 * 1. eat when hungry (interrupts anything else),
 * 2. a task given by command (goto, come, get...),
 * 3. otherwise, if autonomous, its own most pressing need (see {@link Needs}).
 */
public class BotBrain {
    /** How often a higher-priority need may pre-empt the current one. */
    private static final int REEVALUATE_TICKS = 100;
    /** After a need fails, leave it alone this long before trying again. */
    private static final int FAILED_NEED_COOLDOWN = 20 * 60 * 3;
    /** A need not met in this long is given up on (and retried after the cooldown). */
    private static final int NEED_TIMEOUT = 20 * 60 * 20;
    /** After a failed attempt to eat, wait this long before trying again. */
    private static final int EAT_RETRY_TICKS = 20 * 10;

    private final BotPlayer bot;
    private @Nullable Task command;
    private @Nullable Task eating;
    private @Nullable Task fetchingGift;
    private @Nullable Task current;
    private @Nullable Needs.Need currentNeed;
    private long currentSince;
    private final Map<String, Long> cooldownUntil = new HashMap<>();
    /** Needs whose task finished at once, how many times in a row. */
    private final Map<String, Integer> quickFinishes = new HashMap<>();
    private @Nullable String lastResult;
    private int ticks;
    private int nextEatTick;
    private int foodBeforeEating;

    public BotBrain(BotPlayer bot) {
        this.bot = bot;
        this.ticks = bot.phase(); // (its own moment in each cycle, see BotPlayer.phase)
    }

    public void setCommand(@Nullable Task task) {
        if (command != null) {
            command.stop();
        }
        stopCurrent();
        command = task;
        lastResult = null;
    }

    public void tick() {
        ticks++;
        if (ticks % 40 == 0) {
            Armor.equipBest(bot); // (also while following commands)
        }
        if (eating != null) {
            if (eating.tick() != Task.Status.RUNNING) {
                eating.stop();
                eating = null;
                if (bot.getFoodData().getFoodLevel() <= foodBeforeEating) {
                    // Didn't work (interrupted, item gone...): get on with life, try again later
                    bot.debug("eating didn't work, retrying in a bit");
                    nextEatTick = ticks + EAT_RETRY_TICKS;
                }
            }
            return;
        }
        if (fetchingGift != null) {
            if (fetchingGift.tick() != Task.Status.RUNNING) {
                fetchingGift.stop();
                fetchingGift = null;
            }
            return;
        }
        Vec3 gift = Sharing.takeGift(bot);
        if (gift != null) {
            // Another bot dropped something for us: go pick it up
            bot.navigator().interrupt();
            fetchingGift = new CollectItemsTask(bot, gift, 4.0, 120);
            return;
        }
        if (ticks >= nextEatTick && Food.isHungry(bot) && Food.pickFood(bot) >= 0) {
            bot.navigator().interrupt();
            foodBeforeEating = bot.getFoodData().getFoodLevel();
            eating = new EatTask(bot);
            return;
        }

        if (command != null) {
            Task.Status status = command.tick();
            if (status != Task.Status.RUNNING) {
                lastResult = command.describe() + " -> " + status;
                bot.debug("command finished: {}", lastResult);
                command.stop();
                command = null;
            }
            return;
        }
        if (!bot.memory().autonomous()) {
            stopCurrent();
            bot.controller().releaseInputs();
            return;
        }
        live();
    }

    private void live() {
        if (ticks % 1200 == 600 && Home.isNear(bot, 48)) {
            Home.adoptStorage(bot); // (chests someone put in its house or workshop)
        }
        if (ticks % 200 == 0) {
            Home.validate(bot);
            Home.settleIfLost(bot);
            if (Home.has(bot) && bot.memory().homeSince() < 0) {
                bot.memory().setHomeSince(bot.level().getGameTime());
            }
        }
        if (ticks % 2400 == 0) {
            DumpTask.scan(bot);
        }
        if (current != null && ticks % REEVALUATE_TICKS == 0 && currentNeed != null && currentNeed.id().equals("back to zone")
            && !currentNeed.wanted().test(bot)) {
            bot.debug("back in my zone"); // (or the zone took it in: the Great Build's site)
            stopCurrent();
        }
        if (current != null && ticks % REEVALUATE_TICKS == 0) {
            Needs.Need urgent = nextNeed();
            if (urgent != null && urgent != currentNeed && isMoreImportant(urgent, currentNeed)) {
                bot.debug("switching to more urgent need: {}", urgent.id());
                stopCurrent();
            }
        }
        if (current == null) {
            currentNeed = nextNeed();
            current = currentNeed != null ? currentNeed.task().apply(bot) : new IdleTask(bot);
            currentSince = bot.level().getGameTime();
            bot.debug("now: {}", currentNeed != null ? currentNeed.id() : "idle");
        }
        Task.Status status = current.tick();
        if (status == Task.Status.RUNNING && bot.level().getGameTime() - currentSince > NEED_TIMEOUT
            && !(current instanceof JourneyTask) && !(current instanceof GreatBuildTask)) {
            bot.debug("need {} is taking too long", currentNeed != null ? currentNeed.id() : "idle");
            status = Task.Status.FAILURE;
        }
        if (status != Task.Status.RUNNING) {
            // Done the moment it started, again and again (its check says there's work, the task finds
            // none): rest that need a minute rather than starting it every tick
            if (currentNeed != null && bot.level().getGameTime() - currentSince < 20) {
                int quick = quickFinishes.merge(currentNeed.id(), 1, Integer::sum);
                if (quick >= 3) {
                    quickFinishes.remove(currentNeed.id());
                    cooldownUntil.put(currentNeed.id(), bot.level().getGameTime() + 20 * 60);
                    bot.debug("need {} keeps finishing at once; resting it", currentNeed.id());
                }
            } else if (currentNeed != null) {
                quickFinishes.remove(currentNeed.id());
            }
            if (status == Task.Status.FAILURE && currentNeed != null) {
                cooldownUntil.put(currentNeed.id(), bot.level().getGameTime() + FAILED_NEED_COOLDOWN);
                bot.debug("need {} failed, retrying later", currentNeed.id());
            }
            lastResult = (currentNeed != null ? currentNeed.id() : "idle") + " -> " + status;
            stopCurrent();
        }
    }

    private @Nullable Needs.Need nextNeed() {
        long now = bot.level().getGameTime();
        for (Needs.Need need : Needs.ALL) {
            if (cooldownUntil.getOrDefault(need.id(), 0L) > now) {
                continue;
            }
            if (need.wanted().test(bot)) {
                return need;
            }
        }
        return null;
    }

    private static boolean isMoreImportant(Needs.Need need, @Nullable Needs.Need than) {
        return than == null || Needs.ALL.indexOf(need) < Needs.ALL.indexOf(than);
    }

    private void stopCurrent() {
        if (current != null) {
            current.stop();
        }
        current = null;
        currentNeed = null;
    }

    /** What the bot is trying to get right now, so other bots can help; null if nothing. */
    public @Nullable Target wanted() {
        if (Food.isHungry(bot) && Food.pickFood(bot) < 0) {
            return new Target("food", Inv::isFood, 1);
        }
        Task task = command != null ? command : current;
        return task != null ? task.wanted() : null;
    }

    /** Gives up on the current command or need (it's not getting anywhere); the need is retried later. */
    /** Look again at once what matters most (it may be life or death: no air to be had). */
    public void reconsider() {
        if (command == null && currentNeed != null && !"escape".equals(currentNeed.id())) {
            stopCurrent();
        }
    }

    public void abandon(String reason) {
        bot.debug("giving up on {}: {}", describe(), reason);
        if (command != null) {
            command.stop();
            command = null;
            return;
        }
        if (currentNeed != null) {
            cooldownUntil.put(currentNeed.id(), bot.level().getGameTime() + FAILED_NEED_COOLDOWN);
        }
        stopCurrent();
    }

    /** Drops everything the bot was doing (after an error in its AI). */
    public void reset() {
        if (fetchingGift != null) {
            fetchingGift.stop();
            fetchingGift = null;
        }
        if (eating != null) {
            eating.stop();
            eating = null;
        }
        if (command != null) {
            command.stop();
            command = null;
        }
        stopCurrent();
    }

    /** Called when combat takes over movement: keep the plan, just drop the path. */
    public void pause() {
        bot.navigator().interrupt();
    }

    /**
     * Something else (combat chasing an enemy) used the navigator for its own
     * goal: start the current need over, which plans its own way again.
     */
    public void restartTask() {
        if (command == null && current != null) {
            stopCurrent();
        }
    }

    public String describe() {
        if (eating != null) {
            return eating.describe();
        }
        if (command != null) {
            return command.describe();
        }
        if (current != null) {
            return (currentNeed != null ? "[" + currentNeed.id() + "] " : "") + current.describe();
        }
        return lastResult != null ? "idle (" + lastResult + ")" : "idle";
    }
}
