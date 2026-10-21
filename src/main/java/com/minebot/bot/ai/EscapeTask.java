package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Trapped underground (a flooded cave, a pit it can't find a way out of): gets
 * back to the surface. In water it swims up, breaks through a rock ceiling, and
 * at the top climbs onto the nearest ledge, cutting one into the rock or
 * putting a block into the water to stand on. On dry ground it digs and
 * pillars its way up, digging next to water too if it has to.
 */
public class EscapeTask extends Task {
    private static final int MAX_TICKS = 20 * 180;
    /** Deeper than this below the surface counts as underground. */
    private static final int DEPTH = 4;

    private final BlockBreaker breaker;
    private int ticks;
    private int failures;
    private int climbTicks;

    public EscapeTask(BotPlayer bot) {
        super(bot);
        this.breaker = new BlockBreaker(bot);
    }

    /** Underground and in trouble: running out of air, or no way found to wherever it was going. */
    /** Until when (game time) a bot found trapped stays on the way out: starting it cleared what showed it trapped (Quartz and Obsidian: "escape" started and dropped every six seconds, for an hour). */
    private static final java.util.Map<java.util.UUID, Long> TRAPPED = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean wanted(BotPlayer bot) {
        long now = bot.level().getGameTime();
        if (rawWanted(bot)) {
            TRAPPED.put(bot.getUUID(), now + 20 * 90);
            return true;
        }
        return TRAPPED.getOrDefault(bot.getUUID(), 0L) > now;
    }

    private static boolean rawWanted(BotPlayer bot) {
        // (time in the area, not "still": idling counts as resting, never still - Redstone idled two hours at the bottom of his mine)
        if (underground(bot) && bot.areaSeconds() >= 300 && !bot.navigator().isActive() && !GreatBuildTask.isAway(bot)) {
            return true; // (down a pit with nothing to do, nothing tried: Copper, an hour at the bottom of one)
        }
        if (!underground(bot)) {
            // (shut in a room above ground: nowhere to go, its way out walled up - Glowstone, in its own house)
            // (or standing there a minute with nothing to do: shut in by the others' building at the Great Build, it
            // had stopped trying - idle, no failing paths to count - and sat in its pit for good - Tuff)
            if (bot.areaSeconds() >= 60 && bot.recentNavFailures(20 * 60) >= 3 && boxedIn(bot)) { // (in the area: hopping there it is never "still")
                return true; // (see boxedIn)
            }
            if (stranded(bot)) {
                return true; // (see stranded)
            }
            return bot.stillSeconds() >= 20 && (bot.recentNavFailures(20 * 60) >= 4 || bot.stillSeconds() >= 60)
                && bot.every("sealed in", 100, () -> enclosure(bot) != null);
        }
        if (bot.areaSeconds() >= 60 && bot.recentNavFailures(20 * 60) >= 3 && boxedIn(bot)) { // (in the area: hopping there it is never "still")
            return true; // (see boxedIn)
        }
        boolean drowning = bot.isUnderWater() && bot.getAirSupply() < bot.getMaxAirSupply() / 2;
        // (paths failing now and then is normal down a mine: stuck means going nowhere for a minute too)
        // (or on its way somewhere far, yet still in the same few blocks after minutes: Bedrock swam to and fro in a
        // flooded cave for 20 hours, its ways never "failing", just never getting it out)
        BlockPos feet = bot.navigator().feet();
        var goal = bot.navigator().goal();
        boolean goingNowhere = bot.areaSeconds() >= 180 && goal != null && goal.distance(feet.getX(), feet.getY(), feet.getZ()) > 16;
        return drowning || bot.recentNavFailures(20 * 60) >= 3 && bot.stillSeconds() >= 60 || goingNowhere;
    }

    /**
     * Standing on a block in a hole with no step to take: room beside it at its feet, but a ceiling over its head there,
     * and nothing a block up to climb to (Obsidian, on a pillar top under the floor of the Great Build, its head in a
     * gap: a block more under it, or one less, and it walks off).
     */
    private static boolean boxedIn(BotPlayer bot) {
        ServerLevel level = bot.level();
        // (where it stands, mid-jump too: it hops there trying to pillar up, and the check, made in the air, never held)
        BlockPos feet = bot.blockPosition();
        for (int k = 0; k < 2 && isOpen(level, feet.below()); k++) {
            feet = feet.below();
        }
        if (isOpen(level, feet.below()) || bot.onClimbable() || bot.isInWater()) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            if (isOpen(level, side) && isOpen(level, side.above())) {
                return false; // (a step along, or off and down)
            }
            if (!isOpen(level, side) && isOpen(level, side.above()) && isOpen(level, side.above(2)) && isOpen(level, feet.above(2))) {
                return false; // (a step up)
            }
        }
        return true;
    }

    /** Out of the hole: the block under it broken (down a level, where there's head room), or one more put under it. */
    private Status unbox() {
        ServerLevel level = bot.level();
        if (bot.onGround() && !boxedIn(bot)) {
            bot.debug("out of the hole");
            bot.clearNavFailures();
            return Status.SUCCESS;
        }
        if (++ticks > MAX_TICKS) {
            return Status.FAILURE;
        }
        if (breaker.target() != null && breaker.tick() == BlockBreaker.Result.RUNNING) {
            return Status.RUNNING;
        }
        if (bot.onGround() || pillarBase == null) {
            pillarBase = bot.blockPosition();
        }
        BlockPos feet = pillarBase;
        BlockPos below = feet.below();
        // Down: the floor broken, if there's ground under it (no long fall) and no water or lava
        boolean landing = false;
        for (int k = 2; k <= 4 && !landing; k++) {
            BlockPos at = feet.below(k);
            if (!level.getFluidState(at).isEmpty()) {
                break;
            }
            landing = !isOpen(level, at);
        }
        // (open sky over its head: up and out is the way - down into the hollow under the Great Build's floor, Obsidian
        // only climbed back onto the same block, the hole over it its one way out)
        boolean skyOver = isOpen(level, feet.above(2)) && level.canSeeSky(feet.above(2));
        if (!down && !skyOver && landing && mustBreak(level, below) && level.getFluidState(below).isEmpty()) {
            bot.debug("in a hole with no step to take; breaking the block under me at {}", below.toShortString());
            bot.controller().releaseInputs();
            bot.controller().lookAt(Vec3.atCenterOf(below));
            breaker.start(below);
            down = true;
            return Status.RUNNING;
        }
        // Up: a block put under it as it jumps (room over its head first)
        BlockPos overHead = feet.above(2);
        if (!isOpen(level, overHead)) {
            if (!mustBreak(level, overHead)) {
                return Status.FAILURE;
            }
            bot.controller().lookAt(Vec3.atCenterOf(overHead));
            breaker.start(overHead);
            return Status.RUNNING;
        }
        if (Inv.count(bot, Inv::isScaffold) == 0) {
            // Nothing to put under it: a block of the hole's side broken first, for one (it falls by its feet)
            // (head-high first; then at its feet - that opens a way out too, Glowstone had air all round its head, a step too
            // high for it, and gave up; then the block over a step)
            java.util.List<BlockPos> sides = new java.util.ArrayList<>();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                sides.add(feet.above().relative(direction));
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                sides.add(feet.relative(direction));
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                sides.add(feet.above(2).relative(direction));
            }
            for (BlockPos side : sides) {
                if (!isOpen(level, side) && mustBreak(level, side)) {
                    bot.controller().lookAt(Vec3.atCenterOf(side));
                    breaker.start(side);
                    return Status.RUNNING;
                }
            }
            return ++failures > 40 ? Status.FAILURE : Status.RUNNING;
        }
        bot.controller().hold(Vec3.atBottomCenterOf(feet));
        bot.setXRot(90.0F);
        bot.setJumping(true);
        if (bot.getY() > feet.getY() + 1.0 && level.getBlockState(feet).canBeReplaced()) {
            com.minebot.bot.action.BlockPlacer.place(bot, feet, Inv::isScaffold);
        }
        return Status.RUNNING;
    }

    private boolean boxed;

    /**
     * Out in the open but going nowhere: no ways found for minutes, in the same few blocks (on a ledge of old scaffolding six
     * blocks up, a jump too far for the ways - Sunny and Obsidian at the Great Build after a session).
     */
    private static boolean stranded(BotPlayer bot) {
        return !underground(bot) && bot.recentNavFailures(20 * 60) >= 4 && bot.areaSeconds() >= 120 && !bot.isSleeping();
    }

    private boolean strandedMode;
    private @org.jetbrains.annotations.Nullable BlockPos strandedFrom;

    /** A way home, or anywhere off the spot, with the bolder ways of getting out (a longer jump down, digging) */
    private Status unstrand() {
        if (++ticks > 20 * 90) {
            return Status.FAILURE;
        }
        if (strandedFrom == null) {
            strandedFrom = bot.blockPosition();
            var home = bot.memory().home();
            BlockPos to = home != null && home.dimension() == bot.level().dimension() ? home.pos() : bot.memory().anchor().pos();
            bot.debug("stranded here; finding a way off, a longer jump down if need be");
            // (to its very door when it's near home already: "within 16 of home" was there at once, and it sat on in a pit two
            // blocks deep thirteen blocks from its house - Quartz)
            double radius = Math.sqrt(bot.blockPosition().distToLowCornerSqr(to.getX(), bot.getBlockY(), to.getZ())) > 24 ? 16 : 2;
            bot.navigator().navigateEscape(Goal.column(to.getX(), to.getZ(), radius));
        }
        if (!bot.blockPosition().closerThan(strandedFrom, 8)) {
            bot.navigator().stop();
            bot.clearNavFailures();
            return Status.SUCCESS;
        }
        var status = bot.navigator().tick();
        if (status.ended() && status != com.minebot.bot.path.Navigator.Status.SUCCESS) {
            return Status.FAILURE;
        }
        return Status.RUNNING;
    }
    /** Tried the way down already (the block under it broken): up from here on. */
    private boolean down;

    public static boolean underground(BotPlayer bot) {
        ServerLevel level = bot.level();
        BlockPos feet = bot.blockPosition();
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        if (level.canSeeSky(feet.above()) || feet.getY() >= top - DEPTH) {
            return false;
        }
        // Rock or earth over its head, not a roof: in a tall house (a roof well over it) it was "underground", and sent out of
        // its bedroom to the surface every few seconds (Deepslate)
        for (int y = feet.getY() + 2; y < top; y++) {
            if (com.minebot.bot.world.BlockRules.isNaturalTerrain(level.getBlockState(new BlockPos(feet.getX(), y, feet.getZ())))
                && !level.getBlockState(new BlockPos(feet.getX(), y, feet.getZ())).canBeReplaced()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The cells it can reach from where it stands, if they are shut in on every side (no door, no open sky, a small
     * space); null if there is a way out - or no telling.
     */
    private static @org.jetbrains.annotations.Nullable java.util.Set<BlockPos> enclosure(BotPlayer bot) {
        return enclosure(bot, true);
    }

    /** @param doorsOut a door or gate counts as a way out (not when the ways have shown it can't get to it) */
    private static @org.jetbrains.annotations.Nullable java.util.Set<BlockPos> enclosure(BotPlayer bot, boolean doorsOut) {
        ServerLevel level = bot.level();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        BlockPos start = bot.blockPosition();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            if (seen.size() > 600) {
                return null;
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = at.relative(direction);
                if (seen.contains(next) || next.getY() > start.getY() + 2) {
                    continue; // (an open roof is no way out of walls three high: it can't jump higher than that)
                }
                if (!level.isLoaded(next)) {
                    return null;
                }
                BlockState state = level.getBlockState(next);
                if (doorsOut && BlockRules.isOpenable(state, next)) {
                    return null; // (a door or gate: a way out, whatever the paths say)
                }
                if (state.getCollisionShape(level, next).isEmpty()) {
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    private boolean sealed;
    private java.util.List<BlockPos> sealRoute;

    /** Through the nearest wall to the outside: the cells of the way, feet level and head level, thinnest first. */
    private void planSealRoute(java.util.Set<BlockPos> inside) {
        ServerLevel level = bot.level();
        BlockPos feet = bot.blockPosition();
        java.util.List<BlockPos> best = null;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            java.util.List<BlockPos> route = new java.util.ArrayList<>();
            boolean out = false;
            for (int k = 1; k <= 8 && !out; k++) {
                BlockPos low = feet.relative(direction, k);
                BlockPos high = low.above();
                if (!level.isLoaded(low)) {
                    break;
                }
                route.add(low);
                route.add(high);
                out = !inside.contains(low) && isOpen(level, low) && isOpen(level, high) && !isOpen(level, low.below())
                    || !inside.contains(low) && isOpen(level, low) && isOpen(level, high) && k > 1 && route.stream().anyMatch(p -> !isOpen(level, p));
            }
            if (out && route.stream().allMatch(p -> isOpen(level, p) || canBreak(level, p) || level.getBlockState(p).getDestroySpeed(level, p) >= 0 && !level.getBlockState(p).hasBlockEntity() && !ProtectedAreas.isProtected(level, p))
                && (best == null || route.size() < best.size())) {
                best = route;
            }
        }
        sealRoute = best;
    }

    /** Breaks out through the wall along the planned way (walls too: it is its own house it is shut in), then walks out. */
    private Status breakOut() {
        ServerLevel level = bot.level();
        BlockPos lastOpen = null;
        for (int k = 0; k < sealRoute.size(); k++) {
            BlockPos pos = sealRoute.get(k);
            if (k % 2 == 0 && isOpen(level, pos) && isOpen(level, sealRoute.get(k + 1))) {
                lastOpen = pos; // (feet and head both clear: a cell along the way it can stand in)
            }
            if (!isOpen(level, pos)) {
                if (!bot.isWithinBlockInteractionRange(pos, 0.0) && lastOpen != null) {
                    // (a wall two thick: the next block out of reach from where it stands - along the way broken so far first;
                    // it stood breaking at nothing, Zola)
                    breaker.cancel();
                    bot.controller().moveTowards(Vec3.atBottomCenterOf(lastOpen), false, false);
                    return Status.RUNNING;
                }
                bot.controller().lookAt(Vec3.atCenterOf(pos));
                breaker.start(pos);
                return Status.RUNNING;
            }
        }
        if (bot.getY() - bot.getBlockY() > 0.1 && bot.blockPosition().closerThan(sealRoute.get(0), 2.0)) {
            // Standing up on something (a chest): its head is a block higher than the way's - the lintel over the hole too
            // (Zola, up on her chest, pushed at the plank over the hole she had broken)
            BlockPos top = sealRoute.get(0).above(2);
            BlockState state = level.getBlockState(top);
            if (!isOpen(level, top) && !state.hasBlockEntity() && state.getDestroySpeed(level, top) >= 0 && !ProtectedAreas.isProtected(level, top)) {
                bot.controller().lookAt(Vec3.atCenterOf(top));
                breaker.start(top);
                return Status.RUNNING;
            }
        }
        BlockPos end = sealRoute.get(sealRoute.size() - 2);
        bot.controller().moveTowards(Vec3.atBottomCenterOf(end), false, false);
        double dx = end.getX() + 0.5 - bot.getX();
        double dz = end.getZ() + 0.5 - bot.getZ();
        if (dx * dx + dz * dz < 0.5) {
            bot.debug("out of the walls by breaking through at {}", end.toShortString());
            bot.clearNavFailures();
            return Status.SUCCESS;
        }
        return Status.RUNNING;
    }

    @Override
    public Status tick() {
        Status status = run();
        if (status == Status.SUCCESS) {
            TRAPPED.remove(bot.getUUID()); // (out: no more "trapped" for the rest of the 90 s - it started and ended again and again)
        }
        return status;
    }

    private Status run() {
        if (ticks == 0 && !boxed && !sealed && boxedIn(bot)) {
            boxed = true;
        }
        if (boxed) {
            return unbox();
        }
        if (ticks == 0 && !strandedMode && !sealed && !underground(bot) && bot.navigator().boxedIn(30)) {
            // (its ways find a handful of cells and no more, a door beyond a crafting table it can't climb past under the lintel:
            // "a door, so a way out" - and Zola stood in her back room for hours, missing the Great Build. Shut in, then)
            java.util.Set<BlockPos> inside = enclosure(bot, false);
            if (inside != null) {
                planSealRoute(inside);
                sealed = sealRoute != null;
                bot.debug("boxed in with {} cells to move about, a door out of reach; {}", inside.size(), sealed ? "breaking out" : "no way to break out");
            }
        }
        if (ticks == 0 && !strandedMode && !sealed && stranded(bot) && enclosure(bot) == null) {
            strandedMode = true;
        }
        if (strandedMode) {
            return unstrand();
        }
        if (!sealed && !underground(bot)) {
            if (ticks == 0) {
                java.util.Set<BlockPos> inside = enclosure(bot);
                if (inside != null) {
                    planSealRoute(inside);
                    sealed = sealRoute != null;
                    bot.debug("shut in by walls with {} cells to move about; {}", inside.size(), sealed ? "breaking out" : "no way to break out");
                }
            }
        }
        if (sealed) {
            if (++ticks > MAX_TICKS) {
                return Status.FAILURE;
            }
            if (breaker.target() != null && breaker.tick() == BlockBreaker.Result.RUNNING) {
                return Status.RUNNING;
            }
            return breakOut();
        }
        if (!underground(bot)) {
            bot.debug("out of the cave");
            bot.clearNavFailures();
            return Status.SUCCESS;
        }
        if (++ticks > MAX_TICKS) {
            return Status.FAILURE;
        }
        if (breaker.target() != null) {
            // (at the surface it keeps afloat while breaking; under water it stays on the bottom, where digging is quicker)
            bot.setJumping(bot.isInWater() && !bot.isUnderWater());
            if (breaker.tick() == BlockBreaker.Result.RUNNING) {
                return Status.RUNNING;
            }
        }
        if (bot.isInWater()) {
            bot.navigator().stop();
            return swim();
        }
        if (digOut) {
            return digOut();
        }
        if (!bot.navigator().isActive()) {
            bot.navigator().navigateEscape(Goal.surface(bot.level()));
        }
        if (start == null) {
            start = bot.blockPosition();
        }
        boolean ended = bot.navigator().tick().ended();
        if (underground(bot) && (ended && ++failures > 3 || ticks > 20 * 40 && start != null && bot.blockPosition().closerThan(start, 4))) {
            // No way out at all (Makena sat two hours in a pocket of rock under her own house; Steve got only part-ways
            // to the surface each time and stood there, his way never quite "failing"): it digs itself out
            bot.debug("no way out of here; digging my way up");
            bot.navigator().stop();
            digOut = true;
            failures = 0;
        }
        return Status.RUNNING;
    }

    private boolean digOut;
    /** Ticks left of a way along before going up again (when up was barred). */
    private int tunnelTicks;
    private @org.jetbrains.annotations.Nullable BlockPos start;
    private @org.jetbrains.annotations.Nullable BlockPos pillarBase;
    private @org.jetbrains.annotations.Nullable Direction away;

    /**
     * Digging out by hand, as a player would: under its own house (or near it) first a tunnel away from
     * it, not up through its floor; then straight up - the block over its head broken, a block put under
     * its feet as it jumps - till the sky is over it.
     */
    private Status digOut() {
        ServerLevel level = bot.level();
        BlockPos feet = bot.blockPosition();
        BlockPos home = bot.memory().home() != null && bot.memory().home().dimension() == level.dimension() ? bot.memory().home().pos() : null;
        boolean nearHome = home != null && Math.abs(feet.getX() - home.getX()) <= 10 && Math.abs(feet.getZ() - home.getZ()) <= 10;
        if (tunnelTicks > 0) {
            tunnelTicks--;
        }
        if (nearHome || tunnelTicks > 0) {
            if (away == null) {
                int dx = feet.getX() - home.getX();
                int dz = feet.getZ() - home.getZ();
                away = dx == 0 && dz == 0 ? Direction.NORTH
                    : Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
            }
            BlockPos ahead = feet.relative(away);
            for (BlockPos pos : new BlockPos[] {ahead.above(), ahead}) {
                if (!isOpen(level, pos)) {
                    if (!mustBreak(level, pos) && !(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock)) {
                        away = away.getClockWise(); // (something it mustn't break: another way)
                        return ++failures > 12 ? Status.FAILURE : Status.RUNNING;
                    }
                    bot.controller().lookAt(Vec3.atCenterOf(pos));
                    breaker.start(pos);
                    return Status.RUNNING;
                }
            }
            if (isOpen(level, ahead.below())) {
                Inv.select(bot, Inv::isScaffold); // (a floor to walk on over a gap)
            }
            bot.controller().moveTowards(Vec3.atBottomCenterOf(ahead), false, false);
            return Status.RUNNING;
        }
        if (bot.onGround() || pillarBase == null) {
            pillarBase = feet; // (where it stands: mid-jump its feet are a block up, and the block would never go in)
        }
        BlockPos base = pillarBase;
        BlockPos overHead = base.above(2);
        if (!isOpen(level, overHead)) {
            if (!mustBreak(level, overHead)) {
                // (water or lava over it, something it mustn't break: a way along for a while first, then up from there - it gave up at once)
                away = away == null ? Direction.Plane.HORIZONTAL.getRandomDirection(bot.getRandom()) : away.getClockWise();
                tunnelTicks = 20 * 10;
                return ++failures > 8 ? Status.FAILURE : Status.RUNNING;
            }
            bot.controller().lookAt(Vec3.atCenterOf(overHead));
            breaker.start(overHead);
            return Status.RUNNING;
        }
        // Pillar up: jump, and a block under its feet at the top of the jump
        if (Inv.count(bot, Inv::isScaffold) == 0) {
            // Nothing to stand on: a block of the wall beside it first, for one (Obsidian, in a cave pocket with an empty bag, gave up
            // in a second)
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (BlockPos side : new BlockPos[] {base.relative(direction), base.above().relative(direction), base.above(2).relative(direction),
                        base.above(3), base.below()}) { // (in an open cave: the roof over it, or the floor)
                    if (!isOpen(level, side) && mustBreak(level, side)) {
                        bot.controller().lookAt(Vec3.atCenterOf(side));
                        breaker.start(side);
                        return Status.RUNNING;
                    }
                }
            }
        }
        bot.controller().hold(Vec3.atBottomCenterOf(base));
        bot.setXRot(90.0F);
        bot.setJumping(true);
        if (bot.getY() > base.getY() + 1.0 && level.getBlockState(base).canBeReplaced()) {
            if (!com.minebot.bot.action.BlockPlacer.place(bot, base, Inv::isScaffold) && Inv.count(bot, Inv::isScaffold) == 0) {
                return Status.FAILURE; // (nothing to stand on: not likely, it has dug enough)
            }
        }
        return Status.RUNNING;
    }

    /** In the water: up to the top, then out onto a ledge. */
    private Status swim() {
        ServerLevel level = bot.level();
        BlockPos feet = bot.blockPosition();
        BlockPos eyes = BlockPos.containing(bot.getEyePosition());
        if (isWater(level, eyes)) {
            // Under water: the rock ceiling above gets broken, standing on the bottom if it's within
            // reach (digging while afloat is five times slower); the hole fills with air to breathe
            BlockPos ceiling = eyes.above();
            while (isWater(level, ceiling) && ceiling.getY() - eyes.getY() < 6) {
                ceiling = ceiling.above();
            }
            bot.controller().releaseInputs();
            if (!isWater(level, ceiling) && !isOpen(level, ceiling) && bot.isWithinBlockInteractionRange(ceiling, 0.0)) {
                bot.setJumping(false);
                return breakOrGiveUp(ceiling);
            }
            bot.setJumping(true);
            return Status.RUNNING;
        }
        // Head above the water: climb onto a neighbouring block. A step beside it needs a floor
        // (rock, or a block put into the water) and room above (cut out of the rock if need be)
        boolean haveBlocks = Inv.count(bot, Inv::isScaffold) > 0;
        Direction best = null;
        int bestWork = Integer.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            BlockState floor = level.getBlockState(side);
            boolean solid = !floor.getCollisionShape(level, side).isEmpty() && floor.getFluidState().isEmpty();
            boolean water = isWater(level, side);
            if (!solid && !(water && haveBlocks)) {
                continue;
            }
            int work = water ? 1 : 0;
            boolean possible = true;
            for (BlockPos room : new BlockPos[] {side.above(), side.above(2)}) {
                if (!isOpen(level, room)) {
                    work += 2;
                    possible &= canBreak(level, room);
                }
            }
            if (possible && work < bestWork) {
                best = direction;
                bestWork = work;
            }
        }
        if (best != null) {
            BlockPos side = feet.relative(best);
            if (!isOpen(level, side.above())) {
                return breakOrGiveUp(side.above());
            }
            if (!isOpen(level, side.above(2))) {
                return breakOrGiveUp(side.above(2));
            }
            if (isWater(level, side)) {
                // A block into the water beside it, to climb onto
                BlockPlacer.place(bot, side, Inv::isScaffold);
                bot.setJumping(true);
                return Status.RUNNING;
            }
            if (!isOpen(level, feet.above(2))) {
                // (the hop up onto the step needs headroom over the water too)
                return breakOrGiveUp(feet.above(2));
            }
            bot.controller().moveTowards(Vec3.atBottomCenterOf(side.above()), false, true);
            // Floating a little low, the bot may not quite clear the edge (a player mashes jump):
            // pressed against the free step for over a second, it steps up onto it
            double dx = side.getX() + 0.5 - bot.getX();
            double dz = side.getZ() + 0.5 - bot.getZ();
            if (bot.horizontalCollision && dx * dx + dz * dz < 1.7 && ++climbTicks > 20) {
                climbTicks = 0;
                bot.teleportTo(side.getX() + 0.5, side.getY() + 1, side.getZ() + 0.5);
            }
            return Status.RUNNING;
        }
        // Nothing to climb onto here: up through the ceiling, and look again from there
        BlockPos above = feet.above(2);
        if (!isOpen(level, above)) {
            return breakOrGiveUp(above);
        }
        bot.setJumping(true);
        return Status.RUNNING;
    }

    private Status breakOrGiveUp(BlockPos pos) {
        if (!canBreak(bot.level(), pos)) {
            return ++failures > 20 ? Status.FAILURE : Status.RUNNING;
        }
        bot.controller().lookAt(Vec3.atCenterOf(pos));
        breaker.start(pos);
        return Status.RUNNING;
    }

    private static boolean isWater(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER);
    }

    private static boolean isOpen(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /** Rock it may break to get out (not bedrock, nothing built, nothing protected). */
    /** Shut in for good: a wall someone built (stone bricks of the Great Build, say) goes too - they build it again after. */
    private static boolean mustBreak(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return canBreak(level, pos) || !state.isAir() && state.getFluidState().isEmpty() && !state.hasBlockEntity()
            && state.getDestroySpeed(level, pos) >= 0 && (!ProtectedAreas.isProtected(level, pos)
                // (shut in inside a no-go area - Obsidian, under the village: the rock round it may go, to get out; never by a player's bed)
                || !ProtectedAreas.nearRespawn(level, pos) && com.minebot.bot.world.BlockRules.isNaturalTerrain(state));
    }

    private static boolean canBreak(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty() && !state.hasBlockEntity()
            && state.getDestroySpeed(level, pos) >= 0 && !BlockRules.isBuilt(state) && !ProtectedAreas.isProtected(level, pos);
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        breaker.cancel();
        bot.controller().releaseInputs();
    }

    @Override
    public String describe() {
        return "getting out of the cave";
    }
}
