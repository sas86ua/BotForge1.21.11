package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.craft.Target;
import org.jetbrains.annotations.Nullable;

/**
 * Crafts better armour and puts it on, piece after piece, for as long as the
 * diamonds, iron or leather (in its bag and chests nearby) last. Iron kept as blocks
 * is taken apart into ingots for it first.
 */
public class ArmorTask extends Task {
    private @Nullable Task child;
    /** The piece being made (after the iron for it, if blocks had to be taken apart). */
    private @Nullable Target piece;
    private int made;

    public ArmorTask(BotPlayer bot, Target first) {
        super(bot);
        start(first);
    }

    /** The next piece: ingots out of blocks of iron first, if that's what it needs. */
    private void start(Target next) {
        piece = next;
        int cost = Armor.ironCost(next);
        if (cost > 0 && IronStockTask.ingots(bot) < cost && IronStockTask.blocks(bot) > 0) {
            child = IronStockTask.ingotsFor(bot, cost);
        } else {
            child = new ObtainTask(bot, next, 0);
        }
    }

    @Override
    public Status tick() {
        if (child == null) {
            return Status.SUCCESS;
        }
        Status status = child.tick();
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        child.stop();
        boolean ironReady = child instanceof IronStockTask;
        child = null;
        if (status != Status.SUCCESS) {
            return made > 0 ? Status.SUCCESS : Status.FAILURE;
        }
        if (ironReady) {
            child = new ObtainTask(bot, piece, 0); // (the ingots are there now)
            return Status.RUNNING;
        }
        made++;
        Armor.equipBest(bot);
        Target next = Armor.findUpgrade(bot);
        if (next == null) {
            return Status.SUCCESS;
        }
        start(next);
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
        return child != null ? "making armour: " + child.describe() : "making armour";
    }
}
