package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** Eats one item, the normal way: hold it in hand and use it until it's consumed. */
public class EatTask extends Task {
    private static final int MAX_TICKS = 80;

    private ItemStack eating = ItemStack.EMPTY;
    private int ticks;
    private boolean started;

    public EatTask(BotPlayer bot) {
        super(bot);
    }

    @Override
    public Status tick() {
        bot.controller().releaseInputs();
        if (!started) {
            int slot = Food.pickFood(bot);
            if (slot < 0) {
                return Status.FAILURE;
            }
            ItemStack food = bot.getInventory().getItem(slot);
            if (!Inv.select(bot, stack -> stack == food)) {
                return Status.FAILURE;
            }
            if (bot.isUsingItem()) {
                bot.stopUsingItem(); // e.g. a raised shield
            }
            eating = bot.getMainHandItem().copy(); // a copy: the held stack shrinks as it's eaten
            bot.startUsingItem(InteractionHand.MAIN_HAND);
            started = true;
            return Status.RUNNING;
        }
        if (++ticks > MAX_TICKS) {
            bot.stopUsingItem();
            return Status.FAILURE;
        }
        // Using an item finishes by itself after its use duration
        if (bot.isUsingItem()) {
            return Status.RUNNING;
        }
        bot.debug("ate {} (food {})", eating.getHoverName().getString(), bot.getFoodData().getFoodLevel());
        return Status.SUCCESS;
    }

    @Override
    public void stop() {
        if (bot.isUsingItem()) {
            bot.stopUsingItem();
        }
    }

    @Override
    public String describe() {
        return "eating " + eating.getHoverName().getString();
    }
}
