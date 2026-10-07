package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.BotSpawnPoints;
import com.minebot.bot.world.HomeFinder;
import com.minebot.bot.world.NoGoAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/** Checks on the bot's home: does it still stand, what's missing. */
public final class Home {
    private Home() {
    }

    /** The home's level if the bot is in it, else null. */
    public static @Nullable ServerLevel levelIfHere(BotPlayer bot) {
        GlobalPos home = bot.memory().home();
        return home != null && home.dimension() == bot.level().dimension() ? bot.level() : null;
    }

    /**
     * Forgets home blocks that were destroyed, and the whole home if its bed
     * is gone. Only checks what's in loaded chunks.
     */
    /** How far from the bed or workshop a room is looked through for chests that are the bot's. */
    private static final int ROOM_RADIUS = 12;

    /**
     * Everything inside its house (the room with its bed) and its workshop is its own: chests and
     * barrels someone put there are stored in like the ones it put down itself. Out in the open (no
     * walls round it) nothing is taken over, nor in a protected place.
     */
    public static void adoptStorage(BotPlayer bot) {
        ServerLevel level = levelIfHere(bot);
        if (level == null) {
            return;
        }
        BotMemory memory = bot.memory();
        for (BlockPos anchor : new BlockPos[] {memory.bed(), memory.workshop()}) {
            if (anchor == null || !level.isLoaded(anchor)) {
                continue;
            }
            java.util.Set<BlockPos> room = FurnishTask.enclosedRoom(level, anchor, ROOM_RADIUS);
            if (room == null) {
                continue;
            }
            for (BlockPos floor : room) {
                for (int dy = -6; dy <= 3; dy++) { // (a cellar under the room, a shelf or loft above it)
                    BlockPos pos = floor.above(dy);
                    net.minecraft.world.level.block.Block block = level.getBlockState(pos).getBlock();
                    if ((block instanceof net.minecraft.world.level.block.ChestBlock || block instanceof net.minecraft.world.level.block.BarrelBlock)
                        && !memory.chests().contains(pos) && !com.minebot.bot.world.ProtectedAreas.isProtected(level, pos)) {
                        memory.addChest(pos.immutable());
                        bot.debug("the chest at {} in my house is mine now", pos.toShortString());
                    }
                }
            }
        }
    }

    public static void validate(BotPlayer bot) {
        BotMemory memory = bot.memory();
        ServerLevel level = levelIfHere(bot);
        if (level == null) {
            return;
        }
        BlockPos bed = memory.bed();
        if (bed != null && NoGoAreas.contains(level, bed)) {
            bot.debug("my bed at {} is in a no-go area now; moving out", bed.toShortString());
            HomeFinder.release(level, bed);
            memory.setHome(null, false);
            return;
        }
        if (bed != null && level.isLoaded(bed) && !HomeFinder.isBed(level, bed)) {
            HomeFinder.release(level, bed);
            if (memory.houseOrigin() != null && !memory.houseDone() && memory.workshop() != null) {
                // Carried over to its new house: the hut stays its workshop, chests and all
                bot.debug("took my bed from {} to move into the house", bed.toShortString());
                memory.setBed(null);
            } else {
                bot.debug("my bed at {} is gone; looking for a new home", bed.toShortString());
                memory.setHome(null, false);
                return;
            }
        }
        for (BlockPos chest : List.copyOf(memory.chests())) {
            if (level.isLoaded(chest) && !(level.getBlockState(chest).getBlock() instanceof ChestBlock)) {
                memory.removeChest(chest);
            }
        }
        BlockPos table = memory.craftingTable();
        if (table != null && level.isLoaded(table) && !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
            memory.setCraftingTable(null);
        }
        BlockPos furnace = memory.furnace();
        if (furnace != null && level.isLoaded(furnace) && !(level.getBlockState(furnace).getBlock() instanceof FurnaceBlock)) {
            memory.setFurnace(null);
        }
        BlockPos campfire = memory.campfire();
        if (campfire != null && level.isLoaded(campfire)
            && !(level.getBlockState(campfire).getBlock() instanceof net.minecraft.world.level.block.CampfireBlock)) {
            memory.setCampfire(null);
        }
    }

    /**
     * A homeless bot that finds itself outside its zone (old data, teleported...)
     * makes a new life where it is instead of trekking back: around a spawn point
     * whose zone it's in, or else right here. A bot with a home walks back to it.
     */
    public static void settleIfLost(BotPlayer bot) {
        BotMemory memory = bot.memory();
        BlockPos pos = bot.blockPosition();
        if (memory.home() != null || memory.inZone(bot.level().dimension(), pos)) {
            return;
        }
        GlobalPos anchor = BotSpawnPoints.get(bot.level().getServer()).points().stream()
            .map(BotSpawnPoints.Point::pos)
            .filter(point -> point.dimension() == bot.level().dimension()
                && point.pos().closerThan(pos, BotMemory.ZONE_RADIUS - 100))
            .min(Comparator.comparingDouble(point -> point.pos().distSqr(pos)))
            .orElse(GlobalPos.of(bot.level().dimension(), pos));
        bot.debug("outside my zone around {} with no home; settling around {} instead",
            memory.anchor().pos().toShortString(), anchor.pos().toShortString());
        bot.setAnchor(anchor);
    }

    /** The bot has a home (with a bed) at most this far away. */
    public static boolean isNear(BotPlayer bot, int range) {
        BlockPos bed = bot.memory().bed();
        return has(bot) && levelIfHere(bot) != null && bed.closerThan(bot.blockPosition(), range);
    }

    public static boolean has(BotPlayer bot) {
        return bot.memory().home() != null && bot.memory().bed() != null;
    }

    public static boolean needsFurniture(BotPlayer bot) {
        BotMemory memory = bot.memory();
        return has(bot) && (memory.chests().isEmpty() || memory.craftingTable() == null || memory.furnace() == null
            || darkSpot(bot) != null || memory.campfire() == null);
    }

    /** Places each bot gave up lighting (three torches and still dark), and until when (game time). */
    private static final java.util.Map<java.util.UUID, java.util.Map<BlockPos, Long>> UNLIGHTABLE = new java.util.HashMap<>();
    private static final int UNLIGHTABLE_TICKS = 20 * 60 * 30;

    /** Forgotten when the bot leaves the server. */
    public static void forget(java.util.UUID bot) {
        UNLIGHTABLE.remove(bot);
    }

    /** Lighting this spot didn't work (no wall in the room, or still dark after a few torches): left alone a while. */
    public static void cannotLight(BotPlayer bot, BlockPos spot) {
        UNLIGHTABLE.computeIfAbsent(bot.getUUID(), id -> new java.util.HashMap<>())
            .put(spot.immutable(), bot.level().getGameTime() + UNLIGHTABLE_TICKS);
    }

    /**
     * Where to stand by the bed or in the workshop, if it's dark there (no torch or other light
     * nearby); else null. A workshop spot that ended up in a wall is looked at from the room beside it
     * (inside a block it is always dark: torches were hung outside, one after another).
     */
    public static @Nullable BlockPos darkSpot(BotPlayer bot) {
        ServerLevel level = levelIfHere(bot);
        if (level == null || !has(bot)) {
            return null;
        }
        java.util.Map<BlockPos, Long> given = UNLIGHTABLE.getOrDefault(bot.getUUID(), java.util.Map.of());
        long now = level.getGameTime();
        for (BlockPos anchor : new BlockPos[] {bot.memory().bed(), bot.memory().workshop()}) {
            if (anchor == null || !level.isLoaded(anchor)) {
                continue;
            }
            BlockPos spot = openSpot(level, anchor);
            if (spot != null && given.getOrDefault(spot, 0L) <= now && level.getBrightness(LightLayer.BLOCK, spot.above()) < 7) {
                return spot;
            }
        }
        return null;
    }

    /** The spot itself if there's air over it (a bed, a floor), else the nearest place to stand within two blocks. */
    private static @Nullable BlockPos openSpot(ServerLevel level, BlockPos anchor) {
        if (level.getBlockState(anchor.above()).isAir()) {
            return anchor;
        }
        return BlockPos.betweenClosedStream(anchor.offset(-2, -1, -2), anchor.offset(2, 1, 2))
            .filter(pos -> level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP))
            .map(BlockPos::immutable)
            // (indoors first: the room, not the yard on the other side of the wall)
            .min(Comparator.comparingInt((BlockPos pos) -> level.canSeeSky(pos) ? 1 : 0)
                .thenComparingDouble(pos -> pos.distSqr(anchor)))
            .orElse(null);
    }

    public static boolean isNight(BotPlayer bot) {
        return bot.level().isDarkOutside();
    }
}
