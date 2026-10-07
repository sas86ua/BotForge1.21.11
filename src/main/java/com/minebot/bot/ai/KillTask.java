package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.path.Goal;
import net.minecraft.world.entity.LivingEntity;

/**
 * "/bot kill": goes after a player or another bot and fights it until it's dead (or gone: logged
 * out, through a portal, five minutes up). Close by, the bot's combat does the fighting (the target
 * counts as having attacked it); further off this walks it over.
 */
public class KillTask extends Task {
    private static final int MAX_TICKS = 20 * 60 * 5;
    /** Closer than this, combat takes over (it chases and fights within 24 blocks). */
    private static final double CLOSE = 12.0;

    private final LivingEntity target;
    private int ticks;

    public KillTask(BotPlayer bot, LivingEntity target) {
        super(bot);
        this.target = target;
    }

    @Override
    public Status tick() {
        if (!target.isAlive() || target.isRemoved()) {
            bot.debug("{} is dead (or gone)", target.getName().getString());
            return target.isDeadOrDying() ? Status.SUCCESS : Status.FAILURE;
        }
        if (target.level() != bot.level() || ++ticks > MAX_TICKS) {
            bot.debug("giving up on killing {}", target.getName().getString());
            return Status.FAILURE;
        }
        bot.combat().order(target, 200);
        if (bot.distanceTo(target) > CLOSE) {
            if (ticks % 20 == 1 || !bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.near(target.blockPosition(), 2.0));
            }
            bot.navigator().tick();
        } else {
            bot.navigator().stop();
        }
        return Status.RUNNING;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return "going to kill " + target.getName().getString();
    }
}
