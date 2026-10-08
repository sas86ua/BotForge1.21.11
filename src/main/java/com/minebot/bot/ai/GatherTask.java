package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.craft.Sources;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.world.BlockSearch;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Mines blocks of a source (trees, stone, ores) until the target count is in the inventory. */
public class GatherTask extends Task {
    private static final int SEARCH_RADIUS = 48;
    /** Where diamonds are looked for from: most lie near the bottom of the world (y -59). */
    private static final int DIAMOND_DEPTH = -40;
    private static final int MAX_EXPLORES = 5;
    /** Ticks allowed on one block; some turn out unreachable after all. */
    private static final int MAX_TICKS_PER_BLOCK = 20 * 60;

    private final Sources.Mine source;
    private final Target target;
    private final Predicate<BlockState> blocks;
    private final BlockBreaker breaker;
    private final Set<BlockPos> failed = new HashSet<>();
    /** No pits or tunnels this close to anyone's house (players', villages', bots'). */
    private static final int BUILDING_DISTANCE = 24;
    private List<BlockPos> buildings = List.of();
    private @Nullable BlockPos buildingsCenter;
    /** The tree being felled: where it stood, and its logs still standing. */
    /** No logs: no trees (the tree being felled is the bot's, so an interrupted one gets finished later). */
    private static final Set<BlockPos> NO_TREE = Set.of();
    /** Blocks pillared up to reach the top of the tree; taken down after. */
    private final List<BlockPos> towerBlocks = new ArrayList<>();
    private @Nullable BlockPos plantAt;
    private @Nullable BlockPos current;
    private @Nullable Task child;
    private int explores;
    private int currentTicks;
    private int recycles;
    /** Down a mine wanting logs: it has been up to the surface to look for trees. */
    private boolean surfaced;
    private static final int MAX_RECYCLES = 150;

    public GatherTask(BotPlayer bot, Sources.Mine source, Target target) {
        super(bot);
        this.source = source;
        this.target = target;
        this.blocks = source.blocksFor(target);
        this.breaker = new BlockBreaker(bot);
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
            if (plantAt != null) {
                towerBlocks.clear(); // (taken down, or as much as it could)
                plantSaplings();
                if (child != null) {
                    return Status.RUNNING;
                }
            }
        }
        if (source == Sources.LOGS) {
            trunk().removeIf(log -> failed.contains(log) || !bot.level().getBlockState(log).is(BlockTags.LOGS));
        }
        if (bot.fellingTree() != null && trunk().isEmpty()) {
            finishTree();
            if (child != null) {
                return Status.RUNNING;
            }
        }
        // A tree that was started gets felled completely, even if that's more than needed
        if (target.satisfied(bot) && trunk().isEmpty()) {
            return Status.SUCCESS;
        }
        if (!source.canHarvest(bot)) {
            return Status.FAILURE; // the planner gets a tool first
        }
        if (current == null && recycleGravel()) {
            return Status.RUNNING;
        }
        if (current == null) {
            if (!Home.isNear(bot, 48)) {
                Stash.makeRoom(bot, 2); // (out there with a full bag, what it digs would be left lying)
            }
            current = findNext();
            if (current == null && source == Sources.LOGS && !surfaced && EscapeTask.underground(bot)) {
                surfaced = true; // (no trees grow down here: up to the surface to look)
                bot.debug("no trees underground; going up to the surface");
                child = new GoToTask(bot, Goal.surface(bot.level()));
                return Status.RUNNING;
            }
            if (current == null) {
                if (++explores > MAX_EXPLORES) {
                    return Status.FAILURE;
                }
                bot.debug("no {} nearby, exploring", source.name());
                child = new ExploreTask(bot, 64);
                return Status.RUNNING;
            }
            bot.navigator().navigate(Goal.reach(current));
            currentTicks = 0;
        }
        if (bot.gaspedRecently() && current != null && currentTicks > 40) {
            // Getting to it meant diving until out of breath: not this one (a gasp from before it set off
            // for this block doesn't count, or one gasp would cross off every block in turn)
            bot.debug("{} at {} is too deep under water, leaving it", source.name(), current.toShortString());
            breaker.cancel();
            giveUpOn(current);
            // (and the rest of it down there: block after block of the same seam, a dive and a gasp each, it went on)
            for (BlockPos near : BlockPos.betweenClosed(current.offset(-6, -6, -6), current.offset(6, 6, 6))) {
                if (blocks.test(bot.level().getBlockState(near)) && nextToWater(near)) {
                    failed.add(near.immutable());
                }
            }
            current = null;
            return Status.RUNNING;
        }
        if (++currentTicks > MAX_TICKS_PER_BLOCK) {
            bot.debug("giving up on {} at {}", source.name(), current.toShortString());
            breaker.cancel();
            giveUpOn(current);
            current = null;
            return Status.RUNNING;
        }

        ServerLevel level = bot.level();
        if (!blocks.test(level.getBlockState(current))) {
            current = null; // someone else mined it
            return Status.RUNNING;
        }

        if (breaker.target() == null) {
            Navigator.Status status = bot.navigator().tick();
            if (status == Navigator.Status.FAILED) {
                giveUpOn(current);
                current = null;
                return Status.RUNNING;
            }
            if (status != Navigator.Status.SUCCESS) {
                return Status.RUNNING;
            }
            // In reach. Mine whatever natural block is in the line of sight first; no digging through walls
            BlockPos next = firstBlockInSight(current);
            if (next == null) {
                failed.add(current);
                current = null;
                return Status.RUNNING;
            }
            breaker.start(next);
        }

        BlockPos mining = breaker.target();
        BlockBreaker.Result result = breaker.tick();
        if (result == BlockBreaker.Result.FAILED) {
            failed.add(current);
            current = null;
        } else if (result == BlockBreaker.Result.SUCCESS) {
            if (current.equals(mining)) {
                if (source == Sources.LOGS) {
                    startOrContinueTree(current);
                }
                child = new CollectItemsTask(bot, Vec3.atCenterOf(current), 4.0, 80);
                current = null;
            } else {
                bot.navigator().navigate(Goal.reach(current));
            }
        }
        return Status.RUNNING;
    }

    /** First log of a tree cut: note the whole trunk, to fell it all, and any pillar put up for it. */
    private void startOrContinueTree(BlockPos cut) {
        trunk().remove(cut);
        if (bot.fellingTree() != null) {
            return;
        }
        bot.setFellingTree(cut);
        bot.navigator().startPillarLog();
        ServerLevel level = bot.level();
        Deque<BlockPos> open = new ArrayDeque<>();
        open.add(cut);
        Set<BlockPos> seen = new HashSet<>(Set.of(cut));
        while (!open.isEmpty() && trunk().size() < 64) {
            BlockPos at = open.poll();
            for (BlockPos next : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
                BlockPos log = next.immutable();
                // (no higher than 7 above where it cut: not up into the crown of a giant tree)
                if (Math.abs(log.getX() - cut.getX()) <= 4 && Math.abs(log.getZ() - cut.getZ()) <= 4
                    && log.getY() >= cut.getY() - 2 && log.getY() <= cut.getY() + 7 && seen.add(log) && level.getBlockState(log).is(BlockTags.LOGS)) {
                    trunk().add(log);
                    open.add(log);
                }
            }
        }
        if (!trunk().isEmpty()) {
            bot.debug("felling the whole tree at {} ({} more logs)", cut.toShortString(), trunk().size());
        }
    }

    /**
     * Digging for flint with gravel in the bag already: that gravel is put down beside it and dug
     * again (a tenth of the time it gives flint), rather than the deposit dug away block by block.
     */
    private boolean recycleGravel() {
        if (source != Sources.GRAVEL || !target.accepts().test(new ItemStack(Items.FLINT)) || ++recycles > MAX_RECYCLES
            || Inv.count(bot, stack -> stack.is(Items.GRAVEL)) == 0) {
            return false;
        }
        com.minebot.bot.action.PlaceSpots.Spot spot = com.minebot.bot.action.PlaceSpots.find(bot);
        if (spot == null || spot.clearFirst()
            || !com.minebot.bot.action.BlockPlacer.place(bot, spot.pos(), stack -> stack.is(Items.GRAVEL))) {
            return false;
        }
        current = spot.pos();
        currentTicks = 0;
        bot.navigator().navigate(Goal.reach(current)); // (right by it)
        return true;
    }

    /** Trunk gone: take down the pillar used to reach the top, and plant saplings in its place. */
    private void finishTree() {
        BlockPos base = bot.fellingTree();
        bot.setFellingTree(null);
        towerBlocks.addAll(bot.navigator().stopPillarLog());
        bot.choppedTrees().add(base);
        plantAt = base;
        if (!towerBlocks.isEmpty()) {
            child = new DismantleTask(bot, towerBlocks); // then plant (below)
        } else {
            plantSaplings();
        }
    }

    private void plantSaplings() {
        BlockPos at = plantAt;
        plantAt = null;
        if (at != null && PlantTask.wanted(bot)) {
            child = new PlantTask(bot, at);
        }
    }

    /** Marks a block as unreachable; for a tree, the whole trunk (its other logs are just as hard to get at). */
    /** Water beside or over it (within a couple of blocks up): it's under the water. */
    private boolean nextToWater(BlockPos pos) {
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            if (bot.level().getFluidState(pos.relative(direction)).is(net.minecraft.tags.FluidTags.WATER)) {
                return true;
            }
        }
        return bot.level().getFluidState(pos.above(2)).is(net.minecraft.tags.FluidTags.WATER);
    }

    private void giveUpOn(BlockPos pos) {
        failed.add(pos);
        if (source == Sources.LOGS) {
            for (BlockPos log : BlockPos.betweenClosed(pos.offset(-2, -12, -2), pos.offset(2, 12, 2))) {
                if (bot.level().getBlockState(log).is(BlockTags.LOGS)) {
                    failed.add(log.immutable());
                }
            }
        }
    }

    /** The logs of the tree being felled, still standing (only when gathering logs). */
    private Set<BlockPos> trunk() {
        if (source != Sources.LOGS) {
            return NO_TREE;
        }
        BlockPos tree = bot.fellingTree();
        if (tree != null && !tree.closerThan(bot.blockPosition(), 48)) {
            bot.setFellingTree(null); // (far away now: forget it)
        }
        return bot.trunk();
    }

    private @Nullable BlockPos findNext() {
        if (!trunk().isEmpty()) {
            // Finish the tree: lowest log first, then up the trunk
            return trunk().stream().filter(log -> !failed.contains(log))
                .min(Comparator.comparingInt((BlockPos log) -> log.getY()).thenComparingDouble(log -> log.distSqr(bot.blockPosition())))
                .orElse(null);
        }
        ServerLevel level = bot.level();
        BlockPos center = bot.blockPosition();
        boolean logs = source == Sources.LOGS;
        // Without blocks to pillar up on, only logs reachable from the ground
        boolean scaffold = Inv.count(bot, Inv::isScaffold) >= 4;
        // (diamonds lie deep: look all the way down for them)
        int minY = logs ? center.getY() - 12 : source == Sources.DIAMOND ? level.getMinY() : center.getY() - 48;
        int maxY = center.getY() + (logs ? 24 : 16);
        List<BlockPos> buildings = logs ? List.of() : buildingsAround(level, center);
        // (the search reaches SEARCH_RADIUS every way, up and down too: for diamonds it is centred down deep,
        // where they lie, or from the surface it would never reach them)
        BlockPos searchCenter = source == Sources.DIAMOND ? center.atY(Math.max(level.getMinY() + 24, DIAMOND_DEPTH)) : center;
        MiningRule rule = miningRule(level);
        if (rule != null) {
            // Underground only, from its mine: searched (and nearest picked) around where the mine got to
            center = rule.centre();
            searchCenter = source == Sources.DIAMOND ? searchCenter : center;
            minY = level.getMinY() + 1;
            maxY = Math.min(level.getMaxY(), center.getY() + 24);
        }
        BlockPos from = center;
        List<BlockPos> candidates = BlockSearch.find(level, searchCenter, SEARCH_RADIUS, minY, maxY, blocks,
            (pos, state) -> (rule == null || rule.allows(pos)) && !failed.contains(pos)
                && bot.memory().inZone(level.dimension(), pos)
                && !ProtectedAreas.isProtected(level, pos)
                && !nearBuilding(pos, buildings)
                && !nearWater(level, pos)
                && (!logs || BlockRules.isNaturalTreeLog(level, pos, state) && (scaffold || withinReachOfGround(level, pos))),
            12);
        // Prefer blocks out in the open over ones that need a tunnel
        BlockPos next = candidates.stream()
            .min(Comparator.comparingDouble(pos -> Math.sqrt(pos.distSqr(from)) + (isExposed(level, pos) ? 0 : 8)))
            .orElse(null);
        if (rule != null && next != null) {
            rule.dugAt(next);
        }
        return next;
    }

    /** Where this source may be dug now (see MiningRule), or null for anywhere. */
    private @Nullable MiningRule miningRule(ServerLevel level) {
        MiningRule rule = bot.miningRule();
        if (rule == null && GreatBuildTask.isAway(bot) && com.minebot.bot.build.GreatBuild.nearSite(bot.blockPosition())) {
            // At the Great Build (a new tool, say): stone and ore from under the site only
            rule = MiningRule.underSite(level, com.minebot.bot.build.GreatBuild.get(level.getServer()));
        }
        return rule != null && rule.appliesTo(source) ? rule : null;
    }

    /**
     * Blocks of houses (players', villages', bots') around here, to keep pits and
     * tunnels away from them. Cached while the bot stays in the same area.
     */
    private List<BlockPos> buildingsAround(ServerLevel level, BlockPos center) {
        if (buildingsCenter != null && buildingsCenter.closerThan(center, 16)) {
            return buildings;
        }
        buildingsCenter = center;
        buildings = BlockSearch.find(level, center, SEARCH_RADIUS + BUILDING_DISTANCE, center.getY() - 48, center.getY() + 40,
            state -> BlockRules.isBuilt(state) && !state.is(Blocks.COBBLESTONE) // (the bot's own pillars and bridges)
                && !state.is(Blocks.TORCH) && !state.is(Blocks.WALL_TORCH),
            // Near the surface: a house or a basement, not an abandoned mineshaft deep down
            (pos, state) -> level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - pos.getY() < 10,
            4000);
        return buildings;
    }

    /**
     * Water right by it, or a lake or the sea above it: getting it means diving
     * (and coming up for air again and again).
     */
    private static boolean nearWater(ServerLevel level, BlockPos pos) {
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
            if (!level.getFluidState(near).isEmpty()) {
                return true;
            }
        }
        // Under a sea or a lake (water on top of the column, however deep): no diving down to it
        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
        if (top > pos.getY() && level.getFluidState(new BlockPos(pos.getX(), top - 1, pos.getZ())).is(net.minecraft.tags.FluidTags.WATER)) {
            return true;
        }
        BlockPos.MutableBlockPos above = pos.mutable();
        for (int dy = 2; dy <= 24; dy++) {
            above.setY(pos.getY() + dy);
            if (level.getFluidState(above).is(net.minecraft.tags.FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /** Measured flat: an ore right under a house is no better than one next to it. */
    private static boolean nearBuilding(BlockPos pos, List<BlockPos> buildings) {
        for (BlockPos building : buildings) {
            long dx = building.getX() - pos.getX();
            long dz = building.getZ() - pos.getZ();
            if (dx * dx + dz * dz < (long) BUILDING_DISTANCE * BUILDING_DISTANCE) {
                return true;
            }
        }
        return false;
    }

    /** At most 5 blocks above the bottom of its trunk: reachable standing next to the tree. */
    private static boolean withinReachOfGround(ServerLevel level, BlockPos log) {
        BlockPos.MutableBlockPos cursor = log.mutable();
        for (int depth = 0; depth <= 5; depth++) {
            cursor.move(0, -1, 0);
            if (!level.getBlockState(cursor).is(BlockTags.LOGS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExposed(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /** The target if visible, otherwise the breakable block blocking the view; null if blocked by something else. */
    private @Nullable BlockPos firstBlockInSight(BlockPos targetPos) {
        ServerLevel level = bot.level();
        Vec3 eyes = bot.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eyes, Vec3.atCenterOf(targetPos),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(targetPos)) {
            return targetPos;
        }
        BlockPos blocker = hit.getBlockPos();
        return BlockRules.canBreak(level, blocker, level.getBlockState(blocker)) ? blocker : null;
    }

    @Override
    public void stop() {
        breaker.cancel();
        bot.navigator().stop();
        // Interrupted: the pillar is taken down later by the "dismantle" need
        towerBlocks.addAll(bot.navigator().stopPillarLog());
        bot.pillars().addAll(towerBlocks);
        towerBlocks.clear();
        if (child != null) {
            child.stop();
        }
    }

    @Override
    public Target wanted() {
        return target;
    }

    @Override
    public String describe() {
        if (child != null) {
            return "gathering " + target + ": " + child.describe();
        }
        return "gathering " + target + (current != null ? " at " + current.toShortString() : "");
    }
}
