package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.Inv;
import com.minebot.bot.action.PlaceNearby;
import com.minebot.bot.craft.Recipes;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Approach;
import com.minebot.bot.world.Stations;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import java.util.function.Predicate;

/**
 * Smelts items in a furnace until the target count is reached. Uses the home
 * furnace if it's near, otherwise puts one down (and takes it back after).
 */
public class SmeltTask extends Task {
    private static final int SLOT_INPUT = 0;
    private static final int SLOT_FUEL = 1;
    private static final int SLOT_RESULT = 2;
    /** Furnaces each bot couldn't get to (buried, walled in): not tried again. */
    private static final java.util.Map<java.util.UUID, java.util.Set<BlockPos>> UNREACHABLE = new java.util.HashMap<>();

    /** Forgotten when the bot leaves the server: back again, it may try those once more. */
    public static void forget(java.util.UUID bot) {
        UNREACHABLE.remove(bot);
    }
    private static final int HOME_FURNACE_RANGE = 64;
    private static final int NEARBY_FURNACE_RANGE = 24;
    private static final int MAX_IDLE_TICKS = 20 * 30;

    private final Recipes.CookOption option;
    private final Target target;
    private final Predicate<ItemStack> keep;

    private @Nullable BlockPos furnace;
    private boolean placedFurnace;
    /** Looked in the chest for saplings to burn (once). */
    private boolean saplingsTaken;
    private boolean surplusTaken;
    /** Someone else's furnace: take only our own output, leave their fuel. */
    private boolean foreign;
    private @Nullable Task child;
    private @Nullable BlockBreaker pickup;
    private int idleTicks;
    private int failures;
    private @Nullable PlaceNearby placer;
    private @Nullable Approach approach;
    /** Putting a furnace in the home (the child task); if that fails, place one here. */
    private boolean furnishing;
    private boolean triedHome;
    private @Nullable Approach spareApproach;
    private @Nullable List<BlockPos> spareCache;
    private long spareCacheUntil;

    public SmeltTask(BotPlayer bot, Recipes.CookOption option, Target target) {
        super(bot);
        this.option = option;
        this.target = target;
        // Never burn what we're smelting (logs turning into charcoal)
        this.keep = stack -> option.input().test(stack);
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
            boolean wasFurnishing = furnishing;
            furnishing = false;
            if (status == Status.FAILURE && !wasFurnishing) {
                return Status.FAILURE; // couldn't get input, fuel or a furnace: let the planner try another way
            }
        }
        if (pickup != null) {
            return pickUpFurnace();
        }

        ServerLevel level = bot.level();
        if (furnace != null && !(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity)) {
            furnace = null;
            placedFurnace = false;
        }
        if (furnace == null && target.satisfied(bot)) {
            // Done (e.g. just took our furnace back): don't put down another one
            return Status.SUCCESS;
        }
        if (furnace == null && !locateFurnace()) {
            return failures > 3 ? Status.FAILURE : Status.RUNNING;
        }

        AbstractFurnaceBlockEntity entity = (AbstractFurnaceBlockEntity) level.getBlockEntity(furnace);
        int inFurnace = entity.getItem(SLOT_INPUT).getCount() + entity.getItem(SLOT_RESULT).getCount();
        List<AbstractFurnaceBlockEntity> spares = !foreign ? sparesFor(furnace) : List.of();
        for (AbstractFurnaceBlockEntity spare : spares) {
            inFurnace += spare.getItem(SLOT_INPUT).getCount() + spare.getItem(SLOT_RESULT).getCount();
        }
        int stillNeeded = target.missing(bot) - inFurnace;

        if (target.satisfied(bot) && entity.getItem(SLOT_RESULT).isEmpty() && entity.getItem(SLOT_INPUT).isEmpty()) {
            return finish(entity);
        }

        // Bring enough input and fuel before walking over
        if (stillNeeded > 0 && Inv.count(bot, option.input()::test) < stillNeeded) {
            child = new ObtainTask(bot, Target.ingredient(option.input(), stillNeeded), 1);
            return Status.RUNNING;
        }
        int unfuelled = 0; // (input left in its other furnaces with no fuel)
        for (AbstractFurnaceBlockEntity spare : spares) {
            if (spare.getItem(SLOT_FUEL).isEmpty() && !spare.getBlockState().getOptionalValue(AbstractFurnaceBlock.LIT).orElse(false)) {
                unfuelled += spare.getItem(SLOT_INPUT).getCount();
            }
        }
        int burnNeeded = Math.max(0, stillNeeded + entity.getItem(SLOT_INPUT).getCount() + unfuelled) * option.cookingTime();
        if (burnNeeded > 0 && !saplingsTaken && Home.isNear(bot, 32)) {
            // Saplings and leaf litter put away in the chest burn first, before coal or wood
            saplingsTaken = true;
            Predicate<ItemStack> kindling = stack -> stack.is(ItemTags.SAPLINGS) || stack.is(Items.LEAF_LITTER);
            int stored = ChestTask.stored(bot, new Target("kindling", kindling, 1));
            if (stored > 0) {
                furnishing = true; // (a side errand: no matter if it fails)
                // (half an item each: as much as the batch takes, a stack and a half at most)
                int wanted = Math.min(stored, Math.min(96, burnNeeded / 100 + 1));
                child = ChestTask.withdraw(bot, new Target("kindling", kindling, Inv.count(bot, kindling) + wanted));
                return Status.RUNNING;
            }
        }
        if (burnNeeded > 0 && !surplusTaken && Home.isNear(bot, 32)) {
            // Chests, crafting tables and doors made over what it keeps: burnt too (a chest burns as long as a log)
            surplusTaken = true;
            Predicate<ItemStack> surplus = stack -> Fuel.isSurplusFurniture(bot, stack);
            int stored = ChestTask.stored(bot, new Target("spare furniture", surplus, 1));
            if (stored > 0) {
                furnishing = true; // (a side errand: no matter if it fails)
                int wanted = Math.min(stored, Math.min(64, burnNeeded / 250 + 1));
                child = ChestTask.withdraw(bot, new Target("spare furniture", surplus, Inv.count(bot, surplus) + wanted));
                return Status.RUNNING;
            }
        }
        if (burnNeeded > 0 && (entity.getItem(SLOT_FUEL).isEmpty() || unfuelled > 0) && Fuel.ticks(bot, keep) < Math.min(burnNeeded, 1600)) {
            // (8 more than it has: with a few saplings already in the bag, "8 fuel" would be done at once, again and again)
            java.util.function.Predicate<ItemStack> fuel = Fuel.GATHERABLE.and(keep.negate());
            child = new ObtainTask(bot, new Target("fuel", fuel, Inv.count(bot, fuel) + 8), 1);
            return Status.RUNNING;
        }

        // Its other furnaces at home (in the house, the workshop's second): each gets its share of a big
        // batch, and what they've cooked is fetched once they're done
        AbstractFurnaceBlockEntity visit = null;
        int share = 0;
        int inSpares = 0;
        for (AbstractFurnaceBlockEntity spare : spares) {
            inSpares += spare.getItem(SLOT_INPUT).getCount();
        }
        // An even share of the whole batch for each furnace (what's in them already counts)
        int mainInput = option.input().test(entity.getItem(SLOT_INPUT)) ? entity.getItem(SLOT_INPUT).getCount() : 0;
        int batch = Math.max(0, stillNeeded) + inSpares + mainInput;
        int each = (batch + spares.size()) / (spares.size() + 1);
        for (AbstractFurnaceBlockEntity spare : spares) {
            boolean ready = !spare.getItem(SLOT_RESULT).isEmpty() && (spare.getItem(SLOT_INPUT).isEmpty() || spare.getItem(SLOT_RESULT).getCount() >= 16);
            int portion = batch >= MIN_SHARE ? Math.min(stillNeeded, each - spare.getItem(SLOT_INPUT).getCount()) : 0;
            // (input left in it, and the fuel gone: one stack of saplings didn't cook it all)
            boolean outOfFuel = !spare.getItem(SLOT_INPUT).isEmpty() && spare.getItem(SLOT_FUEL).isEmpty()
                && !spare.getBlockState().getOptionalValue(AbstractFurnaceBlock.LIT).orElse(false) && Fuel.ticks(bot, keep) > 0;
            if (ready || outOfFuel || portion > 0 && spare.getItem(SLOT_INPUT).isEmpty()) {
                visit = spare;
                share = portion;
                break;
            }
        }
        if (visit != null) {
            BlockPos at = visit.getBlockPos();
            if (spareApproach == null || !spareApproach.target().equals(at)) {
                spareApproach = new Approach(bot, at);
            }
            Approach.Result reachedSpare = spareApproach.tick();
            if (reachedSpare == Approach.Result.MOVING) {
                return Status.RUNNING;
            }
            spareApproach = null;
            if (reachedSpare == Approach.Result.FAILED) {
                UNREACHABLE.computeIfAbsent(bot.getUUID(), id -> new java.util.HashSet<>()).add(at);
                return Status.RUNNING;
            }
            bot.navigator().stop();
            bot.controller().lookAt(Vec3.atCenterOf(at));
            takeResult(visit);
            loadFuel(visit);
            if (share > 0) {
                loadInput(visit, share);
                loadFuel(visit);
                bot.debug("put {} in my other furnace at {}", visit.getItem(SLOT_INPUT).getCount(), at.toShortString());
            }
            idleTicks = 0;
            return Status.RUNNING;
        }

        if (approach == null || !approach.target().equals(furnace)) {
            approach = new Approach(bot, furnace);
        }
        Approach.Result reached = approach.tick();
        if (reached == Approach.Result.FAILED) {
            if (furnace.equals(bot.memory().furnace())) {
                // Walled in or buried: no use as the home furnace; a new one goes in
                bot.debug("can't get to my furnace at {}; forgetting it", furnace.toShortString());
                bot.memory().setFurnace(null);
            }
            UNREACHABLE.computeIfAbsent(bot.getUUID(), id -> new java.util.HashSet<>()).add(furnace);
            furnace = null;
            approach = null;
            return ++failures > 3 ? Status.FAILURE : Status.RUNNING;
        }
        if (reached == Approach.Result.MOVING) {
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().lookAt(Vec3.atCenterOf(furnace));

        boolean progressed = !foreign && clearOut(entity);
        progressed |= takeResult(entity);
        // More in this one than its share while another stands empty (loaded before that one was there):
        // the extra comes out again, for the others
        ItemStack input = entity.getItem(SLOT_INPUT);
        if (!spares.isEmpty() && spares.stream().anyMatch(spare -> spare.getItem(SLOT_INPUT).isEmpty())
            && option.input().test(input) && input.getCount() - each >= MIN_SHARE / 2) {
            Inv.give(bot, input.split(input.getCount() - each));
            entity.setChanged();
            bot.debug("took some back out of the furnace to share it with the others");
            return Status.RUNNING;
        }
        progressed |= loadInput(entity, stillNeeded);
        progressed |= loadFuel(entity);
        boolean lit = level.getBlockState(furnace).getOptionalValue(AbstractFurnaceBlock.LIT).orElse(false);
        int longest = entity.getItem(SLOT_INPUT).getCount();
        for (AbstractFurnaceBlockEntity spare : spares) {
            // (waiting by the home furnace while the others cook is still getting on)
            lit |= level.getBlockState(spare.getBlockPos()).getOptionalValue(AbstractFurnaceBlock.LIT).orElse(false);
            longest = Math.max(longest, spare.getItem(SLOT_INPUT).getCount());
        }
        // All of it in, and minutes to go: no need to stand there (see GreatBuildPrepTask)
        int ticksLeft = longest * option.cookingTime();
        if (bot.leaveWhileSmelting() && stillNeeded - (entity.getItem(SLOT_INPUT).getCount() - mainInput) <= 0
            && ticksLeft > LEAVE_AFTER && (lit || entity.getItem(SLOT_FUEL).getCount() > 0)
            && fuelled(entity) && spares.stream().allMatch(this::fuelled)) {
            // (only with fuel in for all of it: Bedrock left 24 stone to cook on a few coal, and the furnace went cold)
            bot.setLeftCooking(ticksLeft);
        }
        if (progressed || lit) {
            idleTicks = 0;
        } else if (++idleTicks > MAX_IDLE_TICKS) {
            return Status.FAILURE;
        }
        return Status.RUNNING;
    }

    /** At the Great Build: the furnaces of its camp there (see GreatBuildTask.setUpCamp). */
    private List<BlockPos> campFurnaces() {
        if (!GreatBuildTask.isAway(bot)) {
            return List.of();
        }
        com.minebot.bot.build.GreatBuild build = com.minebot.bot.build.GreatBuild.get(bot.level().getServer());
        return build.exists() ? build.campFurnaces(bot) : List.of();
    }

    /** The bot's other furnaces to share a batch with: the rest of its camp's, or (at home) its other ones there. */
    private List<AbstractFurnaceBlockEntity> sparesFor(BlockPos main) {
        List<BlockPos> camp = campFurnaces();
        if (camp.contains(main)) {
            List<AbstractFurnaceBlockEntity> result = new java.util.ArrayList<>();
            for (BlockPos pos : camp) {
                if (!pos.equals(main) && bot.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity entity && isFree(entity)) {
                    result.add(entity);
                }
            }
            return result;
        }
        BlockPos home = bot.memory().furnace();
        boolean own = main.equals(home) || otherFurnaces(bot).contains(main);
        if (!own) {
            return List.of();
        }
        // Its other furnaces, whichever of them this batch went into first (its main one busy, Bedrock put a whole
        // stack in the second while the first stood empty); only those close by: one 200 blocks off isn't worth the walk
        List<AbstractFurnaceBlockEntity> result = new java.util.ArrayList<>();
        for (AbstractFurnaceBlockEntity entity : spares()) {
            if (!entity.getBlockPos().equals(main) && entity.getBlockPos().closerThan(main, SPARE_DISTANCE)) {
                result.add(entity);
            }
        }
        if (home != null && !home.equals(main) && home.closerThan(main, SPARE_DISTANCE)
            && bot.level().getBlockEntity(home) instanceof AbstractFurnaceBlockEntity entity && isFree(entity)) {
            result.add(entity);
        }
        return result;
    }

    private boolean locateFurnace() {
        ServerLevel level = bot.level();
        // At the Great Build: its own camp's furnaces
        for (BlockPos pos : campFurnaces()) {
            if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity entity && isFree(entity)) {
                furnace = pos;
                foreign = false;
                return true;
            }
        }
        if (Inv.isFood(option.result())) {
            // Food cooks twice as fast in a smoker (the house kitchen has one): its own first
            BlockPos own = ownSmoker(bot);
            if (own != null && level.getBlockEntity(own) instanceof AbstractFurnaceBlockEntity entity && isFree(entity)) {
                furnace = own;
                foreign = false;
                return true;
            }
            BlockPos smoker = Stations.nearest(bot, state -> state.is(Blocks.SMOKER), NEARBY_FURNACE_RANGE,
                (pos, state) -> level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity entity && isFree(entity));
            if (smoker != null) {
                furnace = smoker;
                // (someone else's: only our own food comes out, their fuel stays; the one in our house is ours)
                BlockPos bed = bot.memory().bed();
                foreign = !(bot.memory().houseDone() && bed != null && smoker.closerThan(bed, 12));
                return true;
            }
        }
        BlockPos home = bot.memory().furnace();
        if (home != null && level.dimension() == bot.memory().homeDimension()
            && level.getBlockEntity(home) instanceof AbstractFurnaceBlockEntity
            && level.getBlockState(home).is(Blocks.FURNACE)
            && home.closerThan(bot.blockPosition(), HOME_FURNACE_RANGE)) {
            furnace = home;
            foreign = false;
            return true;
        }
        // Anyone's furnace nearby that isn't busy with something else
        java.util.Set<BlockPos> unreachable = UNREACHABLE.getOrDefault(bot.getUUID(), java.util.Set.of());
        BlockPos shared = Stations.nearest(bot, state -> state.is(Blocks.FURNACE), NEARBY_FURNACE_RANGE,
            (pos, state) -> !unreachable.contains(pos) && level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity entity && isFree(entity));
        if (shared != null) {
            furnace = shared;
            foreign = true;
            return true;
        }
        if (!triedHome && bot.memory().furnace() == null && Home.isNear(bot, HOME_FURNACE_RANGE)) {
            // Home close by but no furnace there: put one in for good, at the bed's level
            triedHome = true;
            furnishing = true;
            child = FurnishTask.only(bot, Items.FURNACE);
            return false;
        }
        if (bot.getInventory().countItem(Items.FURNACE) == 0) {
            child = new ObtainTask(bot, Target.of(Items.FURNACE, 1), 1);
            return false;
        }
        if (placer == null) {
            placer = new PlaceNearby(bot, stack -> stack.is(Items.FURNACE));
        }
        BlockPos spot = placer.tick();
        if (spot == null && placer.failed()) {
            placer = null;
            failures = Integer.MAX_VALUE / 2; // no room anywhere: give up
        }
        if (spot != null) {
            placer = null;
            furnace = spot;
            foreign = false;
            // (put down out in the open, it's a loan: taken back after - a furnace stays only inside a house or workshop)
            placedFurnace = true;
            return true;
        }
        return false;
    }

    /** A batch smaller than this goes in the home furnace alone. */
    private static final int MIN_SHARE = 8;
    /** Other furnaces further than this from the main one aren't shared with. */
    private static final int SPARE_DISTANCE = 24;
    /** With more cooking left than this, a bot that may leave (GreatBuildPrepTask) goes and comes back. */
    private static final int LEAVE_AFTER = 20 * 30;
    /** Furnaces this close to the bed (in the house) or the workshop or the home furnace are the bot's own too. */
    private static final int OWN_RANGE = 8;

    /** The bot's other furnaces at home, besides the one it remembers (in the house, the workshop's second). */
    public static List<BlockPos> otherFurnaces(BotPlayer bot) {
        BlockPos home = bot.memory().furnace();
        ServerLevel level = Home.levelIfHere(bot);
        if (home == null || level == null || !level.isLoaded(home)) {
            return List.of();
        }
        java.util.Set<BlockPos> found = new java.util.LinkedHashSet<>();
        for (BlockPos centre : new BlockPos[] {home, bot.memory().workshop(), bot.memory().bed()}) {
            if (centre == null || !level.isLoaded(centre)) {
                continue;
            }
            for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-OWN_RANGE, -2, -OWN_RANGE), centre.offset(OWN_RANGE, 2, OWN_RANGE))) {
                if (!pos.equals(home) && level.getBlockState(pos).is(Blocks.FURNACE)) {
                    found.add(pos.immutable());
                }
            }
        }
        return List.copyOf(found);
    }

    /** A smoker in its house or workshop (food cooks there: twice as fast, and the furnace stays free), or null. */
    public static @Nullable BlockPos ownSmoker(BotPlayer bot) {
        ServerLevel level = Home.levelIfHere(bot);
        if (level == null) {
            return null;
        }
        for (BlockPos centre : new BlockPos[] {bot.memory().bed(), bot.memory().workshop()}) {
            if (centre == null || !level.isLoaded(centre)) {
                continue;
            }
            for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-OWN_RANGE, -2, -OWN_RANGE), centre.offset(OWN_RANGE, 2, OWN_RANGE))) {
                if (level.getBlockState(pos).is(Blocks.SMOKER)) {
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    /** Its other furnaces that are free for this batch (empty, or holding this very job). */
    private List<AbstractFurnaceBlockEntity> spares() {
        if (spareCache == null || bot.level().getGameTime() >= spareCacheUntil) {
            spareCache = otherFurnaces(bot);
            spareCacheUntil = bot.level().getGameTime() + 100;
        }
        java.util.Set<BlockPos> unreachable = UNREACHABLE.getOrDefault(bot.getUUID(), java.util.Set.of());
        List<AbstractFurnaceBlockEntity> result = new java.util.ArrayList<>();
        for (BlockPos pos : spareCache) {
            if (!unreachable.contains(pos) && bot.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity entity && isFree(entity)) {
                result.add(entity);
            }
        }
        return result;
    }

    /** A furnace we can use: empty, or already smelting what we want. */
    private boolean isFree(AbstractFurnaceBlockEntity entity) {
        ItemStack input = entity.getItem(SLOT_INPUT);
        ItemStack result = entity.getItem(SLOT_RESULT);
        return (input.isEmpty() || option.input().test(input))
            && (result.isEmpty() || ItemStack.isSameItem(result, option.result()));
    }

    /**
     * Its own furnace: anything else left in it (a nugget from smelting old gear,
     * leftovers of an interrupted job) is taken out, or nothing new can come out.
     */
    private boolean clearOut(AbstractFurnaceBlockEntity entity) {
        boolean cleared = false;
        ItemStack result = entity.getItem(SLOT_RESULT);
        if (!result.isEmpty() && !ItemStack.isSameItem(result, option.result())) {
            bot.debug("took {} {} out of the furnace", result.getCount(), result.getItem());
            entity.setItem(SLOT_RESULT, ItemStack.EMPTY);
            Inv.give(bot, result);
            cleared = true;
        }
        ItemStack input = entity.getItem(SLOT_INPUT);
        if (!input.isEmpty() && !option.input().test(input)) {
            bot.debug("took {} {} out of the furnace", input.getCount(), input.getItem());
            entity.setItem(SLOT_INPUT, ItemStack.EMPTY);
            Inv.give(bot, input);
            cleared = true;
        }
        if (cleared) {
            entity.setChanged();
        }
        return cleared;
    }

    private boolean takeResult(AbstractFurnaceBlockEntity entity) {
        ItemStack result = entity.getItem(SLOT_RESULT);
        if (result.isEmpty() || !ItemStack.isSameItem(result, option.result())) {
            // (in a shared furnace, someone else's output stays where it is)
            return false;
        }
        entity.setItem(SLOT_RESULT, ItemStack.EMPTY);
        Inv.give(bot, result);
        // Same XP a player gets for taking furnace output
        entity.awardUsedRecipesAndPopExperience(bot);
        return true;
    }

    private boolean loadInput(AbstractFurnaceBlockEntity entity, int stillNeeded) {
        ItemStack slot = entity.getItem(SLOT_INPUT);
        if (stillNeeded <= 0 || (!slot.isEmpty() && slot.getCount() >= slot.getMaxStackSize())) {
            return false;
        }
        int from = Inv.findSlot(bot, stack -> option.input().test(stack)
            && (slot.isEmpty() || ItemStack.isSameItemSameComponents(stack, slot)));
        if (from < 0) {
            return false;
        }
        ItemStack stack = bot.getInventory().getItem(from);
        int amount = Math.min(stillNeeded, Math.min(stack.getCount(), stack.getMaxStackSize() - slot.getCount()));
        if (slot.isEmpty()) {
            entity.setItem(SLOT_INPUT, stack.split(amount));
        } else {
            slot.grow(amount);
            stack.shrink(amount);
            entity.setChanged();
        }
        return amount > 0;
    }

    /** Fuel in this furnace for all that's in it to cook (the one burning now counted as one item's worth). */
    private boolean fuelled(AbstractFurnaceBlockEntity furnace) {
        ItemStack input = furnace.getItem(SLOT_INPUT);
        if (input.isEmpty()) {
            return true;
        }
        ItemStack fuel = furnace.getItem(SLOT_FUEL);
        int ticks = fuel.isEmpty() ? 0 : bot.level().fuelValues().burnDuration(fuel) * fuel.getCount();
        if (furnace.getBlockState().getOptionalValue(AbstractFurnaceBlock.LIT).orElse(false)) {
            ticks += option.cookingTime();
        }
        return ticks >= input.getCount() * option.cookingTime();
    }

    private boolean loadFuel(AbstractFurnaceBlockEntity entity) {
        ItemStack slot = entity.getItem(SLOT_FUEL);
        int from = Fuel.pick(bot, keep);
        if (from < 0) {
            return false;
        }
        ItemStack stack = bot.getInventory().getItem(from);
        if (!slot.isEmpty() && !ItemStack.isSameItemSameComponents(slot, stack)) {
            return false;
        }
        int burnPerItem = Math.max(1, bot.level().fuelValues().burnDuration(stack));
        int itemsToCook = entity.getItem(SLOT_INPUT).getCount();
        int needed = (itemsToCook * option.cookingTime() + burnPerItem - 1) / burnPerItem;
        // Its own furnace keeps a store of fuel in it besides, whatever burns (never left to go cold mid-batch);
        // of coal and wood it keeps some in the bag (torches, crafting)
        int reserve = foreign ? 0 : (FuelFurnacesTask.RESERVE_TICKS + burnPerItem - 1) / burnPerItem;
        int want = Math.max(needed, reserve) - slot.getCount();
        int spare = needed <= slot.getCount() ? FuelFurnacesTask.spareFuel(bot, stack) : stack.getCount();
        int amount = Math.min(Math.min(want, stack.getCount()), Math.min(spare, slot.getMaxStackSize() - slot.getCount()));
        if (amount <= 0 || slot.isEmpty() && itemsToCook == 0 && reserve == 0) {
            return false;
        }
        if (slot.isEmpty()) {
            entity.setItem(SLOT_FUEL, stack.split(amount));
        } else {
            slot.grow(stack.split(amount).getCount());
        }
        entity.setChanged();
        return true;
    }

    private Status finish(AbstractFurnaceBlockEntity entity) {
        // Leftover fuel stays in its own furnaces (a store, ready for next time); out of one it put down to take away again, back
        ItemStack leftoverFuel = entity.getItem(SLOT_FUEL);
        if (!leftoverFuel.isEmpty() && !foreign && placedFurnace) {
            entity.setItem(SLOT_FUEL, ItemStack.EMPTY);
            Inv.give(bot, leftoverFuel);
        }
        if (placedFurnace && furnace != null) {
            pickup = new BlockBreaker(bot);
            pickup.start(furnace);
            return Status.RUNNING;
        }
        return Status.SUCCESS;
    }

    private Status pickUpFurnace() {
        BlockBreaker.Result result = pickup.tick();
        if (result == BlockBreaker.Result.RUNNING) {
            return Status.RUNNING;
        }
        BlockPos at = furnace;
        pickup = null;
        placedFurnace = false;
        furnace = null;
        if (result == BlockBreaker.Result.SUCCESS && at != null) {
            child = new CollectItemsTask(bot, Vec3.atCenterOf(at), 3.0, 60);
            return Status.RUNNING;
        }
        return Status.SUCCESS;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        if (pickup != null) {
            pickup.cancel();
        }
        if (placedFurnace && furnace != null && bot.level().getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity) {
            bot.leftBehind().add(furnace); // interrupted before taking it back: fetch it later
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
        String what = "smelting " + target;
        return child != null ? what + ": " + child.describe() : what;
    }
}
