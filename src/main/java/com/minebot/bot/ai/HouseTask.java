package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.BotRegistry;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.build.Blueprint;
import com.minebot.bot.build.HouseTemplates;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * After some weeks in its first hut, the bot builds a proper house next to it
 * (one of a few plans, see {@link HouseTemplates}): levels the ground, cuts
 * the trees there, builds it from stone and wood, then moves its bed and
 * crafting table in. The hut stays as its workshop with the chests and furnace.
 */
public class HouseTask extends Task {
    private static final long DAY = 24000;
    /** The house goes this far from the hut's middle... */
    private static final int MIN_DISTANCE = 8;
    private static final int MAX_DISTANCE = 26;
    /** ...and keeps this much space from the hut's walls. */
    private static final int HUT_SPACING = 5;
    /** Uneven ground: dig or fill at most this much at any spot. */
    private static final int MAX_LEVELLING = 3;
    private static final int TICKS_PER_BLOCK = 20 * 20;

    private enum Stage { SITE, MATERIALS, CLEAR, FILL, BUILD, MOVE_BED, MOVE_TABLE, DONE }

    private Stage stage = Stage.SITE;
    private @Nullable Blueprint plan;
    private @Nullable Task child;
    private final BlockBreaker breaker;
    private final Set<BlockPos> skipped = new HashSet<>();
    private @Nullable BlockPos working;
    private int workingTicks;
    private boolean pillarLogging;

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
        if (!Home.has(bot) || memory.houseDone() || memory.homeSince() < 0 || Home.levelIfHere(bot) == null
            || Home.isNight(bot)) {
            return false;
        }
        return bot.level().getGameTime() >= startTime(memory)
            && Tools.has(bot, ItemTags.PICKAXES, Tools.Tier.STONE) && Tools.has(bot, ItemTags.AXES, Tools.Tier.STONE);
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
            case MATERIALS -> gatherMaterials();
            case CLEAR -> clear();
            case FILL -> fill();
            case BUILD -> build();
            case MOVE_BED -> moveBed();
            case MOVE_TABLE -> moveTable();
            case DONE -> Status.SUCCESS;
        };
    }

    // ---- site ---------------------------------------------------------------------------

    private Status chooseSite() {
        BotMemory memory = bot.memory();
        BlockPos saved = memory.houseOrigin();
        if (saved != null && !isOurArea(bot, saved)) {
            // Planned (or built) back where it lived before: it has moved since (died far away and
            // started over elsewhere). A new house goes up by the home it has now
            bot.debug("house: the one at {} is far from home now; planning a new one here", saved.toShortString());
            memory.setHouseSite(null, net.minecraft.core.Direction.NORTH, 0);
            memory.setHouseDone(false);
            memory.setWorkshop(null); // (the old hut, back there too)
            saved = null;
        }
        if (saved != null) {
            plan = new Blueprint(HouseTemplates.ALL.get(Math.floorMod(memory.houseTemplate(), HouseTemplates.ALL.size())),
                saved, memory.houseFront());
            bot.debug("house: carrying on with the {} at {}", plan.template().name(), saved.toShortString());
            stage = Stage.MATERIALS;
            return Status.RUNNING;
        }
        int templateIndex = bot.getRandom().nextInt(HouseTemplates.ALL.size());
        plan = findSite(HouseTemplates.ALL.get(templateIndex));
        if (plan == null) {
            bot.debug("house: no good spot near the hut");
            return Status.FAILURE;
        }
        memory.setHouseSite(plan.origin(), plan.front(), templateIndex);
        bot.debug("house: building a {} at {} facing {}", plan.template().name(), plan.origin().toShortString(), plan.front());
        stage = Stage.MATERIALS;
        return Status.RUNNING;
    }

    private @Nullable Blueprint findSite(HouseTemplates.Template template) {
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
        // Other buildings around (players', villages', other bots')
        List<BlockPos> built = BlockSearch.find(level, hutCenter, MAX_DISTANCE + 14, bed.getY() - 8, bed.getY() + 16,
            BlockRules::isBuilt, (pos, state) -> true, 4000);

        Blueprint best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -MAX_DISTANCE; dx <= MAX_DISTANCE; dx += 2) {
            for (int dz = -MAX_DISTANCE; dz <= MAX_DISTANCE; dz += 2) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < MIN_DISTANCE || distance > MAX_DISTANCE) {
                    continue;
                }
                // Door towards the hut
                Direction front = Math.abs(dx) > Math.abs(dz)
                    ? (dx > 0 ? Direction.WEST : Direction.EAST)
                    : (dz > 0 ? Direction.NORTH : Direction.SOUTH);
                Blueprint candidate = new Blueprint(template, new BlockPos(hutCenter.getX() + dx, 0, hutCenter.getZ() + dz), front);
                Double score = evaluate(level, candidate, hutMinX, hutMaxX, hutMinZ, hutMaxZ, built);
                if (score != null && score + distance < bestScore) {
                    bestScore = score + distance;
                    best = new Blueprint(template, candidate.origin().atY(candidateFloor), front);
                }
            }
        }
        return best;
    }

    /**
     * How much digging and filling a spot needs (null: unsuitable). Its floor
     * height is left in {@link #candidateFloor} (the candidate's own y means nothing).
     */
    private @Nullable Double evaluate(ServerLevel level, Blueprint candidate, int hutMinX, int hutMaxX, int hutMinZ,
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
        double cost = 0;
        for (int x = -1; x <= sx; x++) {
            for (int z = -1; z <= sz; z++) {
                boolean margin = x < 0 || z < 0 || x >= sx || z >= sz;
                int gx = o.getX() + x;
                int gz = o.getZ() + z;
                int top = ground(level, gx, gz) - 1;
                int diff = Math.abs(top - floorY);
                if (diff > (margin ? MAX_LEVELLING + 1 : MAX_LEVELLING)) {
                    return null;
                }
                cost += diff;
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
            if (other != bot.memory() && other.bed() != null && other.bed().closerThan(o, 16)) {
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
            if (!Blueprint.isDone(kind, level.getBlockState(cell.pos()))) {
                missing.merge(kind, 1, Integer::sum);
            }
        }
        return missing;
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

    /** Already the right block for the plan (a log where a log goes...). */
    private boolean isPlanned(BlockPos pos, BlockState state) {
        for (Blueprint.Cell cell : plan.cells()) {
            if (cell.pos().equals(pos)) {
                return cell.kind() != '.' && Blueprint.isDone(cell.kind(), state);
            }
        }
        return false;
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
        stage = Stage.BUILD;
        if (!pillarLogging) {
            pillarLogging = true;
            bot.navigator().startPillarLog(); // pillars put up to reach the roof come down after
        }
        return Status.RUNNING;
    }

    // ---- building ---------------------------------------------------------------------------

    private Status build() {
        ServerLevel level = bot.level();
        for (Blueprint.Cell cell : plan.cells()) {
            char kind = cell.kind();
            if (kind == '.' || kind == 'B' || kind == 'T' || skipped.contains(cell.pos())) {
                continue;
            }
            BlockState state = level.getBlockState(cell.pos());
            if (Blueprint.isDone(kind, state)) {
                continue;
            }
            if (!state.canBeReplaced()) {
                if (isInTheWay(level, cell.pos(), state) || isOurScaffold(state)) {
                    return dig(cell.pos());
                }
                skip(cell.pos(), "something else is in the way");
                continue;
            }
            Predicate<ItemStack> material = Blueprint.material(kind);
            if (Inv.count(bot, material) == 0) {
                stage = Stage.MATERIALS; // ran out (some went into pillars and fills)
                return Status.RUNNING;
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
        Direction back = plan.front().getOpposite();
        ServerLevel level = bot.level();
        if (Blueprint.isDone('B', level.getBlockState(foot.pos()))) {
            stage = Stage.MOVE_TABLE;
            return Status.RUNNING;
        }
        if (Inv.count(bot, Blueprint.material('B')) == 0) {
            BlockPos old = bot.memory().bed();
            if (old != null && !skipped.contains(old) && HomeFinder.isBed(level, old) && !plan.inFootprint(old)) {
                keepHutAsWorkshop(); // (before the bed goes: without it the hut is no home, see Home.validate)
                HomeFinder.release(level, old);
                return pickUp(old);
            }
            child = new ObtainTask(bot, Target.tag(ItemTags.BEDS, 1), 0);
            return Status.RUNNING;
        }
        for (BlockPos cell : new BlockPos[] {foot.pos(), foot.pos().relative(back)}) {
            if (!level.getBlockState(cell).canBeReplaced()) {
                return dig(cell); // (a ladder, a torch, a leftover block where the bed goes: out first)
            }
        }
        return place(foot.pos(), Blueprint.material('B'), Direction.DOWN, back);
    }

    private Status moveTable() {
        Blueprint.Cell spot = cellOf('T');
        ServerLevel level = bot.level();
        if (Blueprint.isDone('T', level.getBlockState(spot.pos()))) {
            moveIn(spot.pos());
            stage = Stage.DONE;
            return Status.SUCCESS;
        }
        if (Inv.count(bot, Blueprint.material('T')) == 0) {
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
        return place(spot.pos(), Blueprint.material('T'), Direction.DOWN, null);
    }

    /** The hut stays as the workshop: its chests and furnace remain the bot's. */
    private void keepHutAsWorkshop() {
        BotMemory memory = bot.memory();
        if (memory.home() != null && memory.workshop() == null && memory.builtHome()) { // (a bed in someone else's house: that's no workshop of its own)
            memory.setWorkshop(memory.home().pos());
        }
    }

    private void moveIn(BlockPos table) {
        ServerLevel level = bot.level();
        BotMemory memory = bot.memory();
        BlockPos head = cellOf('B').pos().relative(plan.front().getOpposite());
        Home.returnBorrowed(bot); // (what it used in someone else's house stays there)
        keepHutAsWorkshop();
        memory.setHome(GlobalPos.of(level.dimension(), plan.center()), true);
        memory.setBed(head);
        memory.setCraftingTable(table);
        for (Blueprint.Cell cell : plan.cells()) {
            if (cell.kind() == 'H' && level.getBlockState(cell.pos()).is(Blocks.CHEST)) {
                memory.addChest(cell.pos());
            }
        }
        memory.setHouseDone(true);
        HomeFinder.claim(level, head);
        bot.useBed(head);
        bot.debug("moved into the new {} at {}", plan.template().name(), plan.origin().toShortString());
    }

    private Blueprint.Cell cellOf(char kind) {
        for (Blueprint.Cell cell : plan.cells()) {
            if (cell.kind() == kind) {
                return cell;
            }
        }
        throw new IllegalStateException("no " + kind + " in " + plan.template().name());
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
