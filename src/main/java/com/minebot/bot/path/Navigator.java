package com.minebot.bot.path;

import com.minebot.bot.BotPlayer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.world.BlockRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Follows paths from {@link PathFinder}: walks, jumps, swims and climbs,
 * mines blocks in the way, opens doors, bridges gaps and pillars up.
 * Replans whenever the bot gets knocked off the path or the world changed.
 */
public class Navigator {
    private static final int NODES_PER_TICK = 1500;
    private static final int MAX_NODES = 30_000;
    /** All bots' searches together, per tick (server thread only). */
    private static final int NODES_ALL_BOTS = 5000;
    private static final int MIN_NODES_PER_TICK = 300;
    private static int budgetTick = -1;
    private static int searchersThisTick;
    private static int searchersLastTick = 1;
    private static final int STEP_TIMEOUT = 80;
    private static final int MAX_FAILED_REPLANS = 6;
    private static final int MAX_STALLED_PARTIALS = 3;
    /** Ways walked to the end without the goal reached, before calling it done (or not). */
    private static final int MAX_FALSE_ARRIVALS = 3;
    /** A goal it found no way to is given up at once if asked for again within this long (ticks). */
    private static final int FAILED_GOAL_TICKS = 20 * 60;

    public enum Status {
        IDLE, SEARCHING, MOVING, SUCCESS, FAILED;

        /** The trip is over: arrived, or no way there. */
        public boolean ended() {
            return this == SUCCESS || this == FAILED;
        }
    }

    private final BotPlayer bot;
    private final BlockBreaker breaker;

    private @Nullable Goal goal;
    private @Nullable PathFinder search;
    /** Sprint when at least this many plain steps lie ahead. */
    private static final int SPRINT_STEPS = 5;
    private @Nullable Path path;
    /** In a boat: which path step it rows to, where the water ends, progress. */
    private @Nullable net.minecraft.world.entity.vehicle.boat.AbstractBoat boat;
    private int boatIndex;
    private int boatEnd;
    private double boatBest;
    private int boatStill;
    /** When it may next try to get into a boat (ticks): every few seconds while swimming with one. */
    private int boatRetryAt;
    private boolean wasInWater;
    /** Getting out of a cave: digging next to water allowed. */
    private boolean escape;
    /** Take a boat for this many steps of water in a row or more. */
    private static final int BOAT_WATER = 12;
    private static final double BOAT_SPEED = 0.35;
    private int index;
    private int stepTicks;
    private int failedReplans;
    private int maxNodes = MAX_NODES;
    /**
     * Goals there was no way to, and until when (game time): searched again and again, each
     * search up to 30 000 nodes, they made the server stutter. Not kept for escapes.
     */
    private final java.util.Map<String, Long> failedGoals = new java.util.HashMap<>();
    private int stalledPartials;
    private int falseArrivals;
    private double lastPartialDistance;
    private double bestGoalDistance;
    private int airTicks;
    /** Steps that keep failing, by target position; after a few tries the spot is avoided. */
    private final Long2IntOpenHashMap stepFailures = new Long2IntOpenHashMap();
    private final LongOpenHashSet avoid = new LongOpenHashSet();
    /** Where a ladder wouldn't go up (twice): planned some other way (a pillar, steps) from then on. */
    private final LongOpenHashSet noLadder = new LongOpenHashSet();
    private @Nullable String lastRefused;
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap ladderFailures = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    /** While set, every block placed to pillar up is noted here (to take the pillar down later). */
    private @Nullable List<BlockPos> pillarLog;
    private Status status = Status.IDLE;

    private @Nullable BlockPos zoneCenter;
    private int zoneRadius;

    public Navigator(BotPlayer bot) {
        this.bot = bot;
        this.breaker = new BlockBreaker(bot);
    }

    public void setZone(@Nullable BlockPos center, int radius) {
        this.zoneCenter = center;
        this.zoneRadius = radius;
    }

    public void navigate(Goal goal) {
        navigate(goal, MAX_NODES);
    }

    /** With a smaller search: for something a few steps away, no point combing 30 000 blocks for it. */
    public void navigate(Goal goal, int maxNodes) {
        this.maxNodes = maxNodes;
        stop(); // (out on the water it stays in the boat; it gets out where the way ahead is land)
        escape = false;
        stepFailures.clear();
        avoid.clear();
        this.goal = goal;
        this.failedReplans = 0;
        this.stalledPartials = 0;
        this.falseArrivals = 0;
        this.lastPartialDistance = Double.MAX_VALUE;
        this.bestGoalDistance = Double.MAX_VALUE;
        this.status = Status.SEARCHING;
    }

    /**
     * Drops the current path but keeps the goal (e.g. combat or eating took
     * over); the next tick plans a fresh path from wherever the bot is then.
     */
    public void interrupt() {
        search = null;
        path = null;
        breaker.cancel();
        bot.setShiftKeyDown(false);
        bot.controller().releaseInputs();
        if (goal != null) {
            status = Status.SEARCHING;
        }
    }

    /** Notes the blocks pillared up from now on, until {@link #stopPillarLog}. */
    /** Like navigate, but trapped underground: may dig next to water to get out. */
    public void navigateEscape(Goal goal) {
        navigate(goal);
        escape = true;
    }

    public void startPillarLog() {
        if (pillarLog == null) {
            pillarLog = new ArrayList<>();
        }
    }

    /**
     * A block put down to stand on (a pillar, a bridge), to be taken down again after: by the
     * task that logs them, or else by the "dismantle" need. Bridges far from home stay (a way
     * back over a gap on a journey), and so does whatever it built getting out of a cave.
     */
    private void noteScaffold(BlockPos pos, boolean bridge) {
        if (pillarLog != null) {
            pillarLog.add(pos.immutable());
        } else if (!escape && (!bridge || com.minebot.bot.ai.Home.isNear(bot, 48)) && !com.minebot.bot.build.GreatBuild.nearSite(pos)) {
            // (at the Great Build the ground is reshaped by plan: nothing to take down there)
            bot.notePillar(pos.immutable());
        }
    }

    /** The blocks pillared up since {@link #startPillarLog}, bottom first. */
    public List<BlockPos> stopPillarLog() {
        List<BlockPos> placed = pillarLog != null ? pillarLog : List.of();
        pillarLog = null;
        return placed;
    }

    public void stop() {
        bot.setShiftKeyDown(false);
        if (!onWaterInBoat()) {
            leaveBoat(); // (out on the water it stays in the boat: the next way decides where to get out)
        }
        goal = null;
        search = null;
        path = null;
        breaker.cancel();
        bot.controller().releaseInputs();
        status = Status.IDLE;
    }

    public Status status() {
        return status;
    }

    public @Nullable Goal goal() {
        return goal;
    }

    public boolean isActive() {
        return status == Status.SEARCHING || status == Status.MOVING;
    }

    /** Feet block, robust to standing on slabs, paths, farmland. */
    public BlockPos feet() {
        BlockPos feet = BlockPos.containing(bot.getX(), bot.getY() + 0.2, bot.getZ());
        // Standing on a slab, path block, farmland...: the feet are inside that block's
        // space, but for paths the bot is on top of it, like on any other floor
        BlockState state = bot.level().getBlockState(feet);
        if (bot.onGround() && !BlockRules.isPassable(bot.level(), feet, state) && BlockRules.isStandable(bot.level(), feet, state)) {
            return feet.above();
        }
        return feet;
    }

    public Status tick() {
        if (goal == null) {
            return status;
        }
        if (goal.isReached(feet())) {
            finish(Status.SUCCESS);
            return status;
        }
        if (search == null && path == null) {
            long now = bot.level().getGameTime();
            Long until = escape ? null : failedGoals.get(goal.toString());
            if (until != null && now < until) {
                if (!goal.toString().equals(lastRefused)) {
                    lastRefused = goal.toString(); // (said once: a task asking every tick would fill the log)
                    bot.debug("no way to {} a moment ago; not searching again yet", goal);
                }
                finish(Status.FAILED);
                return status;
            }
            startSearch();
        }
        if (search != null) {
            bot.controller().releaseInputs();
            search.step(nodesThisTick());
            if (!search.isFinished()) {
                return status;
            }
            path = search.result();
            bot.debug("path {} nodes, {} steps, complete={}, goal {}", search.expanded(), path == null ? 0 : path.size(),
                path != null && path.complete(), goal);
            search = null;
            index = 0;
            if (boat == null && bot.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat riding) {
                // Still sitting in the boat from the last leg: rowed on (or out of it at the shore), not walked
                // (a bot in a boat can't walk: it sat there, the step timing out, path after path)
                boat = riding;
            }
            if (boat != null) {
                restartBoatRun();
            }
            stepTicks = 0;
            if (path == null || path.size() <= 1) {
                path = null;
                onPathFailed();
                return status;
            }
            status = Status.MOVING;
        }
        follow();
        return status;
    }

    /**
     * This search's share of the nodes for this tick: all the bots' searches together get about
     * {@link #NODES_ALL_BOTS} (a dozen bots searching at once made the server lag), each the same.
     */
    private int nodesThisTick() {
        int tick = bot.level().getServer().getTickCount();
        if (tick != budgetTick) {
            searchersLastTick = Math.max(1, searchersThisTick);
            searchersThisTick = 0;
            budgetTick = tick;
        }
        searchersThisTick++;
        return Math.max(MIN_NODES_PER_TICK, Math.min(NODES_PER_TICK, NODES_ALL_BOTS / searchersLastTick));
    }

    private void startSearch() {
        // Don't plan mid-fall: the start position would be in the air
        if (!bot.onGround() && !bot.isInWater() && !bot.onClimbable() && airTicks++ < 40) {
            bot.controller().releaseInputs();
            return;
        }
        airTicks = 0;
        boolean canPlace = Inv.count(bot, Inv::isScaffold) > 0;
        // Outside its zone (teleported away...) the bot must be able to walk back in
        // (on the way to the Great Build and back, it may go further than its zone)
        BlockPos zone = zoneCenter != null && insideZone(feet()) && !com.minebot.bot.ai.GreatBuildTask.isAway(bot) ? zoneCenter : null;
        boolean boat = Inv.count(bot, stack -> stack.is(net.minecraft.tags.ItemTags.BOATS)) > 0
            || bot.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat; // (in one: plan for it too)
        PathFinder.Options options = new PathFinder.Options(true, canPlace, maxNodes, zone, zoneRadius, avoid, boat, escape,
            Inv.count(bot, stack -> stack.is(net.minecraft.world.item.Items.LADDER)) > 0, noLadder);
        search = new PathFinder(bot, feet(), goal, options);
        status = Status.SEARCHING;
    }

    private boolean insideZone(BlockPos pos) {
        long dx = pos.getX() - zoneCenter.getX();
        long dz = pos.getZ() - zoneCenter.getZ();
        return dx * dx + dz * dz <= (long) zoneRadius * zoneRadius;
    }

    private void replan(String reason) {
        bot.debug("replan: {}", reason);
        breaker.cancel();
        path = null;
        search = null;
        if (++failedReplans > MAX_FAILED_REPLANS) {
            finish(Status.FAILED);
        }
    }

    private void onPathFailed() {
        if (++failedReplans > MAX_FAILED_REPLANS) {
            finish(Status.FAILED);
        }
    }

    private void finish(Status result) {
        bot.debug("navigation {}", result);
        if (result == Status.FAILED) {
            bot.noteNavFailure();
            if (goal != null && !escape) {
                long now = bot.level().getGameTime();
                failedGoals.values().removeIf(until -> until <= now);
                failedGoals.putIfAbsent(goal.toString(), now + FAILED_GOAL_TICKS);
            }
        } else if (result == Status.SUCCESS && goal != null) {
            failedGoals.remove(goal.toString());
        }
        if (!(result == Status.SUCCESS && onWaterInBoat())) {
            leaveBoat(); // (got there out on the water: it stays in the boat for the next leg)
        }
        bot.setShiftKeyDown(false);
        breaker.cancel();
        bot.controller().releaseInputs();
        search = null;
        path = null;
        goal = null;
        status = result;
    }

    private void follow() {
        Path current = path;
        if (current == null) {
            return;
        }
        if (boat != null) {
            driveBoat(current);
            return;
        }
        if (index >= current.size() - 1) {
            reachedPathEnd(current);
            return;
        }
        Path.Step from = current.get(index);
        Path.Step next = current.get(index + 1);

        if (arrived(next)) {
            index++;
            stepTicks = 0;
            // Only real progress towards the goal earns back the replans (not bobbing in place)
            double distance = goal == null ? 0 : goal.distance(next.pos().getX(), next.pos().getY(), next.pos().getZ());
            if (distance < bestGoalDistance - 1.0) {
                bestGoalDistance = distance;
                failedReplans = 0;
            }
            breaker.cancel();
            return;
        }
        BlockPos feet = feet();
        boolean horizontalMove = from.pos().getX() != next.pos().getX() || from.pos().getZ() != next.pos().getZ();
        // (only once centred: at the very edge the bot may still be held up by the neighbouring block)
        if (horizontalMove && stepTicks > 10 && horizontalDistance(Vec3.atBottomCenterOf(next.pos())) < 0.3 && bot.onGround()
            && Math.abs(bot.getY() - next.pos().getY()) >= 0.6 && breaker.target() == null) {
            // Right column, wrong height: sand fell in, a block appeared... plan from here
            replan("height mismatch at " + next.pos().toShortString());
            return;
        }
        if (feet.distSqr(from.pos()) > 6.25 && feet.distSqr(next.pos()) > 6.25) {
            replan("off path"); // knocked off by combat, water current, explosion...
            return;
        }
        if (breaker.target() == null && ++stepTicks > STEP_TIMEOUT) {
            long key = next.pos().asLong();
            if (stepFailures.addTo(key, 1) >= 2) {
                avoid.add(key); // third time this step failed: plan around it
            }
            replan("step " + next.move() + " to " + next.pos().toShortString() + " timed out");
            return;
        }
        // Crouch on the way to and across a bridge so the bot never walks off the edge
        boolean bridging = next.move() == Move.BRIDGE
            || index + 2 < current.size() && current.get(index + 2).move() == Move.BRIDGE;
        bot.setShiftKeyDown(bridging);
        // Just come down into the water (off a ledge too high to put the boat in from): into the boat at once
        boolean inWater = bot.isInWater();
        if (inWater && !wasInWater) {
            boatRetryAt = Math.min(boatRetryAt, bot.tickCount);
        }
        wasInWater = inWater;
        if (bot.tickCount >= boatRetryAt && waterAhead(current, index + 1) >= BOAT_WATER) {
            boatRetryAt = bot.tickCount + 100; // (from the shore, or straight from the water if that didn't work)
            if (board(current)) {
                return;
            }
        }
        execute(from.pos(), next);
    }

    private void reachedPathEnd(Path current) {
        if (goal != null && !current.complete()) {
            // Partial path (long trip or unreachable goal): plan the next leg
            double distance = goal.distance(feet().getX(), feet().getY(), feet().getZ());
            if (distance >= lastPartialDistance - 1.0 && ++stalledPartials >= MAX_STALLED_PARTIALS) {
                finish(Status.FAILED);
                return;
            }
            lastPartialDistance = Math.min(lastPartialDistance, distance);
        }
        BlockPos feet = feet();
        if (goal != null && current.complete() && !goal.isReached(feet) && ++falseArrivals >= MAX_FALSE_ARRIVALS) {
            // The way was done (a boat stopping a little short out at sea, say) yet the goal isn't, time
            // after time: close by the end of the way counts as there, else no way there
            BlockPos end = current.get(current.size() - 1).pos();
            boolean close = Math.abs(end.getX() - feet.getX()) <= 2 && Math.abs(end.getZ() - feet.getZ()) <= 2;
            bot.debug("came to the end of the way {} times without getting to {}; {}", falseArrivals, goal,
                close ? "near enough" : "giving up");
            finish(close ? Status.SUCCESS : Status.FAILED);
            return;
        }
        path = null;
    }

    private boolean arrived(Path.Step step) {
        BlockPos pos = step.pos();
        if (bot.getBlockX() != pos.getX() || bot.getBlockZ() != pos.getZ()) {
            return false;
        }
        double dy = bot.getY() - pos.getY();
        return switch (step.move()) {
            // (a slab is a step up of only half a block)
            case PILLAR, ASCEND -> dy > -0.55 && dy < 0.6 && bot.onGround();
            case SWIM_UP, SWIM_DOWN, CLIMB_UP, CLIMB_DOWN, LADDER -> Math.abs(dy) < 0.8;
            default -> dy > -0.6 && dy < 0.6;
        };
    }

    private void execute(BlockPos from, Path.Step step) {
        BlockPos to = step.pos();
        Vec3 center = Vec3.atBottomCenterOf(to);
        switch (step.move()) {
            case WALK, DIAGONAL, BRIDGE -> {
                if (clear(List.of(to, to.above()))) {
                    return;
                }
                if (step.move() == Move.BRIDGE && !BlockRules.isStandable(bot.level(), to.below(), bot.level().getBlockState(to.below()))) {
                    // Sneaking keeps the bot from walking off the edge; edge closer until the block fits
                    if (!BlockPlacer.place(bot, to.below(), Inv::isScaffold)) {
                        if (Inv.count(bot, Inv::isScaffold) == 0) {
                            replan("out of blocks to bridge with");
                        } else {
                            move(center, false);
                        }
                    } else {
                        noteScaffold(to.below(), true);
                    }
                    return;
                }
                move(center, false);
            }
            case ASCEND -> {
                if (clear(List.of(from.above(2), to, to.above()))) {
                    return;
                }
                boolean jump = (bot.onGround() || bot.onClimbable()) && horizontalDistance(center) < 1.4; // (off a ladder onto a roof too)
                move(center, jump);
            }
            case DESCEND -> {
                BlockPos entry = new BlockPos(to.getX(), from.getY(), to.getZ());
                if (clear(List.of(entry, entry.above()))) {
                    return;
                }
                move(center, false);
            }
            case PILLAR -> {
                if (clear(List.of(from.above(2)))) {
                    return;
                }
                bot.controller().hold(Vec3.atBottomCenterOf(from));
                bot.setXRot(90.0F);
                bot.setJumping(true);
                if (bot.getY() > from.getY() + 0.9 && bot.level().getBlockState(from).canBeReplaced()) {
                    if (!BlockPlacer.place(bot, from, Inv::isScaffold)) {
                        replan("could not pillar");
                    } else {
                        noteScaffold(from, false);
                    }
                }
            }
            case LADDER -> {
                // A ladder on the building's wall where it stands and one block up, then climb
                net.minecraft.core.Direction wall = BlockRules.ladderWall(bot.level(), from);
                if (wall == null) {
                    replan("no wall for a ladder at " + from.toShortString());
                    return;
                }
                for (BlockPos rung : List.of(from, to)) {
                    if (!bot.level().getBlockState(rung).is(net.minecraft.world.level.block.Blocks.LADDER)) {
                        bot.controller().hold(Vec3.atBottomCenterOf(from));
                        if (horizontalDistance(Vec3.atBottomCenterOf(from)) > 0.15 && stepTicks++ < 20) {
                            return; // (in the middle first: pressed to the wall, the ladder won't fit beside it)
                        }
                        if (!BlockPlacer.place(bot, rung, stack -> stack.is(net.minecraft.world.item.Items.LADDER), wall)) {
                            if (ladderFailures.addTo(rung.asLong(), 1) >= 1) {
                                noLadder.add(rung.asLong()); // (second time: some other way up from here)
                                noLadder.add(from.asLong());
                            }
                            replan(Inv.count(bot, stack -> stack.is(net.minecraft.world.item.Items.LADDER)) == 0
                                ? "out of ladders" : "could not put up a ladder");
                        } else {
                            if (!com.minebot.bot.build.GreatBuild.nearSite(rung)) {
                                bot.notePillar(rung.immutable()); // (taken down again once it's done with it, see DismantleTask)
                            }
                        }
                        return;
                    }
                }
                bot.controller().hold(center);
                bot.setJumping(true);
            }
            case DIG_DOWN -> {
                bot.controller().hold(Vec3.atBottomCenterOf(from));
                clear(List.of(to));
            }
            case STAIR_DOWN -> {
                BlockPos entry = new BlockPos(to.getX(), from.getY(), to.getZ());
                if (clear(List.of(entry.above(), entry, to))) {
                    bot.controller().hold(Vec3.atBottomCenterOf(from));
                    return;
                }
                move(center, false);
            }
            case SWIM_UP, CLIMB_UP -> {
                bot.controller().hold(center);
                bot.setJumping(true);
            }
            case SWIM_DOWN, CLIMB_DOWN -> {
                bot.controller().hold(center);
                bot.setJumping(false);
            }
            case START -> {
            }
        }
    }

    private void move(Vec3 destination, boolean jump) {
        // Keep the head above water while swimming along
        boolean swim = bot.isInWater() && destination.y >= Math.floor(bot.getY());
        bot.controller().moveTowards(destination, shouldSprint(), jump || swim);
    }

    // ---- boats ---------------------------------------------------------------------------------

    /** Steps of water in a row from {@code from} on. */
    private int waterAhead(Path current, int from) {
        int run = 0;
        for (int i = from; i < current.size(); i++) {
            if (!bot.level().getFluidState(current.get(i).pos()).is(net.minecraft.tags.FluidTags.WATER)) {
                break;
            }
            run++;
        }
        return run;
    }

    /**
     * A long stretch of water ahead: into a boat (a free one right here, or its own
     * put on the water). False if there's none; then it swims.
     */
    private boolean board(Path current) {
        net.minecraft.server.level.ServerLevel level = bot.level();
        BlockPos water = current.get(index + 1).pos();
        // Put down a couple of blocks out: right at the bank the boat would bump into the shore and not go down
        BlockPos launch = current.get(Math.min(index + 3, current.size() - 1)).pos();
        net.minecraft.world.entity.vehicle.boat.AbstractBoat found = freeBoatNear(Vec3.atCenterOf(water), 6);
        if (found == null && Inv.select(bot, stack -> stack.is(net.minecraft.tags.ItemTags.BOATS))) {
            found = launchBoat(launch);
        }
        if (found == null || !bot.startRiding(found)) {
            bot.debug("no boat to cross the water at {}; swimming", water.toShortString());
            return false;
        }
        boat = found;
        restartBoatRun();
        bot.debug("crossing {} blocks of water by boat", boatEnd - boatIndex);
        return true;
    }

    /**
     * Puts its own boat (the one in hand) on open water close by, where it fits (not up against
     * the bank or under something), the nearer the way ahead the better. Null if there's no room.
     */
    private @Nullable net.minecraft.world.entity.vehicle.boat.AbstractBoat launchBoat(BlockPos ahead) {
        net.minecraft.server.level.ServerLevel level = bot.level();
        net.minecraft.world.item.ItemStack stack = bot.getMainHandItem();
        var type = net.minecraft.world.entity.EntityType.byString(
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).orElse(null);
        if (type == null) {
            return null;
        }
        BlockPos feet = bot.blockPosition();
        java.util.List<BlockPos> spots = new java.util.ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-3, -4, -3), feet.offset(3, 4, 3))) { // (down off a ledge as far as it reaches, up to the surface if under water)
            if (level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER) && level.getFluidState(pos).isSource()
                && level.getBlockState(pos.above()).isAir()) {
                spots.add(pos.immutable());
            }
        }
        spots.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(ahead)));
        for (BlockPos pos : spots) {
            if (!(type.create(level, net.minecraft.world.entity.EntitySpawnReason.SPAWN_ITEM_USE) instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat boat)) {
                return null;
            }
            boat.setPos(pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5);
            boat.setYRot(bot.getYRot());
            if (level.noCollision(boat, boat.getBoundingBox()) && !boat.getBoundingBox().intersects(bot.getBoundingBox())) {
                level.addFreshEntity(boat);
                stack.shrink(1);
                return boat;
            }
        }
        return null;
    }

    private @Nullable net.minecraft.world.entity.vehicle.boat.AbstractBoat freeBoatNear(Vec3 center, double radius) {
        return bot.level().getEntitiesOfClass(net.minecraft.world.entity.vehicle.boat.AbstractBoat.class,
                new net.minecraft.world.phys.AABB(center, center).inflate(radius),
                b -> b.isAlive() && b.getPassengers().isEmpty() && !(b instanceof net.minecraft.world.entity.vehicle.boat.AbstractChestBoat))
            .stream()
            .min(java.util.Comparator.comparingDouble(b -> b.distanceToSqr(center)))
            .orElse(null);
    }

    /** In the boat with a (new) path: row along its water steps. */
    private void restartBoatRun() {
        Path current = path;
        boatIndex = index + 1;
        boatEnd = current == null ? boatIndex : boatIndex + waterAhead(current, boatIndex);
        boatBest = Double.MAX_VALUE;
        boatStill = 0;
    }

    private void driveBoat(Path current) {
        net.minecraft.world.entity.vehicle.boat.AbstractBoat riding = boat;
        if (riding == null || !riding.isAlive() || bot.getVehicle() != riding) {
            boat = null;
            replan("lost the boat");
            return;
        }
        if (boatIndex >= current.size()) {
            // The way ends out on the water: arrived (and still in the boat)
            index = current.size() - 1;
            reachedPathEnd(current);
            return;
        }
        if (boatIndex >= boatEnd) {
            leaveBoat(); // across: the next step is the shore
            replan("left the boat");
            return;
        }
        Vec3 target = Vec3.atBottomCenterOf(current.get(boatIndex).pos());
        double dx = target.x - riding.getX();
        double dz = target.z - riding.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.2) {
            boatIndex++;
            boatBest = Double.MAX_VALUE;
            return;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        riding.setYRot(yaw);
        bot.setYRot(yaw);
        double speed = Math.min(BOAT_SPEED, distance);
        riding.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(dx / distance * speed, 0, dz / distance * speed));
        if (riding.horizontalCollision) {
            // Caught on a corner or the bank: slide off it, turning a little either way (nearest the course first)
            for (double turn : new double[] {30, -30, 60, -60, 90, -90}) {
                double angle = Math.toRadians(turn);
                double sx = (dx * Math.cos(angle) - dz * Math.sin(angle)) / distance * speed;
                double sz = (dx * Math.sin(angle) + dz * Math.cos(angle)) / distance * speed;
                if (bot.level().noCollision(riding, riding.getBoundingBox().move(sx, 0, sz))) {
                    riding.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(sx, 0, sz));
                    break;
                }
            }
        }
        // Stuck on something (a rock, the shore earlier than planned): get out and walk
        if (distance < boatBest - 0.05) {
            boatBest = distance;
            boatStill = 0;
        } else if (++boatStill > 60) {
            bot.debug("the boat is stuck; getting out");
            leaveBoat();
            replan("boat stuck");
        }
    }

    /** Sitting in its boat out on the water. */
    private boolean onWaterInBoat() {
        return boat != null && boat.isAlive() && bot.getVehicle() == boat && boat.isInWater();
    }

    /** Out of the boat, which it takes along. */
    private void leaveBoat() {
        if (boat != null) {
            if (bot.getVehicle() == boat) {
                bot.stopRiding();
            }
            // The boat comes along (broken and picked up, as a player does), for the next stretch of water;
            // with no room in the bag it stays by the shore (picked up later), not dropped and lost
            if (boat.isAlive() && boat.getPassengers().isEmpty() && Inv.freeSlots(bot) > 0
                && !(boat instanceof net.minecraft.world.entity.vehicle.boat.AbstractChestBoat)) {
                net.minecraft.world.item.ItemStack item = boat.getPickResult();
                boat.discard();
                if (item != null && !item.isEmpty()) {
                    Inv.give(bot, item);
                }
            }
            boat = null;
        }
    }

    /**
     * Run along open stretches, like a player: a few plain steps ahead, on land,
     * not hungry (sprinting costs food and needs more than 6), not sneaking.
     */
    private boolean shouldSprint() {
        Path current = path;
        if (current == null || bot.isInWater() || bot.isShiftKeyDown() || bot.getFoodData().getFoodLevel() <= 6
            || index + SPRINT_STEPS >= current.size()) {
            return false;
        }
        // Never near lava or fire: a sprinting bot overshoots corners
        for (BlockPos near : BlockPos.betweenClosed(feet().offset(-3, -2, -3), feet().offset(3, 1, 3))) {
            BlockState state = bot.level().getBlockState(near);
            if (BlockRules.isLava(state) || state.is(net.minecraft.tags.BlockTags.FIRE)) {
                return false;
            }
        }
        for (int i = index + 1; i <= index + SPRINT_STEPS; i++) {
            Move move = current.get(i).move();
            if (move != Move.WALK && move != Move.DIAGONAL && move != Move.ASCEND && move != Move.DESCEND) {
                return false;
            }
        }
        return true;
    }

    /**
     * Makes sure the bot can pass the given blocks: opens doors, mines what's
     * in the way. @return true while busy (don't move this tick)
     */
    private boolean clear(List<BlockPos> positions) {
        ServerLevel level = bot.level();
        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            if (BlockRules.isPassable(level, pos, state)) {
                continue;
            }
            bot.controller().releaseInputs();
            if (BlockRules.isOpenable(state)) {
                if (!isOpen(state)) {
                    openDoor(pos);
                    return true;
                }
                continue;
            }
            if (!BlockRules.canBreak(level, pos, state)) {
                replan("cannot break " + state.getBlock() + " at " + pos.toShortString());
                return true;
            }
            breaker.start(pos);
            BlockBreaker.Result result = breaker.tick();
            if (result == BlockBreaker.Result.FAILED) {
                replan("mining " + pos.toShortString() + " failed");
            } else if (result == BlockBreaker.Result.SUCCESS) {
                stepTicks = 0;
            }
            return true;
        }
        return false;
    }

    private static boolean isOpen(BlockState state) {
        if (state.hasProperty(DoorBlock.OPEN)) {
            return state.getValue(DoorBlock.OPEN);
        }
        return state.hasProperty(FenceGateBlock.OPEN) && state.getValue(FenceGateBlock.OPEN);
    }

    private void openDoor(BlockPos pos) {
        Vec3 hit = Vec3.atCenterOf(pos);
        bot.controller().lookAt(hit);
        bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND,
            new BlockHitResult(hit, Direction.UP, pos, false));
        bot.swing(InteractionHand.MAIN_HAND);
    }

    private double horizontalDistance(Vec3 point) {
        double dx = point.x - bot.getX();
        double dz = point.z - bot.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
