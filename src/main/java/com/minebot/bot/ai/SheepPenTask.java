package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.BotRegistry;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.build.GreatBuild;
import com.minebot.bot.build.HousePlan;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.world.BlockSearch;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A sheep pen by its house, 6x6 inside a fence with a gate: for wool (the Great Build's orders ran on wool, and the
 * sheep round the houses had all been eaten) and for meat. It builds the pen, lures sheep in with wheat, breeds them up
 * to {@link #MAX_SHEEP}, shears them when wool is wanted (a sheep dyed first for a colour it's asked for: it gives that
 * colour from then on), and any over that number go for food. In and out by the gate, shut behind it; other bots' ways
 * don't go through pens, nor do their hunts take the sheep in them or round them.
 */
public class SheepPenTask extends Task {
    public static final int SIZE = 6;
    private static final int MIN_DISTANCE = 8;
    private static final int MAX_DISTANCE = 28;
    /** More grown sheep than this: one goes for food. */
    private static final int MAX_SHEEP = 8;
    /** Fewer sheep than this (grown and lambs): one more lured in. */
    private static final int STOCK = 3;
    /** How far from the pen sheep are fetched. */
    private static final int LURE_RANGE = 64;
    /** Wild sheep this close to a pen are left alive by hunters (to be lured in). */
    public static final int SPARE_RANGE = 48;
    /** White wool kept at home besides what the Great Build asks for (beds, carpets...). */
    private static final int WOOL_KEEP = 16;
    private static final int CHECK_TICKS = 20 * 60 * 3;
    private static final int MAX_TICKS = 20 * 60 * 6;
    private static final int TICKS_PER_BLOCK = 20 * 15;
    private static final int LURE_TICKS = 20 * 120;
    private static final long DAY = 24000L;

    /** When each bot next looks after its pen (after a round, whether it got done or not). */
    private static final Map<UUID, Long> NEXT = new ConcurrentHashMap<>();

    private enum Job { BUILD, LURE, BREED, DYE, SHEAR, CULL }

    private final BlockBreaker breaker;
    private @Nullable Job job;
    private @Nullable Task child;
    private int ticks;
    private final Set<BlockPos> skipped = new HashSet<>();
    private @Nullable BlockPos working;
    private int workingTicks;
    /** The sheep being lured, fed, dyed, shorn or killed. */
    private @Nullable Sheep sheep;
    private int sheepTicks;
    private boolean done;
    private boolean materialsTried;
    private int fed;

    public SheepPenTask(BotPlayer bot) {
        super(bot);
        this.breaker = new BlockBreaker(bot);
        this.job = job(bot);
        if (job != null) {
            bot.debug("sheep pen: {}", job.name().toLowerCase());
        }
    }

    public static boolean wanted(BotPlayer bot) {
        if (!Home.has(bot) || Home.levelIfHere(bot) == null || bot.level().dimension() != Level.OVERWORLD || Home.isNight(bot)
            || !Home.isNear(bot, 64) || GreatBuildTask.isAway(bot) || GreatBuildTask.sessionOn(bot)
            || bot.level().getGameTime() < NEXT.getOrDefault(bot.getUUID(), 0L)) {
            return false;
        }
        return bot.every("sheep pen", CHECK_TICKS, () -> job(bot) != null);
    }

    // ---- the pen ----------------------------------------------------------------------------

    /** Cells of the fence round the pen (the gate's among them). */
    private static List<BlockPos> ring(BlockPos pen) {
        List<BlockPos> cells = new ArrayList<>();
        for (int x = -1; x <= SIZE; x++) {
            for (int z = -1; z <= SIZE; z++) {
                if (x == -1 || z == -1 || x == SIZE || z == SIZE) {
                    cells.add(pen.offset(x, 0, z));
                }
            }
        }
        return cells;
    }

    private static List<BlockPos> inside(BlockPos pen) {
        List<BlockPos> cells = new ArrayList<>();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                cells.add(pen.offset(x, 0, z));
            }
        }
        return cells;
    }

    private static AABB box(BlockPos pen) {
        return new AABB(pen.getX(), pen.getY() - 1, pen.getZ(), pen.getX() + SIZE, pen.getY() + 3, pen.getZ() + SIZE);
    }

    /** Which way out through the gate. */
    private static Direction out(BlockPos pen, BlockPos gate) {
        if (gate.getZ() < pen.getZ()) {
            return Direction.NORTH;
        }
        if (gate.getZ() >= pen.getZ() + SIZE) {
            return Direction.SOUTH;
        }
        return gate.getX() < pen.getX() ? Direction.WEST : Direction.EAST;
    }

    private static boolean fenced(ServerLevel level, BlockPos pen, BlockPos gate) {
        for (BlockPos cell : ring(pen)) {
            if (!level.isLoaded(cell)) {
                return true; // (can't tell: as it was)
            }
            BlockState state = level.getBlockState(cell);
            if (!(cell.equals(gate) ? state.is(BlockTags.FENCE_GATES) : state.is(BlockTags.FENCES))) {
                return false;
            }
        }
        return true;
    }

    private static List<Sheep> sheepIn(ServerLevel level, BlockPos pen) {
        return level.getEntitiesOfClass(Sheep.class, box(pen), Sheep::isAlive);
    }

    // ---- other bots' sake --------------------------------------------------------------------

    /** Is this a pen's gate? (No way goes through one: the sheep would get out.) */
    public static boolean isGate(MinecraftServer server, int x, int y, int z) {
        for (BotMemory memory : BotRegistry.get(server).all()) {
            BlockPos gate = memory.penGate();
            if (gate != null && gate.getX() == x && gate.getY() == y && gate.getZ() == z) {
                return true;
            }
        }
        return false;
    }

    /** Is this in a pen, or (with a margin) round one? */
    public static boolean nearPen(ServerLevel level, BlockPos pos, int margin) {
        if (level.dimension() != Level.OVERWORLD) {
            return false;
        }
        for (BotMemory memory : BotRegistry.get(level.getServer()).all()) {
            BlockPos pen = memory.pen();
            if (pen != null && pos.getX() >= pen.getX() - margin && pos.getX() < pen.getX() + SIZE + margin
                && pos.getZ() >= pen.getZ() - margin && pos.getZ() < pen.getZ() + SIZE + margin
                && Math.abs(pos.getY() - pen.getY()) <= 3 + margin) {
                return true;
            }
        }
        return false;
    }

    // ---- what to do -------------------------------------------------------------------------

    private static @Nullable Job job(BotPlayer bot) {
        BotMemory memory = bot.memory();
        ServerLevel level = bot.level();
        BlockPos pen = memory.pen();
        BlockPos gate = memory.penGate();
        if (pen == null || gate == null) {
            return Job.BUILD;
        }
        if (!level.isLoaded(pen) || !level.isLoaded(pen.offset(SIZE, 0, SIZE))) {
            return null;
        }
        if (!fenced(level, pen, gate)) {
            return Job.BUILD;
        }
        List<Sheep> sheep = sheepIn(level, pen);
        List<Sheep> grown = sheep.stream().filter(s -> !s.isBaby()).toList();
        int wheat = have(bot, Items.WHEAT);
        if (sheep.size() < STOCK && wheat > 0 && wildSheep(bot, pen) != null) {
            return Job.LURE;
        }
        if (grown.size() > MAX_SHEEP) {
            return Job.CULL;
        }
        if (grown.size() >= 2 && sheep.size() < MAX_SHEEP && wheat >= 2 && grown.stream().filter(SheepPenTask::canBreed).count() >= 2) {
            return Job.BREED;
        }
        Map<DyeColor, Integer> wanted = woolWanted(bot);
        if (!wanted.isEmpty() && grown.stream().anyMatch(s -> !s.isSheared())) {
            if (toDye(bot, grown, wanted) != null) {
                return Job.DYE;
            }
            if (grown.stream().anyMatch(s -> !s.isSheared() && wanted.containsKey(s.getColor()))) {
                return Job.SHEAR;
            }
        }
        return null;
    }

    private static boolean canBreed(Sheep sheep) {
        return !sheep.isBaby() && sheep.getAge() == 0 && sheep.canFallInLove();
    }

    /** How many of an item it has: bag and chests. */
    private static int have(BotPlayer bot, Item item) {
        return Inv.count(bot, item) + ChestTask.stored(bot, Target.of(item, 1));
    }

    private static Item wool(DyeColor color) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(color.getSerializedName() + "_wool"));
    }

    private static Item dye(DyeColor color) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(color.getSerializedName() + "_dye"));
    }

    /** The wool it still wants, by colour: its Great Build order, and a little white for itself. */
    private static Map<DyeColor, Integer> woolWanted(BotPlayer bot) {
        Map<DyeColor, Integer> wanted = new LinkedHashMap<>();
        GreatBuild build = GreatBuild.get(bot.level().getServer());
        for (Map.Entry<Item, Integer> entry : build.orderOf(bot.getUUID()).entrySet()) {
            for (DyeColor color : DyeColor.values()) {
                if (entry.getKey() == wool(color)) {
                    int left = entry.getValue() - have(bot, entry.getKey());
                    if (left > 0) {
                        wanted.merge(color, left, Integer::sum);
                    }
                }
            }
        }
        int white = have(bot, Items.WHITE_WOOL);
        if (white < WOOL_KEEP) {
            wanted.merge(DyeColor.WHITE, WOOL_KEEP - white, Integer::sum);
        }
        return wanted;
    }

    /**
     * A sheep to dye: a colour it's asked for that none of its sheep has yet, a dye of it in the bag, and a woolly
     * sheep of a colour not wanted (white wanted too: one white is always kept).
     */
    private static @Nullable Sheep toDye(BotPlayer bot, List<Sheep> grown, Map<DyeColor, Integer> wanted) {
        for (DyeColor color : wanted.keySet()) {
            if (color == DyeColor.WHITE || grown.stream().anyMatch(s -> s.getColor() == color)
                || Inv.count(bot, dye(color)) == 0) {
                continue;
            }
            long whites = grown.stream().filter(s -> s.getColor() == DyeColor.WHITE).count();
            for (Sheep sheep : grown) {
                if (!sheep.isSheared() && sheep.getColor() != color
                    && (!wanted.containsKey(sheep.getColor()) || sheep.getColor() == DyeColor.WHITE && whites > 1)) {
                    return sheep;
                }
            }
        }
        return null;
    }

    /** The nearest grown sheep out in the open (no one's pet, in no pen) within reach of the pen, or null. */
    private static @Nullable Sheep wildSheep(BotPlayer bot, BlockPos pen) {
        ServerLevel level = bot.level();
        return level.getEntitiesOfClass(Sheep.class, new AABB(pen).inflate(LURE_RANGE, 16, LURE_RANGE),
                sheep -> sheep.isAlive() && !sheep.isBaby() && !sheep.hasCustomName() && !((Mob) sheep).isLeashed()
                    && !nearPen(level, sheep.blockPosition(), 0) && !ProtectedAreas.isProtected(level, sheep.blockPosition()))
            .stream()
            .min(Comparator.comparingDouble(sheep -> sheep.distanceToSqr(Vec3.atCenterOf(pen))))
            .orElse(null);
    }

    // ---- doing it -----------------------------------------------------------------------------

    @Override
    public Status tick() {
        if (child != null) {
            Status status = child.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            child.stop();
            child = null;
            if (status == Status.FAILURE && !materialsTried) {
                return finish(Status.FAILURE, DAY / 4); // (no fences, no wheat, no shears to be had: later)
            }
        }
        if (job == null) {
            return finish(Status.SUCCESS, CHECK_TICKS);
        }
        if (++ticks > MAX_TICKS) {
            bot.debug("sheep pen: {} took too long", job.name().toLowerCase());
            return finish(Status.FAILURE, CHECK_TICKS);
        }
        return switch (job) {
            case BUILD -> build();
            case LURE -> lure();
            case BREED, DYE, SHEAR, CULL -> tend();
        };
    }

    private Status finish(Status status, long pause) {
        bot.navigator().stop();
        breaker.cancel();
        NEXT.put(bot.getUUID(), bot.level().getGameTime() + pause);
        bot.every("sheep pen", 0, () -> false); // (looked at afresh next time)
        return status;
    }

    private boolean steady() {
        return bot.onGround() || bot.isInWater() || bot.onClimbable() || bot.getVehicle() != null;
    }

    private boolean track(BlockPos pos) {
        if (!pos.equals(working)) {
            working = pos;
            workingTicks = 0;
        }
        if (++workingTicks > TICKS_PER_BLOCK) {
            bot.debug("sheep pen: leaving out {}", pos.toShortString());
            skipped.add(pos);
            working = null;
            bot.navigator().stop();
            return false;
        }
        return true;
    }

    /** Close enough to work on this block, standing; else on its way. */
    private boolean reach(BlockPos pos) {
        if (bot.isWithinBlockInteractionRange(pos, 0.0) && steady()) {
            bot.navigator().stop();
            return true;
        }
        if (!bot.navigator().isActive()) {
            bot.navigator().navigate(Goal.reach(pos));
        }
        bot.navigator().tick();
        return false;
    }

    // ---- building ---------------------------------------------------------------------------

    private Status build() {
        ServerLevel level = bot.level();
        BotMemory memory = bot.memory();
        if (memory.pen() == null || memory.penGate() == null) {
            Site site = findSite();
            if (site == null) {
                bot.debug("sheep pen: no room for one by the house");
                return finish(Status.FAILURE, DAY);
            }
            memory.setPen(site.pen(), site.gate());
            bot.debug("sheep pen: at {}, the gate at {}", site.pen().toShortString(), site.gate().toShortString());
        }
        BlockPos pen = memory.pen();
        BlockPos gate = memory.penGate();
        Direction out = out(pen, gate);
        // What it takes: fences and a gate, and dirt for any holes
        int fences = 0;
        for (BlockPos cell : ring(pen)) {
            if (!cell.equals(gate) && !level.getBlockState(cell).is(BlockTags.FENCES) && !skipped.contains(cell)) {
                fences++;
            }
        }
        boolean gateNeeded = !level.getBlockState(gate).is(BlockTags.FENCE_GATES);
        if (!materialsTried) {
            if (Inv.count(bot, stack -> stack.is(ItemTags.WOODEN_FENCES)) < fences) {
                child = new ObtainTask(bot, Target.tag(ItemTags.WOODEN_FENCES, fences), 0);
                return Status.RUNNING;
            }
            if (gateNeeded && Inv.count(bot, stack -> stack.is(ItemTags.FENCE_GATES)) == 0) {
                child = new ObtainTask(bot, Target.tag(ItemTags.FENCE_GATES, 1), 0);
                return Status.RUNNING;
            }
            materialsTried = true;
        }
        // Ground to stand on under the inside, the fence and the step in front of the gate; nothing in the way above it
        List<BlockPos> ground = new ArrayList<>(inside(pen));
        ground.addAll(ring(pen));
        ground.add(gate.relative(out));
        for (BlockPos cell : ground) {
            if (skipped.contains(cell)) {
                continue;
            }
            BlockPos below = cell.below();
            if (level.getBlockState(below).canBeReplaced() && !skipped.contains(below)) {
                if (Inv.count(bot, stack -> stack.is(Items.DIRT)) == 0) {
                    child = new ObtainTask(bot, Target.of(Items.DIRT, 8), 0);
                    return Status.RUNNING;
                }
                if (track(below) && reach(below) && !BlockPlacer.place(bot, below, stack -> stack.is(Items.DIRT))) {
                    stepAside(below);
                }
                return Status.RUNNING;
            }
            for (BlockPos clear : new BlockPos[] {cell, cell.above()}) {
                BlockState state = level.getBlockState(clear);
                if (state.canBeReplaced() || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES) || skipped.contains(clear)) {
                    continue;
                }
                if (!BlockRules.canBreak(level, clear, state) || BlockRules.isBuilt(state)) {
                    skipped.add(clear);
                    continue;
                }
                if (track(clear) && reach(clear)) {
                    if (!breaker.isBreaking(clear)) {
                        breaker.start(clear);
                    }
                    if (breaker.tick() == BlockBreaker.Result.FAILED) {
                        skipped.add(clear);
                    }
                }
                return Status.RUNNING;
            }
        }
        // The gate first (a way out once the fence is round it), then the fence
        List<BlockPos> order = new ArrayList<>();
        order.add(gate);
        for (BlockPos cell : ring(pen)) {
            if (!cell.equals(gate)) {
                order.add(cell);
            }
        }
        for (BlockPos cell : order) {
            BlockState state = level.getBlockState(cell);
            boolean isGate = cell.equals(gate);
            if ((isGate ? state.is(BlockTags.FENCE_GATES) : state.is(BlockTags.FENCES)) || skipped.contains(cell)) {
                continue;
            }
            if (!track(cell) || !reach(cell)) {
                return Status.RUNNING;
            }
            if (bot.getBoundingBox().intersects(new AABB(cell))) {
                stepAside(cell);
                return Status.RUNNING;
            }
            boolean placed = isGate
                ? BlockPlacer.place(bot, cell, stack -> stack.is(ItemTags.FENCE_GATES), null, out)
                : BlockPlacer.place(bot, cell, stack -> stack.is(ItemTags.WOODEN_FENCES));
            if (!placed && Inv.count(bot, stack -> stack.is(isGate ? ItemTags.FENCE_GATES : ItemTags.WOODEN_FENCES)) == 0) {
                bot.debug("sheep pen: out of {}", isGate ? "gates" : "fences");
                return finish(Status.FAILURE, CHECK_TICKS);
            }
            return Status.RUNNING;
        }
        // Done: out of it, the gate shut
        if (!leave()) {
            return Status.RUNNING;
        }
        bot.debug("sheep pen: built at {}", pen.toShortString());
        return finish(Status.SUCCESS, 20 * 10);
    }

    /** Standing where a block goes: a step towards the middle of the pen (or out of it, from the gate). */
    private void stepAside(BlockPos cell) {
        BlockPos pen = bot.memory().pen();
        Vec3 middle = pen != null ? Vec3.atBottomCenterOf(pen.offset(SIZE / 2, 0, SIZE / 2)) : bot.position().add(1, 0, 0);
        bot.navigator().stop();
        bot.controller().moveTowards(middle, false, false);
    }

    private record Site(BlockPos pen, BlockPos gate) {
    }

    /** A place for the pen (the inside's north-west corner, and the gate), or null. */
    private @Nullable Site findSite() {
        ServerLevel level = bot.level();
        BotMemory memory = bot.memory();
        BlockPos home = memory.home().pos();
        int outer = SIZE + 2;
        List<BlockPos> built = BlockSearch.find(level, home, MAX_DISTANCE + outer + 4, home.getY() - 8, home.getY() + 12,
            BlockRules::isBuilt, (pos, state) -> true, 4000);
        List<BlockPos> keepOff = new ArrayList<>();
        var house = memory.houseOrigin() != null ? com.minebot.bot.build.HousePlans.of(memory) : null;
        Site best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -MAX_DISTANCE; dx <= MAX_DISTANCE; dx += 2) {
            for (int dz = -MAX_DISTANCE; dz <= MAX_DISTANCE; dz += 2) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < MIN_DISTANCE || distance > MAX_DISTANCE) {
                    continue;
                }
                int x0 = home.getX() + dx - outer / 2;
                int z0 = home.getZ() + dz - outer / 2;
                Integer y = flat(level, x0, z0, outer);
                if (y == null) {
                    continue;
                }
                if (blocked(x0, z0, outer, built, house)) {
                    continue;
                }
                double score = distance + bumpiness * 3;
                if (score < bestScore) {
                    bestScore = score;
                    BlockPos pen = new BlockPos(x0 + 1, y + 1, z0 + 1);
                    best = new Site(pen, gateFacing(pen, home));
                }
            }
        }
        return best;
    }

    private int bumpiness;

    /** The ground height of a square (its top block) if it's flat enough, dry, open earth in this bot's zone; else null. */
    private @Nullable Integer flat(ServerLevel level, int x0, int z0, int outer) {
        for (int[] c : new int[][] {{0, 0}, {outer - 1, 0}, {0, outer - 1}, {outer - 1, outer - 1}}) {
            if (level.getChunkSource().getChunkNow((x0 + c[0]) >> 4, (z0 + c[1]) >> 4) == null) {
                return null;
            }
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + outer / 2, z0 + outer / 2) - 1;
        bumpiness = 0;
        for (int dx = 0; dx < outer; dx++) {
            for (int dz = 0; dz < outer; dz++) {
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + dx, z0 + dz) - 1;
                if (Math.abs(top - y) > 1) {
                    return null;
                }
                bumpiness += Math.abs(top - y);
                BlockPos ground = new BlockPos(x0 + dx, top, z0 + dz);
                BlockState state = level.getBlockState(ground);
                boolean inner = dx > 0 && dz > 0 && dx < outer - 1 && dz < outer - 1;
                if (inner && !state.is(BlockTags.DIRT) || !level.getFluidState(ground).isEmpty()
                    || !level.getFluidState(ground.above()).isEmpty() || state.is(Blocks.FARMLAND)
                    || ProtectedAreas.isProtected(level, ground) || !bot.memory().inZone(level.dimension(), ground)
                    || level.getBlockState(ground.above()).is(BlockTags.LOGS)) {
                    return null; // (grass inside for the sheep to eat; no water, field, tree)
                }
            }
        }
        return y;
    }

    /** Too close to a building, a field, or its house (built or to be)? */
    private boolean blocked(int x0, int z0, int outer, List<BlockPos> built, @Nullable HousePlan house) {
        int gap = 2;
        for (BlockPos pos : built) {
            if (pos.getX() >= x0 - gap && pos.getX() < x0 + outer + gap && pos.getZ() >= z0 - gap && pos.getZ() < z0 + outer + gap) {
                return true;
            }
        }
        for (BotMemory any : BotRegistry.get(bot.level().getServer()).all()) {
            for (BotMemory.Farm farm : any.farms()) {
                BlockPos o = farm.origin();
                if (o.getX() - gap < x0 + outer && x0 < o.getX() + farm.size() + gap
                    && o.getZ() - gap < z0 + outer && z0 < o.getZ() + farm.size() + gap) {
                    return true;
                }
            }
        }
        if (house != null) {
            BlockPos o = house.origin();
            int margin = 3;
            return o.getX() - margin < x0 + outer && x0 < o.getX() + house.sizeX() + margin
                && o.getZ() - margin < z0 + outer && z0 < o.getZ() + house.sizeZ() + margin;
        }
        return false;
    }

    /** The gate: the middle of the side that faces the house. */
    private static BlockPos gateFacing(BlockPos pen, BlockPos home) {
        int dx = home.getX() - (pen.getX() + SIZE / 2);
        int dz = home.getZ() - (pen.getZ() + SIZE / 2);
        if (Math.abs(dx) >= Math.abs(dz)) {
            return pen.offset(dx > 0 ? SIZE : -1, 0, SIZE / 2);
        }
        return pen.offset(SIZE / 2, 0, dz > 0 ? SIZE : -1);
    }

    // ---- in and out by the gate --------------------------------------------------------------

    private boolean isInside() {
        BlockPos pen = bot.memory().pen();
        BlockPos feet = bot.blockPosition();
        return pen != null && feet.getX() >= pen.getX() && feet.getX() < pen.getX() + SIZE
            && feet.getZ() >= pen.getZ() && feet.getZ() < pen.getZ() + SIZE && Math.abs(feet.getY() - pen.getY()) <= 2;
    }

    private boolean gateOpen() {
        BlockState state = bot.level().getBlockState(bot.memory().penGate());
        return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
    }

    private void useGate() {
        BlockPos gate = bot.memory().penGate();
        Vec3 hit = Vec3.atCenterOf(gate);
        bot.controller().lookAt(hit);
        bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND,
            new BlockHitResult(hit, Direction.UP, gate, false));
        bot.swing(InteractionHand.MAIN_HAND);
    }

    private boolean inGateway() {
        return bot.getBoundingBox().intersects(new AABB(bot.memory().penGate()));
    }

    /** Through the gate from one side to the other, shut behind it; true once there. */
    private boolean pass(boolean in) {
        BlockPos gate = bot.memory().penGate();
        Direction out = out(bot.memory().pen(), gate);
        BlockPos from = gate.relative(in ? out : out.getOpposite());
        BlockPos to = gate.relative(in ? out.getOpposite() : out);
        if (isInside() == in && !inGateway()) {
            if (gateOpen()) {
                bot.controller().releaseInputs();
                useGate(); // (shut behind it)
            }
            return true;
        }
        boolean atGate = inGateway() || bot.blockPosition().equals(from)
            || bot.position().distanceTo(Vec3.atBottomCenterOf(from)) < 0.8;
        if (!atGate) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.near(from, 0.6));
            }
            bot.navigator().tick();
            return false;
        }
        bot.navigator().stop();
        if (!gateOpen()) {
            bot.controller().releaseInputs();
            useGate();
            return false;
        }
        bot.controller().moveTowards(Vec3.atBottomCenterOf(to), false, false);
        return false;
    }

    private boolean enter() {
        return pass(true);
    }

    private boolean leave() {
        if (!isInside() && !inGateway()) {
            if (gateOpen() && bot.position().distanceTo(Vec3.atCenterOf(bot.memory().penGate())) < 4) {
                useGate(); // (left open: shut)
            }
            return true;
        }
        return pass(false);
    }

    // ---- fetching sheep ---------------------------------------------------------------------

    private Status lure() {
        BlockPos pen = bot.memory().pen();
        if (Inv.count(bot, Items.WHEAT) == 0) {
            if (materialsTried) {
                return finish(Status.FAILURE, CHECK_TICKS);
            }
            materialsTried = true;
            child = ChestTask.withdraw(bot, Target.of(Items.WHEAT, 4));
            return Status.RUNNING;
        }
        if (sheep != null && (!sheep.isAlive() || sheep.isBaby())) {
            sheep = null;
        }
        if (sheep != null && box(pen).contains(sheep.position())) {
            // In: the wheat away (or it follows back out), out by the gate, shut
            Inv.select(bot, stack -> !stack.isEmpty() && !stack.is(Items.WHEAT));
            if (!leave()) {
                return Status.RUNNING;
            }
            bot.debug("sheep pen: brought a sheep in");
            return finish(Status.SUCCESS, 20 * 5);
        }
        if (sheep == null || ++sheepTicks > LURE_TICKS) {
            if (isInside() && !leave()) {
                return Status.RUNNING;
            }
            sheep = wildSheep(bot, pen);
            sheepTicks = 0;
            if (sheep == null || done) {
                bot.debug("sheep pen: no sheep to bring in");
                return finish(Status.FAILURE, CHECK_TICKS);
            }
            done = true; // (one try at another, then later)
        }
        Inv.select(bot, stack -> stack.is(Items.WHEAT)); // (they follow wheat in the hand)
        double distance = bot.distanceTo(sheep);
        boolean following = distance < 6;
        if (!following && !isInside() && !inGateway()) {
            // Off to it, wheat in hand, till it's close and following
            if (!bot.navigator().isActive() || bot.navigator().goal() == null) {
                bot.navigator().navigate(Goal.near(sheep.blockPosition(), 2.5));
            }
            bot.navigator().tick();
            return Status.RUNNING;
        }
        if (distance > 4 && !isInside()) {
            // Waiting for it to catch up, facing it
            bot.navigator().stop();
            bot.controller().lookAt(sheep.getEyePosition());
            return Status.RUNNING;
        }
        if (!isInside()) {
            enter();
            return Status.RUNNING;
        }
        // In the pen: to the far side, so it comes in after
        BlockPos gate = bot.memory().penGate();
        Direction out = out(pen, gate);
        BlockPos far = gate.relative(out.getOpposite(), SIZE - 1);
        if (bot.position().distanceTo(Vec3.atBottomCenterOf(far)) > 1.0) {
            bot.controller().moveTowards(Vec3.atBottomCenterOf(far), false, false);
        } else {
            bot.controller().releaseInputs();
            bot.controller().lookAt(sheep.getEyePosition());
        }
        return Status.RUNNING;
    }

    // ---- breeding, dyeing, shearing, the pot ----------------------------------------------------

    private Status tend() {
        BlockPos pen = bot.memory().pen();
        if (job == Job.SHEAR && Inv.count(bot, Items.SHEARS) == 0) {
            if (materialsTried) {
                return finish(Status.FAILURE, DAY / 4);
            }
            materialsTried = true;
            child = new ObtainTask(bot, Target.of(Items.SHEARS, 1), 0);
            return Status.RUNNING;
        }
        if (job == Job.BREED && Inv.count(bot, Items.WHEAT) < 2) {
            if (materialsTried) {
                return finish(Status.FAILURE, CHECK_TICKS);
            }
            materialsTried = true;
            child = ChestTask.withdraw(bot, Target.of(Items.WHEAT, 8));
            return Status.RUNNING;
        }
        if (!enter()) {
            return Status.RUNNING;
        }
        if (sheep == null || !sheep.isAlive() || !box(pen).contains(sheep.position()) || ++sheepTicks > 20 * 30) {
            sheep = next(pen);
            sheepTicks = 0;
            if (sheep == null) {
                if (job == Job.SHEAR || job == Job.CULL) {
                    // Wool and meat lying about the pen: picked up before going
                    if (!done) {
                        done = true;
                        child = new CollectItemsTask(bot, Vec3.atBottomCenterOf(pen.offset(SIZE / 2, 0, SIZE / 2)), SIZE, 20 * 8);
                        return Status.RUNNING;
                    }
                }
                if (!leave()) {
                    return Status.RUNNING;
                }
                return finish(Status.SUCCESS, 20 * 10);
            }
        }
        if (bot.distanceTo(sheep) > 2.2) {
            if (!bot.navigator().isActive() || sheepTicks % 20 == 0) {
                bot.navigator().navigate(Goal.near(sheep.blockPosition(), 1.5));
            }
            bot.navigator().tick();
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().lookAt(sheep.getEyePosition());
        switch (job) {
            case BREED -> {
                Inv.select(bot, stack -> stack.is(Items.WHEAT));
                bot.interactOn(sheep, InteractionHand.MAIN_HAND);
                if (sheep.isInLove()) {
                    fed++;
                }
                sheep = null;
                if (fed >= 2) {
                    bot.debug("sheep pen: two sheep fed to breed");
                    job = Job.SHEAR; // (nothing more to feed: on round to any to shear, else out)
                    if (woolWanted(bot).isEmpty()) {
                        done = true;
                    }
                }
            }
            case DYE -> {
                DyeColor color = dyeFor(sheep);
                if (color != null && Inv.select(bot, stack -> stack.is(dye(color)))) {
                    bot.interactOn(sheep, InteractionHand.MAIN_HAND);
                    bot.debug("sheep pen: dyed a sheep {}", color.getSerializedName());
                }
                sheep = null;
                job = Job.SHEAR; // (and shorn now, its new colour)
            }
            case SHEAR -> {
                Inv.select(bot, stack -> stack.is(Items.SHEARS));
                bot.interactOn(sheep, InteractionHand.MAIN_HAND);
                bot.swing(InteractionHand.MAIN_HAND);
                sheep = null;
                done = false; // (wool on the ground to pick up after)
            }
            case CULL -> {
                bot.combat().selectMeleeWeapon();
                bot.combat().strike(sheep);
                if (!sheep.isAlive()) {
                    sheep = null;
                    done = false;
                }
            }
            default -> {
            }
        }
        return Status.RUNNING;
    }

    /** The colour this sheep is to be dyed (see {@link #toDye}). */
    private @Nullable DyeColor dyeFor(Sheep sheep) {
        Map<DyeColor, Integer> wanted = woolWanted(bot);
        List<Sheep> grown = sheepIn(bot.level(), bot.memory().pen()).stream().filter(s -> !s.isBaby()).toList();
        for (DyeColor color : wanted.keySet()) {
            if (color != DyeColor.WHITE && grown.stream().noneMatch(s -> s.getColor() == color) && Inv.count(bot, dye(color)) > 0) {
                return color;
            }
        }
        return null;
    }

    /** The next sheep in the pen for the job, or null when there's none left to do. */
    private @Nullable Sheep next(BlockPos pen) {
        List<Sheep> sheep = sheepIn(bot.level(), pen);
        List<Sheep> grown = sheep.stream().filter(s -> !s.isBaby()).toList();
        return switch (job) {
            case BREED -> grown.stream().filter(SheepPenTask::canBreed).min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
            case DYE -> toDye(bot, grown, woolWanted(bot));
            case SHEAR -> {
                Map<DyeColor, Integer> wanted = woolWanted(bot);
                yield grown.stream().filter(s -> !s.isSheared() && wanted.containsKey(s.getColor()))
                    .min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
            }
            case CULL -> grown.size() <= MAX_SHEEP ? null : grown.stream()
                // (the plainest go first: shorn, then white - a dyed one is worth keeping)
                .min(Comparator.comparingInt((Sheep s) -> (s.isSheared() ? 0 : 2) + (s.getColor() == DyeColor.WHITE ? 0 : 1))
                    .thenComparingDouble(bot::distanceToSqr))
                .orElse(null);
            default -> null;
        };
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        breaker.cancel();
        if (child != null) {
            child.stop();
        }
        if (bot.memory().penGate() != null && gateOpen() && !inGateway()
            && bot.position().distanceTo(Vec3.atCenterOf(bot.memory().penGate())) < 4) {
            useGate(); // (called away: the gate isn't left open)
        }
    }

    @Override
    public @Nullable Target wanted() {
        return child != null ? child.wanted() : null;
    }

    @Override
    public String describe() {
        String what = job == null ? "seeing to its sheep pen" : switch (job) {
            case BUILD -> "building a sheep pen";
            case LURE -> "bringing a sheep into its pen";
            case BREED -> "breeding its sheep";
            case DYE -> "dyeing a sheep";
            case SHEAR -> "shearing its sheep";
            case CULL -> "taking a sheep from its pen for food";
        };
        return child != null ? what + ": " + child.describe() : what;
    }
}
