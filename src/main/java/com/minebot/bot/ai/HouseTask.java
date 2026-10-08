package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.BotRegistry;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.build.Blueprint;
import com.minebot.bot.build.HousePlan;
import com.minebot.bot.build.HousePlans;
import com.minebot.bot.build.HouseTemplates;
import com.minebot.bot.build.Placement;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.world.BlockSearch;
import com.minebot.bot.world.HomeFinder;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * After some weeks in its first hut, the bot builds a proper house next to it: one of the
 * schematic houses (see {@link com.minebot.bot.build.HouseSchematics}), picked at random, or, with
 * no room for any of them, one of its own simple plans ({@link HouseTemplates}). It levels the
 * ground, cuts the trees there, gets the materials a batch at a time and builds it bottom up, then
 * moves its bed and crafting table in. The hut stays as its workshop with the chests and furnace.
 * A bot in an old house of its own simple plan builds a schematic one in time too, and moves.
 */
public class HouseTask extends Task {
    private static final long DAY = 24000;
    /** The house goes this far from the hut's middle (plus half its size)... */
    private static final int MIN_DISTANCE = 8;
    private static final int MAX_DISTANCE = 26;
    /** ...and keeps this much space from the hut's walls. */
    private static final int HUT_SPACING = 5;
    /** Uneven ground: dig or fill at most this much at any spot. */
    private static final int MAX_LEVELLING = 3;
    private static final int TICKS_PER_BLOCK = 20 * 20;
    /** A batch of materials: at most this many stacks of them at a time (a big house doesn't fit in the bag). */
    private static final int BATCH_STACKS = 10;
    /** What it couldn't get for its house is left out for a day (then tried again). */
    private static final long UNOBTAINABLE_TICKS = DAY;
    private static final Map<UUID, Map<Item, Long>> UNOBTAINABLE = new ConcurrentHashMap<>();

    private enum Stage { SITE, MATERIALS, CLEAR, FILL, BUILD, MOVE_BED, MOVE_TABLE, DONE }

    private Stage stage = Stage.SITE;
    private @Nullable HousePlan plan;
    private @Nullable Task child;
    private final BlockBreaker breaker;
    private final Set<BlockPos> skipped = new HashSet<>();
    private @Nullable BlockPos working;
    private int workingTicks;
    private boolean pillarLogging;
    /** The schematic houses still to try for a site, one a tick (in random order). */
    private @Nullable List<Integer> designQueue;
    /** What the materials child is getting (left out if it can't be had). */
    private @Nullable Item getting;
    private @Nullable Map<BlockPos, Blueprint.Cell> cellAt;

    public HouseTask(BotPlayer bot) {
        super(bot);
        this.breaker = new BlockBreaker(bot);
    }

    /** 20-30 days after moving into its first home (a bit different for each bot). */
    /** Game time the house is due to be started, or -1 (no hut yet, or the house is built). */
    public static long startTime(BotMemory memory) {
        if (memory.houseDone() || memory.homeSince() < 0) {
            return -1;
        }
        return memory.homeSince() + DAY * (20 + Math.floorMod(memory.uuid().hashCode(), 11));
    }

    /**
     * Is this house site where the bot lives: in its zone, and near its home (if it has one; while
     * moving in it has none, the bed being in its bag)?
     */
    private static boolean isOurArea(BotPlayer bot, BlockPos site) {
        BotMemory memory = bot.memory();
        if (!memory.inZone(bot.level().dimension(), site)) {
            return false;
        }
        BlockPos bed = memory.bed();
        return bed == null || bed.closerThan(site, 128);
    }

    /**
     * Moving into the house: the bed taken down from the hut (in its bag, or put in the house
     * already, the crafting table still to come), the house standing. Until it's done it has no
     * home, and mustn't go and build another hut or take some bed for its home.
     */
    public static boolean movingIn(BotPlayer bot) {
        BotMemory memory = bot.memory();
        if (memory.houseOrigin() == null || memory.houseDone() || !isOurArea(bot, memory.houseOrigin())) {
            return false;
        }
        // (the hut becomes the workshop as its bed is taken down; Home.validate forgets the bed then)
        return Inv.count(bot, stack -> stack.is(ItemTags.BEDS)) > 0 || memory.workshop() != null && memory.bed() == null;
    }

    public static boolean wanted(BotPlayer bot) {
        BotMemory memory = bot.memory();
        if (movingIn(bot)) {
            return true; // (finish moving in, home or no home)
        }
        if (!Home.has(bot) || memory.homeSince() < 0 || Home.levelIfHere(bot) == null || Home.isNight(bot)) {
            return false;
        }
        boolean tools = Tools.has(bot, ItemTags.PICKAXES, Tools.Tier.STONE) && Tools.has(bot, ItemTags.AXES, Tools.Tier.STONE);
        if (memory.houseDone()) {
            return tools && rebuildDue(bot);
        }
        return bot.level().getGameTime() >= startTime(memory) && tools;
    }

    /**
     * Living in an old house of its own simple plan: a schematic house in its place in time (a few days
     * to three weeks on, each bot its own moment; set the first time this is asked).
     */
    private static boolean rebuildDue(BotPlayer bot) {
        BotMemory memory = bot.memory();
        if (memory.houseOrigin() == null || HousePlans.isSchematic(memory.houseTemplate()) || HousePlans.designs() == 0) {
            return false;
        }
        long now = bot.level().getGameTime();
        if (memory.rebuildAt() < 0) {
            memory.setRebuildAt(now + DAY * (3 + Math.floorMod(memory.uuid().hashCode() >> 4, 21)));
            return false;
        }
        return now >= memory.rebuildAt();
    }

    @Override
    public Status tick() {
        if (child != null) {
            Status status = child.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            child.stop();
            Task finished = child;
            child = null;
            if (status == Status.FAILURE && stage == Stage.MATERIALS) {
                if (plan != null && plan.fromSchematic() && getting != null) {
                    // (one thing it can't get - a dye, deepslate...: those blocks are left out for now, the rest goes on)
                    bot.debug("house: can't get {}; leaving those blocks out for now", getting);
                    UNOBTAINABLE.computeIfAbsent(bot.getUUID(), u -> new ConcurrentHashMap<>())
                        .put(getting, bot.level().getGameTime() + UNOBTAINABLE_TICKS);
                    getting = null;
                    return Status.RUNNING;
                }
                bot.debug("house: couldn't get the materials");
                return Status.FAILURE;
            }
            if (status == Status.FAILURE && finished instanceof ObtainTask
                && (stage == Stage.MOVE_BED || stage == Stage.MOVE_TABLE)) {
                bot.debug("house: couldn't get a {} to move in with", stage == Stage.MOVE_BED ? "bed" : "crafting table");
                return Status.FAILURE; // (it carries on moving in later)
            }
        }
        if (stage != Stage.SITE && plan == null) {
            return Status.FAILURE;
        }
        return switch (stage) {
            case SITE -> chooseSite();
            case MATERIALS -> plan.fromSchematic() ? gatherBatch() : gatherMaterials();
            case CLEAR -> plan.fromSchematic() ? clearSchematic() : clear();
            case FILL -> plan.fromSchematic() ? fillSchematic() : fill();
            case BUILD -> build();
            case MOVE_BED -> moveBed();
            case MOVE_TABLE -> moveTable();
            case DONE -> Status.SUCCESS;
        };
    }

    // ---- site ---------------------------------------------------------------------------

    private Status chooseSite() {
        BotMemory memory = bot.memory();
        if (memory.houseDone()) {
            // Time for a new house in place of the old one of its simple plan: planned afresh (the old one stays)
            bot.debug("house: time for a new house");
            memory.setHouseDone(false);
            memory.setHouseSite(null, Direction.NORTH, 0);
        }
        BlockPos saved = memory.houseOrigin();
        if (saved != null && !isOurArea(bot, saved)) {
            // Planned (or built) back where it lived before: it has moved since (died far away and
            // started over elsewhere). A new house goes up by the home it has now
            bot.debug("house: the one at {} is far from home now; planning a new one here", saved.toShortString());
            memory.setHouseSite(null, Direction.NORTH, 0);
            memory.setHouseDone(false);
            memory.setWorkshop(null); // (the old hut, back there too)
            saved = null;
        }
        if (saved != null) {
            plan = HousePlans.create(memory.houseTemplate(), saved, memory.houseFront());
            bot.debug("house: carrying on with the {} at {}", plan.name(), saved.toShortString());
            stage = Stage.MATERIALS;
            return Status.RUNNING;
        }
        if (designQueue == null) {
            designQueue = new ArrayList<>();
            for (int i = 0; i < HousePlans.designs(); i++) {
                designQueue.add(HousePlans.SCHEMATIC_BASE + i);
            }
            Collections.shuffle(designQueue, new java.util.Random(bot.getRandom().nextLong()));
        }
        int index;
        if (!designQueue.isEmpty()) {
            index = designQueue.remove(0); // (one a tick: a big house is a lot of ground to look over)
        } else {
            index = -1;
        }
        if (index < 0) {
            // No room for any of them: one of its own simple plans
            index = bot.getRandom().nextInt(HouseTemplates.ALL.size());
        }
        plan = findSite(index);
        if (plan == null) {
            bot.debug("house: no good spot near the hut for a {}", HousePlans.create(index, BlockPos.ZERO, Direction.NORTH).name());
            return index < HousePlans.SCHEMATIC_BASE ? Status.FAILURE : Status.RUNNING;
        }
        memory.setHouseSite(plan.origin(), plan.front(), index);
        bot.debug("house: building a {} at {} facing {}", plan.name(), plan.origin().toShortString(), plan.front());
        stage = Stage.MATERIALS;
        return Status.RUNNING;
    }

    private @Nullable HousePlan findSite(int index) {
        ServerLevel level = bot.level();
        BlockPos bed = bot.memory().bed();
        // The hut (or whatever house the bot lives in now): everything built around the bed
        List<BlockPos> hut = BlockSearch.find(level, bed, 8, bed.getY() - 3, bed.getY() + 6, BlockRules::isBuilt,
            (pos, state) -> true, 500);
        int hutMinX = bed.getX(), hutMaxX = bed.getX(), hutMinZ = bed.getZ(), hutMaxZ = bed.getZ();
        for (BlockPos pos : hut) {
            hutMinX = Math.min(hutMinX, pos.getX());
            hutMaxX = Math.max(hutMaxX, pos.getX());
            hutMinZ = Math.min(hutMinZ, pos.getZ());
            hutMaxZ = Math.max(hutMaxZ, pos.getZ());
        }
        BlockPos hutCenter = new BlockPos((hutMinX + hutMaxX) / 2, bed.getY(), (hutMinZ + hutMaxZ) / 2);
        HousePlan sample = HousePlans.create(index, BlockPos.ZERO, Direction.NORTH);
        int half = Math.max(sample.sizeX(), sample.sizeZ()) / 2;
        int maxDistance = MAX_DISTANCE + half;
        int step = half > 6 ? 3 : 2;
        // Other buildings around (players', villages', other bots')
        List<BlockPos> built = BlockSearch.find(level, hutCenter, maxDistance + half + 14, bed.getY() - 8, bed.getY() + 16,
            BlockRules::isBuilt, (pos, state) -> true, 4000);

        HousePlan best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -maxDistance; dx <= maxDistance; dx += step) {
            for (int dz = -maxDistance; dz <= maxDistance; dz += step) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < MIN_DISTANCE + half || distance > maxDistance) {
                    continue;
                }
                // Door towards the hut (dx, dz: the house's middle from the hut's)
                Direction front = Math.abs(dx) > Math.abs(dz)
                    ? (dx > 0 ? Direction.WEST : Direction.EAST)
                    : (dz > 0 ? Direction.NORTH : Direction.SOUTH);
                HousePlan candidate = HousePlans.create(index, BlockPos.ZERO, front);
                candidate = HousePlans.create(index, new BlockPos(hutCenter.getX() + dx - candidate.sizeX() / 2, 0,
                    hutCenter.getZ() + dz - candidate.sizeZ() / 2), front);
                Double score = evaluate(level, candidate, hutMinX, hutMaxX, hutMinZ, hutMaxZ, built);
                if (score != null && score + distance < bestScore) {
                    bestScore = score + distance;
                    best = candidate.atFloor(candidateFloor);
                }
            }
        }
        return best;
    }

    /**
     * How much digging and filling a spot needs (null: unsuitable). Its floor
     * height is left in {@link #candidateFloor} (the candidate's own y means nothing).
     */
    private @Nullable Double evaluate(ServerLevel level, HousePlan candidate, int hutMinX, int hutMaxX, int hutMinZ,
                                      int hutMaxZ, List<BlockPos> built) {
        BlockPos o = candidate.origin();
        int sx = candidate.sizeX();
        int sz = candidate.sizeZ();
        // Keep away from the hut's walls
        if (o.getX() - HUT_SPACING <= hutMaxX && o.getX() + sx - 1 + HUT_SPACING >= hutMinX
            && o.getZ() - HUT_SPACING <= hutMaxZ && o.getZ() + sz - 1 + HUT_SPACING >= hutMinZ) {
            return null;
        }
        for (int[] corner : new int[][] {{-1, -1}, {sx, -1}, {-1, sz}, {sx, sz}}) {
            if (level.getChunkSource().getChunkNow((o.getX() + corner[0]) >> 4, (o.getZ() + corner[1]) >> 4) == null) {
                return null;
            }
        }
        int centerGround = ground(level, o.getX() + sx / 2, o.getZ() + sz / 2);
        int floorY = centerGround - 1;
        int levelling = candidate.fromSchematic() ? MAX_LEVELLING + 1 : MAX_LEVELLING;
        int stride = Math.max(sx, sz) > 12 ? 2 : 1; // (a big house: every other column will do to judge it)
        double cost = 0;
        for (int x = -1; x <= sx; x += stride) {
            for (int z = -1; z <= sz; z += stride) {
                boolean margin = x < 0 || z < 0 || x >= sx || z >= sz;
                int gx = o.getX() + x;
                int gz = o.getZ() + z;
                int top = ground(level, gx, gz) - 1;
                int diff = Math.abs(top - floorY);
                if (diff > (margin ? levelling + 1 : levelling)) {
                    return null;
                }
                cost += diff * stride * stride;
                BlockPos column = new BlockPos(gx, floorY, gz);
                if (!bot.memory().inZone(level.dimension(), column) || ProtectedAreas.isProtected(level, column)) {
                    return null;
                }
                for (int y = floorY - 1; y <= floorY + 2; y++) {
                    if (!level.getFluidState(new BlockPos(gx, y, gz)).isEmpty()) {
                        return null; // a pond, a river: not here
                    }
                }
            }
        }
        for (BlockPos pos : built) {
            if (pos.getX() >= o.getX() - 2 && pos.getX() <= o.getX() + sx + 1 && pos.getZ() >= o.getZ() - 2
                && pos.getZ() <= o.getZ() + sz + 1 && pos.getY() >= floorY - 2) {
                return null; // something's built there already
            }
        }
        for (BotMemory other : BotRegistry.get(level.getServer()).all()) {
            if (other != bot.memory() && other.bed() != null && other.bed().closerThan(o.offset(sx / 2, 0, sz / 2), 16 + Math.max(sx, sz) / 2)) {
                return null;
            }
        }
        candidateFloor = floorY;
        return cost;
    }

    /** Floor height of the spot {@link #evaluate} last looked at. */
    private int candidateFloor;

    private static int ground(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    // ---- materials ------------------------------------------------------------------------

    private Status gatherMaterials() {
        Map<Character, Integer> missing = missingBlocks();
        List<Target> needs = new ArrayList<>();
        needs.add(new Target("stone for the house", Blueprint.material('C'), missing.getOrDefault('C', 0) + 4));
        needs.add(new Target("planks for the house", Blueprint.material('P'), missing.getOrDefault('P', 0) + 4));
        needs.add(new Target("logs for the house", Blueprint.material('L'), missing.getOrDefault('L', 0)));
        needs.add(new Target("windows", Blueprint.material('G'), missing.getOrDefault('G', 0)));
        needs.add(new Target("a door", Blueprint.material('D'), missing.getOrDefault('D', 0)));
        needs.add(new Target("a chest for the house", Blueprint.material('H'), missing.getOrDefault('H', 0)));
        needs.add(new Target("a smoker for the kitchen", Blueprint.material('S'), missing.getOrDefault('S', 0)));
        needs.add(new Target("torches", Blueprint.material('t'), missing.getOrDefault('t', 0)));
        needs.add(new Target("blocks to fill holes", Inv::isScaffold, 16));
        for (Target need : needs) {
            if (need.count() > 0 && !need.satisfied(bot)) {
                bot.debug("house needs {}", need);
                child = new ObtainTask(bot, need, 0);
                return Status.RUNNING;
            }
        }
        stage = Stage.CLEAR;
        return Status.RUNNING;
    }

    /** Cells still to build, by kind (the bed and table are moved in, not built). */
    private Map<Character, Integer> missingBlocks() {
        Map<Character, Integer> missing = new LinkedHashMap<>();
        ServerLevel level = bot.level();
        for (Blueprint.Cell cell : plan.cells()) {
            char kind = cell.kind();
            if (kind == '.' || kind == 'B' || kind == 'T') {
                continue;
            }
            if (!plan.isDone(cell, level.getBlockState(cell.pos()))) {
                missing.merge(kind, 1, Integer::sum);
            }
        }
        return missing;
    }

    /** Left out for now: it couldn't get the item. */
    private boolean unobtainable(Item item) {
        Long until = UNOBTAINABLE.getOrDefault(bot.getUUID(), Map.of()).get(item);
        return until != null && until > bot.level().getGameTime();
    }

    /**
     * A schematic house: the materials for the next blocks to build, bottom up, a few stacks at a time
     * (a big house is thousands of blocks), and earth or stone for the ground it's to stand on.
     */
    private Status gatherBatch() {
        ServerLevel level = bot.level();
        Map<Item, Integer> batch = new LinkedHashMap<>();
        Map<Item, Integer> stacks = new HashMap<>();
        int totalStacks = 0;
        int ground = 0;
        int soil = 0;
        for (Blueprint.Cell cell : plan.cells()) {
            if (skipped.contains(cell.pos())) {
                continue;
            }
            char kind = cell.kind();
            if (kind != 'X' && kind != 'g' && kind != 's') {
                continue;
            }
            BlockState state = level.getBlockState(cell.pos());
            if (plan.isDone(cell, state)) {
                continue;
            }
            if (kind == 'g') {
                ground++;
                continue;
            }
            if (kind == 's') {
                soil++;
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
                bot.debug("house needs {}", need);
                getting = entry.getKey();
                child = new ObtainTask(bot, need, 0);
                return Status.RUNNING;
            }
        }
        getting = null;
        List<Target> earth = new ArrayList<>();
        earth.add(new Target("blocks to fill holes", Inv::isScaffold, Math.min(64, ground + 16)));
        if (soil > 0) {
            earth.add(Target.of(Items.DIRT, Math.min(64, soil)));
        }
        for (Target need : earth) {
            if (!need.satisfied(bot)) {
                bot.debug("house needs {}", need);
                child = new ObtainTask(bot, need, 0);
                return Status.RUNNING;
            }
        }
        stage = Stage.CLEAR;
        return Status.RUNNING;
    }

    /** A double slab takes two. */
    private static int slabs(BlockState state) {
        return state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
    }

    // ---- clearing and levelling -----------------------------------------------------------------

    /** Trees, earth and plants in the way: in the house, on the roof, and a path all round. */
    private Status clear() {
        ServerLevel level = bot.level();
        BlockPos o = plan.origin();
        for (int y = plan.floorY() + 1; y <= plan.topY() + 2; y++) {
            for (int x = -1; x <= plan.sizeX(); x++) {
                for (int z = -1; z <= plan.sizeZ(); z++) {
                    boolean margin = x < 0 || z < 0 || x >= plan.sizeX() || z >= plan.sizeZ();
                    if (margin && y > plan.floorY() + 3) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(o.getX() + x, y, o.getZ() + z);
                    if (skipped.contains(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (isInTheWay(level, pos, state) && !isPlanned(pos, state)) {
                        return dig(pos);
                    }
                }
            }
        }
        stage = Stage.FILL;
        return Status.RUNNING;
    }

    /** A schematic house: whatever grows or lies where the plan has it empty, top down (trees from their crowns). */
    private Status clearSchematic() {
        ServerLevel level = bot.level();
        List<Blueprint.Cell> cells = plan.cells();
        for (int i = cells.size() - 1; i >= 0; i--) {
            Blueprint.Cell cell = cells.get(i);
            if (cell.kind() != '.' || skipped.contains(cell.pos())) {
                continue;
            }
            BlockState state = level.getBlockState(cell.pos());
            if (isInTheWay(level, cell.pos(), state)) {
                return dig(cell.pos());
            }
        }
        stage = Stage.FILL;
        return Status.RUNNING;
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
            return false; // grass and flowers don't stop anything
        }
        return BlockRules.canBreak(level, pos, state);
    }

    /** The plan's cell at a spot, or null. */
    private @Nullable Blueprint.Cell cellAt(BlockPos pos) {
        if (cellAt == null) {
            cellAt = new HashMap<>();
            for (Blueprint.Cell cell : plan.cells()) {
                cellAt.put(cell.pos(), cell);
            }
        }
        return cellAt.get(pos);
    }

    /** Already the right block for the plan (a log where a log goes...). */
    private boolean isPlanned(BlockPos pos, BlockState state) {
        Blueprint.Cell cell = cellAt(pos);
        return cell != null && cell.kind() != '.' && plan.isDone(cell, state);
    }

    /** Holes under the floor and round the house filled in, so it stands on solid ground. */
    private Status fill() {
        ServerLevel level = bot.level();
        BlockPos o = plan.origin();
        for (int x = -1; x <= plan.sizeX(); x++) {
            for (int z = -1; z <= plan.sizeZ(); z++) {
                boolean margin = x < 0 || z < 0 || x >= plan.sizeX() || z >= plan.sizeZ();
                // Under the floor; for the path round the house, the path itself
                BlockPos pos = new BlockPos(o.getX() + x, margin ? plan.floorY() : plan.floorY() - 1, o.getZ() + z);
                if (!skipped.contains(pos) && level.getBlockState(pos).canBeReplaced()) {
                    return place(pos, Inv::isScaffold, null, null);
                }
            }
        }
        startBuilding();
        return Status.RUNNING;
    }

    /** A schematic house: its ground (and garden) where the plan has it, filled in where it's missing. */
    private Status fillSchematic() {
        ServerLevel level = bot.level();
        for (Blueprint.Cell cell : plan.cells()) {
            char kind = cell.kind();
            if (kind != 'g' && kind != 's' || skipped.contains(cell.pos())) {
                continue;
            }
            BlockState state = level.getBlockState(cell.pos());
            if (!plan.isDone(cell, state) && state.canBeReplaced()) {
                return place(cell.pos(), plan.material(cell), null, null);
            }
        }
        startBuilding();
        return Status.RUNNING;
    }

    private void startBuilding() {
        stage = Stage.BUILD;
        if (!pillarLogging) {
            pillarLogging = true;
            bot.navigator().startPillarLog(); // pillars put up to reach the roof come down after
        }
    }

    // ---- building ---------------------------------------------------------------------------

    private Status build() {
        ServerLevel level = bot.level();
        for (Blueprint.Cell cell : plan.cells()) {
            char kind = cell.kind();
            if (kind == '.' || kind == 'B' || kind == 'T' || kind == 'g' || kind == 's' || skipped.contains(cell.pos())) {
                continue;
            }
            BlockState state = level.getBlockState(cell.pos());
            if (plan.isDone(cell, state)) {
                continue;
            }
            if (cell.state() != null && unobtainable(cell.state().getBlock().asItem())) {
                continue; // (left out for now)
            }
            if (!state.canBeReplaced()) {
                if (isInTheWay(level, cell.pos(), state) || isOurScaffold(state)
                    || cell.state() != null && state.getBlock() == cell.state().getBlock()) {
                    return dig(cell.pos()); // (in the way; or the right block turned the wrong way: again)
                }
                skip(cell.pos(), "something else is in the way");
                continue;
            }
            Predicate<ItemStack> material = plan.material(cell);
            if (Inv.count(bot, material) == 0) {
                stage = Stage.MATERIALS; // ran out (some went into pillars and fills)
                return Status.RUNNING;
            }
            if (cell.state() != null) {
                return placeExact(cell);
            }
            Direction facing = kind == 'D' ? plan.front() : null;
            Direction face = kind == 't' ? wallFor(level, cell.pos())
                : kind == 'D' || kind == 'L' || kind == 'H' || kind == 'S' ? Direction.DOWN : null;
            return place(cell.pos(), material, face, facing);
        }
        finishBuilding();
        stage = Stage.MOVE_BED;
        return Status.RUNNING;
    }

    private void finishBuilding() {
        if (pillarLogging) {
            pillarLogging = false;
            bot.pillars().addAll(bot.navigator().stopPillarLog());
        }
        bot.debug("house: built ({} blocks left out)", skipped.size());
    }

    /** The wall a torch hangs on (any side with a sturdy face), or the floor if there is none. */
    private static Direction wallFor(ServerLevel level, BlockPos pos) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos wall = pos.relative(side);
            if (level.getBlockState(wall).isFaceSturdy(level, wall, side.getOpposite())) {
                return side;
            }
        }
        return Direction.DOWN;
    }

    private static boolean isOurScaffold(BlockState state) {
        return Inv.isScaffold(new ItemStack(state.getBlock().asItem()));
    }

    // ---- moving in ----------------------------------------------------------------------------

    private Status moveBed() {
        Blueprint.Cell foot = cellOf('B');
        if (foot == null) {
            // (no room for a bed anywhere in it: a plan of no use to live in; a new one some other time)
            bot.debug("house: the {} has nowhere for a bed; giving it up", plan.name());
            bot.memory().setHouseSite(null, Direction.NORTH, 0);
            return Status.FAILURE;
        }
        Direction head = plan.bedHead(foot);
        ServerLevel level = bot.level();
        if (plan.isDone(foot, level.getBlockState(foot.pos()))) {
            stage = Stage.MOVE_TABLE;
            return Status.RUNNING;
        }
        Predicate<ItemStack> bed = plan.material(foot);
        if (Inv.count(bot, bed) == 0) {
            BlockPos old = bot.memory().bed();
            if (old != null && !skipped.contains(old) && HomeFinder.isBed(level, old) && !plan.inFootprint(old)) {
                keepHutAsWorkshop(); // (before the bed goes: without it the hut is no home, see Home.validate)
                HomeFinder.release(level, old);
                return pickUp(old);
            }
            child = new ObtainTask(bot, Target.tag(ItemTags.BEDS, 1), 0);
            return Status.RUNNING;
        }
        for (BlockPos cell : new BlockPos[] {foot.pos(), foot.pos().relative(head)}) {
            if (!level.getBlockState(cell).canBeReplaced()) {
                return dig(cell); // (a ladder, a torch, a leftover block where the bed goes: out first)
            }
        }
        return place(foot.pos(), bed, Direction.DOWN, head);
    }

    private Status moveTable() {
        Blueprint.Cell spot = cellOf('T');
        ServerLevel level = bot.level();
        if (spot == null || plan.isDone(spot, level.getBlockState(spot.pos()))) {
            moveIn(spot != null ? spot.pos() : bot.memory().craftingTable());
            stage = Stage.DONE;
            return Status.SUCCESS;
        }
        if (Inv.count(bot, plan.material(spot)) == 0) {
            BlockPos old = bot.memory().craftingTable();
            if (old != null && !skipped.contains(old) && level.getBlockState(old).is(Blocks.CRAFTING_TABLE)
                && !plan.inFootprint(old)) {
                return pickUp(old);
            }
            child = new ObtainTask(bot, Target.of(Items.CRAFTING_TABLE, 1), 0);
            return Status.RUNNING;
        }
        if (!level.getBlockState(spot.pos()).canBeReplaced()) {
            // Something in its corner (a ladder or torch put up on the way, a block left from a pillar): out first
            return dig(spot.pos());
        }
        return place(spot.pos(), plan.material(spot), Direction.DOWN, null);
    }

    /** The hut stays as the workshop: its chests and furnace remain the bot's. */
    private void keepHutAsWorkshop() {
        BotMemory memory = bot.memory();
        if (memory.home() != null && memory.workshop() == null && memory.builtHome()) { // (a bed in someone else's house: that's no workshop of its own)
            memory.setWorkshop(memory.home().pos());
        }
    }

    private void moveIn(@Nullable BlockPos table) {
        ServerLevel level = bot.level();
        BotMemory memory = bot.memory();
        Blueprint.Cell foot = cellOf('B');
        BlockPos head = foot.pos().relative(plan.bedHead(foot));
        Home.returnBorrowed(bot); // (what it used in someone else's house stays there)
        keepHutAsWorkshop();
        memory.setHome(GlobalPos.of(level.dimension(), plan.center()), true);
        memory.setBed(head);
        memory.setCraftingTable(table);
        for (Blueprint.Cell cell : plan.cells()) {
            boolean chest = cell.kind() == 'H' || cell.state() != null && cell.state().is(Blocks.CHEST);
            if (chest && level.getBlockState(cell.pos()).is(Blocks.CHEST)) {
                memory.addChest(cell.pos());
            }
        }
        memory.setHouseDone(true);
        memory.setRebuildAt(-1);
        HomeFinder.claim(level, head);
        bot.useBed(head);
        bot.debug("moved into the new {} at {}", plan.name(), plan.origin().toShortString());
    }

    private @Nullable Blueprint.Cell cellOf(char kind) {
        for (Blueprint.Cell cell : plan.cells()) {
            if (cell.kind() == kind) {
                return cell;
            }
        }
        return null;
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Breaks a block (going over to it first) and picks up what drops. */
    private Status pickUp(BlockPos pos) {
        // From where it can be seen (inside the hut), not through the wall: it has to pick it up after
        if (!breaker.isBreaking(pos) && !bot.canUse(pos)) {
            if (!track(pos)) {
                skip(pos, "can't get to it");
                return Status.RUNNING;
            }
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.reachVisible(bot.level(), pos));
            }
            bot.navigator().tick();
            return Status.RUNNING;
        }
        Status status = dig(pos);
        if (bot.level().getBlockState(pos).isAir()) {
            bot.debug("house: took down {} to bring it over", pos.toShortString());
            child = new CollectItemsTask(bot, Vec3.atCenterOf(pos), 3.0, 200);
        }
        return status;
    }

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

    private Status place(BlockPos pos, Predicate<ItemStack> material, @Nullable Direction face, @Nullable Direction facing) {
        if (!track(pos)) {
            skip(pos, "can't place it");
            return Status.RUNNING;
        }
        if (Inv.count(bot, material) == 0) {
            stage = Stage.MATERIALS;
            return Status.RUNNING;
        }
        boolean standingInIt = bot.getBoundingBox().intersects(new AABB(pos));
        if (standingInIt || !bot.isWithinBlockInteractionRange(pos, 0.0)) {
            approach(pos);
            return Status.RUNNING;
        }
        bot.navigator().stop();
        BlockPlacer.place(bot, pos, material, face, facing);
        return Status.RUNNING;
    }

    /** A schematic house's block, put down turned the way the plan has it. */
    private Status placeExact(Blueprint.Cell cell) {
        BlockPos pos = cell.pos();
        if (!track(pos)) {
            skip(pos, "can't place it");
            return Status.RUNNING;
        }
        boolean standingInIt = bot.getBoundingBox().intersects(new AABB(pos));
        if (standingInIt || !bot.isWithinBlockInteractionRange(pos, 0.0)) {
            approach(pos);
            return Status.RUNNING;
        }
        bot.navigator().stop();
        Placement.place(bot, pos, cell.state());
        return Status.RUNNING;
    }

    private void approach(BlockPos pos) {
        if (!bot.navigator().isActive()) {
            bot.navigator().navigate(Goal.reach(pos));
        }
        if (bot.navigator().tick().ended() && !bot.isWithinBlockInteractionRange(pos, 0.0)) {
            workingTicks += 20; // no way there: give up on this block sooner
        }
    }

    /** Counts the time spent on one block; false once it's been too long. */
    private boolean track(BlockPos pos) {
        if (!pos.equals(working)) {
            working = pos;
            workingTicks = 0;
        }
        return ++workingTicks <= TICKS_PER_BLOCK;
    }

    private void skip(BlockPos pos, String why) {
        bot.debug("house: leaving out {} ({})", pos.toShortString(), why);
        skipped.add(pos);
        working = null;
        bot.navigator().stop();
    }

    @Override
    public void stop() {
        breaker.cancel();
        bot.navigator().stop();
        if (pillarLogging) {
            pillarLogging = false;
            bot.pillars().addAll(bot.navigator().stopPillarLog());
        }
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
        String what = "building a house (" + stage.name().toLowerCase().replace('_', ' ') + ")";
        return child != null ? what + ": " + child.describe() : what;
    }
}
