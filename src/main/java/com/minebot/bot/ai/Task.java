package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.craft.Target;
import org.jetbrains.annotations.Nullable;

/**
 * A unit of bot behaviour that runs over many ticks (walk somewhere, chop a
 * tree, craft a pickaxe...). Tasks may run sub-tasks of their own.
 */
public abstract class Task {
    public enum Status { RUNNING, SUCCESS, FAILURE }

    protected final BotPlayer bot;

    protected Task(BotPlayer bot) {
        this.bot = bot;
    }

    public abstract Status tick();

    /**
     * The items this task is working to get right now (deepest sub-goal), so
     * other bots can help out; null if none.
     */
    public @Nullable Target wanted() {
        return null;
    }

    /** Called when the task is interrupted or replaced; release inputs/locks here. */
    public void stop() {
    }

    /** Short human-readable state for /bot list and logs. */
    public abstract String describe();
}
