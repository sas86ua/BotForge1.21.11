package com.minebot.bot.world;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.BotRegistry;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Finds a bed a bot may move into: any bed (village, player-placed, set out
 * for bots) that isn't another bot's, isn't a player's current respawn point,
 * isn't claimed by a villager and isn't buried deep underground.
 */
public final class HomeFinder {
    private static final Predicate<Holder<PoiType>> HOME = holder -> holder.is(PoiTypes.HOME);
    /** How far below the surface a bed may be. */
    private static final int MAX_DEPTH = 12;

    private HomeFinder() {
    }

    /** Finds the nearest free bed and reserves it at once (villagers grab free beds quickly). */
    public static @Nullable BlockPos claimFreeBed(BotPlayer bot, int radius) {
        ServerLevel level = bot.level();
        List<BlockPos> beds = level.getPoiManager()
            .findAllClosestFirstWithType(HOME, pos -> isUsable(bot, pos), bot.blockPosition(), radius, PoiManager.Occupancy.HAS_SPACE)
            .map(Pair::getSecond)
            .toList();
        for (BlockPos bed : beds) {
            if (claim(level, bed)) {
                return bed;
            }
        }
        return null;
    }

    /**
     * A bed nearby to spend the night in: not another bot's, not a player's respawn
     * bed, nobody in it right now. Not reserved, just borrowed for the night.
     */
    public static @Nullable BlockPos findBedForTheNight(BotPlayer bot, int radius) {
        ServerLevel level = bot.level();
        return level.getPoiManager()
            .findAllClosestFirstWithType(HOME, pos -> isUsable(bot, pos) && !isOccupied(level, pos),
                bot.blockPosition(), radius, PoiManager.Occupancy.ANY)
            .map(Pair::getSecond)
            .findFirst()
            .orElse(null);
    }

    private static boolean isOccupied(ServerLevel level, BlockPos bed) {
        BlockState state = level.getBlockState(bed);
        return state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED);
    }

    /** Reserves the bed's POI ticket so villagers don't take it. */
    public static boolean claim(ServerLevel level, BlockPos bed) {
        Optional<BlockPos> taken = level.getPoiManager().take(HOME, (holder, pos) -> pos.equals(bed), bed, 1);
        return taken.isPresent();
    }

    public static void release(ServerLevel level, BlockPos bed) {
        try {
            if (level.getPoiManager().existsAtPosition(PoiTypes.HOME, bed)) {
                level.getPoiManager().release(bed);
            }
        } catch (IllegalStateException ignored) {
            // the bed is gone, and its POI with it
        }
    }

    private static boolean isUsable(BotPlayer bot, BlockPos pos) {
        ServerLevel level = bot.level();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)) {
            return false;
        }
        // Any bed will do (village, player-placed, set out for bots...) except another
        // bot's, and a bed a player currently respawns at (they'd find it occupied at night)
        if (!bot.memory().inZone(level.dimension(), pos) || ProtectedAreas.isPlayerRespawnBlock(level, pos)
            || NoGoAreas.contains(level, pos)) {
            return false;
        }
        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - pos.getY() > MAX_DEPTH) {
            return false; // deep underground (a buried structure): no place to live
        }
        for (BotMemory other : BotRegistry.get(level.getServer()).all()) {
            if (other != bot.memory() && other.bed() != null && other.bed().closerThan(pos, 2)) {
                return false;
            }
        }
        return true;
    }

    /** Is there still a bed at this position? */
    public static boolean isBed(ServerLevel level, BlockPos bed) {
        return level.getBlockState(bed).getBlock() instanceof BedBlock;
    }
}
