package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.BotRegistry;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Small pits in the ground it comes past (a block or two dug out, a hole left by
 * someone): filled in with dirt, flush with the ground around. Only little ones
 * (1-3 columns, up to 3 deep) in open natural ground; never by a building, a
 * field or water, nor in protected places.
 */
public class FillHoleTask extends Task {
    private static final int RADIUS = 24;
    private static final int MAX_COLUMNS = 3;
    private static final int MAX_DEPTH = 3;
    private static final int CHECK_INTERVAL = 20 * 30;
    private static final int MAX_TICKS = 20 * 40;

    /** Pits each bot gave up on: not tried again. */
    private static final Map<UUID, Set<BlockPos>> GIVEN_UP = new HashMap<>();

    /** Forgotten when the bot leaves the server: back again, it may try those once more. */
    public static void forget(UUID bot) {
        GIVEN_UP.remove(bot);
    }

    private final List<BlockPos> cells;
    private final @Nullable BlockPos key;
    /** A bump to dig away (cells are solid blocks, top first) rather than a pit to fill. */
    private final boolean bump;
    private final boolean earthy;
    private final com.minebot.bot.action.BlockBreaker breaker;
    private @Nullable Task collect;
    /** Getting dirt for a pit: out of the chests, or picking it up off a terrace edge. */
    private @Nullable Task fetch;
    private @Nullable BlockPos donorBlock;
    private int dirtTries;
    private int ticks;
    private int failures;

    public FillHoleTask(BotPlayer bot) {
        super(bot);
        Spot found = find(bot);
        this.cells = found != null ? new ArrayList<>(found.blocks()) : new ArrayList<>();
        this.key = found != null ? key(found.blocks()) : null;
        this.bump = found != null && found.bump();
        this.earthy = found != null && found.earthy();
        this.breaker = new com.minebot.bot.action.BlockBreaker(bot);
        // A pit bottom up (every block has one under it), a bump top down
        Comparator<BlockPos> upwards = Comparator.comparingInt(BlockPos::getY);
        cells.sort(bump ? upwards.reversed() : upwards);
        if (found != null) {
            bot.debug("levelling: {} at {} ({} blocks)", bump ? "a bump" : "a pit", cells.get(0).toShortString(), cells.size());
        }
    }

    public static boolean wanted(BotPlayer bot) {
        return bot.every("fill holes", CHECK_INTERVAL, () -> {
            Spot spot = find(bot);
            return spot != null;
        });
    }

    private static boolean isFill(ItemStack stack) {
        return stack.is(Items.DIRT) || stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE);
    }

    /** No pit filled, no dirt taken, this close to a building (anyone's): its surroundings are left as they are. */
    private static final int BUILDING_DISTANCE = 6;

    /**
     * Columns within {@link #BUILDING_DISTANCE} of a built block (a house, a hut, a fence...) around
     * {@code center}: looked for once, near the ground, so the search stays cheap.
     */
    private static Set<Long> nearBuildings(ServerLevel level, BlockPos center) {
        Set<Long> near = new HashSet<>();
        int reach = RADIUS + BUILDING_DISTANCE;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                    continue;
                }
                int h = height(level, x, z);
                boolean built = false;
                for (int y = h - 2; y <= h + 2 && !built; y++) {
                    built = BlockRules.isBuilt(level.getBlockState(new BlockPos(x, y, z)));
                }
                if (!built) {
                    continue;
                }
                for (int ox = -BUILDING_DISTANCE; ox <= BUILDING_DISTANCE; ox++) {
                    for (int oz = -BUILDING_DISTANCE; oz <= BUILDING_DISTANCE; oz++) {
                        near.add(columnKey(x + ox, z + oz));
                    }
                }
            }
        }
        return near;
    }

    private static int height(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /**
     * A pit (air blocks to fill) or a bump (blocks to dig away). {@code earthy}: a pit in dirt
     * (filled with dirt only), else in rock (cobblestone does).
     */
    private record Spot(List<BlockPos> blocks, boolean bump, boolean earthy) {
    }

    /** What fills this pit: dirt in dirt; in rock cobblestone (or dirt if that's all it has). */
    private static Predicate<ItemStack> fillFor(BotPlayer bot, boolean earthy) {
        if (earthy) {
            return stack -> stack.is(Items.DIRT);
        }
        Predicate<ItemStack> rock = stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE);
        return Inv.count(bot, rock) > 0 ? rock : FillHoleTask::isFill;
    }

    /** Is the ground round this pit mostly dirt, sand, gravel (rather than rock)? */
    private static boolean isEarthy(ServerLevel level, List<BlockPos> pit) {
        int soil = 0;
        int rock = 0;
        for (BlockPos pos : pit) {
            for (Direction direction : Direction.values()) {
                if (direction == Direction.UP) {
                    continue;
                }
                BlockState state = level.getBlockState(pos.relative(direction));
                if (state.is(BlockTags.BASE_STONE_OVERWORLD)) {
                    rock++;
                } else if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY)) {
                    soil++;
                }
            }
        }
        return soil >= rock;
    }

    /** The nearest little pit or bump around the bot, or null. */
    private static @Nullable Spot find(BotPlayer bot) {
        ServerLevel level = bot.level();
        BlockPos center = bot.blockPosition();
        Set<BlockPos> givenUp = GIVEN_UP.getOrDefault(bot.getUUID(), Set.of());
        Set<Long> seen = new HashSet<>();
        Spot best = null;
        double bestDistance = Double.MAX_VALUE;
        Boolean dirtToHad = null;
        Set<Long> nearBuildings = nearBuildings(level, center);
        List<BoundingBox> fields = fields(level);
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null || seen.contains(columnKey(x, z))) {
                    continue;
                }
                List<BlockPos> pit = pitAt(level, x, z, seen);
                Spot spot = pit != null ? new Spot(pit, false, isEarthy(level, pit)) : bumpAt(level, x, z, seen);
                if (spot == null) {
                    spot = leftoverAt(level, x, z);
                }
                if (spot != null && spot.bump() && nearBuildings.contains(columnKey(x, z))) {
                    continue; // (a pit by a building is filled; nothing is dug away there)
                }
                if (spot != null && !spot.bump() && Inv.count(bot, fillFor(bot, spot.earthy())) == 0) {
                    // Nothing right to fill it with (a pit in dirt only gets dirt): unless there's dirt to be had
                    if (!spot.earthy()) {
                        continue;
                    }
                    if (dirtToHad == null) {
                        dirtToHad = storedDirt(bot) > 0 || donor(bot) != null;
                    }
                    if (!dirtToHad) {
                        continue;
                    }
                }
                if (spot == null || givenUp.contains(key(spot.blocks())) || ProtectedAreas.isProtected(level, spot.blocks().get(0))
                    || spot.blocks().stream().anyMatch(block -> inField(fields, block))) {
                    continue; // (in a field: the water hole in its middle, unfinished rows)
                }
                double distance = spot.blocks().get(0).distSqr(center);
                if (distance < bestDistance) {
                    best = spot;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    /** The same pit however it was found: its lowest-numbered block. */
    private static BlockPos key(List<BlockPos> pit) {
        return pit.stream().min(Comparator.comparingLong(BlockPos::asLong)).orElseThrow();
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /**
     * The pit this column is part of (its columns grouped, lower than the ground right
     * around them), as the air blocks to fill; null if it isn't a little natural pit.
     */
    private static @Nullable List<BlockPos> pitAt(ServerLevel level, int x, int z, Set<Long> seen) {
        // Columns lower than some neighbour, grouped; the rim is the lowest ground around the group
        List<int[]> columns = new ArrayList<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        Set<Long> inPit = new HashSet<>();
        int[] start = {x, z};
        if (!isLowerThanANeighbour(level, x, z)) {
            return null;
        }
        queue.add(start);
        inPit.add(columnKey(x, z));
        int rim = Integer.MAX_VALUE;
        while (!queue.isEmpty()) {
            int[] column = queue.poll();
            columns.add(column);
            if (columns.size() > MAX_COLUMNS) {
                return null; // a wider dip (a valley, a pond bed...): not a pit
            }
            int h = height(level, column[0], column[1]);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                int nx = column[0] + direction.getStepX();
                int nz = column[1] + direction.getStepZ();
                if (inPit.contains(columnKey(nx, nz))) {
                    continue;
                }
                if (level.getChunkSource().getChunkNow(nx >> 4, nz >> 4) == null) {
                    return null;
                }
                int nh = height(level, nx, nz);
                if (nh <= h) {
                    // As low or lower: part of the pit
                    inPit.add(columnKey(nx, nz));
                    queue.add(new int[] {nx, nz});
                } else {
                    rim = Math.min(rim, nh);
                }
            }
        }
        seen.addAll(inPit);
        List<BlockPos> air = new ArrayList<>();
        for (int[] column : columns) {
            int h = height(level, column[0], column[1]);
            if (rim - h > MAX_DEPTH || rim <= h) {
                return null;
            }
            for (int y = h; y < rim; y++) {
                BlockPos pos = new BlockPos(column[0], y, column[1]);
                BlockState state = level.getBlockState(pos);
                if (!state.isAir() && !state.canBeReplaced() || !state.getFluidState().isEmpty()) {
                    return null; // water, lava, something standing in it
                }
                air.add(pos);
            }
            // The ground around it natural, nothing built or farmed close by; under it natural ground,
            // or the cobblestone a bot leaves at the bottom of a hole it climbed out of
            if (!isPitFloor(level.getBlockState(new BlockPos(column[0], h - 1, column[1])))) {
                return null;
            }
            for (int y = h - 1; y < rim; y++) {
                for (Direction direction : Direction.values()) {
                    BlockPos near = new BlockPos(column[0], y, column[1]).relative(direction);
                    if (inPit.contains(columnKey(near.getX(), near.getZ())) && direction != Direction.DOWN) {
                        continue;
                    }
                    BlockState state = level.getBlockState(near);
                    if (state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty()) {
                        continue;
                    }
                    if (direction == Direction.DOWN ? !isPitFloor(state) : !isNaturalGround(state)) {
                        return null;
                    }
                }
            }
        }
        return air.isEmpty() ? null : air;
    }

    private static boolean isPitFloor(BlockState state) {
        return isNaturalGround(state) || isScaffoldBlock(state);
    }

    /** Every bot's fields here, a block round them too: no levelling there, no dirt taken. */
    private static List<BoundingBox> fields(ServerLevel level) {
        List<BoundingBox> fields = new ArrayList<>();
        for (BotMemory memory : BotRegistry.get(level.getServer()).all()) {
            if (memory.homeDimension() != level.dimension()) {
                continue;
            }
            for (BotMemory.Farm farm : memory.farms()) {
                BlockPos origin = farm.origin();
                fields.add(new BoundingBox(origin.getX() - 1, origin.getY() - 4, origin.getZ() - 1,
                    origin.getX() + farm.size(), origin.getY() + 4, origin.getZ() + farm.size()));
            }
        }
        return fields;
    }

    private static boolean inField(List<BoundingBox> fields, BlockPos pos) {
        for (BoundingBox field : fields) {
            if (field.isInside(pos)) {
                return true;
            }
        }
        return false;
    }

    /** What bots stand on and leave behind: cobblestone, dirt and the like. */
    private static boolean isScaffoldBlock(BlockState state) {
        return state.is(Blocks.COBBLESTONE) || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.DIRT)
            || state.is(Blocks.NETHERRACK) || state.is(Blocks.ANDESITE) || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE);
    }

    private static boolean isOpenAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced() || state.is(BlockTags.LEAVES);
    }

    /**
     * Scaffolding left standing (by a bot or anyone): a lone 1x1 column of cobblestone or dirt,
     * 2-8 high, out in the open on the ground, nothing against it; or a lone block of it
     * hanging in the air (a bit of bridge). Taken down from the top.
     */
    private static @Nullable Spot leftoverAt(ServerLevel level, int x, int z) {
        int top = height(level, x, z) - 1;
        BlockPos head = new BlockPos(x, top, z);
        if (!isScaffoldBlock(level.getBlockState(head))) {
            return null;
        }
        List<BlockPos> blocks = new ArrayList<>();
        BlockPos pos = head;
        while (isScaffoldBlock(level.getBlockState(pos)) && blocks.size() <= 8) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (!isOpenAt(level, pos.relative(direction))) {
                    return null; // against something: part of a wall, a heap, the ground
                }
            }
            blocks.add(pos);
            pos = pos.below();
        }
        BlockState under = level.getBlockState(pos);
        boolean hanging = isOpenAt(level, pos) && blocks.size() == 1;
        boolean column = blocks.size() >= 2 && blocks.size() <= 8 && isNaturalGround(under) && !isScaffoldBlock(under);
        if (!hanging && !column) {
            return null;
        }
        for (BlockPos block : blocks) {
            for (Direction direction : Direction.values()) {
                BlockPos near = block.relative(direction);
                if (!blocks.contains(near) && BlockRules.isBuilt(level.getBlockState(near))) {
                    return null; // (touching a building: not a leftover)
                }
            }
        }
        return new Spot(blocks, true, false);
    }

    /** Bumps this high at most are levelled. */
    private static final int MAX_BUMP = 2;

    /**
     * The bump this column is part of: a column or a few sticking up 1-2 blocks out of
     * flat ground (the ground all round at one height). A hilltop or a slope isn't one.
     */
    private static @Nullable Spot bumpAt(ServerLevel level, int x, int z, Set<Long> seen) {
        int h = height(level, x, z);
        int ground = Integer.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int nx = x + direction.getStepX();
            int nz = z + direction.getStepZ();
            if (level.getChunkSource().getChunkNow(nx >> 4, nz >> 4) == null) {
                return null;
            }
            ground = Math.min(ground, height(level, nx, nz));
        }
        if (ground >= h) {
            return null;
        }
        List<int[]> columns = new ArrayList<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        Set<Long> inBump = new HashSet<>();
        queue.add(new int[] {x, z});
        inBump.add(columnKey(x, z));
        while (!queue.isEmpty()) {
            int[] column = queue.poll();
            columns.add(column);
            if (columns.size() > MAX_COLUMNS) {
                return null;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                int nx = column[0] + direction.getStepX();
                int nz = column[1] + direction.getStepZ();
                if (inBump.contains(columnKey(nx, nz))) {
                    continue;
                }
                if (level.getChunkSource().getChunkNow(nx >> 4, nz >> 4) == null) {
                    return null;
                }
                int nh = height(level, nx, nz);
                if (nh > ground) {
                    inBump.add(columnKey(nx, nz));
                    queue.add(new int[] {nx, nz});
                } else if (nh != ground) {
                    return null; // the ground around isn't flat: a slope, a hillside
                }
            }
        }
        seen.addAll(inBump);
        List<BlockPos> blocks = new ArrayList<>();
        for (int[] column : columns) {
            int top = height(level, column[0], column[1]);
            if (top - ground > MAX_BUMP) {
                return null;
            }
            for (int y = ground; y < top; y++) {
                BlockPos pos = new BlockPos(column[0], y, column[1]);
                if (!isNaturalGround(level.getBlockState(pos))) {
                    return null; // a tree, ore, something built
                }
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (BlockRules.isBuilt(level.getBlockState(pos.relative(direction)))) {
                        return null;
                    }
                }
                blocks.add(pos);
            }
        }
        return blocks.isEmpty() ? null : new Spot(blocks, true, false);
    }

    private static boolean isLowerThanANeighbour(ServerLevel level, int x, int z) {
        int h = height(level, x, z);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int nx = x + direction.getStepX();
            int nz = z + direction.getStepZ();
            if (level.getChunkSource().getChunkNow(nx >> 4, nz >> 4) != null && height(level, nx, nz) > h) {
                return true;
            }
        }
        return false;
    }

    /** Plain ground: dirt, grass, stone, sand, gravel... (not farmland, nothing built, no water). */
    private static boolean isNaturalGround(BlockState state) {
        if (state.is(Blocks.FARMLAND) || BlockRules.isBuilt(state) || !state.getFluidState().isEmpty()) {
            return false;
        }
        return state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SAND)
            || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.SNOW);
    }

    @Override
    public Status tick() {
        if (bump) {
            return dig();
        }
        while (!cells.isEmpty() && !bot.level().getBlockState(cells.get(0)).canBeReplaced()) {
            cells.remove(0); // filled (by us, or it was already)
        }
        if (cells.isEmpty()) {
            if (key == null) {
                bot.every("fill holes", 0, () -> false); // (nothing found after all: looked for again in 30 s)
            }
            return Status.SUCCESS;
        }
        if (++ticks > MAX_TICKS) {
            return giveUp();
        }
        BlockPos next = cells.get(0);
        // Dirt where it can (grass grows back over it), else cobblestone
        if (fetch != null) {
            Status status = fetch.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            fetch.stop();
            fetch = null;
        }
        if (donorBlock != null) {
            return digDonor();
        }
        Predicate<ItemStack> fill = fillFor(bot, earthy);
        if (Inv.count(bot, fill) == 0) {
            if (!earthy || dirtTries++ >= 3) {
                return Status.FAILURE;
            }
            // Dirt for a pit in dirt: out of the chests, else off the edge of a terrace
            int stored = storedDirt(bot);
            if (stored > 0) {
                fetch = ChestTask.withdraw(bot, com.minebot.bot.craft.Target.of(Items.DIRT, Math.min(stored, cells.size() + 2)));
                return Status.RUNNING;
            }
            donorBlock = donor(bot);
            return donorBlock != null ? Status.RUNNING : Status.FAILURE;
        }
        // From the edge, not standing in the pit
        boolean inPit = cells.stream().anyMatch(cell -> cell.getX() == bot.getBlockX() && cell.getZ() == bot.getBlockZ());
        if (inPit || !bot.isWithinBlockInteractionRange(next, 0.0)) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.near(next.above(cells.get(cells.size() - 1).getY() - next.getY() + 1), 2.5));
            }
            if (bot.navigator().tick().ended() && ++failures > 3) {
                return giveUp();
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        if (!BlockPlacer.place(bot, next, fill, Direction.DOWN) && ++failures > 20) {
            return giveUp();
        }
        return Status.RUNNING;
    }

    /** A bump: dug away from the top, then what fell out picked up (dirt to fill pits with). */
    private Status dig() {
        if (collect != null) {
            Status status = collect.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            collect.stop();
            return Status.SUCCESS;
        }
        if (breaker.target() != null) {
            if (breaker.tick() == com.minebot.bot.action.BlockBreaker.Result.RUNNING) {
                return Status.RUNNING;
            }
        }
        BlockPos last = null;
        while (!cells.isEmpty() && bot.level().getBlockState(cells.get(0)).canBeReplaced()) {
            last = cells.remove(0); // gone
        }
        if (cells.isEmpty()) {
            collect = new CollectItemsTask(bot, net.minecraft.world.phys.Vec3.atCenterOf(last != null ? last : bot.blockPosition()), 3.0, 60);
            return Status.RUNNING;
        }
        if (++ticks > MAX_TICKS) {
            return giveUp();
        }
        BlockPos next = cells.get(0);
        if (!bot.canUse(next)) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.reachVisible(bot.level(), next));
            }
            if (bot.navigator().tick().ended() && !bot.canUse(next) && ++failures > 3) {
                return giveUp();
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        breaker.start(next);
        return Status.RUNNING;
    }

    /** Dirt in its own chests at home (0 if it isn't home). */
    private static int storedDirt(BotPlayer bot) {
        return Home.isNear(bot, 48) ? ChestTask.stored(bot, com.minebot.bot.craft.Target.of(Items.DIRT, 1)) : 0;
    }

    /**
     * Dirt to be had without digging a new pit: the top block of a terrace edge (a column a
     * block higher than two or more of its neighbours, and no higher than the rest), away
     * from the house, buildings and fields. Taking it rounds the step off a little.
     */
    private static @Nullable BlockPos donor(BotPlayer bot) {
        ServerLevel level = bot.level();
        BlockPos center = bot.blockPosition();
        BlockPos bed = bot.memory().bed();
        Set<Long> nearBuildings = nearBuildings(level, center);
        List<BoundingBox> fields = fields(level);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null || nearBuildings.contains(columnKey(x, z))) {
                    continue;
                }
                int h = height(level, x, z);
                BlockPos top = new BlockPos(x, h - 1, z);
                if (bed != null && top.closerThan(bed, 8) || inField(fields, top)) {
                    continue;
                }
                BlockState state = level.getBlockState(top);
                if (!state.is(Blocks.GRASS_BLOCK) && !state.is(Blocks.DIRT)) {
                    continue;
                }
                int lower = 0;
                boolean ok = true;
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    int nx = x + direction.getStepX();
                    int nz = z + direction.getStepZ();
                    if (level.getChunkSource().getChunkNow(nx >> 4, nz >> 4) == null) {
                        ok = false;
                        break;
                    }
                    int nh = height(level, nx, nz);
                    if (nh == h - 1) {
                        lower++;
                    } else if (nh != h) {
                        ok = false; // a cliff, or ground going up: not a gentle edge
                        break;
                    }
                    BlockState side = level.getBlockState(top.relative(direction));
                    if (BlockRules.isBuilt(side) || side.is(Blocks.FARMLAND)) {
                        ok = false;
                        break;
                    }
                }
                double distance = top.distSqr(center);
                if (ok && lower >= 2 && distance < bestDistance && !ProtectedAreas.isProtected(level, top)
                    && BlockRules.isBuilt(level.getBlockState(top.below())) == false) {
                    best = top;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    /** Takes the top block off a terrace edge and picks up the dirt. */
    private Status digDonor() {
        BlockPos block = donorBlock;
        if (breaker.target() != null) {
            if (breaker.tick() == com.minebot.bot.action.BlockBreaker.Result.RUNNING) {
                return Status.RUNNING;
            }
        }
        if (bot.level().getBlockState(block).canBeReplaced()) {
            donorBlock = null;
            fetch = new CollectItemsTask(bot, net.minecraft.world.phys.Vec3.atCenterOf(block), 3.0, 60);
            return Status.RUNNING;
        }
        if (!bot.canUse(block)) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.reachVisible(bot.level(), block));
            }
            if (bot.navigator().tick().ended() && !bot.canUse(block) && ++failures > 3) {
                donorBlock = null;
                return Status.FAILURE;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        breaker.start(block);
        return Status.RUNNING;
    }

    private Status giveUp() {
        if (key != null) {
            GIVEN_UP.computeIfAbsent(bot.getUUID(), id -> new HashSet<>()).add(key);
        }
        return Status.FAILURE;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        breaker.cancel();
        if (collect != null) {
            collect.stop();
        }
        if (fetch != null) {
            fetch.stop();
        }
    }

    @Override
    public String describe() {
        return bump ? "levelling a bump" : "filling in a hole";
    }
}
