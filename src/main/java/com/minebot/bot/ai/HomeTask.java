package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.craft.Target;
import org.jetbrains.annotations.Nullable;

/** Gets the bot a home: moves into a free house if there is one nearby, else builds a hut. */
public class HomeTask extends Task {
    private @Nullable Task child;
    private boolean triedOccupying;

    public HomeTask(BotPlayer bot) {
        super(bot);
    }

    @Override
    public Status tick() {
        if (Home.has(bot)) {
            return Status.SUCCESS;
        }
        if (child == null) {
            child = triedOccupying ? new BuildHomeTask(bot) : new OccupyHomeTask(bot);
        }
        Status status = child.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        child.stop();
        child = null;
        if (status == Status.FAILURE) {
            if (triedOccupying) {
                return Status.FAILURE;
            }
            triedOccupying = true; // nothing to move into: build one
        }
        return Status.RUNNING;
    }

    @Override
    public void stop() {
        if (child != null) {
            child.stop();
        }
    }

    @Override
    public @Nullable Target wanted() {
        return child != null ? child.wanted() : null;
    }

    @Override
    public String describe() {
        return child != null ? child.describe() : "looking for a home";
    }
}
