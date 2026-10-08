package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.world.BlockSearch;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps its buildings (the house, the hut it keeps as a workshop) clear of trees: a tree
 * standing within {@link #CLEARANCE} blocks of the walls is felled whole, and leaves in that
 * space are taken off (a tree that grew up by the door once shut a bot in). Only natural trees:
 * logs with leaves of their own, leaves that would decay; never logs that are part of a building.
 */
public class ClearTreesTask extends Task {
    /** How far from its walls trees aren't let grow. */
    public static final int CLEARANCE = 4;
    private static final int CHECK_INTERVAL = 20 * 60 * 5;
    private static final int TICKS_PER_BLOCK = 20 * 15;
    private static final int MAX_TICKS = 20 * 60 * 4;
    /** How far round its bed and workshop the walls are looked for. */
    private static final int BUILDING_RADIUS = 10;

    /** Blocks each bot couldn't get at, and until when (game time) they are left alone. */
    private static final Map<UUID, Map<BlockPos, Long>> GIVEN_UP = new HashMap<>();
    private static final int GIVE_UP_TICKS = 20 * 60 * 30;

    /** Forgotten when the bot leaves the server: back again, it may try those once more. */
    public static void forget(UUID bot) {
        GIVEN_UP.remove(bot);
    }

    private final List<BlockPos> blocks;
    private final BlockBreaker breaker;
    private @Nullable Task collect;
    private @Nullable BlockPos working;
    private int workingTicks;
    /** Got there but couldn't see it (a wall between): where it can be seen from, next. */
    private boolean lookingFor;
    private int arrivals;
    private int ticks;
    private int broken;

    public ClearTreesTask(BotPlayer bot) {
        super(bot);
        this.blocks = new ArrayList<>(find(bot));
        this.breaker = new BlockBreaker(bot);
        if (!blocks.isEmpty()) {
            bot.debug("clearing {} blocks of trees by its buildings, from {}", blocks.size(), blocks.get(0).toShortString());
        }
    }

    public static boolean wanted(BotPlayer bot) {
        return Home.isNear(bot, 48) && !Home.isNight(bot)
            && bot.every("clear trees", CHECK_INTERVAL, () -> !find(bot).isEmpty());
    }

    /** Its buildings: the space taken by the built blocks round its bed, house and workshop. */
    private static List<BoundingBox> buildings(BotPlayer bot) {
        ServerLevel level = bot.level();
        BotMemory memory = bot.memory();
        List<BoundingBox> boxes = new ArrayList<>();
        if (memory.houseOrigin() != null && memory.houseDone()) {
            // The house: its plan, roof and all
            var plan = com.minebot.bot.build.HousePlans.of(memory);
            BlockPos o = plan.origin();
            boxes.add(new BoundingBox(o.getX(), o.getY(), o.getZ(),
                o.getX() + plan.sizeX() - 1, plan.topY() + 1, o.getZ() + plan.sizeZ() - 1));
        }
        Set<BlockPos> anchors = new LinkedHashSet<>();
        if (memory.bed() != null) {
            anchors.add(memory.bed());
        }
        if (memory.workshop() != null) {
            anchors.add(memory.workshop());
        }
        if (memory.home() != null && memory.home().dimension() == level.dimension()) {
            anchors.add(memory.home().pos());
        }
        for (BlockPos anchor : anchors) {
            if (!level.isLoaded(anchor) || boxes.stream().anyMatch(box -> box.isInside(anchor))) {
                continue;
            }
            List<BlockPos> built = BlockSearch.find(level, anchor, BUILDING_RADIUS, anchor.getY() - 4, anchor.getY() + 10,
                state -> BlockRules.isBuilt(state) || state.is(BlockTags.DOORS), (pos, state) -> true, 2000);
            if (built.size() < 8) {
                continue; // (a bed out in the open, a chest or two: no building to keep clear)
            }
            boxes.add(BoundingBox.encapsulatingPositions(built).orElseThrow());
        }
        return boxes;
    }

    /** What to take down: whole trees standing near the walls (logs, top first), then leaves near them. */
    private static List<BlockPos> find(BotPlayer bot) {
        ServerLevel level = bot.level();
        if (Home.levelIfHere(bot) == null) {
            return List.of();
        }
        Map<BlockPos, Long> givenUp = GIVEN_UP.getOrDefault(bot.getUUID(), Map.of());
        long now = level.getGameTime();
        Set<BlockPos> logs = new LinkedHashSet<>();
        Set<BlockPos> leaves = new LinkedHashSet<>();
        List<BoundingBox> buildings = buildings(bot);
        for (BoundingBox building : buildings) {
            BoundingBox zone = building.inflatedBy(CLEARANCE);
            for (BlockPos at : BlockPos.betweenClosed(zone.minX(), zone.minY(), zone.minZ(), zone.maxX(), zone.maxY(), zone.maxZ())) {
                if (!level.isLoaded(at)) {
                    continue;
                }
                BlockState state = level.getBlockState(at);
                BlockPos pos = at.immutable();
                if (givenUp.getOrDefault(pos, 0L) > now || ProtectedAreas.isProtected(level, pos)) {
                    continue;
                }
                if (isNaturalLeaves(state)) {
                    leaves.add(pos);
                } else if (state.is(BlockTags.LOGS) && !inside(buildings, pos) && !logs.contains(pos)) {
                    List<BlockPos> tree = tree(level, pos, buildings);
                    if (!tree.isEmpty()) {
                        logs.addAll(tree);
                    }
                }
            }
        }
        List<BlockPos> order = new ArrayList<>(logs);
        // Logs bottom up, the way a tree is felled (standing by the trunk, then on a pillar), then the leaves
        order.sort(Comparator.comparingInt(BlockPos::getY));
        List<BlockPos> leafOrder = new ArrayList<>(leaves);
        leafOrder.sort(Comparator.comparingInt(BlockPos::getY));
        order.addAll(leafOrder);

        return order;
    }

    private static boolean isNaturalLeaves(BlockState state) {
        return state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
    }

    private static boolean inside(List<BoundingBox> buildings, BlockPos pos) {
        for (BoundingBox building : buildings) {
            if (building.isInside(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The tree this log belongs to (its logs), or none if it isn't a tree: a tree has leaves of
     * its own and stands on the ground (or hangs where its trunk was cut). Never into a building
     * (a log wall it touches).
     */
    private static List<BlockPos> tree(ServerLevel level, BlockPos log, List<BoundingBox> buildings) {
        List<BlockPos> logs = new ArrayList<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>(List.of(log));
        Set<BlockPos> seen = new HashSet<>(Set.of(log));
        boolean leafy = false;
        BlockPos lowest = log;
        while (!open.isEmpty() && logs.size() < 64) {
            BlockPos at = open.poll();
            logs.add(at);
            if (at.getY() < lowest.getY()) {
                lowest = at;
            }
            for (BlockPos next : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
                BlockState state = level.getBlockState(next);
                if (!leafy && isNaturalLeaves(state)) {
                    leafy = true;
                }
                BlockPos pos = next.immutable();
                if (state.is(BlockTags.LOGS) && Math.abs(pos.getX() - log.getX()) <= 5 && Math.abs(pos.getZ() - log.getZ()) <= 5
                    && !inside(buildings, pos) && seen.add(pos)) {
                    open.add(pos);
                }
            }
        }
        // On the ground, or what is left hanging of one whose trunk was cut
        BlockState under = level.getBlockState(lowest.below());
        boolean rooted = under.is(BlockTags.DIRT) || under.canBeReplaced();
        return leafy && rooted ? logs : List.of();
    }

    @Override
    public Status tick() {
        if (collect != null) {
            Status status = collect.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            collect.stop();
            collect = null;
        }
        if (breaker.target() != null && breaker.tick() == BlockBreaker.Result.RUNNING) {
            return Status.RUNNING;
        }
        ServerLevel level = bot.level();
        while (!blocks.isEmpty() && !isTarget(level.getBlockState(blocks.get(0)))) {
            BlockPos gone = blocks.remove(0);
            if (gone.equals(working)) {
                broken++;
                working = null;
                if (broken % 8 == 0 || blocks.isEmpty()) {
                    // What fell off: logs, saplings (planted away from the house), apples
                    collect = new CollectItemsTask(bot, Vec3.atCenterOf(gone), 6.0, 100);
                    return Status.RUNNING;
                }
            }
        }
        if (blocks.isEmpty()) {
            if (broken == 0) {
                bot.every("clear trees", 0, () -> false); // (nothing after all: looked for again later)
            } else {
                bot.debug("cleared {} blocks of trees by its buildings", broken);
            }
            return broken > 0 ? Status.SUCCESS : Status.FAILURE;
        }
        if (++ticks > MAX_TICKS) {
            return broken > 0 ? Status.SUCCESS : Status.FAILURE;
        }
        BlockPos next = blocks.get(0);
        if (!next.equals(working)) {
            working = next;
            workingTicks = 0;
            lookingFor = false;
            arrivals = 0;
        }
        if (++workingTicks > TICKS_PER_BLOCK) {
            bot.debug("can't get at the {} at {}; leaving it", level.getBlockState(next).getBlock().getName().getString(),
                next.toShortString());
            GIVEN_UP.computeIfAbsent(bot.getUUID(), id -> new HashMap<>()).put(next, level.getGameTime() + GIVE_UP_TICKS);
            breaker.cancel();
            bot.navigator().stop();
            blocks.remove(0);
            working = null;
            return Status.RUNNING;
        }
        // In reach (and not halfway up a pillar): leaves or more of the tree in the way go first,
        // the way it fells any tree
        if (!bot.navigator().isActive()) {
            BlockPos blocker = bot.isWithinBlockInteractionRange(next, 0.0) ? firstInSight(next) : null;
            if (blocker != null) {
                breaker.start(blocker);
                return Status.RUNNING;
            }
            // Out of reach, or a wall in the way (it's in the house): over to where it can be seen
            bot.navigator().navigate(lookingFor ? Goal.reachVisible(level, next) : Goal.reach(next));
        }
        com.minebot.bot.path.Navigator.Status status = bot.navigator().tick();
        if (status == com.minebot.bot.path.Navigator.Status.FAILED || status.ended() && ++arrivals > 3) {
            workingTicks = TICKS_PER_BLOCK; // (no way to it, or never in sight: given up on next time round)
        } else if (status == com.minebot.bot.path.Navigator.Status.SUCCESS) {
            lookingFor = true; // there, but not in sight: somewhere it can be seen from
        }
        return Status.RUNNING;
    }

    /** The block it would hit going for this one: itself, or tree in the way; null if something else is. */
    private @Nullable BlockPos firstInSight(BlockPos target) {
        ServerLevel level = bot.level();
        BlockHitResult hit = level.clip(new ClipContext(bot.getEyePosition(), Vec3.atCenterOf(target),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target)) {
            return target;
        }
        BlockPos blocker = hit.getBlockPos();
        return isNaturalLeaves(level.getBlockState(blocker)) || blocks.contains(blocker) ? blocker : null;
    }

    private static boolean isTarget(BlockState state) {
        return state.is(BlockTags.LOGS) || isNaturalLeaves(state);
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
        return "clearing trees away from its buildings";
    }
}
