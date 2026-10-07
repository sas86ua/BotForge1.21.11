package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.build.GreatBuild;
import com.minebot.bot.build.LegacyBlocks;
import com.minebot.bot.build.Schematic;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The Great Build's days (see {@link GreatBuild}): takes its order out of the chests, walks to the
 * site with everyone else, and works there until the session is over: digs out what must be empty
 * (highest first), fills in the ground, and builds with what it brought (lowest first). Out of
 * materials, it goes down a mine under the site for 2-5 stacks of what's needed next. Then home.
 */
public class GreatBuildTask extends Task {
    private enum Stage { PACK, GO, WORK, RETURN }

    /** Bots away for the build (on the road or at the site), and at which stage. */
    private static final Map<UUID, Stage> AWAY = new ConcurrentHashMap<>();
    /** Bots that said they're setting off early, and for which session (once each). */
    private static final Map<UUID, Long> ANNOUNCED = new ConcurrentHashMap<>();
    /** Ticks one job may take before it's left for later. */
    private static final int JOB_TICKS = 20 * 30;
    /**
     * A way to a job: jobs are near (the nearest first), so a small search; one it can't get to
     * (a tree top, a ledge) is left for later at once, not combed for over 30 000 blocks, again and again.
     */
    private static final int JOB_NODES = 6000;
    private static final int GO_TRIES = 8;
    /** Bag slots left free when packing materials (for what it digs and picks up there). */
    private static final int PACK_FREE_SLOTS = 5;

    private final BlockBreaker breaker;
    private Stage stage;
    private @Nullable Task child;
    private boolean childIsRestock;
    private @Nullable Item restockItem;
    private final Set<Item> packed = new HashSet<>();
    private final Set<Item> cantGet = new HashSet<>();
    private boolean foodPacked;
    private boolean breadPacked;
    private boolean toolPacked;
    private boolean campPacked;
    private boolean campfirePacked;
    private boolean campDone;
    private boolean campfireDone;
    private int campTries;
    private int campTicks;
    private @Nullable BlockPos campTarget;
    private final Set<BlockPos> badSpots = new HashSet<>();
    private int goTries;
    private double closest = Double.MAX_VALUE;
    private long retryAt;
    private @Nullable GreatBuild.Job job;
    private int jobTicks;
    private int placeFails;
    private int idleTicks;

    public GreatBuildTask(BotPlayer bot) {
        super(bot);
        this.breaker = new BlockBreaker(bot);
        this.stage = AWAY.getOrDefault(bot.getUUID(), Home.isNear(bot, 64) ? Stage.PACK : Stage.GO);
    }

    private static @Nullable GreatBuild build(BotPlayer bot) {
        GreatBuild build = GreatBuild.get(bot.level().getServer());
        return build.exists() && build.plan(bot.level().getServer()) != null ? build : null;
    }

    public static boolean wanted(BotPlayer bot) {
        GreatBuild build = build(bot);
        return build != null && bot.memory().autonomous() && bot.level() == bot.level().getServer().overworld()
            && (AWAY.containsKey(bot.getUUID())
                || !Long.valueOf(build.sessionEnd()).equals(SKIPPED.get(bot.getUUID())) // (too far to make it this time)
                && (build.inSession(bot.level().getServer()) || build.departureDue(bot)));
    }

    /** Bots that couldn't have got there before the end of a session (after dying, say): which session they sit out. */
    private static final Map<UUID, Long> SKIPPED = new ConcurrentHashMap<>();

    /** On the road to the build, at it, or on the way back. */
    public static boolean isAway(BotPlayer bot) {
        Stage stage = AWAY.get(bot.getUUID());
        return stage != null && stage != Stage.PACK;
    }

    public static boolean sessionOn(BotPlayer bot) {
        GreatBuild build = build(bot);
        return build != null && (build.inSession(bot.level().getServer()) || build.departureDue(bot));
    }

    public static void forget(BotPlayer bot) {
        AWAY.remove(bot.getUUID());
    }

    @Override
    public Status tick() {
        GreatBuild build = build(bot);
        if (build == null) {
            AWAY.remove(bot.getUUID());
            return Status.FAILURE;
        }
        if (child != null) {
            Status status = child.tick();
            if (status == Status.RUNNING && childIsRestock && bot.takeLeftCooking() > 0) {
                // Its camp's furnaces are loaded: off to dig and build meanwhile, back for it when it's out of blocks again
                bot.debug("great build: the camp furnaces are cooking; working meanwhile");
                status = Status.SUCCESS;
            }
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            child.stop();
            child = null;
            bot.setLeaveWhileSmelting(false);
            if (childIsRestock) {
                childIsRestock = false;
                bot.setMiningRule(null);
                build.restocking(bot, false);
                if (status == Status.FAILURE && restockItem != null) {
                    bot.debug("great build: couldn't get {}", GreatBuild.key(restockItem));
                    cantGet.add(restockItem);
                    build.failed(restockItem);
                }
            } else if (status == Status.FAILURE && stage == Stage.GO) {
                goTries++;
                retryAt = bot.level().getGameTime() + 200; // (a moment before trying again: the same way just failed)
            }
        }
        // (set off early for a long way: on the road or there already, it waits for the start)
        boolean session = build.inSession(bot.level().getServer())
            || (stage == Stage.PACK ? build.departureDue(bot) : build.awaitingSession(bot.level().getDayTime()) || build.sentEarly(bot));
        if (!session && stage != Stage.RETURN) {
            stage = Stage.RETURN;
            dropJob(build);
            bot.debug("great build: the session is over, going home");
        }
        AWAY.put(bot.getUUID(), stage);
        return switch (stage) {
            case PACK -> pack(build);
            case GO -> go(build);
            case WORK -> work(build);
            case RETURN -> goHome();
        };
    }

    // ---- getting there and back -----------------------------------------------------------------

    /** Its order out of the chests (as much as there is), and food for the road. */
    private Status pack(GreatBuild build) {
        if (build.tooLateFor(bot)) {
            bot.debug("great build: too late to get there in time; staying home this time");
            SKIPPED.put(bot.getUUID(), build.sessionEnd());
            AWAY.remove(bot.getUUID());
            return Status.SUCCESS;
        }
        if (Home.isNear(bot, 64)) {
            if (!breadPacked) {
                breadPacked = true; // (8-16 loaves to eat there, if there are some at home)
                child = Food.packBread(bot);
                if (child != null) {
                    return Status.RUNNING;
                }
            }
            if (!foodPacked && Inv.count(bot, Food::isCooked) < JourneyTask.ROAD_FOOD) {
                foodPacked = true;
                child = new FoodTask(bot, JourneyTask.ROAD_FOOD, true);
                return Status.RUNNING;
            }
            if (!toolPacked) {
                // A spare pickaxe from the chest, or else the iron for a new one: it can't run home for it there
                toolPacked = true;
                int pickaxes = Inv.count(bot, stack -> stack.is(ItemTags.PICKAXES));
                Target spare = Target.tag(ItemTags.PICKAXES, pickaxes + 1);
                if (ChestTask.stored(bot, spare) > 0) {
                    child = ChestTask.withdraw(bot, spare);
                    return Status.RUNNING;
                }
                int ingots = Inv.count(bot, stack -> stack.is(Items.IRON_INGOT));
                if (ingots < 3 && ChestTask.stored(bot, Target.of(Items.IRON_INGOT, 1)) >= 3 - ingots) {
                    child = ChestTask.withdraw(bot, Target.of(Items.IRON_INGOT, 3));
                    return Status.RUNNING;
                }
            }
            if (!campPacked) {
                // For its camp by the site: the furnaces it still lacks there, and a camp fire (made at home, quicker)
                campPacked = true;
                int furnaces = GreatBuild.campFurnaceCount(bot) - build.campFurnaces(bot).size();
                int inBag = Inv.count(bot, stack -> stack.is(Items.FURNACE));
                if (furnaces > inBag) {
                    child = new ObtainTask(bot, Target.of(Items.FURNACE, furnaces), 0);
                    return Status.RUNNING;
                }
            }
            if (!campfirePacked && Inv.count(bot, stack -> stack.is(Items.CAMPFIRE)) == 0
                && build.campFurnaces(bot).size() < GreatBuild.campFurnaceCount(bot)) { // (a camp to set up still)
                campfirePacked = true;
                child = new ObtainTask(bot, Target.of(Items.CAMPFIRE, 1), 0);
                return Status.RUNNING;
            }
            // All it got ready for its order first, then any other blocks the plan uses that it has at home,
            // as much as the bag holds (a few slots kept free for what it picks up there)
            List<Item> materials = new ArrayList<>(build.orderOf(bot.getUUID()).keySet());
            for (Item item : build.materialTotals().keySet()) {
                if (!materials.contains(item)) {
                    materials.add(item);
                }
            }
            for (Item item : materials) {
                if (Inv.freeSlots(bot) <= PACK_FREE_SLOTS) {
                    break;
                }
                if (!packed.add(item)) {
                    continue;
                }
                int stored = ChestTask.stored(bot, Target.of(item, 1));
                if (stored > 0) {
                    int inBag = Inv.count(bot, stack -> stack.is(item));
                    int room = (Inv.freeSlots(bot) - PACK_FREE_SLOTS) * item.getDefaultMaxStackSize();
                    int take = Math.min(stored, room);
                    bot.debug("great build: packing {} {}", take, GreatBuild.key(item));
                    child = ChestTask.withdraw(bot, Target.of(item, inBag + take));
                    return Status.RUNNING;
                }
            }
        }
        if (!build.inSession(bot.level().getServer()) && !build.sentEarly(bot) // (sent by an admin: that was said once for all)
            && build.nextDay() * GreatBuild.DAY - bot.level().getDayTime() > GreatBuild.DAY / 4
            && !Long.valueOf(build.nextDay()).equals(ANNOUNCED.put(bot.getUUID(), build.nextDay()))) {
            // (a long way to go: off before the others, to be there for the start)
            GreatBuild.announce(bot.level().getServer(), bot.getPlainTextName() + " выходит на стройку заранее: идти ~"
                + (int) Math.sqrt(bot.blockPosition().distSqr(build.centre().atY(bot.getBlockY()))) + " блоков.");
        }
        bot.debug("great build: setting off for the site at {}", build.centre().toShortString());
        stage = Stage.GO;
        return Status.RUNNING;
    }

    private Status go(GreatBuild build) {
        if (build.inSite(bot.blockPosition(), 4)) {
            bot.debug("great build: at the site");
            stage = Stage.WORK;
            return Status.RUNNING;
        }
        if (build.tooLateFor(bot)) {
            // The way there takes longer than what's left of the session: this one's sat out
            bot.debug("great build: too far to get there before it's over; staying home this time");
            SKIPPED.put(bot.getUUID(), build.sessionEnd());
            stage = Stage.RETURN;
            return Status.RUNNING;
        }
        // A long way (a trip that ends short of it still counts if it got closer)
        double distance = Math.sqrt(bot.blockPosition().distSqr(build.centre().atY(bot.getBlockY())));
        if (distance < closest - 16) {
            closest = distance;
            goTries = 0;
        }
        if (goTries >= GO_TRIES) {
            bot.debug("great build: can't get to the site; going home");
            stage = Stage.RETURN;
            goTries = 0;
            return Status.RUNNING;
        }
        if (bot.level().getGameTime() < retryAt) {
            return Status.RUNNING;
        }
        child = new GoToTask(bot, Goal.column(build.centre().getX(), build.centre().getZ(), 60));
        return Status.RUNNING;
    }

    private Status goHome() {
        var home = bot.memory().home();
        if (home == null || Home.isNear(bot, 32) || goTries >= GO_TRIES * 2) {
            AWAY.remove(bot.getUUID());
            return Status.SUCCESS;
        }
        goTries++;
        child = new GoToTask(bot, Goal.column(home.pos().getX(), home.pos().getZ(), 8));
        return Status.RUNNING;
    }

    // ---- at the site ----------------------------------------------------------------------------

    private Status work(GreatBuild build) {
        if (!build.inSite(bot.blockPosition(), 16)) {
            // (off somewhere: after food, a tree...) back to the site first
            bot.debug("great build: away from the site; going back");
            stage = Stage.GO;
            goTries = 0;
            closest = Double.MAX_VALUE;
            return Status.RUNNING;
        }
        if (Inv.count(bot, Food::isCooked) < 3) {
            child = new FoodTask(bot, 6, true, false, 1); // (hunts around the site, cooks on a camp fire)
            return Status.RUNNING;
        }
        if (!campDone) {
            Status camp = setUpCamp(build);
            if (camp != null) {
                return camp;
            }
        }
        if (job == null) {
            Stash.makeRoom(bot, 3);
            job = build.nextJob(bot, true);
            boolean carries = carriesMaterial(build);
            if ((job == null || job.type() != GreatBuild.JobType.PLACE) && !carries && restock(build)) {
                dropJob(build);
                return Status.RUNNING;
            }
            if (job == null) {
                if (++idleTicks % 200 == 1) {
                    bot.debug("great build: nothing to do here for now");
                }
                return Status.RUNNING;
            }
            idleTicks = 0;
            build.claim(bot, job);
            jobTicks = 0;
            placeFails = 0;
        }
        return doJob(build);
    }

    /**
     * Its own little camp by the site, first thing: a camp fire, and its 2-3 furnaces round it (a block's
     * gap between, turned to the fire), for smelting what it gets there. Null once there's nothing to do.
     */
    private @Nullable Status setUpCamp(GreatBuild build) {
        ServerLevel level = bot.level();
        BlockPos centre = build.campCentre(bot);
        if (centre == null || campTries > 6) {
            campDone = true;
            return null;
        }
        BlockPos target = null;
        Item item = null;
        if (!campfireDone && !level.getBlockState(centre).is(Blocks.CAMPFIRE)) {
            if (Inv.count(bot, stack -> stack.is(Items.CAMPFIRE)) > 0) {
                target = centre;
                item = Items.CAMPFIRE;
            } else {
                campfireDone = true; // (none brought: the camp does without)
            }
        }
        if (target == null) {
            int want = GreatBuild.campFurnaceCount(bot);
            List<BlockPos> have = build.campFurnaces(bot);
            if (have.size() >= want) {
                campDone = true;
                return null;
            }
            for (int k = 0; k < want; k++) {
                BlockPos spot = build.campFurnaceSpot(bot, k);
                // (one of its furnaces in that column already: the ground's top is that furnace now, not a new spot)
                boolean taken = spot != null && have.stream().anyMatch(pos -> pos.getX() == spot.getX() && pos.getZ() == spot.getZ());
                if (spot != null && !taken && !badSpots.contains(spot)) {
                    target = spot;
                    break;
                }
            }
            if (target == null) {
                campDone = true;
                return null;
            }
            if (Inv.count(bot, stack -> stack.is(Items.FURNACE)) == 0) {
                child = new ObtainTask(bot, Target.of(Items.FURNACE, 1), 0);
                campTries++;
                return Status.RUNNING;
            }
            item = Items.FURNACE;
        }
        if (!target.equals(campTarget)) {
            campTarget = target;
            campTicks = 0;
        }
        if (++campTicks > JOB_TICKS || !level.getBlockState(target).canBeReplaced()) {
            badSpots.add(target); // (a tree, a rock, out of reach: that spot is left out)
            if (item == Items.CAMPFIRE) {
                campfireDone = true;
            }
            campTries++;
            campTarget = null;
            return Status.RUNNING;
        }
        if (!bot.isWithinBlockInteractionRange(target, 0.5) || bot.getBoundingBox().intersects(new AABB(target))) {
            Navigator navigator = bot.navigator();
            if (!navigator.isActive()) {
                navigator.navigate(Goal.reach(target));
            }
            navigator.tick();
            return Status.RUNNING;
        }
        bot.navigator().stop();
        Item placing = item;
        if (!BlockPlacer.place(bot, target, stack -> stack.is(placing), Direction.DOWN, null)) {
            return Status.RUNNING;
        }
        if (item == Items.CAMPFIRE) {
            campfireDone = true;
            bot.debug("great build: my camp fire at {}", target.toShortString());
        } else {
            // Turned to the fire
            Direction toFire = Direction.getApproximateNearest(centre.getX() - target.getX(), 0, centre.getZ() - target.getZ());
            BlockState furnace = level.getBlockState(target);
            if (furnace.hasProperty(BlockStateProperties.HORIZONTAL_FACING) && toFire.getAxis().isHorizontal()) {
                level.setBlock(target, furnace.setValue(BlockStateProperties.HORIZONTAL_FACING, toFire), Block.UPDATE_ALL);
            }
            build.addCampFurnace(bot, target);
            bot.debug("great build: a furnace at my camp, {}", target.toShortString());
        }
        campTarget = null;
        return Status.RUNNING;
    }

    private boolean carriesMaterial(GreatBuild build) {
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (!stack.isEmpty() && build.isMaterial(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Out of materials: down a mine under the site for 2-5 stacks of what's needed next (half
     * the bots at most; the rest dig and fill).
     */
    private boolean restock(GreatBuild build) {
        int atSite = 0;
        for (var entry : AWAY.values()) {
            if (entry == Stage.WORK) {
                atSite++;
            }
        }
        if (build.restockers() >= Math.max(1, atSite / 2)) {
            return false;
        }
        List<Map.Entry<Item, Integer>> needed = build.neededNext(4096, cantGet);
        if (needed.isEmpty()) {
            return false;
        }
        Item item = needed.get(0).getKey();
        int stackSize = new ItemStack(item).getMaxStackSize();
        int amount = Math.min((2 + bot.getRandom().nextInt(4)) * stackSize, Math.max(needed.get(0).getValue(), stackSize));
        int have = Inv.count(bot, stack -> stack.is(item));
        bot.debug("great build: out of materials; getting {} {} from under the site", amount, GreatBuild.key(item));
        restockItem = item;
        childIsRestock = true;
        build.restocking(bot, true);
        bot.setMiningRule(MiningRule.underSite(bot.level(), build));
        bot.setLeaveWhileSmelting(true); // (its camp furnaces cook while it works)
        child = new ObtainTask(bot, Target.of(item, have + amount), 0).notFromChests();
        return true;
    }

    private Status doJob(GreatBuild build) {
        ServerLevel level = bot.level();
        GreatBuild.Job current = job;
        BlockPos pos = current.pos();
        BlockState state = level.getBlockState(pos);
        if (GreatBuild.isDone(current.spec(), state)) {
            build.done(level, current);
            job = null;
            return Status.RUNNING;
        }
        if (++jobTicks > JOB_TICKS) {
            bot.debug("great build: leaving {} at {} for later", current.type(), pos.toShortString());
            giveUp(build);
            return Status.RUNNING;
        }
        boolean dig = current.type() == GreatBuild.JobType.DIG || !state.canBeReplaced();
        if (!dig && (current.type() == GreatBuild.JobType.PLACE && Inv.count(bot, stack -> stack.is(GreatBuild.itemFor(current.spec()))) == 0
            || current.type() == GreatBuild.JobType.FILL && Inv.count(bot, fillFor(current.spec())) == 0)) {
            dropJob(build); // (used up meanwhile)
            return Status.RUNNING;
        }
        // Close enough, and (to put a block there) not standing in it
        boolean inside = bot.getBoundingBox().intersects(new AABB(pos));
        if (!bot.isWithinBlockInteractionRange(pos, 0.5) || !dig && inside) {
            Navigator navigator = bot.navigator();
            if (!navigator.isActive() || jobTicks == 1) {
                navigator.navigate(Goal.reach(pos), JOB_NODES);
            }
            if (navigator.tick() == Navigator.Status.FAILED) {
                giveUp(build);
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        if (dig) {
            // Only what it can see, like a player: something in the way is dug first (if it may be)
            BlockPos target = inSight(build, pos);
            if (target == null) {
                bot.debug("great build: {} is behind something to keep; from another side later", pos.toShortString());
                giveUp(build);
                return Status.RUNNING;
            }
            breaker.start(target);
            BlockBreaker.Result result = breaker.tick();
            if (result == BlockBreaker.Result.FAILED) {
                giveUp(build);
            } else if (result == BlockBreaker.Result.SUCCESS && !target.equals(pos)) {
                build.recheck(level, target); // (the block in the way: maybe a cell of the plan)
            } else if (result == BlockBreaker.Result.SUCCESS && current.type() == GreatBuild.JobType.DIG) {
                build.done(level, current);
                job = null;
            }
            return Status.RUNNING;
        }
        if (!place(current)) {
            if (++placeFails > 3) {
                giveUp(build);
            }
            return Status.RUNNING;
        }
        build.done(level, current);
        job = null;
        return Status.RUNNING;
    }

    /**
     * The block itself if it's in sight; else the block in the way, if that's to go anyway (a cell
     * of the plan to dig out, or plain ground off the plan); null if it's part of the building.
     */
    private @Nullable BlockPos inSight(GreatBuild build, BlockPos pos) {
        ServerLevel level = bot.level();
        BlockHitResult hit = level.clip(new ClipContext(bot.getEyePosition(), Vec3.atCenterOf(pos),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) {
            return pos;
        }
        BlockPos blocker = hit.getBlockPos();
        int index = build.indexOf(blocker);
        Schematic plan = build.plan(level.getServer());
        if (index >= 0 && plan != null) {
            return plan.at(index).kind() == LegacyBlocks.Kind.AIR ? blocker : null;
        }
        return BlockRules.canBreak(level, blocker, level.getBlockState(blocker)) ? blocker : null;
    }

    private static Predicate<ItemStack> fillFor(LegacyBlocks.Spec spec) {
        return spec.kind() == LegacyBlocks.Kind.SOIL ? stack -> stack.is(Items.DIRT) : GreatBuild.FILL;
    }

    private boolean place(GreatBuild.Job current) {
        BlockPos pos = current.pos();
        if (current.type() == GreatBuild.JobType.FILL) {
            return BlockPlacer.place(bot, pos, fillFor(current.spec()));
        }
        BlockState wanted = current.spec().state();
        Item item = GreatBuild.itemFor(current.spec());
        Direction facing = wanted.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            ? wanted.getValue(BlockStateProperties.HORIZONTAL_FACING) : null;
        Direction face = null;
        Direction look = null;
        if (wanted.is(Blocks.WALL_TORCH) || wanted.is(Blocks.LADDER)) {
            face = facing.getOpposite(); // (against the wall behind it)
        } else if (wanted.is(Blocks.TORCH) || wanted.is(Blocks.LANTERN) || wanted.is(BlockTags.PRESSURE_PLATES)
            || wanted.is(BlockTags.WOOL_CARPETS) || wanted.is(Blocks.FLOWER_POT)) {
            face = Direction.DOWN;
        } else if (wanted.is(Blocks.CHEST) || wanted.is(Blocks.FURNACE)) {
            look = facing != null ? facing.getOpposite() : null; // (they face whoever puts them down)
        } else {
            look = facing; // stairs, doors, beds, gates: the way the player looks
        }
        if (!BlockPlacer.place(bot, pos, stack -> stack.is(item), face, look)) {
            return false;
        }
        turn(bot.level(), pos, wanted);
        return true;
    }

    /** Placed the wrong way round (upside-down stairs, top slabs, logs on their side...): turned the right way. */
    private static void turn(ServerLevel level, BlockPos pos, BlockState wanted) {
        BlockState placed = level.getBlockState(pos);
        if (!placed.is(wanted.getBlock()) || wanted.is(BlockTags.BEDS) || wanted.is(BlockTags.DOORS)) {
            return;
        }
        BlockState turned = placed;
        for (Property<?> property : GreatBuild.ORIENTATION) {
            turned = copy(turned, wanted, property);
        }
        if (turned != placed) {
            level.setBlock(pos, Block.updateFromNeighbourShapes(turned, level, pos), Block.UPDATE_ALL);
        }
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState into, BlockState from, Property<T> property) {
        return from.hasProperty(property) && into.hasProperty(property) ? into.setValue(property, from.getValue(property)) : into;
    }

    private void giveUp(GreatBuild build) {
        breaker.cancel();
        bot.navigator().stop();
        if (job != null) {
            build.giveUp(bot, job);
        }
        job = null;
    }

    private void dropJob(GreatBuild build) {
        breaker.cancel();
        job = null;
    }

    @Override
    public void stop() {
        breaker.cancel();
        bot.navigator().stop();
        bot.setLeaveWhileSmelting(false);
        if (child != null) {
            child.stop();
            child = null;
        }
        if (childIsRestock) {
            childIsRestock = false;
            bot.setMiningRule(null);
            GreatBuild build = build(bot);
            if (build != null) {
                build.restocking(bot, false);
            }
        }
        job = null;
    }

    @Override
    public @Nullable Target wanted() {
        return child != null ? child.wanted() : null;
    }

    @Override
    public String describe() {
        if (child != null) {
            return "great build: " + child.describe();
        }
        return switch (stage) {
            case PACK -> "great build: packing";
            case GO -> "great build: on the way";
            case RETURN -> "great build: going home";
            case WORK -> job == null ? "great build: looking for work"
                : "great build: " + job.type().name().toLowerCase() + " at " + job.pos().toShortString();
        };
    }
}
