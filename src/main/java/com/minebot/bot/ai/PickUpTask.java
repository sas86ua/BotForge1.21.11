package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Goes back for a crafting table, furnace or campfire the bot put down away
 * from home and didn't get to take back (its task was interrupted).
 */
public class PickUpTask extends Task {
    /** Further away than this, it's not worth the walk; forget it. */
    public static final int MAX_DISTANCE = 64;

    private @Nullable BlockPos target;
    private final BlockBreaker breaker;
    private @Nullable Task collect;

    public PickUpTask(BotPlayer bot) {
        super(bot);
        this.breaker = new BlockBreaker(bot);
    }

    /** Is there anything left behind worth fetching? Drops entries that are gone or too far. */
    public static boolean wanted(BotPlayer bot) {
        List<BlockPos> left = bot.leftBehind();
        left.removeIf(pos -> !isStation(bot.level(), pos) || !pos.closerThan(bot.blockPosition(), MAX_DISTANCE));
        return !left.isEmpty();
    }

    private static boolean isStation(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE) || state.getBlock() instanceof CampfireBlock;
    }

    @Override
    public Status tick() {
        if (collect != null) {
            Status status = collect.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            collect = null;
            return Status.SUCCESS;
        }
        if (target == null) {
            if (!wanted(bot)) {
                return Status.SUCCESS;
            }
            target = bot.leftBehind().get(0);
            bot.navigator().navigate(Goal.reachVisible(bot.level(), target));
        }
        if (!isStation(bot.level(), target)) {
            bot.leftBehind().remove(target);
            target = null;
            return Status.RUNNING;
        }
        if (breaker.target() == null) {
            Navigator.Status status = bot.navigator().tick();
            if (status == Navigator.Status.FAILED) {
                bot.leftBehind().remove(target); // can't get there; let it be
                return Status.FAILURE;
            }
            if (status != Navigator.Status.SUCCESS) {
                return Status.RUNNING;
            }
            breaker.start(target);
        }
        BlockBreaker.Result result = breaker.tick();
        if (result == BlockBreaker.Result.RUNNING) {
            return Status.RUNNING;
        }
        BlockPos at = target;
        bot.leftBehind().remove(at);
        target = null;
        if (result == BlockBreaker.Result.SUCCESS) {
            collect = new CollectItemsTask(bot, Vec3.atCenterOf(at), 3.0, 60);
            return Status.RUNNING;
        }
        return Status.FAILURE;
    }

    @Override
    public void stop() {
        breaker.cancel();
        bot.navigator().stop();
        if (collect != null) {
            collect.stop();
        }
    }

    @Override
    public String describe() {
        return target == null ? "picking up my things" : "picking up my things at " + target.toShortString();
    }
}
