package com.minebot.bot.action;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.function.Predicate;

/**
 * Puts a block (crafting table, furnace...) down somewhere next to the bot,
 * digging a niche into the wall first if there's no room. Call {@link #tick}
 * until it returns the position.
 */
public class PlaceNearby {
    private static final int MAX_ATTEMPTS = 40;
    private static final int LAND_RADIUS = 10;

    private final BotPlayer bot;
    private final Predicate<ItemStack> item;
    private @Nullable BlockBreaker clearing;
    private @Nullable BlockPos clearingPos;
    private int attempts;
    private boolean walkingToLand;
    private boolean triedLand;

    public PlaceNearby(BotPlayer bot, Predicate<ItemStack> item) {
        this.bot = bot;
        this.item = item;
    }

    /** @return where the block was placed, or null while still working (see {@link #failed}) */
    public @Nullable BlockPos tick() {
        if (walkingToLand) {
            Navigator.Status status = bot.navigator().tick();
            if (status == Navigator.Status.SUCCESS || status == Navigator.Status.FAILED || status == Navigator.Status.IDLE) {
                walkingToLand = false;
            }
            return null;
        }
        if (clearing != null) {
            BlockBreaker.Result result = clearing.tick();
            if (result == BlockBreaker.Result.RUNNING) {
                return null;
            }
            BlockPos pos = clearingPos;
            clearing = null;
            clearingPos = null;
            if (result == BlockBreaker.Result.SUCCESS && BlockPlacer.place(bot, pos, item)) {
                return pos;
            }
            attempts++;
            return null;
        }
        PlaceSpots.Spot spot = bot.isInWater() ? null : PlaceSpots.find(bot);
        if (spot == null && !triedLand) {
            // In the water, or boxed in: step onto dry, open ground first
            triedLand = true;
            BlockPos land = findDryLand();
            if (land != null) {
                bot.navigator().navigate(Goal.near(land, 0.5));
                walkingToLand = true;
                return null;
            }
        }
        if (spot == null) {
            if (attempts++ == 0) {
                bot.debug("no spot to put something down near {}", bot.blockPosition().toShortString());
            }
            return null;
        }
        if (spot.clearFirst()) {
            clearing = new BlockBreaker(bot);
            clearing.start(spot.pos());
            clearingPos = spot.pos();
            return null;
        }
        if (BlockPlacer.place(bot, spot.pos(), item)) {
            return spot.pos();
        }
        if (attempts++ == 0) {
            bot.debug("could not place at {} ({})", spot.pos().toShortString(), bot.level().getBlockState(spot.pos()));
        }
        return null;
    }

    private @Nullable BlockPos findDryLand() {
        ServerLevel level = bot.level();
        BlockPos feet = bot.blockPosition();
        return BlockPos.betweenClosedStream(feet.offset(-LAND_RADIUS, -3, -LAND_RADIUS), feet.offset(LAND_RADIUS, 3, LAND_RADIUS))
            .map(BlockPos::immutable)
            .filter(pos -> PlaceSpots.isFreeGroundSpot(level, pos)
                && level.getBlockState(pos.above()).canBeReplaced() && level.getFluidState(pos.above()).isEmpty())
            .min(Comparator.comparingDouble(pos -> pos.distSqr(feet)))
            .orElse(null);
    }

    public boolean failed() {
        return attempts > MAX_ATTEMPTS;
    }

    public void cancel() {
        if (clearing != null) {
            clearing.cancel();
        }
    }
}
