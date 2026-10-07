package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Walks over to another bot and drops a share of food or materials at its feet. */
public class GiveTask extends Task {
    private static final double HAND_OVER_DISTANCE = 2.5;
    private static final int MAX_TICKS = 20 * 45;

    private final @Nullable Sharing.Offer offer;
    private @Nullable BlockPos heading;
    private int ticks;

    /** A give task for whatever this bot can share right now (does nothing if that changed meanwhile). */
    public static GiveTask forBestOffer(BotPlayer bot) {
        return new GiveTask(bot, Sharing.findOffer(bot));
    }

    public GiveTask(BotPlayer bot, @Nullable Sharing.Offer offer) {
        super(bot);
        this.offer = offer;
    }

    @Override
    public Status tick() {
        if (offer == null) {
            return Status.SUCCESS;
        }
        BotPlayer receiver = offer.receiver();
        if (receiver.isRemoved() || receiver.isDeadOrDying() || receiver.level() != bot.level() || ++ticks > MAX_TICKS) {
            return Status.FAILURE;
        }
        if (bot.distanceTo(receiver) > HAND_OVER_DISTANCE) {
            BlockPos target = receiver.blockPosition();
            if (heading == null || !bot.navigator().isActive() || heading.distSqr(target) > 9) {
                heading = target;
                bot.navigator().navigate(Goal.near(target, 1.5));
            }
            if (bot.navigator().tick() == Navigator.Status.FAILED) {
                return Status.FAILURE;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().lookAt(receiver.getEyePosition());
        int given = dropItems();
        if (given == 0) {
            return Status.FAILURE;
        }
        bot.debug("gave {} {} to {}", given, offer.what(), receiver.getPlainTextName());
        receiver.debug("{} gave me {} {}", bot.getPlainTextName(), given, offer.what());
        Sharing.shared(bot, receiver, receiver.position());
        return Status.SUCCESS;
    }

    /** Throws the items towards the receiver, like pressing Q while looking at them. */
    private int dropItems() {
        Inventory inventory = bot.getInventory();
        int left = offer.count();
        for (int slot = 0; slot < Inv.MAIN_SIZE && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !offer.items().test(stack)) {
                continue;
            }
            int amount = Math.min(left, stack.getCount());
            bot.drop(stack.split(amount), false, true);
            left -= amount;
        }
        return offer.count() - left;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return offer == null ? "sharing" : "bringing " + offer.count() + " " + offer.what() + " to " + offer.receiver().getPlainTextName();
    }
}
