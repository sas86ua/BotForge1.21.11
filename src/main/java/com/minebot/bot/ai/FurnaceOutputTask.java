package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.path.Approach;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Takes finished output out of its own furnace at home: ingots or charcoal
 * left behind when a smelting job was cut short (night, a fight, hunger).
 * Only once the furnace has gone out, so a job in progress isn't disturbed;
 * from the bag it goes into the chest with the rest.
 */
public class FurnaceOutputTask extends Task {
    private static final int SLOT_RESULT = 2;
    private static final int CHECK_INTERVAL = 20 * 60 * 5;

    private final @Nullable BlockPos furnace;
    private @Nullable Approach approach;

    public FurnaceOutputTask(BotPlayer bot) {
        this(bot, ready(bot));
    }

    /** That furnace's output (at the Great Build: any camp's, it's all for the build). */
    public FurnaceOutputTask(BotPlayer bot, @Nullable BlockPos furnace) {
        super(bot);
        this.furnace = furnace;
    }

    /** Gone out with something in the output slot. */
    public static boolean finished(BotPlayer bot, BlockPos pos) {
        return bot.level().isLoaded(pos) && bot.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity entity
            && !entity.getItem(SLOT_RESULT).isEmpty()
            && !bot.level().getBlockState(pos).getOptionalValue(AbstractFurnaceBlock.LIT).orElse(false);
    }

    public static boolean wanted(BotPlayer bot) {
        return Home.isNear(bot, 32) && bot.every("furnace output", CHECK_INTERVAL, () -> ready(bot) != null);
    }

    /** Its furnace (or smoker, or other furnace at home), if it has gone out with something in the output slot. */
    private static @Nullable BlockPos ready(BotPlayer bot) {
        if (Home.levelIfHere(bot) == null || Inv.freeSlots(bot) == 0) {
            return null;
        }
        java.util.List<BlockPos> own = new java.util.ArrayList<>();
        own.add(bot.memory().furnace());
        own.add(SmeltTask.ownSmoker(bot)); // (baked potatoes left to cook)
        own.addAll(SmeltTask.otherFurnaces(bot));
        for (BlockPos pos : own) {
            if (pos == null || !bot.level().isLoaded(pos)
                || !(bot.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity entity) || entity.getItem(SLOT_RESULT).isEmpty()) {
                continue;
            }
            if (!bot.level().getBlockState(pos).getOptionalValue(AbstractFurnaceBlock.LIT).orElse(false)) {
                return pos;
            }
        }
        return null;
    }

    @Override
    public Status tick() {
        if (furnace == null || !(bot.level().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity entity)) {
            return done(Status.FAILURE);
        }
        if (approach == null) {
            approach = new Approach(bot, furnace);
        }
        Approach.Result reached = approach.tick();
        if (reached == Approach.Result.MOVING) {
            return Status.RUNNING;
        }
        if (reached == Approach.Result.FAILED) {
            return done(Status.FAILURE);
        }
        bot.controller().lookAt(Vec3.atCenterOf(furnace));
        ItemStack result = entity.getItem(SLOT_RESULT);
        if (!result.isEmpty()) {
            bot.debug("took {} {} out of the furnace at {}", result.getCount(), result.getItem(), furnace.toShortString());
            entity.setItem(SLOT_RESULT, ItemStack.EMPTY);
            Inv.give(bot, result);
            entity.awardUsedRecipesAndPopExperience(bot); // (as a player gets for taking it)
            entity.setChanged();
        }
        return done(Status.SUCCESS);
    }

    private Status done(Status status) {
        if (status == Status.SUCCESS) {
            bot.every("furnace output", 0, () -> false); // (looks again next time round, then every 5 minutes)
        }
        return status;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return "taking things out of the furnace";
    }
}
