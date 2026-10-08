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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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
    /** This job's block: walked to where it can be seen already (or there's nowhere): no second walk for that. */
    private boolean sightWalked;
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
    private boolean bagEmptied;
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
        if (stage == null) {
            // (just logged in at the site, a session on: there for the build, not for its chores at home)
            return sessionOn(bot) && GreatBuild.nearSite(bot.blockPosition());
        }
        return stage != Stage.PACK;
    }

    /** What a digger puts in the camp chest: what digging gives. */
    private static final Predicate<ItemStack> DUG = stack -> Stash.isBulk(stack) || stack.is(Items.GRAVEL) || stack.is(Items.SAND)
        || stack.is(Items.FLINT) || stack.is(Items.CLAY_BALL);
    /**
     * Taken to the camp chest only by the load: 10 stacks of what it dug, or, its bag full before that
     * (tools, food and the like take room), what there is if it's 2 stacks or more.
     */
    private static final int DUMP_LOAD = 640;
    private static final int DUMP_MIN = 128;
    /** A chest errand (putting in, taking out, putting one down) at most this often: one that failed isn't tried every tick. */
    private static final int CHEST_RETRY_TICKS = 20 * 30;
    private long nextChestAt;
    private long nextTakeAt;
    private int chestPlaceTicks;

    /** The digger's bag is full: what it dug into its camp chest (put down first, if there's none). Null: no can do. */
    private @Nullable Status dumpDug(GreatBuild build) {
        ServerLevel level = bot.level();
        long now = level.getGameTime();
        if (now < nextChestAt || Inv.count(bot, DUG) == 0) {
            return null;
        }
        // A chest of its camp with room; else any camp's nearby with room; else one more at its camp
        BlockPos chest = null;
        for (BlockPos pos : build.campChests(bot)) {
            if (level.getBlockEntity(pos) instanceof ChestBlockEntity entity && hasRoom(entity)) {
                chest = pos;
                break;
            }
        }
        BlockPos spot = chest == null ? build.nextCampChestSpot(bot) : null;
        if (chest == null && spot == null) {
            double best = (double) CHEST_RANGE * CHEST_RANGE;
            for (BlockPos pos : build.allCampChests(level)) {
                if (pos.distSqr(bot.blockPosition()) < best && level.getBlockEntity(pos) instanceof ChestBlockEntity entity && hasRoom(entity)) {
                    chest = pos;
                    best = pos.distSqr(bot.blockPosition());
                }
            }
        }
        if (chest != null) {
            nextChestAt = now + CHEST_RETRY_TICKS;
            child = CampChestTask.put(bot, chest, DUG);
            return Status.RUNNING;
        }
        if (spot == null) {
            nextChestAt = now + CHEST_RETRY_TICKS;
            return null; // (all full, and no more room for chests: it fills and builds with what it carries meanwhile)
        }
        if (Inv.count(bot, stack -> stack.is(Items.CHEST)) == 0) {
            nextChestAt = now + CHEST_RETRY_TICKS; // (if it can't make one: tried again later)
            bot.debug("great build: making a chest for my camp");
            child = new ObtainTask(bot, Target.of(Items.CHEST, 1), 0);
            return Status.RUNNING;
        }
        if (level.getBlockState(spot).is(Blocks.CHEST) || ++chestPlaceTicks > JOB_TICKS || !level.getBlockState(spot).canBeReplaced()) {
            if (level.getBlockState(spot).is(Blocks.CHEST)) {
                build.setCampChest(bot, spot);
            }
            chestPlaceTicks = 0;
            nextChestAt = now + CHEST_RETRY_TICKS * 10; // (no room there: some other time)
            return null;
        }
        if (!bot.isWithinBlockInteractionRange(spot, 0.5) || bot.getBoundingBox().intersects(new AABB(spot))) {
            Navigator navigator = bot.navigator();
            if (!navigator.isActive()) {
                navigator.navigate(Goal.reach(spot), JOB_NODES);
            }
            navigator.tick();
            return Status.RUNNING;
        }
        bot.navigator().stop();
        // Facing the fire like the camp's others (put down looking away from the fire, standing up): beside one, a
        // double chest (one turned another way, or put down crouching, stays single)
        bot.setShiftKeyDown(false);
        if (BlockPlacer.place(bot, spot, stack -> stack.is(Items.CHEST), Direction.DOWN, Direction.SOUTH) && level.getBlockState(spot).is(Blocks.CHEST)) {
            build.setCampChest(bot, spot);
            chestPlaceTicks = 0;
            bot.debug("great build: a chest at my camp, {}", spot.toShortString());
        }
        return Status.RUNNING;
    }

    /** Air (or anything without a body) on some side of it: a face there to see and aim at. */
    private static boolean openFace(ServerLevel level, BlockPos pos) {
        for (Direction face : Direction.values()) {
            BlockPos side = pos.relative(face);
            if (level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRoom(ChestBlockEntity chest) {
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Out of materials: building blocks (or fill) out of the nearest camp chest with some, anyone's. */
    private boolean takeFromCampChest(GreatBuild build) {
        ServerLevel level = bot.level();
        long now = level.getGameTime();
        if (now < nextTakeAt || Inv.freeSlots(bot) <= 3) {
            return false;
        }
        nextTakeAt = now + CHEST_RETRY_TICKS;
        Predicate<ItemStack> useful = stack -> build.isMaterial(stack.getItem()) || GreatBuild.FILL.test(stack);
        BlockPos best = null;
        double bestDistance = (double) CHEST_RANGE * CHEST_RANGE;
        for (BlockPos pos : build.allCampChests(level)) {
            double distance = pos.distSqr(bot.blockPosition());
            if (distance < bestDistance && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof ChestBlockEntity chest
                && Stash.count(chest, useful) > 0) {
                best = pos;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return false;
        }
        child = CampChestTask.take(bot, best, useful, (Inv.freeSlots(bot) - 3) * 64);
        return true;
    }

    /** Camp chests this close are used. */
    private static final int CHEST_RANGE = 96;
    /** Under this much dirt and cobblestone in the bag, a builder takes some out of a camp chest (the digger's). */
    private static final int FILL_LOW = 32;
    private static final int FILL_TAKE = 3 * 64;
    private static final int FILL_CHECK_TICKS = 20 * 60;
    private long nextFillAt;

    /** Next to no dirt or cobblestone to fill holes with: 2-3 stacks out of the nearest camp chest that has some. */
    private boolean takeFill(GreatBuild build) {
        ServerLevel level = bot.level();
        long now = level.getGameTime();
        if (now < nextFillAt || Inv.count(bot, GreatBuild.FILL) >= FILL_LOW || Inv.freeSlots(bot) < 6) {
            return false;
        }
        nextFillAt = now + FILL_CHECK_TICKS;
        BlockPos best = null;
        double bestDistance = (double) CHEST_RANGE * CHEST_RANGE;
        for (BlockPos pos : build.allCampChests(level)) {
            double distance = pos.distSqr(bot.blockPosition());
            if (distance < bestDistance && level.isLoaded(pos) && level.getBlockEntity(pos) instanceof ChestBlockEntity chest
                && Stash.count(chest, GreatBuild.FILL) >= FILL_LOW) {
                best = pos;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return false;
        }
        bot.debug("great build: out of dirt to fill with; some from the camp chest at {}", best.toShortString());
        child = CampChestTask.take(bot, best, GreatBuild.FILL, Math.min(FILL_TAKE, (Inv.freeSlots(bot) - 3) * 64));
        return true;
    }

    /** Camp furnaces are looked at this often, and only those this close. */
    private static final int OUTPUT_CHECK_TICKS = 20 * 30;
    private long nextOutputCheck;
    private static final int OUTPUT_RANGE = 48;

    /** The nearest camp furnace (anyone's) that has gone out with something done in it, or null. */
    private @Nullable BlockPos doneFurnace(GreatBuild build) {
        BlockPos best = null;
        double bestDistance = (double) OUTPUT_RANGE * OUTPUT_RANGE;
        for (BlockPos pos : build.allCampFurnaces()) {
            double distance = pos.distSqr(bot.blockPosition());
            if (distance < bestDistance && FurnaceOutputTask.finished(bot, pos)) {
                best = pos;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** About this share of the bots at the site (a fifth) clear it first (digging out what the plan has empty), the rest build. */
    private static final double DIGGERS = 0.2;

    /** One of the site's diggers: the first fifth of the bots working there (by id, so the same ones; at least one). */
    private static boolean isDigger(BotPlayer bot) {
        List<UUID> working = new ArrayList<>();
        AWAY.forEach((uuid, stage) -> {
            if (stage == Stage.WORK) {
                working.add(uuid);
            }
        });
        working.sort(null);
        int count = Math.max(1, (int) Math.round(working.size() * DIGGERS));
        int rank = working.indexOf(bot.getUUID());
        return rank >= 0 && rank < count;
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
            if (!bagEmptied && !bot.memory().chests().isEmpty() && !Stash.toStore(bot).isEmpty()) {
                // What it won't need there into the chests first (spare tools, seeds, what it brought home...): a full
                // bag left most of its order at home (Makena took none of hers once). Its order and the plan's blocks stay
                bagEmptied = true;
                bot.debug("great build: emptying my bag into the chests to make room for my order");
                child = ChestTask.store(bot);
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
        if (job == null && Inv.freeSlots(bot) >= 3 && bot.level().getGameTime() >= nextOutputCheck) {
            // What's done in the camps' furnaces (its own or anyone's, all of it is for the build): taken out to build with
            nextOutputCheck = bot.level().getGameTime() + OUTPUT_CHECK_TICKS; // (one it can't get to isn't tried every tick)
            BlockPos ready = doneFurnace(build);
            if (ready != null) {
                child = new FurnaceOutputTask(bot, ready);
                return Status.RUNNING;
            }
        }
        if (job == null) {
            boolean digger = isDigger(bot);
            if (!digger && takeFill(build)) {
                return Status.RUNNING; // (what the digger dug, to fill the holes with)
            }
            int dug = digger ? Inv.count(bot, DUG) : 0;
            if (digger && (dug >= DUMP_LOAD || Inv.freeSlots(bot) < 3 && !Stash.makeRoom(bot, 3, false) && dug >= DUMP_MIN)) {
                // Bag full of what it dug (rubbish proper thrown out first): into the camp chest in one go, to dig
                // on; not much of it, it fills and builds with it instead (it isn't thrown away, as rubbish would be)
                Status dump = dumpDug(build);
                if (dump != null) {
                    return dump;
                }
            }
            Stash.makeRoom(bot, 3);
            job = build.nextJob(bot, true, digger);
            boolean carries = carriesMaterial(build);
            if ((job == null || job.type() != GreatBuild.JobType.PLACE) && !carries && !(digger && job != null)) {
                // Out of materials: from a camp chest if there's some there, else from the mine under the site
                if (takeFromCampChest(build) || restock(build)) {
                    dropJob(build);
                    return Status.RUNNING;
                }
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
        boolean near = bot.isWithinBlockInteractionRange(pos, 0.5);
        if (!dig && inside && near && !bot.blockPosition().equals(pos) && !bot.blockPosition().above().equals(pos)) {
            // Only its body over the edge of the cell (it stands next to it): a step back into the middle of its
            // own block (the way there is "reached" already: it stood there till the job timed out)
            bot.navigator().stop();
            bot.controller().moveTowards(Vec3.atBottomCenterOf(bot.blockPosition()), false, false);
            return Status.RUNNING;
        }
        if (jobTicks == 1) {
            sightWalked = false;
        }
        // To dig it, also where it can be seen (like a player: not through other blocks); one walk for that
        boolean unseen = dig && near && !sightWalked && !Goal.canSee(level, bot.getEyePosition(), pos);
        if (!near || !dig && inside || unseen) {
            Navigator navigator = bot.navigator();
            if (!navigator.isActive() || jobTicks == 1) {
                // (one walled in all round is never in sight: just close by, it's swapped where it is)
                navigator.navigate(dig && openFace(level, pos) ? Goal.reachVisible(level, pos) : Goal.reach(pos), JOB_NODES);
            }
            Navigator.Status status = navigator.tick();
            if (status == Navigator.Status.FAILED) {
                if (dig && near) {
                    sightWalked = true; // (nowhere to see it from: what's in the way, if it may go, is dug first)
                } else {
                    giveUp(build);
                }
            } else if (status == Navigator.Status.SUCCESS && dig) {
                sightWalked = true;
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
        Vec3 eyes = bot.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eyes, Vec3.atCenterOf(pos),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) {
            return pos;
        }
        // Like a player, it can aim at any open face (the top of a block in the floor, say), not just the middle
        boolean open = false;
        for (Direction face : Direction.values()) {
            BlockPos side = pos.relative(face);
            if (!level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
                continue;
            }
            open = true;
            Vec3 point = Vec3.atCenterOf(pos).add(face.getStepX() * 0.45, face.getStepY() * 0.45, face.getStepZ() * 0.45);
            BlockHitResult faceHit = level.clip(new ClipContext(eyes, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
            if (faceHit.getType() == HitResult.Type.MISS || faceHit.getBlockPos().equals(pos)) {
                return pos;
            }
        }
        if (!open) {
            // Walled in on every side (a wrong block inside the foundation): no way to see it short of breaking
            // finished blocks round it, so it's swapped where it is
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
