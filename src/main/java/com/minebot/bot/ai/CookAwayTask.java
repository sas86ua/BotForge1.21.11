package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.craft.Target;
import org.jetbrains.annotations.Nullable;

/**
 * Gets something that's cooked (baked potatoes...) without standing by the smoker or furnace: once
 * the whole batch is in and cooking, it goes about its business; what's done is taken out later (by
 * this need again, or "furnace output").
 */
public class CookAwayTask extends Task {
    private final ObtainTask obtain;
    private boolean started;

    public CookAwayTask(BotPlayer bot, ObtainTask obtain) {
        super(bot);
        this.obtain = obtain;
    }

    @Override
    public Status tick() {
        if (!started) {
            started = true;
            bot.setLeaveWhileSmelting(true);
        }
        Status status = obtain.tick();
        int cooking = bot.takeLeftCooking();
        if (status == Status.RUNNING && cooking > 0) {
            bot.debug("the batch is cooking ({} s); off to other things meanwhile", cooking / 20);
            status = Status.SUCCESS;
        }
        if (status != Status.RUNNING) {
            stop();
        }
        return status;
    }

    @Override
    public void stop() {
        obtain.stop();
        bot.setLeaveWhileSmelting(false);
    }

    @Override
    public @Nullable Target wanted() {
        return obtain.wanted();
    }

    @Override
    public String describe() {
        return obtain.describe();
    }
}
