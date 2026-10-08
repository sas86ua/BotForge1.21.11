package com.minebot.bot.path;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.world.ProtectedAreas;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A* over block positions. Costs are in ticks, so the search prefers what is
 * actually quickest (walking around a hill vs. digging through it).
 * The search is incremental: {@link #step} expands a limited number of nodes
 * per call so a long search is spread over several ticks. It only looks at
 * chunks that are already loaded and never loads new ones.
 */
public class PathFinder {
    /**
     * @param avoid positions not to step on (moves that kept failing there)
     */
    /** @param boat the bot carries a boat: open water is quick to cross */
    public record Options(boolean canBreak, boolean canPlace, int maxNodes, @Nullable BlockPos zoneCenter, int zoneRadius,
                          LongSet avoid, boolean boat, boolean escape, boolean ladders, LongSet noLadder) {
    }

    private static final double INF = Double.POSITIVE_INFINITY;
    private static final double WALK = 4.6;
    private static final double DIAGONAL = 6.5;
    private static final double JUMP = 2.5;
    private static final double FALL_PER_BLOCK = 1.0;
    private static final double SWIM = 9.0;
    /** Around its own home, no digging below ground (blocks, horizontally). */
    private static final long HOME_NO_DIG = 8;
    /** Extra cost of a step right beside lava or fire. */
    private static final double HAZARD_PENALTY = 60;
    /** Lava or fire, by block state (asked for the blocks round every spot looked at). */
    private static final java.util.Map<BlockState, Boolean> HAZARD = new java.util.concurrent.ConcurrentHashMap<>();
    private static final double CLIMB = 6.0;
    /** Putting up a ladder: under a pillar, but well over climbing one that's there (a walk round to it is cheaper). */
    private static final double LADDER_COST = 16.0;
    private static final double PLACE = 14.0;
    private static final double DOOR = 4.0;
    // Digging wears tools out: walk around unless the detour is long
    private static final double BREAK_PENALTY = 25.0;
    private static final double FALLING_BLOCK_PENALTY = 30.0;
    /**
     * The top layers of the ground (blocks down from the surface): breaking into them costs extra,
     * so it goes underground where there is a way in already (a cave, a stairway dug before)
     * rather than digging a new hole wherever it stands; and never straight down there.
     */
    private static final int SURFACE_DEPTH = 4;
    private static final double SURFACE_PENALTY = 40.0;
    /** >1 makes the search greedier: much faster, paths slightly longer. */
    private static final double HEURISTIC = WALK * 1.4;
    private static final int MAX_SAFE_FALL = 3;
    private static final int MAX_WATER_FALL = 24;

    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final BotPlayer bot;
    private final ServerLevel level;
    private final Goal goal;
    private final Options options;
    private final List<BlockPos> noDigging;

    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final PriorityQueue<Entry> open = new PriorityQueue<>();
    private final Object2DoubleOpenHashMap<BlockState> breakTicks = new Object2DoubleOpenHashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private @Nullable LevelChunk cachedChunk;

    private Node best;
    private double bestDistance;
    private int expanded;
    private @Nullable Path result;

    public PathFinder(BotPlayer bot, BlockPos start, Goal goal, Options options) {
        this.bot = bot;
        this.level = bot.level();
        this.goal = goal;
        this.options = options;
        this.noDigging = homeAreas(bot, start);
        Node startNode = new Node(start.getX(), start.getY(), start.getZ());
        startNode.g = 0;
        startNode.move = Move.START;
        nodes.put(start.asLong(), startNode);
        open.add(new Entry(startNode, 0));
        best = startNode;
        bestDistance = goal.distance(start.getX(), start.getY(), start.getZ());
    }

    public boolean isFinished() {
        return result != null;
    }

    public @Nullable Path result() {
        return result;
    }

    public int expanded() {
        return expanded;
    }

    public Goal goal() {
        return goal;
    }

    /** Expands up to {@code budget} nodes. */
    public void step(int budget) {
        while (result == null && budget-- > 0) {
            Entry entry = open.poll();
            if (entry == null) {
                finish(best, false);
                return;
            }
            Node node = entry.node;
            if (node.closed || entry.f != node.f()) {
                continue; // stale queue entry
            }
            node.closed = true;
            if (goal.isReached(node.x, node.y, node.z)) {
                finish(node, true);
                return;
            }
            double distance = goal.distance(node.x, node.y, node.z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = node;
            }
            if (++expanded >= options.maxNodes()) {
                finish(best, false);
                return;
            }
            expand(node);
        }
    }

    private void finish(Node end, boolean complete) {
        List<Path.Step> steps = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            steps.add(new Path.Step(new BlockPos(n.x, n.y, n.z), n.move));
        }
        Collections.reverse(steps);
        result = new Path(steps, complete);
    }

    private void expand(Node from) {
        int x = from.x;
        int y = from.y;
        int z = from.z;
        BlockState feet = state(x, y, z);
        if (feet == null) {
            return;
        }
        boolean inWater = BlockRules.isWater(feet);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int tx = x + direction.getStepX();
            int tz = z + direction.getStepZ();
            expandSameLevel(from, tx, y, tz);
            expandAscend(from, x, y, z, tx, tz);
            if (options.canBreak() && !inWater) {
                expandStairDown(from, y, tx, tz);
            }
        }
        for (int[] d : DIAGONALS) {
            expandDiagonal(from, x, y, z, x + d[0], z + d[1]);
        }

        // Straight up by pillaring, straight down by digging
        // After a pillar/bridge step the block we placed is under us, though the world doesn't have it yet
        boolean placedFloor = from.move == Move.PILLAR || from.move == Move.BRIDGE;
        if (options.canPlace() && !inWater && (placedFloor || standable(x, y - 1, z)) && mayPlace(x, y, z)) {
            double clear = clearCost(x, y + 2, z);
            if (clear < INF) {
                add(from, x, y + 1, z, PLACE + WALK + clear + (options.ladders() && nearBuilding(x, y, z) ? PLACE * 3 : 0), Move.PILLAR);
            }
        }
        if (options.ladders() && !inWater && (from.move == Move.LADDER || standable(x, y - 1, z) || BlockRules.isClimbable(feet))
            && ladderHere(x, y, z) && mayPlace(x, y, z) && !options.noLadder().contains(BlockPos.asLong(x, y, z))) {
            // Up the wall of a building on a ladder (it stays there) rather than a pillar beside it
            add(from, x, y + 1, z, CLIMB + LADDER_COST, Move.LADDER);
        }
        // (not through the top of the ground: a shaft there is a hole left in the surface; stairs instead)
        if (options.canBreak() && (options.escape() || !inSurfaceLayer(x, y - 1, z))) {
            double dig = clearCost(x, y - 1, z);
            if (dig > 0 && dig < INF && standable(x, y - 2, z)) {
                add(from, x, y - 1, z, dig + 2, Move.DIG_DOWN);
            }
        }

        if (inWater) {
            // Only up through water: a swimmer floats with its feet in the top water block
            // and can't rise into the air above it to climb a ledge from there
            BlockState above = state(x, y + 1, z);
            if (above != null && BlockRules.isWater(above)) {
                add(from, x, y + 1, z, SWIM, Move.SWIM_UP);
            }
            BlockState below = state(x, y - 1, z);
            if (below != null && BlockRules.isWater(below)) {
                add(from, x, y - 1, z, swimCost(x, y - 1, z), Move.SWIM_DOWN);
            }
        }

        BlockState above = state(x, y + 1, z);
        if (above != null && BlockRules.isClimbable(above) && passableOrClimbable(x, y + 2, z)) {
            add(from, x, y + 1, z, CLIMB, Move.CLIMB_UP);
        }
        BlockState below = state(x, y - 1, z);
        if (below != null && BlockRules.isClimbable(below)) {
            add(from, x, y - 1, z, CLIMB, Move.CLIMB_DOWN);
        }
    }

    private void expandSameLevel(Node from, int tx, int y, int tz) {
        double clear = clearCost(tx, y, tz) + clearCost(tx, y + 1, tz);
        if (clear == INF) {
            return;
        }
        BlockState body = state(tx, y, tz);
        BlockState floor = state(tx, y - 1, tz);
        if (body == null || floor == null) {
            return;
        }
        if (BlockRules.isWater(body)) {
            add(from, tx, y, tz, swimCost(tx, y, tz) + clear, Move.WALK);
        } else if (BlockRules.isClimbable(body) || BlockRules.isStandable(level, cursor.set(tx, y - 1, tz), floor)) {
            add(from, tx, y, tz, WALK + clear, Move.WALK);
        } else if (passable(tx, y - 1, tz, floor)) {
            expandDescend(from, tx, y, tz, clear);
            if (options.canPlace() && floor.canBeReplaced() && !BlockRules.isLava(floor) && mayPlace(tx, y - 1, tz)) {
                add(from, tx, y, tz, WALK + PLACE + clear, Move.BRIDGE);
            }
        }
    }

    /**
     * May it put a block here (a pillar, a bridge, a ladder)? Not in protected places (by players'
     * beds, no-go areas such as a village): no building there. Getting out of a cave is the exception.
     */
    private boolean mayPlace(int x, int y, int z) {
        return options.escape() || !ProtectedAreas.isProtected(level, cursor.set(x, y, z));
    }

    /** A building within a couple of blocks: up its wall on a ladder rather than on a pillar beside it. */
    private boolean nearBuilding(int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockState state = state(x + dx, y + dy, z + dz);
                    if (state != null && BlockRules.isBuilt(state)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Room to climb from here up one block on a ladder put on a building's wall? */
    private boolean ladderHere(int x, int y, int z) {
        BlockState up = state(x, y + 1, z);
        BlockState up2 = state(x, y + 2, z);
        if (up == null || up2 == null || !(passable(x, y + 1, z, up) || BlockRules.isClimbable(up))
            || !(passable(x, y + 2, z, up2) || BlockRules.isClimbable(up2))) {
            return false;
        }
        if (!rungFits(state(x, y, z)) || !rungFits(up)) {
            return false; // (a torch on the wall there: passable, but no ladder goes in its place)
        }
        return BlockRules.ladderWall(level, new BlockPos(x, y, z)) != null;
    }

    /** Can a rung go here: a ladder already, or room for one (air, grass, water)? */
    private static boolean rungFits(@Nullable BlockState state) {
        return state != null && (state.is(net.minecraft.world.level.block.Blocks.LADDER) || state.canBeReplaced());
    }

    /** Swimming with the head under water costs breath: strongly prefer the surface. */
    private double swimCost(int x, int y, int z) {
        BlockState head = state(x, y + 1, z);
        if (head != null && BlockRules.isWater(head)) {
            return SWIM * 4;
        }
        if (!options.boat()) {
            return SWIM;
        }
        // With a boat, rowing beats swimming; but a boat is wider than a block: keep off the bank and corners
        double cost = SWIM / 3;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockState side = state(x + dx, y, z + dz);
                BlockState over = state(x + dx, y + 1, z + dz);
                if (side == null || !BlockRules.isWater(side) || over != null && !over.getCollisionShape(level, cursor.set(x + dx, y + 1, z + dz)).isEmpty()) {
                    cost += 4;
                }
            }
        }
        return cost;
    }

    private void expandDescend(Node from, int tx, int y, int tz, double clear) {
        for (int fall = 1; fall <= MAX_WATER_FALL; fall++) {
            int ty = y - fall;
            BlockState state = state(tx, ty, tz);
            if (state == null || BlockRules.isDangerous(state)) {
                return;
            }
            if (BlockRules.isWater(state) || BlockRules.isClimbable(state)) {
                add(from, tx, ty, tz, WALK + FALL_PER_BLOCK * fall + clear, Move.DESCEND);
                return;
            }
            if (!passable(tx, ty, tz, state)) {
                int landY = ty + 1;
                int height = y - landY;
                if (height >= 1 && height <= MAX_SAFE_FALL && BlockRules.isStandable(level, cursor.set(tx, ty, tz), state)) {
                    add(from, tx, landY, tz, WALK + FALL_PER_BLOCK * height + clear, Move.DESCEND);
                }
                return;
            }
        }
    }

    /**
     * One step of a stairway dug down: forward and a block down, cutting the block it lands in,
     * the one at head height and the one over that, so the way can be walked back up.
     */
    private void expandStairDown(Node from, int y, int tx, int tz) {
        BlockState landing = state(tx, y - 1, tz);
        if (landing == null || passable(tx, y - 1, tz, landing) || !standable(tx, y - 2, tz)) {
            return; // (open already: a plain step down)
        }
        double cost = WALK + FALL_PER_BLOCK + clearCost(tx, y + 1, tz) + clearCost(tx, y, tz) + clearCost(tx, y - 1, tz);
        if (cost < INF) {
            add(from, tx, y - 1, tz, cost, Move.STAIR_DOWN);
        }
    }

    private void expandAscend(Node from, int x, int y, int z, int tx, int tz) {
        if (!standable(tx, y, tz)) {
            return;
        }
        double clear = clearCost(x, y + 2, z) + clearCost(tx, y + 1, tz) + clearCost(tx, y + 2, tz);
        if (clear < INF) {
            add(from, tx, y + 1, tz, WALK + JUMP + clear, Move.ASCEND);
        }
    }

    private void expandDiagonal(Node from, int x, int y, int z, int tx, int tz) {
        // No digging or corner cutting on diagonals: both side columns must be open
        if (!open(x, y, tz) || !open(tx, y, z) || !open(tx, y, tz) || !standable(tx, y - 1, tz)) {
            return;
        }
        BlockState body = state(tx, y, tz);
        if (body == null || BlockRules.isWater(body)) {
            return;
        }
        add(from, tx, y, tz, DIAGONAL, Move.DIAGONAL);
    }

    private void add(Node from, int x, int y, int z, double cost, Move move) {
        if (cost == INF || y <= level.getMinY() || y >= level.getMaxY() || options.avoid().contains(BlockPos.asLong(x, y, z))) {
            return;
        }
        if (options.zoneCenter() != null) {
            long dx = x - options.zoneCenter().getX();
            long dz = z - options.zoneCenter().getZ();
            if (dx * dx + dz * dz > (long) options.zoneRadius() * options.zoneRadius()) {
                return;
            }
        }
        long key = BlockPos.asLong(x, y, z);
        Node node = nodes.get(key);
        if (node == null) {
            node = new Node(x, y, z);
            node.h = goal.distance(x, y, z) * HEURISTIC;
            node.hazard = hazardPenalty(x, y, z); // (looked at once per spot, not for every way into it)
            nodes.put(key, node);
        } else if (node.closed) {
            return;
        }
        double g = from.g + cost + node.hazard;
        if (g >= node.g) {
            return;
        }
        node.g = g;
        node.parent = from;
        node.move = move;
        open.add(new Entry(node, node.f()));
    }

    /** Ticks to make the body able to pass through this block (0 if already open, INF if impossible). */
    /** Lava or fire right next to the spot (or the floor beside it): keep a step away if at all possible. */
    private double hazardPenalty(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    if (dx == 0 && dz == 0 && dy == 0) {
                        continue;
                    }
                    BlockState state = state(x + dx, y + dy, z + dz);
                    if (state != null && !state.isAir() && HAZARD.computeIfAbsent(state, s -> BlockRules.isLava(s) || s.is(BlockTags.FIRE))) {
                        return HAZARD_PENALTY;
                    }
                }
            }
        }
        return 0;
    }

    private double clearCost(int x, int y, int z) {
        BlockState state = state(x, y, z);
        if (state == null) {
            return INF;
        }
        if (passable(x, y, z, state)) {
            return 0;
        }
        if (BlockRules.isOpenable(state)) {
            return DOOR;
        }
        if (!options.canBreak() || !canBreak(x, y, z, state)) {
            return INF;
        }
        cursor.set(x, y, z);
        double ticks = breakTicks.computeIfAbsent(state, s -> BlockBreaker.estimateTicks(bot, state, level, cursor.immutable()));
        if (ticks == INF) {
            return INF;
        }
        BlockState above = state(x, y + 1, z);
        double extra = above != null && above.getBlock() instanceof FallingBlock ? FALLING_BLOCK_PENALTY : 0;
        if (!options.escape() && inSurfaceLayer(x, y, z)) {
            extra += SURFACE_PENALTY;
        }
        return ticks + BREAK_PENALTY + extra;
    }

    /** Like BlockRules#canBreak, but only reads already-loaded chunks. */
    private boolean canBreak(int x, int y, int z, BlockState state) {
        if (state.isAir() || !BlockRules.isNaturalTerrain(state) || state.hasBlockEntity()) {
            return false;
        }
        for (BlockPos home : noDigging) {
            long dx = home.getX() - x;
            long dz = home.getZ() - z;
            if (dx * dx + dz * dz <= HOME_NO_DIG * HOME_NO_DIG
                && y < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1) {
                return false; // no shafts or tunnels under or right by its own house
            }
        }
        // (at the Great Build the ground is all next to building blocks and the cobblestone filling it in: digging
        // there is fine - Tuff stood walled in in a hole in the floor; the building's own blocks are no ground anyway)
        boolean site = com.minebot.bot.build.GreatBuild.nearSite(cursor.set(x, y, z));
        for (Direction direction : Direction.values()) {
            BlockState neighbour = state(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
            if (neighbour == null) {
                return false;
            }
            // No digging into or under a building (a house dug into a hill, a basement):
            // the way in is the door
            if (!site && BlockRules.isBuilt(neighbour)) {
                return false;
            }
            if (direction != Direction.DOWN && !neighbour.getFluidState().isEmpty() && !options.escape()) {
                // (no flooding things; but getting out of a flooded cave it has to dig next to the water)
                return false;
            }
        }
        return !ProtectedAreas.isProtected(level, cursor.set(x, y, z));
    }

    /**
     * The bot's own home, workshop and house: no digging below ground there. Not if
     * it's down there already (it has to be able to dig its way out).
     */
    private static List<BlockPos> homeAreas(BotPlayer bot, BlockPos start) {
        List<BlockPos> areas = new ArrayList<>();
        var home = bot.memory().home();
        if (home == null || home.dimension() != bot.level().dimension()) {
            return areas;
        }
        areas.add(home.pos());
        if (bot.memory().workshop() != null) {
            areas.add(bot.memory().workshop());
        }
        for (BlockPos area : areas) {
            long dx = area.getX() - start.getX();
            long dz = area.getZ() - start.getZ();
            if (dx * dx + dz * dz <= HOME_NO_DIG * HOME_NO_DIG && isUnderground(bot, start)) {
                return List.of();
            }
        }
        return areas;
    }

    /** Rock or earth overhead (not just a roof or leaves). */
    private static boolean isUnderground(BotPlayer bot, BlockPos pos) {
        int rock = 0;
        for (int dy = 2; dy <= 24 && rock < 3; dy++) {
            BlockState state = bot.level().getBlockState(pos.above(dy));
            if (BlockRules.isNaturalTerrain(state) && !state.is(BlockTags.LEAVES) && !state.canBeReplaced()) {
                rock++;
            }
        }
        return rock >= 3;
    }

    private boolean open(int x, int y, int z) {
        BlockState feet = state(x, y, z);
        BlockState head = state(x, y + 1, z);
        return feet != null && head != null && passable(x, y, z, feet) && passable(x, y + 1, z, head);
    }

    private boolean passable(int x, int y, int z, BlockState state) {
        return BlockRules.isPassable(level, cursor.set(x, y, z), state);
    }

    private boolean passableOrClimbable(int x, int y, int z) {
        BlockState state = state(x, y, z);
        return state != null && (BlockRules.isClimbable(state) || passable(x, y, z, state));
    }

    private boolean standable(int x, int y, int z) {
        BlockState state = state(x, y, z);
        return state != null && BlockRules.isStandable(level, cursor.set(x, y, z), state);
    }

    /** In the top layers of the ground here (or the chunk isn't loaded)? */
    private boolean inSurfaceLayer(int x, int y, int z) {
        if (state(x, y, z) == null) {
            return true;
        }
        int top = cachedChunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
        return y > top - SURFACE_DEPTH && y <= top;
    }

    /** Block state from an already-loaded chunk, or null if the chunk isn't loaded. */
    private @Nullable BlockState state(int x, int y, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        LevelChunk chunk = cachedChunk;
        if (chunk == null || chunk.getPos().x != chunkX || chunk.getPos().z != chunkZ) {
            chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                return null;
            }
            cachedChunk = chunk;
        }
        return chunk.getBlockState(cursor.set(x, y, z));
    }

    private static final class Node {
        final int x;
        final int y;
        final int z;
        double g = INF;
        double h;
        double hazard;
        @Nullable Node parent;
        Move move;
        boolean closed;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        double f() {
            return g + h;
        }
    }

    private record Entry(Node node, double f) implements Comparable<Entry> {
        @Override
        public int compareTo(Entry other) {
            return Double.compare(f, other.f);
        }
    }
}
