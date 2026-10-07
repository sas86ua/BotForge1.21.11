package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.BotRegistry;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
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
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Builds a small 5x5 hut (3x3 inside) on flat ground: walls and a roof of
 * cobblestone or planks, a door, and inside a bed, chest, crafting table,
 * furnace and a torch. Then sleeps in the bed once to make it the bot's
 * respawn point.
 *
 * Layout, seen from above (x to the east, z to the south), door on the north:
 * <pre>
 *   W W D W W      W wall, D door
 *   W C . T W      C chest, T crafting table
 *   W b . F W      b bed foot, F furnace
 *   W B . t W      B bed head, t torch (on the wall)
 *   W W W W W
 * </pre>
 */
public class BuildHomeTask extends Task {
    private static final int SIZE = 5;
    private static final int WALL_HEIGHT = 3;
    private static final int SITE_RADIUS = 64;
    /** No hut closer than this to any existing building (houses, villages...). */
    private static final int BUILDING_DISTANCE = 64;
    private static final int MAX_SITE_SEARCHES = 3;

    private static final Predicate<ItemStack> BUILDING_BLOCK = stack ->
        stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE) || stack.is(ItemTags.PLANKS);

    /**
     * Inside cells (x, z) a bed may take up when sheltering an existing one: not the
     * two cells from the door to the middle, where the bot walks and stands.
     */
    private static final int[][] SHELTER_BED_CELLS = {{1, 1}, {1, 2}, {1, 3}, {3, 1}, {3, 2}, {3, 3}, {2, 3}};

    private enum Stage { SITE, MATERIALS, CLEAR, FOUNDATION, WALLS, ROOF, DOOR, FURNITURE, MOVE_IN, DONE }

    private Stage stage = Stage.SITE;
    private @Nullable BlockPos origin;
    private @Nullable Task child;
    private final BlockBreaker breaker;
    /** Shelter mode: only walls, roof and a door around this bed (head), which stays where it is. */
    private final @Nullable BlockPos shelterBed;
    private int stuckTicks;
    private int failures;
    private boolean unreachable;
    private int siteSearches;

    public BuildHomeTask(BotPlayer bot) {
        this(bot, null);
    }

    private BuildHomeTask(BotPlayer bot, @Nullable BlockPos shelterBed) {
        super(bot);
        this.breaker = new BlockBreaker(bot);
        this.shelterBed = shelterBed;
    }

    /** Walls and a roof around the bot's bed. */
    public static BuildHomeTask shelter(BotPlayer bot) {
        return new BuildHomeTask(bot, bot.memory().bed());
    }

    /** The bot sleeps under the open sky. */
    public static boolean needsShelter(BotPlayer bot) {
        BlockPos bed = bot.memory().bed();
        ServerLevel level = Home.levelIfHere(bot);
        return Home.has(bot) && level != null && level.isLoaded(bed) && HomeFinder.isBed(level, bed)
            && level.canSeeSky(bed.above());
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
            if (status == Status.FAILURE) {
                bot.debug("home building: sub-task failed at stage {}", stage);
                return Status.FAILURE;
            }
        }
        if (unreachable) {
            bot.debug("can't reach the building site");
            return Status.FAILURE;
        }
        return switch (stage) {
            case SITE -> chooseSite();
            case MATERIALS -> gatherMaterials();
            case CLEAR -> clearSite();
            case FOUNDATION -> placeAll(foundation(), BUILDING_BLOCK, Stage.WALLS);
            case WALLS -> placeAll(walls(), BUILDING_BLOCK, Stage.ROOF);
            case ROOF -> placeAll(roof(), BUILDING_BLOCK, Stage.DOOR);
            case DOOR -> placeDoor();
            case FURNITURE -> furnish();
            case MOVE_IN -> shelterBed != null ? shelterDone() : moveIn();
            case DONE -> Status.SUCCESS;
        };
    }

    // ---- site -----------------------------------------------------------------------------

    private Status chooseSite() {
        if (shelterBed != null) {
            origin = shelterSite();
            if (origin == null) {
                bot.debug("no room for a shelter around my bed at {}", shelterBed.toShortString());
                return Status.FAILURE;
            }
            bot.debug("building a shelter around my bed at {}", shelterBed.toShortString());
            stage = Stage.MATERIALS;
            return Status.RUNNING;
        }
        GlobalPos started = bot.memory().homeSite();
        if (started != null && nearOtherBotsHome(bot.level(), started.pos())) {
            bot.memory().setHomeSite(null); // another bot took over that spot
            started = null;
        }
        if (started != null && (!bot.memory().inZone(started.dimension(), started.pos())
            || started.dimension() == bot.level().dimension() && !started.pos().closerThan(bot.blockPosition(), SITE_RADIUS * 2))) {
            // Far away now (moved to another zone, respawned elsewhere): start afresh here
            bot.debug("forgetting the hut I started at {}", started.pos().toShortString());
            bot.memory().setHomeSite(null);
            started = null;
        }
        if (started != null && started.dimension() == bot.level().dimension()) {
            origin = started.pos(); // carry on with the hut we started earlier
            stage = Stage.MATERIALS;
            return Status.RUNNING;
        }
        origin = findSite();
        if (origin == null) {
            if (++siteSearches > MAX_SITE_SEARCHES) {
                bot.debug("no spot for a hut found");
                return Status.FAILURE;
            }
            // Too close to other buildings (or no flat ground): move on and look again
            bot.debug("no spot for a hut here, moving on");
            child = new ExploreTask(bot, 96);
            return Status.RUNNING;
        }
        bot.debug("building a hut at {}", origin.toShortString());
        bot.memory().setHomeSite(GlobalPos.of(bot.level().dimension(), origin));
        stage = Stage.MATERIALS;
        return Status.RUNNING;
    }

    private @Nullable BlockPos findSite() {
        ServerLevel level = bot.level();
        BlockPos center = bot.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -SITE_RADIUS; dx <= SITE_RADIUS; dx += 2) {
            for (int dz = -SITE_RADIUS; dz <= SITE_RADIUS; dz += 2) {
                candidates.add(center.offset(dx, 0, dz));
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        // One scan for buildings around the whole search area; sections without any are skipped quickly
        List<BlockPos> buildings = BlockSearch.find(level, center, SITE_RADIUS + BUILDING_DISTANCE,
            center.getY() - 24, center.getY() + 40, BlockRules::isBuilt, (pos, state) -> true, 20_000);
        for (BlockPos column : candidates) {
            BlockPos site = siteAt(level, column.getX(), column.getZ(), buildings);
            if (site != null) {
                return site;
            }
        }
        return null;
    }

    /** NW corner (at floor level) of a valid hut site, or null. */
    private @Nullable BlockPos siteAt(ServerLevel level, int x0, int z0, List<BlockPos> buildings) {
        for (int dx = 0; dx < SIZE; dx += SIZE - 1) {
            for (int dz = 0; dz < SIZE; dz += SIZE - 1) {
                if (level.getChunkSource().getChunkNow((x0 + dx) >> 4, (z0 + dz) >> 4) == null) {
                    return null; // don't load chunks just to look
                }
            }
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0, z0);
        BlockPos origin = new BlockPos(x0, y, z0);
        if (!bot.memory().inZone(level.dimension(), origin) || ProtectedAreas.isProtected(level, origin)) {
            return null;
        }
        if (nearOtherBotsHome(level, origin)) {
            return null; // give neighbours some space
        }
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + dx, z0 + dz) != y) {
                    return null; // not flat
                }
                BlockPos floor = new BlockPos(x0 + dx, y - 1, z0 + dz);
                if (!BlockRules.isStandable(level, floor, level.getBlockState(floor))) {
                    return null;
                }
                for (int dy = 0; dy <= WALL_HEIGHT; dy++) {
                    BlockPos pos = new BlockPos(x0 + dx, y + dy, z0 + dz);
                    BlockState state = level.getBlockState(pos);
                    if (!state.getFluidState().isEmpty()) {
                        return null;
                    }
                    if (!state.canBeReplaced() && !BlockRules.canBreak(level, pos, state)) {
                        return null;
                    }
                }
            }
        }
        // Checked last: the most expensive test
        return farFromBuildings(level, origin, buildings) ? origin : null;
    }

    /** Any placed (non-natural) block within a few blocks of the hut's footprint? */
    /**
     * Nothing built within {@link #BUILDING_DISTANCE} of the hut, and that whole area is
     * loaded (an unloaded chunk might hide someone's house).
     */
    private static boolean farFromBuildings(ServerLevel level, BlockPos origin, List<BlockPos> buildings) {
        int cx = origin.getX() + SIZE / 2;
        int cz = origin.getZ() + SIZE / 2;
        for (int dx = -BUILDING_DISTANCE; dx <= BUILDING_DISTANCE; dx += 16) {
            for (int dz = -BUILDING_DISTANCE; dz <= BUILDING_DISTANCE; dz += 16) {
                if (level.getChunkSource().getChunkNow((cx + dx) >> 4, (cz + dz) >> 4) == null) {
                    return false;
                }
            }
        }
        long limit = (long) BUILDING_DISTANCE * BUILDING_DISTANCE;
        for (BlockPos building : buildings) {
            long dx = building.getX() - cx;
            long dz = building.getZ() - cz;
            if (dx * dx + dz * dz < limit) {
                return false;
            }
        }
        return true;
    }

    /**
     * NW corner of a hut that fits around the existing bed (both halves inside, the
     * way from the door to the middle free), or null if none does.
     */
    private @Nullable BlockPos shelterSite() {
        ServerLevel level = bot.level();
        BlockState bed = level.getBlockState(shelterBed);
        if (!(bed.getBlock() instanceof BedBlock)) {
            return null;
        }
        BlockPos otherHalf = shelterBed.relative(BedBlock.getConnectedDirection(bed));
        for (int[] cell : SHELTER_BED_CELLS) {
            BlockPos corner = shelterBed.offset(-cell[0], 0, -cell[1]);
            BlockPos other = otherHalf.subtract(corner);
            if (isShelterBedCell(other) && shelterFits(level, corner)) {
                return corner;
            }
        }
        return null;
    }

    private static boolean isShelterBedCell(BlockPos offset) {
        if (offset.getY() != 0) {
            return false;
        }
        for (int[] cell : SHELTER_BED_CELLS) {
            if (cell[0] == offset.getX() && cell[1] == offset.getZ()) {
                return true;
            }
        }
        return false;
    }

    private boolean shelterFits(ServerLevel level, BlockPos corner) {
        int holes = 0;
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                for (int dy = -1; dy <= WALL_HEIGHT; dy++) {
                    BlockPos pos = corner.offset(dx, dy, dz);
                    if (!level.isLoaded(pos) || ProtectedAreas.isProtected(level, pos)) {
                        return false;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (dy < 0) {
                        boolean edge = dx == 0 || dz == 0 || dx == SIZE - 1 || dz == SIZE - 1;
                        boolean walkway = dx == 2 && dz >= 1 && dz <= 2;
                        if (walkway && !BlockRules.isStandable(level, pos, state)) {
                            return false;
                        }
                        holes += edge && state.canBeReplaced() ? 1 : 0;
                        continue;
                    }
                    if (!state.getFluidState().isEmpty()) {
                        return false;
                    }
                    if (mustBeOpen(dx, dy, dz) && !state.canBeReplaced() && !BlockRules.canBreak(level, pos, state)) {
                        return false; // someone's chest in the doorway...
                    }
                }
            }
        }
        return holes <= 8; // not on the edge of a cliff
    }

    /** Doorway and the way in to the middle of the hut. */
    private static boolean mustBeOpen(int dx, int dy, int dz) {
        return dx == 2 && dz <= 2 && dy <= 1;
    }

    /** Another bot lives or is building within a few blocks of this spot. */
    private boolean nearOtherBotsHome(ServerLevel level, BlockPos pos) {
        for (BotMemory other : BotRegistry.get(level.getServer()).all()) {
            if (other == bot.memory()) {
                continue;
            }
            if (other.home() != null && other.home().pos().closerThan(pos, 12)
                || other.homeSite() != null && other.homeSite().pos().closerThan(pos, 12)) {
                return true;
            }
        }
        return false;
    }

    // ---- materials -------------------------------------------------------------------------

    private Status gatherMaterials() {
        List<Target> needs = shelterBed != null ? List.of(
            new Target("building blocks", BUILDING_BLOCK, blocksStillNeeded()),
            Target.tag(ItemTags.WOODEN_DOORS, 1)
        ) : List.of(
            new Target("building blocks", BUILDING_BLOCK, blocksStillNeeded()),
            Target.tag(ItemTags.WOODEN_DOORS, 1),
            Target.tag(ItemTags.BEDS, 1),
            Target.of(Items.CHEST, 1),
            Target.of(Items.CRAFTING_TABLE, 1),
            Target.of(Items.FURNACE, 1),
            Target.of(Items.TORCH, 1));
        for (Target need : needs) {
            if (!need.satisfied(bot) && !placed(need)) {
                bot.debug("home building needs {}", need);
                child = new ObtainTask(bot, need, 0);
                return Status.RUNNING;
            }
        }
        stage = Stage.CLEAR;
        return Status.RUNNING;
    }

    /** Wall and roof blocks not placed yet, plus a few spare for filling gaps. */
    private int blocksStillNeeded() {
        ServerLevel level = bot.level();
        int missing = 0;
        List<BlockPos> shell = new ArrayList<>(walls());
        shell.addAll(roof());
        shell.addAll(foundation());
        for (BlockPos pos : shell) {
            BlockState state = level.getBlockState(pos);
            missing += state.canBeReplaced() || shelterBed != null && inTheWay(level, pos, state) ? 1 : 0;
        }
        return missing == 0 ? 0 : missing + 4;
    }

    /** Furniture that's already standing in the hut doesn't need to be in the inventory. */
    private boolean placed(Target need) {
        for (BlockPos pos : furniturePositions()) {
            if (need.accepts().test(new ItemStack(bot.level().getBlockState(pos).getBlock().asItem()))) {
                return true;
            }
        }
        return false;
    }

    // ---- building --------------------------------------------------------------------------

    private Status clearSite() {
        if (!inPosition()) {
            return Status.RUNNING;
        }
        ServerLevel level = bot.level();
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                for (int dy = 0; dy <= WALL_HEIGHT; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    boolean obstacle = shelterBed != null
                        ? inTheWay(level, pos, state)
                        : !state.isAir() && !state.canBeReplaced() && !isOurs(pos, state);
                    if (obstacle) {
                        return mine(pos);
                    }
                }
            }
        }
        stage = Stage.FOUNDATION;
        return Status.RUNNING;
    }

    /**
     * Shelter mode: what has to go before building. Whatever already stands in the
     * walls (dirt, stone, the bot's own chest...) stays and is part of the wall;
     * only the doorway and the way in are dug out, and leaves, grass and the like
     * where a wall or the roof goes.
     */
    private boolean inTheWay(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || !BlockRules.canBreak(level, pos, state)) {
            return false;
        }
        BlockPos offset = pos.subtract(origin);
        if (mustBeOpen(offset.getX(), offset.getY(), offset.getZ())) {
            return !state.canBeReplaced();
        }
        if (walls().contains(pos) || roof().contains(pos)) {
            return state.is(BlockTags.LEAVES) || !state.isCollisionShapeFullBlock(level, pos);
        }
        return false;
    }

    private Status placeAll(List<BlockPos> positions, Predicate<ItemStack> item, Stage next) {
        if (!inPosition()) {
            return Status.RUNNING;
        }
        ServerLevel level = bot.level();
        for (BlockPos pos : positions) {
            if (!level.getBlockState(pos).canBeReplaced()) {
                continue;
            }
            if (Inv.count(bot, item) == 0) {
                stage = Stage.MATERIALS; // ran out (e.g. some got used for bridging)
                return Status.RUNNING;
            }
            if (!BlockPlacer.place(bot, pos, item)) {
                return stuck("could not place at " + relative(pos) + " (" + bot.level().getBlockState(pos).getBlock() + ")");
            }
            stuckTicks = 0;
            return Status.RUNNING; // one block per tick
        }
        stage = next;
        return Status.RUNNING;
    }

    private Status placeDoor() {
        if (!inPosition()) {
            return Status.RUNNING;
        }
        BlockPos door = origin.offset(2, 0, 0);
        Stage next = shelterBed != null ? Stage.MOVE_IN : Stage.FURNITURE;
        if (!bot.level().getBlockState(door).canBeReplaced()) {
            stage = next;
            return Status.RUNNING;
        }
        if (!BlockPlacer.place(bot, door, stack -> stack.is(ItemTags.WOODEN_DOORS), Direction.DOWN, Direction.NORTH)) {
            return stuck("could not place the door");
        }
        stage = next;
        return Status.RUNNING;
    }

    private Status shelterDone() {
        BotMemory memory = bot.memory();
        memory.setHome(memory.home(), true);
        bot.debug("built a shelter around my bed at {}", origin.toShortString());
        stage = Stage.DONE;
        return Status.SUCCESS;
    }

    private Status furnish() {
        if (!inPosition()) {
            return Status.RUNNING;
        }
        ServerLevel level = bot.level();
        // Bed: foot at (1,2), head towards the south at (1,3)
        BlockPos bedFoot = origin.offset(1, 0, 2);
        if (level.getBlockState(bedFoot).canBeReplaced()) {
            return placeOrStuck(bedFoot, stack -> stack.is(ItemTags.BEDS), Direction.DOWN, Direction.SOUTH);
        }
        BlockPos chest = origin.offset(1, 0, 1);
        if (level.getBlockState(chest).canBeReplaced()) {
            return placeOrStuck(chest, stack -> stack.is(Items.CHEST), Direction.DOWN, Direction.WEST);
        }
        BlockPos table = origin.offset(3, 0, 1);
        if (level.getBlockState(table).canBeReplaced()) {
            return placeOrStuck(table, stack -> stack.is(Items.CRAFTING_TABLE), Direction.DOWN, null);
        }
        BlockPos furnace = origin.offset(3, 0, 2);
        if (level.getBlockState(furnace).canBeReplaced()) {
            return placeOrStuck(furnace, stack -> stack.is(Items.FURNACE), Direction.DOWN, Direction.EAST);
        }
        BlockPos torch = origin.offset(3, 1, 3);
        if (level.getBlockState(torch).isAir() && Inv.count(bot, stack -> stack.is(Items.TORCH)) > 0) {
            return placeOrStuck(torch, stack -> stack.is(Items.TORCH), Direction.SOUTH, null);
        }
        stage = Stage.MOVE_IN;
        return Status.RUNNING;
    }

    private Status moveIn() {
        ServerLevel level = bot.level();
        BlockPos bedHead = origin.offset(1, 0, 3);
        BotMemory memory = bot.memory();
        memory.setHome(GlobalPos.of(level.dimension(), bedHead), true);
        memory.setHomeSite(null);
        memory.setBed(bedHead);
        memory.addChest(origin.offset(1, 0, 1));
        memory.setCraftingTable(origin.offset(3, 0, 1));
        memory.setFurnace(origin.offset(3, 0, 2));
        // Beds are village "homes": reserve ours so villagers don't move in
        HomeFinder.claim(level, bedHead);
        // Clicking the bed makes it the respawn point (and sleeps if it's night)
        bot.useBed(bedHead);
        bot.debug("moved into the new hut at {}", origin.toShortString());
        stage = Stage.DONE;
        return Status.SUCCESS;
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Walks to the middle of the hut; true once there. */
    private boolean inPosition() {
        BlockPos spot = origin.offset(2, 0, 2);
        Vec3 center = Vec3.atBottomCenterOf(spot);
        double distance = bot.position().distanceTo(center);
        if (distance < 0.25) {
            bot.navigator().stop();
            bot.controller().releaseInputs();
            return true;
        }
        if (distance < 1.2 && bot.blockPosition().equals(spot)) {
            // Nearly there: centre up exactly, or our own body blocks the furniture spots
            bot.navigator().stop();
            bot.controller().hold(center);
            return false;
        }
        if (!bot.navigator().isActive()) {
            bot.navigator().navigate(Goal.near(spot, 0.0));
        }
        if (bot.navigator().tick() == Navigator.Status.FAILED && ++failures > 5) {
            unreachable = true;
        }
        return false;
    }

    private Status mine(BlockPos pos) {
        if (!breaker.isBreaking(pos)) {
            breaker.start(pos);
        }
        BlockBreaker.Result result = breaker.tick();
        if (result == BlockBreaker.Result.FAILED) {
            return stuck("could not clear " + pos.toShortString());
        }
        return Status.RUNNING;
    }

    private Status placeOrStuck(BlockPos pos, Predicate<ItemStack> item, Direction face, @Nullable Direction facing) {
        if (Inv.count(bot, item) == 0) {
            stage = Stage.MATERIALS;
            return Status.RUNNING;
        }
        if (BlockPlacer.place(bot, pos, item, face, facing)) {
            stuckTicks = 0;
            return Status.RUNNING;
        }
        return stuck("could not place at " + relative(pos) + " (" + bot.level().getBlockState(pos).getBlock() + ")");
    }

    private Status stuck(String why) {
        if (++stuckTicks > 100) {
            bot.debug("home building stuck: {}", why);
            return Status.FAILURE;
        }
        return Status.RUNNING;
    }

    private String relative(BlockPos pos) {
        BlockPos offset = pos.subtract(origin);
        return stage.name().toLowerCase() + " +" + offset.getX() + "," + offset.getY() + "," + offset.getZ();
    }

    /** A block we placed ourselves (wall, roof, door, furniture), as opposed to leaves, dirt... in the way. */
    private boolean isOurs(BlockPos pos, BlockState state) {
        if (walls().contains(pos) || roof().contains(pos)) {
            return BUILDING_BLOCK.test(new ItemStack(state.getBlock().asItem()));
        }
        if (pos.equals(origin.offset(2, 0, 0)) || pos.equals(origin.offset(2, 1, 0))) {
            return state.is(BlockTags.DOORS);
        }
        if (furniturePositions().contains(pos)) {
            return state.is(BlockTags.BEDS) || state.is(Blocks.CHEST) || state.is(Blocks.CRAFTING_TABLE)
                || state.is(Blocks.FURNACE) || state.is(Blocks.WALL_TORCH);
        }
        return false;
    }

    private List<BlockPos> furniturePositions() {
        if (origin == null) {
            return List.of();
        }
        return List.of(origin.offset(1, 0, 2), origin.offset(1, 0, 3), origin.offset(1, 0, 1),
            origin.offset(3, 0, 1), origin.offset(3, 0, 2), origin.offset(3, 1, 3));
    }

    private List<BlockPos> walls() {
        List<BlockPos> walls = new ArrayList<>();
        for (int dy = 0; dy < WALL_HEIGHT; dy++) {
            for (int dx = 0; dx < SIZE; dx++) {
                for (int dz = 0; dz < SIZE; dz++) {
                    boolean edge = dx == 0 || dz == 0 || dx == SIZE - 1 || dz == SIZE - 1;
                    boolean doorway = dx == 2 && dz == 0 && dy < 2;
                    if (edge && !doorway) {
                        walls.add(origin.offset(dx, dy, dz));
                    }
                }
            }
        }
        return walls;
    }

    /** Under the walls, so nothing crawls in where the ground dips (normally all solid already). */
    private List<BlockPos> foundation() {
        List<BlockPos> foundation = new ArrayList<>();
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                if (dx == 0 || dz == 0 || dx == SIZE - 1 || dz == SIZE - 1) {
                    foundation.add(origin.offset(dx, -1, dz));
                }
            }
        }
        return foundation;
    }

    /** Roof blocks, outer ring first so every block has a neighbour to attach to. */
    private List<BlockPos> roof() {
        List<BlockPos> roof = new ArrayList<>();
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                roof.add(origin.offset(dx, WALL_HEIGHT, dz));
            }
        }
        BlockPos middle = origin.offset(2, WALL_HEIGHT, 2);
        roof.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(middle)).reversed());
        return roof;
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
        String what = (shelterBed != null ? "building a shelter (" : "building a home (") + stage.name().toLowerCase() + ")";
        return child != null ? what + ": " + child.describe() : what;
    }
}
