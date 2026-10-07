package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.path.Approach;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * At the Great Build: a camp's chest, anyone's (it's all for the build). Either puts what it dug
 * there (to dig on with room in the bag), or takes building blocks out of it (instead of a trip
 * down the mine). Opens the lid while it works, like a player would.
 */
public class CampChestTask extends Task {
    private static final int OPEN_TICKS = 8;

    private final BlockPos pos;
    private final boolean put;
    private final Predicate<ItemStack> what;
    private final int amount;
    private Approach approach;
    private int openTicks;
    private boolean opened;
    private int moved;

    /** Puts every stack matching {@code what} into the chest at {@code pos}. */
    public static CampChestTask put(BotPlayer bot, BlockPos pos, Predicate<ItemStack> what) {
        return new CampChestTask(bot, pos, true, what, Integer.MAX_VALUE);
    }

    /** Takes up to {@code amount} items matching {@code what} out of the chest at {@code pos}. */
    public static CampChestTask take(BotPlayer bot, BlockPos pos, Predicate<ItemStack> what, int amount) {
        return new CampChestTask(bot, pos, false, what, amount);
    }

    private CampChestTask(BotPlayer bot, BlockPos pos, boolean put, Predicate<ItemStack> what, int amount) {
        super(bot);
        this.pos = pos;
        this.put = put;
        this.what = what;
        this.amount = amount;
        this.approach = new Approach(bot, pos);
    }

    @Override
    public Status tick() {
        if (!(bot.level().getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
            return finish(Status.FAILURE);
        }
        Approach.Result reached = approach.tick();
        if (reached == Approach.Result.FAILED) {
            return finish(Status.FAILURE);
        }
        if (reached == Approach.Result.MOVING) {
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().lookAt(Vec3.atCenterOf(pos));
        if (!opened) {
            opened = true;
            chest.startOpen(bot);
        }
        if (++openTicks < OPEN_TICKS) {
            return Status.RUNNING; // lid opening
        }
        if (!put) {
            moved = Stash.withdraw(bot, chest, what, amount);
            bot.debug("great build: took {} out of the camp chest at {}", moved, pos.toShortString());
            return finish(moved > 0 ? Status.SUCCESS : Status.FAILURE);
        }
        // One slot a tick
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (!stack.isEmpty() && what.test(stack)) {
                int count = Stash.deposit(bot, slot, stack.getCount(), chest);
                if (count > 0) {
                    moved += count;
                    return Status.RUNNING;
                }
            }
        }
        bot.debug("great build: put {} in the camp chest at {}", moved, pos.toShortString());
        return finish(moved > 0 ? Status.SUCCESS : Status.FAILURE);
    }

    private Status finish(Status status) {
        close();
        bot.navigator().stop();
        return status;
    }

    private void close() {
        if (opened && bot.level().getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            chest.stopOpen(bot);
        }
        opened = false;
    }

    @Override
    public void stop() {
        close();
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return put ? "putting what it dug in the camp chest" : "taking building blocks from a camp chest";
    }
}
