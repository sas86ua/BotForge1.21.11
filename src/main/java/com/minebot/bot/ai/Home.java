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

import java.util.ArrayList;
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
        if (!memory.builtHome() && !memory.houseDone() && memory.bed() != null && level.isLoaded(memory.bed())) {
            adoptNearby(bot, level, memory.bed());
        }
    }

    private static final int CLUTTER_CHECK_TICKS = 20 * 60 * 5;
    /** Stray blocks found in a bot's house (see noteClutter): they go, whatever they stand next to. */
    private static final java.util.Map<java.util.UUID, java.util.Set<BlockPos>> CLUTTER = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean isClutter(BotPlayer bot, BlockPos pos) {
        return CLUTTER.getOrDefault(bot.getUUID(), java.util.Set.of()).contains(pos);
    }

    /**
     * A furnace the bot has already: in its house, or by its workshop (the old hut's). A new one isn't put up while there
     * is one (Steve put one by the wall of his new house, with the old house and the workshop both having theirs).
     */
    public static @org.jetbrains.annotations.Nullable BlockPos existingFurnace(BotPlayer bot, ServerLevel level) {
        BotMemory memory = bot.memory();
        var plan = memory.houseOrigin() != null ? com.minebot.bot.build.HousePlans.of(memory) : null;
        if (plan != null) {
            for (var cell : plan.cells()) {
                if (cell.kind() != '.' && level.isLoaded(cell.pos()) && level.getBlockState(cell.pos()).is(net.minecraft.world.level.block.Blocks.FURNACE)) {
                    return cell.pos().immutable();
                }
            }
        }
        BlockPos workshop = memory.workshop();
        if (workshop != null && level.isLoaded(workshop)) {
            for (BlockPos pos : BlockPos.betweenClosed(workshop.offset(-8, -2, -8), workshop.offset(8, 2, 8))) {
                if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.FURNACE)) {
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    /** How far round the house (and the old hut) the steps, pillars and ladders put up to build it are taken down. */
    private static final int TIDY_RANGE = 6;
    private static final int TIDY_HEIGHT = 40;

    /**
     * Once the house is done: what the bots put up to climb on while building it (pillars, ramps, ladders), round the
     * house and the hut it started from, goes like any pillar. Only blocks that stand out (three sides open) or ladders:
     * the ground is left as it is, and so is anything by a field, water or fence.
     */
    private static void noteStrays(BotPlayer bot, ServerLevel level, com.minebot.bot.build.HousePlan plan) {
        BotMemory memory = bot.memory();
        java.util.Set<BlockPos> planned = new java.util.HashSet<>();
        for (var cell : plan.cells()) {
            if (cell.kind() != '.') {
                planned.add(cell.pos());
            }
        }
        BlockPos o = plan.origin();
        int floor = plan.floorY();
        java.util.List<int[]> areas = new java.util.ArrayList<>(); // (minX, minZ, maxX, maxZ)
        areas.add(new int[] {o.getX() - TIDY_RANGE, o.getZ() - TIDY_RANGE, o.getX() + plan.sizeX() - 1 + TIDY_RANGE,
            o.getZ() + plan.sizeZ() - 1 + TIDY_RANGE});
        BlockPos hut = memory.workshop();
        if (hut != null && !plan.inFootprint(hut)) {
            areas.add(new int[] {hut.getX() - TIDY_RANGE - 3, hut.getZ() - TIDY_RANGE - 3, hut.getX() + TIDY_RANGE + 3, hut.getZ() + TIDY_RANGE + 3});
        }
        int found = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int[] area : areas) {
            for (int x = area[0]; x <= area[2] && found < 150; x++) {
                for (int z = area[1]; z <= area[3] && found < 150; z++) {
                    if (!level.isLoaded(at.set(x, floor, z))) {
                        continue;
                    }
                    int yMin = area == areas.get(0) ? floor : Math.max(level.getMinY(), (hut != null ? hut.getY() : floor) - 1);
                    for (int y = yMin; y <= yMin + TIDY_HEIGHT && found < 150; y++) {
                        at.set(x, y, z);
                        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(at);
                        boolean ladder = state.is(net.minecraft.world.level.block.Blocks.LADDER);
                        if (!ladder && !com.minebot.bot.action.Inv.isScaffold(new net.minecraft.world.item.ItemStack(state.getBlock().asItem()))) {
                            continue;
                        }
                        BlockPos pos = at.immutable();
                        if (planned.contains(pos) || bot.pillars().contains(pos) || isClutter(bot, pos) || stray(level, pos, ladder, plan, floor) == false) {
                            continue;
                        }
                        bot.debug("a stray {} at {} by the house; taking it down", state.getBlock().getName().getString(), pos.toShortString());
                        bot.notePillar(pos);
                        CLUTTER.computeIfAbsent(bot.getUUID(), u -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(pos);
                        found++;
                    }
                }
            }
        }
    }

    private static boolean stray(ServerLevel level, BlockPos pos, boolean ladder, com.minebot.bot.build.HousePlan plan, int floor) {
        if (com.minebot.bot.build.GreatBuild.nearSite(pos) || com.minebot.bot.world.ProtectedAreas.isProtected(level, pos)
            || !com.minebot.bot.world.BlockRules.canBreak(level, pos, level.getBlockState(pos))) {
            return false;
        }
        // (the path of ground round the house, at its floor)
        if (pos.getY() <= floor && pos.getX() >= plan.origin().getX() - 1 && pos.getX() <= plan.origin().getX() + plan.sizeX()
            && pos.getZ() >= plan.origin().getZ() - 1 && pos.getZ() <= plan.origin().getZ() + plan.sizeZ()) {
            return false;
        }
        int open = 0;
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            BlockPos side = pos.relative(direction);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(side);
            if (state.is(net.minecraft.world.level.block.Blocks.FARMLAND) || !state.getFluidState().isEmpty()
                || state.is(net.minecraft.tags.BlockTags.FENCES) || state.is(net.minecraft.tags.BlockTags.FENCE_GATES)
                || state.is(net.minecraft.tags.BlockTags.WALLS)) {
                return false;
            }
            if (direction.getAxis().isHorizontal() && (state.canBeReplaced() || state.getCollisionShape(level, side).isEmpty())) {
                open++;
            }
        }
        BlockPos above = pos.above();
        net.minecraft.world.level.block.state.BlockState up = level.getBlockState(above);
        boolean carries = !(up.isAir() || up.canBeReplaced() || up.is(net.minecraft.world.level.block.Blocks.LADDER)
            || com.minebot.bot.action.Inv.isScaffold(new net.minecraft.world.item.ItemStack(up.getBlock().asItem())));
        return !carries && (ladder || open >= 3);
    }

    /**
     * A block of dirt or cobblestone (a step it stood on, a block put down in passing) in its house where the
     * plan has it empty - by the bed, in the way: taken out like a pillar (see DismantleTask). Furniture and
     * torches stay where they are.
     */
    private static void noteClutter(BotPlayer bot, ServerLevel level) {
        BotMemory memory = bot.memory();
        var plan = com.minebot.bot.build.HousePlans.of(memory);
        if (plan == null) {
            return;
        }
        noteStrays(bot, level, plan);
        // The furnace it remembers stands outside the finished house (put up at the hut, or by the wall): the one inside, if
        // there's one, is its furnace; else it forgets that one and puts a new one in the house
        BlockPos furnace = memory.furnace();
        if (furnace != null && level.isLoaded(furnace) && !plan.inFootprint(furnace)) {
            BlockPos inside = existingFurnace(bot, level);
            bot.debug("my furnace at {} is outside the house; {}", furnace.toShortString(),
                inside != null ? "using the one at " + inside.toShortString() : "putting one in the house");
            memory.setFurnace(inside);
            // (and the one outside comes down, unless it's in the workshop's room)
            BlockPos workshop = memory.workshop();
            boolean inWorkshop = workshop != null && Math.abs(workshop.getX() - furnace.getX()) <= 8
                && Math.abs(workshop.getZ() - furnace.getZ()) <= 8 && Math.abs(workshop.getY() - furnace.getY()) <= 2;
            if (!inWorkshop && !furnace.equals(inside) && level.getBlockState(furnace).is(net.minecraft.world.level.block.Blocks.FURNACE)) {
                bot.notePillar(furnace.immutable());
                CLUTTER.computeIfAbsent(bot.getUUID(), u -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(furnace.immutable());
            }
        }
        for (var cell : plan.cells()) {
            if (cell.kind() != '.' || cell.layer() == 0 || !level.isLoaded(cell.pos())) {
                continue;
            }
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(cell.pos());
            if (!state.isAir() && com.minebot.bot.action.Inv.isScaffold(new net.minecraft.world.item.ItemStack(state.getBlock().asItem()))
                && !bot.pillars().contains(cell.pos())) {
                bot.debug("a block of {} in my house at {}; taking it out", state.getBlock().getName().getString(), cell.pos().toShortString());
                bot.notePillar(cell.pos().immutable());
                CLUTTER.computeIfAbsent(bot.getUUID(), u -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(cell.pos().immutable());
            }
        }
    }

    /**
     * Its house is lost: it's built again, like the first one, 20-30 days after it has a home again (a hut,
     * a bed somewhere) - the clock starts over then.
     */
    private static void houseLost(BotPlayer bot) {
        BotMemory memory = bot.memory();
        memory.setHouseDone(false);
        memory.setHouseSite(null, net.minecraft.core.Direction.NORTH, 0);
        memory.setHomeSince(-1);
    }

    /** Moved into a house of its own: what it used in someone else's is left there, not its any more. */
    public static void returnBorrowed(BotPlayer bot) {
        BotMemory memory = bot.memory();
        for (BlockPos pos : memory.borrowed()) {
            memory.chests().remove(pos);
            if (pos.equals(memory.furnace())) {
                memory.setFurnace(null);
            }
            if (pos.equals(memory.craftingTable())) {
                memory.setCraftingTable(null);
            }
        }
        if (!memory.borrowed().isEmpty()) {
            bot.debug("my own house now: left the {} things I used in the other one", memory.borrowed().size());
        }
        memory.borrowed().clear();
    }

    /** How far from its bed (not a house of its own: a village's, say) it uses what stands about. */
    private static final int NEARBY_RADIUS = 12;

    /**
     * Living in someone else's house (a village's, a player's): the furnace, crafting table and chests
     * there and next door (a smithy) are its to use till it has a house of its own (then they're left,
     * see {@link #returnBorrowed}), if no other bot uses them. What's still missing after that goes in a
     * yard by the house (see FurnishTask).
     */
    private static void adoptNearby(BotPlayer bot, ServerLevel level, BlockPos bed) {
        BotMemory memory = bot.memory();
        java.util.Set<BlockPos> taken = new java.util.HashSet<>();
        for (BotPlayer other : com.minebot.bot.BotManager.all()) {
            if (other != bot) {
                taken.addAll(other.memory().chests());
                if (other.memory().furnace() != null) {
                    taken.add(other.memory().furnace());
                }
                if (other.memory().craftingTable() != null) {
                    taken.add(other.memory().craftingTable());
                }
            }
        }
        BlockPos furnace = null;
        BlockPos table = null;
        List<BlockPos> chests = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(bed.offset(-NEARBY_RADIUS, -4, -NEARBY_RADIUS), bed.offset(NEARBY_RADIUS, 4, NEARBY_RADIUS))) {
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            boolean isFurnace = state.is(net.minecraft.world.level.block.Blocks.FURNACE);
            boolean isTable = state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
            boolean isChest = state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock;
            if (!isFurnace && !isTable && !isChest || taken.contains(pos) || com.minebot.bot.world.ProtectedAreas.isProtected(level, pos)) {
                continue;
            }
            double distance = pos.distSqr(bed);
            if (isFurnace && memory.furnace() == null && (furnace == null || distance < furnace.distSqr(bed))) {
                furnace = pos.immutable();
            } else if (isTable && memory.craftingTable() == null && (table == null || distance < table.distSqr(bed))) {
                table = pos.immutable();
            } else if (isChest && !memory.chests().contains(pos)) {
                chests.add(pos.immutable());
            }
        }
        if (furnace != null) {
            memory.setFurnace(furnace);
            memory.borrowed().add(furnace);
            bot.debug("using the furnace at {} near my bed (till I have a house of my own)", furnace.toShortString());
        }
        if (table != null) {
            memory.setCraftingTable(table);
            memory.borrowed().add(table);
            bot.debug("using the crafting table at {} near my bed (till I have a house of my own)", table.toShortString());
        }
        for (BlockPos chest : chests) {
            memory.addChest(chest);
            memory.borrowed().add(chest);
        }
        if (!chests.isEmpty()) {
            bot.debug("using {} chests near my bed (till I have a house of my own)", chests.size());
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
                bot.debug("my bed at {} is gone; looking for a new home (my chests stay mine)", bed.toShortString());
                memory.loseHome();
                houseLost(bot);
                return;
            }
        }
        if (bed != null && memory.houseDone() && memory.houseOrigin() != null && level.isLoaded(bed)) {
            bot.every("house clutter", CLUTTER_CHECK_TICKS, () -> {
                noteClutter(bot, level);
                return false;
            });
        }
        if (bed != null && memory.houseDone() && level.isLoaded(bed) && level.canSeeSky(bed.above())) {
            // The house round the bed is gone (burnt, blown up, pulled down): a shelter round the bed for now,
            // and a proper house again in time, as the first time
            bot.debug("my house round the bed at {} is gone; I'll build one again", bed.toShortString());
            houseLost(bot);
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
