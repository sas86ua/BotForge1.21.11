package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.BotRegistry;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.build.Blueprint;
import com.minebot.bot.build.GreatBuild;
import com.minebot.bot.build.HousePlan;
import com.minebot.bot.build.HousePlans;
import com.minebot.bot.build.Placement;
import com.minebot.bot.build.SchematicHouse;
import com.minebot.bot.build.Villages;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.world.BlockSearch;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Its part of the village's building (see {@link Villages}): the first villager ready to build lays
 * it out near the middle of the village; then each of them, in between its own work, takes the next
 * blocks nobody else is at, gets the materials for them (a few stacks at a time) and builds them.
 */
public class VillageBuildTask extends Task {
    private static final long DAY = 24000;
    private static final int CHECK_TICKS = 20 * 60 * 2;
    private static final int TICKS_PER_BLOCK = 20 * 20;
    private static final int BATCH_STACKS = 8;
    /** A block someone's at is left to them this long. */
    private static final long CLAIM_TICKS = 20 * 40;
    /** Built when no more than this share of its blocks is left (what nobody could get). */
    private static final double DONE_LEFT = 0.03;
    /** Back off to its own work after this long at it (it comes back). */
    private static final int MAX_TICKS = 20 * 60 * 10;
    private static final Map<BlockPos, Claim> CLAIMS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<Item, Long>> UNOBTAINABLE = new ConcurrentHashMap<>();

    private record Claim(UUID bot, long until) {
    }

    private enum Stage { FOUND, GO, MATERIALS, CLEAR, FILL, BUILD }

    private Stage stage = Stage.FOUND;
    private @Nullable Villages.Project project;
    private @Nullable HousePlan plan;
    private @Nullable Task child;
    private final BlockBreaker breaker;
    private final Set<BlockPos> skipped = new HashSet<>();
    private @Nullable BlockPos working;
    private int workingTicks;
    private int ticks;
    private @Nullable Item getting;

    public VillageBuildTask(BotPlayer bot) {
        super(bot);
        this.breaker = new BlockBreaker(bot);
    }

    public static boolean wanted(BotPlayer bot) {
        BotMemory memory = bot.memory();
        boolean ownHouse = memory.houseDone() && HousePlans.isSchematic(memory.houseTemplate());
        if (!memory.autonomous() || !Home.has(bot)
            || Home.levelIfHere(bot) == null || Home.isNight(bot) || Villages.buildings().isEmpty()) {
            return false;
        }
        return bot.every("village build", CHECK_TICKS, () -> {
            List<BotMemory> village = Villages.villageOf(bot);
            if (!ownHouse && !Villages.started(village)) {
                return false; // (its own house first; unless an admin started the village's building)
            }
            Villages villages = Villages.get(bot.level().getServer());
            BlockPos middle = Villages.middle(village);
            return villages.current(middle) != null || Villages.ready(village) && villages.next(middle) >= 0;
        });
    }

    @Override
    public Status tick() {
        if (++ticks > MAX_TICKS) {
            return Status.SUCCESS; // (its own work for a while; it comes back)
        }
        if (child != null) {
            Status status = child.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            child.stop();
            child = null;
            if (status == Status.FAILURE && stage == Stage.MATERIALS) {
                if (getting == null) {
                    return Status.FAILURE;
                }
                bot.debug("village: can't get {}; others may", getting);
                UNOBTAINABLE.computeIfAbsent(bot.getUUID(), u -> new ConcurrentHashMap<>()).put(getting, bot.level().getGameTime() + DAY);
                getting = null;
                return Status.RUNNING;
            }
            if (status == Status.FAILURE && (stage == Stage.FOUND || stage == Stage.GO)) {
                return Status.FAILURE;
            }
        }
        return switch (stage) {
            case FOUND -> found();
            case GO -> go();
            case MATERIALS -> gatherBatch();
            case CLEAR -> clear();
            case FILL -> fill();
            case BUILD -> build();
        };
    }

    // ---- laying it out --------------------------------------------------------------------------

    private boolean walkedToMiddle;

    /** The village's building under way; or, none and all ready, the next one laid out near the middle of the village. */
    private Status found() {
        List<BotMemory> village = Villages.villageOf(bot);
        Villages villages = Villages.get(bot.level().getServer());
        BlockPos middle = Villages.middle(village);
        project = villages.current(middle);
        if (project != null) {
            plan = Villages.plan(project);
            stage = Stage.GO;
            return Status.RUNNING;
        }
        int building = villages.next(middle);
        if (building < 0 || !Villages.ready(village)) {
            return Status.SUCCESS;
        }
        if (!walkedToMiddle) {
            // (out there first: the land round the middle of the village has to be there to look at)
            walkedToMiddle = true;
            child = new GoToTask(bot, Goal.column(middle.getX(), middle.getZ(), 6));
            return Status.RUNNING;
        }
        HousePlan site = findSite(building, middle);
        if (site == null) {
            bot.debug("village: no room for the {} near the middle of the village", Villages.name(building));
            return Status.FAILURE;
        }
        project = new Villages.Project(building, site.origin(), site.front(), false);
        villages.add(project);
        plan = Villages.plan(project);
        List<String> names = new ArrayList<>();
        for (BotMemory memory : village) {
            names.add(memory.name());
        }
        BlockPos c = plan.center();
        GreatBuild.announce(bot.level().getServer(), "Жители деревни (" + String.join(", ", names) + ") начали строить: "
            + Villages.name(building) + " у " + c.getX() + " " + c.getY() + " " + c.getZ() + ".");
        stage = Stage.GO;
        return Status.RUNNING;
    }

    private @Nullable HousePlan findSite(int building, BlockPos middle) {
        ServerLevel level = bot.level();
        List<BlockPos> built = BlockSearch.find(level, middle, 80, middle.getY() - 16, middle.getY() + 24, BlockRules::isBuilt,
            (pos, state) -> true, 6000);
        HousePlan best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -60; dx <= 60; dx += 3) {
            for (int dz = -60; dz <= 60; dz += 3) {
                Direction front = Math.abs(dx) > Math.abs(dz)
                    ? (dx > 0 ? Direction.WEST : Direction.EAST)
                    : (dz > 0 ? Direction.NORTH : Direction.SOUTH);
                HousePlan candidate = new SchematicHouse(Villages.buildings().get(building), BlockPos.ZERO, front, 0, false);
                BlockPos origin = new BlockPos(middle.getX() + dx - candidate.sizeX() / 2, 0, middle.getZ() + dz - candidate.sizeZ() / 2);
                candidate = new SchematicHouse(Villages.buildings().get(building), origin, front, 0, false);
                Double cost = evaluate(level, candidate, built);
                if (cost != null && cost + Math.sqrt(dx * dx + dz * dz) < bestScore) {
                    bestScore = cost + Math.sqrt(dx * dx + dz * dz);
                    best = candidate.atFloor(floor);
                }
            }
        }
        return best;
    }

    private int floor;

    /** How much levelling the spot takes (null: no good - water, fields, buildings, a slope, beyond the loaded land). */
    private @Nullable Double evaluate(ServerLevel level, HousePlan candidate, List<BlockPos> built) {
        BlockPos o = candidate.origin();
        int sx = candidate.sizeX();
        int sz = candidate.sizeZ();
        for (int[] corner : new int[][] {{-2, -2}, {sx + 1, -2}, {-2, sz + 1}, {sx + 1, sz + 1}}) {
            if (level.getChunkSource().getChunkNow((o.getX() + corner[0]) >> 4, (o.getZ() + corner[1]) >> 4) == null) {
                return null;
            }
        }
        int floorY = ground(level, o.getX() + sx / 2, o.getZ() + sz / 2) - 1;
        double cost = 0;
        for (int x = -2; x <= sx + 1; x++) {
            for (int z = -2; z <= sz + 1; z++) {
                int gx = o.getX() + x;
                int gz = o.getZ() + z;
                int top = ground(level, gx, gz) - 1;
                int diff = Math.abs(top - floorY);
                if (diff > 4) {
                    return null;
                }
                cost += diff;
                BlockPos column = new BlockPos(gx, floorY, gz);
                if (!bot.memory().inZone(level.dimension(), column) || ProtectedAreas.isProtected(level, column)
                    || level.getBlockState(new BlockPos(gx, top, gz)).is(Blocks.FARMLAND)) {
                    return null;
                }
                for (int y = floorY - 1; y <= floorY + 2; y++) {
                    if (!level.getFluidState(new BlockPos(gx, y, gz)).isEmpty()) {
                        return null;
                    }
                }
            }
        }
        for (BlockPos pos : built) {
            if (pos.getX() >= o.getX() - 3 && pos.getX() <= o.getX() + sx + 2 && pos.getZ() >= o.getZ() - 3
                && pos.getZ() <= o.getZ() + sz + 2 && pos.getY() >= floorY - 2) {
                return null;
            }
        }
        for (BotMemory any : BotRegistry.get(level.getServer()).all()) {
            for (BotMemory.Farm farm : any.farms()) {
                BlockPos f = farm.origin();
                if (f.getX() - 3 <= o.getX() + sx && f.getX() + farm.size() + 2 >= o.getX()
                    && f.getZ() - 3 <= o.getZ() + sz && f.getZ() + farm.size() + 2 >= o.getZ()) {
                    return null;
                }
            }
            if (any.bed() != null && any.bed().closerThan(o.offset(sx / 2, 0, sz / 2), 12 + Math.max(sx, sz) / 2)) {
                return null;
            }
        }
        floor = floorY;
        return cost;
    }

    private static int ground(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    // ---- getting there, materials -----------------------------------------------------------------

    private Status go() {
        BlockPos c = plan.center();
        if (bot.blockPosition().closerThan(c, 24 + Math.max(plan.sizeX(), plan.sizeZ()) / 2.0)) {
            stage = Stage.MATERIALS;
            return Status.RUNNING;
        }
        child = new GoToTask(bot, Goal.column(c.getX(), c.getZ(), 12));
        stage = Stage.MATERIALS;
        return Status.RUNNING;
    }

    private boolean unobtainable(Item item) {
        Long until = UNOBTAINABLE.getOrDefault(bot.getUUID(), Map.of()).get(item);
        return until != null && until > bot.level().getGameTime();
    }

    /** Someone else is at this block. */
    private boolean claimedByOther(BlockPos pos) {
        Claim claim = CLAIMS.get(pos);
        return claim != null && !claim.bot().equals(bot.getUUID()) && claim.until() > bot.level().getGameTime();
    }

    private void claim(BlockPos pos) {
        CLAIMS.put(pos, new Claim(bot.getUUID(), bot.level().getGameTime() + CLAIM_TICKS));
        if (CLAIMS.size() > 4000) {
            long now = bot.level().getGameTime();
            CLAIMS.values().removeIf(c -> c.until() < now);
        }
    }

    /** The materials for the next blocks nobody else is at, bottom up, a few stacks; earth for the ground. */
    private Status gatherBatch() {
        ServerLevel level = bot.level();
        Map<Item, Integer> batch = new LinkedHashMap<>();
        Map<Item, Integer> stacks = new HashMap<>();
        int totalStacks = 0;
        int ground = 0;
        for (Blueprint.Cell cell : plan.cells()) {
            char kind = cell.kind();
            if (kind != 'X' && kind != 'g' && kind != 's' || skipped.contains(cell.pos()) || claimedByOther(cell.pos())) {
                continue;
            }
            if (plan.isDone(cell, level.getBlockState(cell.pos()))) {
                continue;
            }
            if (kind != 'X') {
                ground++;
                continue;
            }
            Item item = cell.state().getBlock().asItem();
            if (unobtainable(item)) {
                continue;
            }
            int count = batch.merge(item, slabs(cell.state()), Integer::sum);
            int need = (count + item.getDefaultMaxStackSize() - 1) / item.getDefaultMaxStackSize();
            if (need > stacks.getOrDefault(item, 0)) {
                stacks.put(item, need);
                if (++totalStacks >= BATCH_STACKS) {
                    break;
                }
            }
        }
        for (Map.Entry<Item, Integer> entry : batch.entrySet()) {
            Target need = Target.of(entry.getKey(), entry.getValue());
            if (!need.satisfied(bot)) {
                bot.debug("village: the {} needs {}", plan.name(), need);
                getting = entry.getKey();
                child = new ObtainTask(bot, need, 0);
                return Status.RUNNING;
            }
        }
        getting = null;
        Target earth = new Target("blocks to fill holes", Inv::isScaffold, Math.min(64, ground + 16));
        if (!earth.satisfied(bot)) {
            child = new ObtainTask(bot, earth, 0);
            return Status.RUNNING;
        }
        if (bot.blockPosition().distSqr(plan.center()) > 48 * 48) {
            stage = Stage.GO; // (it went off for the materials: back to the site)
            return Status.RUNNING;
        }
        stage = Stage.CLEAR;
        return Status.RUNNING;
    }

    private static int slabs(BlockState state) {
        return state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
    }

    // ---- clearing, filling, building ---------------------------------------------------------------

    private Status clear() {
        ServerLevel level = bot.level();
        List<Blueprint.Cell> cells = plan.cells();
        for (int i = cells.size() - 1; i >= 0; i--) {
            Blueprint.Cell cell = cells.get(i);
            if (cell.kind() != '.' || skipped.contains(cell.pos()) || claimedByOther(cell.pos())) {
                continue;
            }
            BlockState state = level.getBlockState(cell.pos());
            if (isInTheWay(level, cell.pos(), state)) {
                claim(cell.pos());
                return dig(cell.pos());
            }
        }
        stage = Stage.FILL;
        return Status.RUNNING;
    }

    private Status fill() {
        ServerLevel level = bot.level();
        for (Blueprint.Cell cell : plan.cells()) {
            char kind = cell.kind();
            if (kind != 'g' && kind != 's' || skipped.contains(cell.pos()) || claimedByOther(cell.pos())) {
                continue;
            }
            BlockState state = level.getBlockState(cell.pos());
            if (!plan.isDone(cell, state) && state.canBeReplaced()) {
                claim(cell.pos());
                return place(cell.pos(), plan.material(cell), null);
            }
        }
        stage = Stage.BUILD;
        return Status.RUNNING;
    }

    private Status build() {
        ServerLevel level = bot.level();
        for (Blueprint.Cell cell : plan.cells()) {
            if (cell.kind() != 'X' || skipped.contains(cell.pos()) || claimedByOther(cell.pos())) {
                continue;
            }
            BlockState state = level.getBlockState(cell.pos());
            if (plan.isDone(cell, state) || unobtainable(cell.state().getBlock().asItem())) {
                continue;
            }
            if (!state.canBeReplaced()) {
                if (isInTheWay(level, cell.pos(), state) || Inv.isScaffold(new ItemStack(state.getBlock().asItem()))
                    || state.getBlock() == cell.state().getBlock()) {
                    claim(cell.pos());
                    return dig(cell.pos());
                }
                skip(cell.pos(), "something else is in the way");
                continue;
            }
            if (Inv.count(bot, plan.material(cell)) == 0) {
                stage = Stage.MATERIALS;
                return Status.RUNNING;
            }
            claim(cell.pos());
            return place(cell.pos(), null, cell.state());
        }
        // Nothing left it can do: built, if (next to) nothing's left at all
        if (finished()) {
            Villages villages = Villages.get(level.getServer());
            villages.finish(project);
            BlockPos c = plan.center();
            GreatBuild.announce(level.getServer(), "Деревня построила: " + plan.name() + " у " + c.getX() + " " + c.getY() + " " + c.getZ() + "!");
        }
        return Status.SUCCESS;
    }

    /** Next to nothing left to build of it (what's left, nobody could get). */
    private boolean finished() {
        ServerLevel level = bot.level();
        int total = 0;
        int left = 0;
        for (Blueprint.Cell cell : plan.cells()) {
            if (cell.kind() == 'X') {
                total++;
                if (!plan.isDone(cell, level.getBlockState(cell.pos()))) {
                    left++;
                }
            }
        }
        return total > 0 && left <= total * DONE_LEFT;
    }

    /** Natural things that can go: earth and stone, trees, plants. */
    private static boolean isInTheWay(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
            return !ProtectedAreas.isProtected(level, pos);
        }
        if (state.canBeReplaced()) {
            return false;
        }
        return BlockRules.canBreak(level, pos, state);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private Status dig(BlockPos pos) {
        if (!track(pos)) {
            breaker.cancel();
            skip(pos, "can't dig it");
            return Status.RUNNING;
        }
        if (!breaker.isBreaking(pos)) {
            if (!bot.isWithinBlockInteractionRange(pos, 0.0)) {
                approach(pos);
                return Status.RUNNING;
            }
            bot.navigator().stop();
            breaker.start(pos);
        }
        if (breaker.tick() == BlockBreaker.Result.FAILED) {
            skip(pos, "won't break");
        }
        return Status.RUNNING;
    }

    /** Puts {@code exact} there (turned as planned), or else something {@code material} takes (earth). */
    private Status place(BlockPos pos, @Nullable java.util.function.Predicate<ItemStack> material, @Nullable BlockState exact) {
        if (!track(pos)) {
            skip(pos, "can't place it");
            return Status.RUNNING;
        }
        if (material != null && Inv.count(bot, material) == 0) {
            stage = Stage.MATERIALS;
            return Status.RUNNING;
        }
        if (bot.getBoundingBox().intersects(new AABB(pos)) || !bot.isWithinBlockInteractionRange(pos, 0.0)) {
            approach(pos);
            return Status.RUNNING;
        }
        bot.navigator().stop();
        if (exact != null) {
            Placement.place(bot, pos, exact);
        } else {
            BlockPlacer.place(bot, pos, material);
        }
        return Status.RUNNING;
    }

    private void approach(BlockPos pos) {
        if (!bot.navigator().isActive()) {
            bot.navigator().navigate(Goal.reach(pos), 6000);
        }
        if (bot.navigator().tick().ended() && !bot.isWithinBlockInteractionRange(pos, 0.0)) {
            workingTicks += 20;
        }
    }

    private boolean track(BlockPos pos) {
        if (!pos.equals(working)) {
            working = pos;
            workingTicks = 0;
        }
        return ++workingTicks <= TICKS_PER_BLOCK;
    }

    private void skip(BlockPos pos, String why) {
        bot.debug("village: leaving out {} ({})", pos.toShortString(), why);
        skipped.add(pos);
        working = null;
        bot.navigator().stop();
    }

    @Override
    public void stop() {
        breaker.cancel();
        bot.navigator().stop();
        if (child != null) {
            child.stop();
        }
    }

    @Override
    public @Nullable Target wanted() {
        return child != null ? child.wanted() : null;
    }

    @Override
    public String describe() {
        String what = "building the village's " + (plan != null ? plan.name() : "building") + " (" + stage.name().toLowerCase() + ")";
        return child != null ? what + ": " + child.describe() : what;
    }
}
