package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.action.PlaceSpots;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Puts a chest, crafting table and furnace in the bot's home, close to the
 * bed and against the walls so they don't block the way.
 */
public class FurnishTask extends Task {
    private static final int RADIUS = 4;

    private @Nullable Task child;
    private @Nullable BlockPos spot;
    /** Which way the chest put at {@link #spot} should face (to join the one beside it), if it matters. */
    private @Nullable Direction spotFacing;
    /** Torches hung this time round; a spot still dark after this many is given up on (Home#cannotLight). */
    private int torchesHung;
    private static final int MAX_TORCHES = 3;
    private int stuck;
    /** Add one more chest even though the home has some (they're full). */
    private boolean extraChest;
    /** Only put this one in (a crafting table or furnace needed right now), nothing else. */
    private final @Nullable Item only;
    private boolean placedOnly;
    /** The furnace put in is a spare: the home furnace stays the one it remembers. */
    private boolean spare;

    public FurnishTask(BotPlayer bot) {
        this(bot, false);
    }

    public FurnishTask(BotPlayer bot, boolean extraChest) {
        this(bot, extraChest, null);
    }

    private FurnishTask(BotPlayer bot, boolean extraChest, @Nullable Item only) {
        super(bot);
        this.extraChest = extraChest;
        this.only = only;
    }

    /** Puts a crafting table or furnace in the home for good, to use it right away. */
    public static FurnishTask only(BotPlayer bot, Item item) {
        return new FurnishTask(bot, false, item);
    }

    /** A second furnace by the first (in the workshop), if there's room: twice as fast smelting big batches. */
    public static FurnishTask spareFurnace(BotPlayer bot) {
        FurnishTask task = new FurnishTask(bot, false, Items.FURNACE);
        task.spare = true;
        return task;
    }

    @Override
    public Status tick() {
        if (child != null) {
            Status status = child.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            child.stop();
            child = null;
            if (status == Status.FAILURE) {
                return Status.FAILURE;
            }
        }
        BotMemory memory = bot.memory();
        ServerLevel level = Home.levelIfHere(bot);
        if (level == null || memory.bed() == null) {
            return Status.FAILURE;
        }
        if (only != null) {
            if (placedOnly) {
                return Status.SUCCESS;
            }
            Consumer<BlockPos> remember = only == Items.CRAFTING_TABLE ? memory::setCraftingTable
                : only == Items.FURNACE ? (spare ? pos -> { } : memory::setFurnace) : only == Items.ANVIL ? pos -> { } : memory::addChest;
            return furnishWith(only, pos -> {
                remember.accept(pos);
                placedOnly = true;
            });
        }
        if (extraChest) {
            return furnishWith(Items.CHEST, pos -> {
                memory.addChest(pos);
                extraChest = false;
            });
        }
        if (memory.chests().isEmpty()) {
            return furnishWith(Items.CHEST, memory::addChest);
        }
        if (memory.craftingTable() == null) {
            return furnishWith(Items.CRAFTING_TABLE, memory::setCraftingTable);
        }
        if (memory.furnace() == null) {
            return furnishWith(Items.FURNACE, memory::setFurnace);
        }
        BlockPos dark = Home.darkSpot(bot);
        if (dark != null) {
            return light(dark);
        }
        if (memory.campfire() == null) {
            return campfire();
        }
        return Status.SUCCESS;
    }

    /** A campfire outside by the camp, for cooking (it stays there). */
    private Status campfire() {
        if (Inv.count(bot, stack -> stack.is(Items.CAMPFIRE)) == 0) {
            child = new ObtainTask(bot, Target.of(Items.CAMPFIRE, 1), 0);
            return Status.RUNNING;
        }
        ServerLevel level = bot.level();
        if (spot == null || !PlaceSpots.isFreeGroundSpot(level, spot)) {
            spot = CampfireTask.outdoorSpot(level, bot.memory().bed());
            if (spot == null) {
                bot.debug("no spot outside for a campfire");
                return Status.FAILURE;
            }
        }
        if (!bot.canUse(spot)) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.reachVisible(level, spot));
            }
            if (bot.navigator().tick().ended() && ++stuck > 5) {
                return Status.FAILURE;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        if (BlockPlacer.place(bot, spot, stack -> stack.is(Items.CAMPFIRE), Direction.DOWN, null)) {
            bot.memory().setCampfire(spot);
            bot.debug("put up a campfire by the camp at {}", spot.toShortString());
            spot = null;
        } else if (++stuck > 40) {
            return Status.FAILURE;
        }
        return Status.RUNNING;
    }

    /** A torch on a wall near a dark bed or workshop, at eye height. */
    private Status light(BlockPos dark) {
        if (Inv.count(bot, stack -> stack.is(Items.TORCH)) == 0) {
            child = new ObtainTask(bot, Target.of(Items.TORCH, 4), 0);
            return Status.RUNNING;
        }
        ServerLevel level = bot.level();
        if (spot == null || !level.getBlockState(spot).isAir()) {
            spot = torchSpot(level, dark);
            if (spot == null) {
                bot.debug("no wall for a torch near {}", dark.toShortString());
                Home.cannotLight(bot, dark);
                return Status.FAILURE;
            }
        }
        if (!bot.canUse(spot)) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.reachVisible(level, spot));
            }
            if (bot.navigator().tick().ended() && ++stuck > 5) {
                return Status.FAILURE;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        Direction wall = null;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (isWall(level, spot.relative(side))) {
                wall = side;
                break;
            }
        }
        if (wall != null && BlockPlacer.place(bot, spot, stack -> stack.is(Items.TORCH), wall, null)) {
            bot.debug("hung a torch at {}", spot.toShortString());
            spot = null;
            if (++torchesHung >= MAX_TORCHES && level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, dark.above()) < 7) {
                bot.debug("still dark at {} after {} torches; leaving it", dark.toShortString(), torchesHung);
                Home.cannotLight(bot, dark);
                return Status.FAILURE;
            }
        } else if (++stuck > 40) {
            return Status.FAILURE;
        }
        return Status.RUNNING;
    }

    /**
     * Air at eye height next to a wall, as close to the dark spot as possible, and in sight of it:
     * the same room (a torch on the outside of the wall lights nothing in there).
     */
    private static @Nullable BlockPos torchSpot(ServerLevel level, BlockPos dark) {
        BlockPos eye = dark.above();
        net.minecraft.world.phys.Vec3 from = net.minecraft.world.phys.Vec3.atCenterOf(eye);
        java.util.Set<BlockPos> room = room(level, dark); // (a torch outside the wall doesn't light the room)
        return BlockPos.betweenClosedStream(eye.offset(-3, 0, -3), eye.offset(3, 0, 3))
            .map(BlockPos::immutable)
            .filter(pos -> level.getBlockState(pos).isAir() && wallsAround(level, pos) > 0 && room.contains(pos.below()))
            .filter(pos -> pos.equals(eye) || level.clip(new net.minecraft.world.level.ClipContext(from,
                net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()))
                .getType() == net.minecraft.world.phys.HitResult.Type.MISS)
            .min(Comparator.comparingDouble(pos -> pos.distSqr(eye)))
            .orElse(null);
    }

    private Status furnishWith(Item item, Consumer<BlockPos> remember) {
        if (bot.getInventory().countItem(item) == 0) {
            child = new ObtainTask(bot, Target.of(item, 1), 0);
            return Status.RUNNING;
        }
        ServerLevel level = bot.level();
        // (a chest's second tier, on top of one of its chests, is no floor spot: still free if the air is)
        boolean stillFree = spot != null && (PlaceSpots.isFreeGroundSpot(level, spot)
            || item == Items.CHEST && level.getBlockState(spot).isAir() && level.getBlockState(spot.below()).is(Blocks.CHEST));
        if (!stillFree) {
            BlockPos workshop = bot.memory().workshop();
            // (the crafting table and the anvil go in the house, by the bed; the rest to the workshop)
            boolean toWorkshop = workshop != null && item != Items.CRAFTING_TABLE && item != Items.ANVIL;
            spotFacing = null;
            spot = findWorkshopSpot(level, toWorkshop ? workshop : bot.memory().bed(), item);
            if (spot == null && spare && toWorkshop && bot.memory().bed() != null) {
                spot = findWorkshopSpot(level, bot.memory().bed(), item); // (the workshop is full: in the house then)
            }
            if (spot == null && workshop != null && !toWorkshop) {
                spot = findWorkshopSpot(level, workshop, item); // (no room by the bed: in the workshop, or the yard)
            }
            if (spot == null && workshop == null && !bot.memory().houseDone() && bot.memory().bed() != null) {
                // What the village has nearby first (a furnace in the smithy, a table, a chest): its to use
                Home.adoptStorage(bot);
                BotMemory memory = bot.memory();
                if (item == Items.FURNACE && !spare && memory.furnace() != null || item == Items.CRAFTING_TABLE && memory.craftingTable() != null
                    || item == Items.CHEST && !memory.chests().isEmpty()) {
                    return Status.SUCCESS;
                }
            }
            if (spot == null && workshop == null && !bot.memory().houseDone() && bot.memory().bed() != null) {
                // A bed in someone else's house (a village's) with no room in it for its things: a yard of its own
                // out by it, as its workshop, till it builds a house (Obsidian went a day without chest or furnace)
                BlockPos yard = yardSpot(level, bot.memory().bed());
                if (yard != null) {
                    bot.debug("no room in the house; my things go in a yard by it, at {}", yard.toShortString());
                    bot.memory().setWorkshop(yard);
                    spot = findWorkshopSpot(level, yard, item);
                }
            }
            if (spot == null) {
                bot.debug("no room for a {} at home", item);
                return Status.FAILURE;
            }
        }
        if (!bot.canUse(spot)) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.reachVisible(bot.level(), spot));
            }
            if (bot.navigator().tick().ended()) {
                spot = null;
                return ++stuck > 5 ? Status.FAILURE : Status.RUNNING;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        if (new net.minecraft.world.phys.AABB(spot).intersects(bot.getBoundingBox())) {
            // Standing on the very spot: step off it first (to a neighbouring open block)
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos aside = spot.relative(direction, 2);
                if (PlaceSpots.isFreeGroundSpot(level, aside)) {
                    bot.controller().moveTowards(net.minecraft.world.phys.Vec3.atBottomCenterOf(aside), false, false);
                    return ++stuck > 40 ? Status.FAILURE : Status.RUNNING;
                }
            }
            spot = null; // (nowhere to step: another spot)
            return ++stuck > 40 ? Status.FAILURE : Status.RUNNING;
        }
        // (a chest beside a single one, facing the same way, joins it into a double chest; the bot faces the other
        // way, and stands up: put down crouching, a chest stays single)
        if (spotFacing != null) {
            bot.setShiftKeyDown(false);
        }
        if (BlockPlacer.place(bot, spot, stack -> stack.is(item), Direction.DOWN, spotFacing != null ? spotFacing.getOpposite() : null)) {
            remember.accept(spot);
            spot = null;
        } else if (++stuck > 40) {
            return Status.FAILURE;
        }
        return Status.RUNNING;
    }

    /**
     * Open ground near the house (under the sky, out of it), with room round it for a few things:
     * the nearest such spot 3-10 blocks from the bed, about its level; null if there's none.
     */
    private static @Nullable BlockPos yardSpot(ServerLevel level, BlockPos bed) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(bed.offset(-10, -3, -10), bed.offset(10, 3, 10))) {
            double distance = pos.distSqr(bed);
            if (distance < 9 || distance >= bestDistance || !level.canSeeSky(pos) || !PlaceSpots.isFreeGroundSpot(level, pos)
                || nextToDoor(level, pos)) {
                continue;
            }
            int free = 0;
            for (BlockPos near : BlockPos.betweenClosed(pos.offset(-2, 0, -2), pos.offset(2, 0, 2))) {
                if (level.canSeeSky(near) && PlaceSpots.isFreeGroundSpot(level, near)) {
                    free++;
                }
            }
            if (free >= 12) {
                best = pos.immutable();
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * A spot for it: by the bed (the crafting table) or in the workshop if it has one (chests,
     * furnaces). Out of floor space, a chest goes on top of one of its chests, two high at most.
     */
    private @Nullable BlockPos findWorkshopSpot(ServerLevel level, BlockPos workshop, Item item) {
        if (item == Items.CHEST) {
            // A double chest first of all: beside a single one here, or in the house; if need be even
            // in front of a furnace or chest (that's only in the way of walking, not of opening)
            BlockPos bed = bot.memory().bed();
            for (boolean strict : new boolean[] {true, false}) {
                BlockPos pair = pairSpot(level, workshop, strict);
                if (pair == null && bed != null && !bed.equals(workshop)) {
                    pair = pairSpot(level, bed, strict);
                }
                if (pair != null) {
                    return pair;
                }
            }
        }
        BlockPos floor = findSpot(level, workshop, item);
        if (floor != null || item != Items.CHEST) {
            return floor;
        }
        for (BlockPos chest : bot.memory().chests()) {
            BlockPos above = chest.above();
            if (chest.closerThan(workshop, RADIUS + 2) && level.getBlockState(chest).is(Blocks.CHEST)
                && !level.getBlockState(chest.below()).is(Blocks.CHEST) && level.getBlockState(above).isAir()
                && level.getBlockState(above.above()).isAir()) {
                return above;
            }
        }
        return null;
    }

    /**
     * A free floor spot near the bed, along a wall, in a corner if possible. Indoors only when
     * the bed is indoors: never out behind the wall of the house.
     */
    /**
     * Beside one of its single chests (to its left or right, on the same floor): a chest put there
     * facing the same way joins it into a double chest. Sets {@link #spotFacing}.
     */
    private @Nullable BlockPos pairSpot(ServerLevel level, BlockPos anchor, boolean strict) {
        boolean indoors = !level.canSeeSky(anchor.above());
        java.util.Set<BlockPos> room = room(level, anchor);
        for (BlockPos chest : bot.memory().chests()) {
            BlockState state = level.getBlockState(chest);
            if (!chest.closerThan(anchor, RADIUS + 2) || !state.is(Blocks.CHEST)
                || state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                continue;
            }
            Direction facing = state.getValue(net.minecraft.world.level.block.ChestBlock.FACING);
            for (Direction side : new Direction[] {facing.getClockWise(), facing.getCounterClockWise()}) {
                BlockPos pos = chest.relative(side);
                BlockState beyond = level.getBlockState(pos.relative(side));
                if (PlaceSpots.isFreeGroundSpot(level, pos) && (!indoors || !level.canSeeSky(pos)) && room.contains(pos)
                    && !beyond.is(Blocks.CHEST) // (not between two chests: it would join the wrong one)
                    && !nextToBed(level, pos) && !nextToDoor(level, pos)
                    && (!strict || !inFrontOfStorage(level, pos) && !nextToFurniture(level, pos, true))) {
                    spotFacing = facing;
                    return pos;
                }
            }
        }
        return null;
    }

    private @Nullable BlockPos findSpot(ServerLevel level, BlockPos bed, Item item) {
        // Same floor as the bed, never on top of it (a blocked bed can't be slept in)
        boolean indoors = !level.canSeeSky(bed.above());
        List<BlockPos> free = BlockPos.betweenClosedStream(bed.offset(-RADIUS, 0, -RADIUS), bed.offset(RADIUS, 0, RADIUS))
            .map(BlockPos::immutable)
            .filter(pos -> PlaceSpots.isFreeGroundSpot(level, pos))
            .filter(pos -> !level.getBlockState(pos.below()).is(BlockTags.BEDS))
            .filter(pos -> !nextToDoor(level, pos))
            .filter(pos -> !inFrontOfStorage(level, pos))
            .filter(pos -> !nextToBed(level, pos))
            .filter(pos -> !nextToFurniture(level, pos, item == Items.CHEST))
            .filter(pos -> !indoors || !level.canSeeSky(pos))
            .filter(room(level, bed)::contains) // (never out behind a wall, roof or no roof)
            .toList();
        // Along a wall, never out in the middle of the room (unless there's no wall at all: a bed in the open)
        List<BlockPos> byWalls = free.stream().filter(pos -> wallsAround(level, pos) > 0).toList();
        return (byWalls.isEmpty() ? free : byWalls).stream()
            .min(Comparator.comparingInt((BlockPos pos) -> -wallsAround(level, pos))
                .thenComparingDouble(pos -> pos.distSqr(bed)))
            .orElse(null);
    }


    /**
     * The floor cells on the anchor's level it can get to without going through a wall or a door:
     * the room it stands in (a hut without a roof too), or everything around in the open.
     */
    static java.util.Set<BlockPos> room(ServerLevel level, BlockPos anchor) {
        return room(level, anchor, RADIUS + 1);
    }

    /** The same, up to {@code radius} every way; null if it goes that far (no walls round it: not a room). */
    static java.util.@Nullable Set<BlockPos> enclosedRoom(ServerLevel level, BlockPos anchor, int radius) {
        java.util.Set<BlockPos> room = room(level, anchor, radius + 1);
        for (BlockPos pos : room) {
            if (Math.abs(pos.getX() - anchor.getX()) > radius || Math.abs(pos.getZ() - anchor.getZ()) > radius) {
                return null;
            }
        }
        return room;
    }

    private static java.util.Set<BlockPos> room(ServerLevel level, BlockPos anchor, int radius) {
        java.util.Set<BlockPos> room = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        queue.add(anchor);
        room.add(anchor);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(direction);
                if (Math.abs(next.getX() - anchor.getX()) > radius || Math.abs(next.getZ() - anchor.getZ()) > radius
                    || room.contains(next) || isWall(level, next) || isWall(level, next.above())
                    || level.getBlockState(next).is(BlockTags.DOORS)) {
                    continue;
                }
                room.add(next);
                queue.add(next);
            }
        }
        return room;
    }

    private static int wallsAround(ServerLevel level, BlockPos pos) {
        int walls = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (isWall(level, pos.relative(direction))) {
                walls++;
            }
        }
        return walls;
    }

    /** A real wall: a solid block, not a piece of furniture (a bed, chest, table, furnace...). */
    static boolean isWall(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isCollisionShapeFullBlock(level, pos) && !state.hasBlockEntity() && !state.is(Blocks.CRAFTING_TABLE)
            && !state.is(BlockTags.BEDS);
    }

    /** Next to a door, or two steps straight in or out of it: the way through must stay free. */
    /**
     * Right beside a chest, furnace, smoker, crafting table, anvil or barrel: furniture stands a
     * step apart, so each can be got at from a side (not only over the top of another).
     */
    private static boolean nextToFurniture(ServerLevel level, BlockPos pos, boolean chest) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState state = level.getBlockState(pos.relative(direction));
            if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock && !chest
                || state.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                || state.is(Blocks.CRAFTING_TABLE) || state.is(BlockTags.ANVIL) || state.is(Blocks.BARREL)) {
                return true;
            }
        }
        return false;
    }

    /** Right beside the bed: things stand a step away from it, not crammed against it. */
    private static boolean nextToBed(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(direction)).is(BlockTags.BEDS)) {
                return true;
            }
        }
        return false;
    }

    /** Right in front of a chest or furnace: it would be in the way of getting to it. */
    private static boolean inFrontOfStorage(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState state = level.getBlockState(pos.relative(direction));
            if ((state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                    || state.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock)
                && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                    == direction.getOpposite()) {
                return true;
            }
        }
        return false;
    }

    private static boolean nextToDoor(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(direction)).is(BlockTags.DOORS)) {
                return true;
            }
            BlockState twoAway = level.getBlockState(pos.relative(direction, 2));
            if (twoAway.is(BlockTags.DOORS) && twoAway.hasProperty(DoorBlock.FACING)
                && twoAway.getValue(DoorBlock.FACING).getAxis() == direction.getAxis()) {
                return true;
            }
        }
        return level.getBlockState(pos).is(BlockTags.DOORS);
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        if (child != null) {
            child.stop();
        }
    }

    @Override
    public String describe() {
        String what = "furnishing the house";
        return child != null ? what + ": " + child.describe() : what;
    }
}
