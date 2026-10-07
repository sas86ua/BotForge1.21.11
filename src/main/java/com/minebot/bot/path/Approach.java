package com.minebot.bot.path;

import com.minebot.bot.BotPlayer;
import net.minecraft.core.BlockPos;

/**
 * Walking up to a block to use it (a chest, furnace, crafting table): close
 * enough and with it in plain sight. The path finder works with block
 * positions and the bot rarely stands dead in the middle of one, so "there"
 * by the plan can still be a corner short of seeing it; then the bot steps
 * closer, and only gives up when even right next to it doesn't work.
 */
public final class Approach {
    public enum Result { READY, MOVING, FAILED }
    /** Searched this far at most: the way to a chest or table at home is short, a failing search shouldn't be costly. */
    private static final int MAX_NODES = 8000;

    private final BotPlayer bot;
    private final BlockPos target;
    /** 0: within reach; 1: within 2 blocks; 2: right next to it. */
    private int stage;

    public Approach(BotPlayer bot, BlockPos target) {
        this.bot = bot;
        this.target = target;
    }

    public BlockPos target() {
        return target;
    }

    public Result tick() {
        if (bot.canUse(target)) {
            bot.navigator().stop();
            return Result.READY;
        }
        Navigator navigator = bot.navigator();
        if (!navigator.isActive()) {
            Goal goal = switch (stage) {
                case 0 -> Goal.reachVisible(bot.level(), target);
                case 1 -> Goal.inSight(bot.level(), Goal.near(target, 2.0), target);
                default -> Goal.inSight(bot.level(), Goal.near(target, 1.0), target);
            };
            navigator.navigate(goal, MAX_NODES); // (a way to something at home: never far, so no huge searches)
        }
        if (navigator.tick().ended() && !bot.canUse(target)) {
            navigator.stop();
            if (++stage > 2) {
                return Result.FAILED;
            }
        }
        return Result.MOVING;
    }
}
