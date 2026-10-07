package com.minebot.bot.action;

import com.minebot.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Mines one block the way a player does: best tool in hand, real mining time
 * (tool, enchantments, effects, being in water or mid-air all count), crack
 * animation, then the vanilla break with drops and tool wear.
 */
public class BlockBreaker {
    /** Give up on blocks that would take longer than this (e.g. obsidian by hand). */
    private static final int MAX_TICKS = 20 * 20;

    private final BotPlayer bot;
    private @Nullable BlockPos target;
    private float progress;
    private int ticks;
    private int lastStage;

    public BlockBreaker(BotPlayer bot) {
        this.bot = bot;
    }

    public @Nullable BlockPos target() {
        return target;
    }

    public boolean isBreaking(BlockPos pos) {
        return pos.equals(target);
    }

    public void start(BlockPos pos) {
        if (pos.equals(target)) {
            return;
        }
        cancel();
        target = pos.immutable();
        progress = 0.0F;
        ticks = 0;
        lastStage = -1;
    }

    public void cancel() {
        if (target != null) {
            bot.level().destroyBlockProgress(bot.getId(), target, -1);
            target = null;
        }
    }

    /** @return SUCCESS once the block is gone */
    public Result tick() {
        if (target == null) {
            return Result.FAILED;
        }
        ServerLevel level = bot.level();
        BlockState state = level.getBlockState(target);
        if (state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty() && state.getCollisionShape(level, target).isEmpty()) {
            // Already gone (or just grass the bot can walk through)
            if (!state.isAir()) {
                bot.gameMode.destroyBlock(target);
            }
            cancel();
            return Result.SUCCESS;
        }
        if (!bot.isWithinBlockInteractionRange(target, 0.5)) {
            cancel();
            return Result.FAILED;
        }

        if (ticks % 10 == 0) {
            // (and again now and then: eating, putting a block down... leaves something else in the hand)
            Inv.selectBestTool(bot, state);
        }
        bot.controller().lookAt(Vec3.atCenterOf(target));
        progress += state.getDestroyProgress(bot, level, target);
        if (ticks % 4 == 0) {
            bot.swing(InteractionHand.MAIN_HAND);
        }
        ticks++;

        if (progress >= 1.0F) {
            BlockPos pos = target;
            cancel();
            return bot.gameMode.destroyBlock(pos) ? Result.SUCCESS : Result.FAILED;
        }
        int stage = (int) (progress * 10.0F);
        if (stage != lastStage) {
            level.destroyBlockProgress(bot.getId(), target, stage);
            lastStage = stage;
        }
        if (ticks > MAX_TICKS) {
            cancel();
            return Result.FAILED;
        }
        return Result.RUNNING;
    }

    /** Estimated ticks to mine with the best tool the bot has (for path costs). */
    public static double estimateTicks(BotPlayer bot, BlockState state, ServerLevel level, BlockPos pos) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) {
            return Double.POSITIVE_INFINITY;
        }
        if (hardness == 0) {
            return 1;
        }
        float speed = Inv.bestSpeed(bot, state);
        boolean harvest = Inv.canHarvest(bot, state);
        double perTick = speed / hardness / (harvest ? 30.0 : 100.0);
        return Math.ceil(1.0 / perTick);
    }

    public enum Result { RUNNING, SUCCESS, FAILED }
}
