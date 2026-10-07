package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;

/** Walks to a goal using the bot's navigator. */
public class GoToTask extends Task {
    private final Goal goal;
    private boolean started;

    public GoToTask(BotPlayer bot, Goal goal) {
        super(bot);
        this.goal = goal;
    }

    @Override
    public Status tick() {
        Navigator navigator = bot.navigator();
        if (!started) {
            started = true;
            navigator.navigate(goal);
        }
        return switch (navigator.tick()) {
            case SUCCESS -> Status.SUCCESS;
            case FAILED, IDLE -> Status.FAILURE;
            default -> Status.RUNNING;
        };
    }

    @Override
    public void stop() {
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return "going to " + goal + " (" + bot.navigator().status() + ")";
    }
}
