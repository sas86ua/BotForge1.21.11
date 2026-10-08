package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.build.Blueprint;
import com.minebot.bot.path.Approach;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Rubbish piles up in the chests at home along with everything else; now and
 * then the bot takes it out again and burns it in the lava it knows of: junk
 * (flowers, leaves, gravel, rotten flesh...) and bulk blocks beyond a few
 * stacks of each (the house needs stone, not a chestful of diorite).
 */
public class JunkRunTask extends Task {
    private static final int CHECK_INTERVAL = 20 * 60 * 10;
    /** Not worth a trip for less. */
    private static final int MIN_RUBBISH = 64;
    /** Bulk blocks of each kind left in the chests. */
    private static final int BULK_KEEP = 192;
    /** Slots kept free in the bag for what it finds on the way. */
    /** Free slots left in the bag when taking rubbish out: more than "store" needs (4), or it would put it all back. */
    private static final int SPARE_SLOTS = 5;

    private final Deque<BlockPos> chests = new ArrayDeque<>();
    private @Nullable Approach approach;
    private @Nullable Task dump;
    private int taken;
    /** Chests each bot couldn't get to, and until when they're left out (10 minutes). */
    private static final java.util.Map<java.util.UUID, java.util.Map<BlockPos, Long>> UNREACHABLE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long UNREACHABLE_TICKS = 20 * 60 * 10;

    public JunkRunTask(BotPlayer bot) {
        super(bot);
        long now = bot.level().getGameTime();
        java.util.Map<BlockPos, Long> unreachable = UNREACHABLE.getOrDefault(bot.getUUID(), java.util.Map.of());
        for (BlockPos pos : bot.memory().chests()) {
            if (unreachable.getOrDefault(pos, 0L) > now) {
                continue; // (couldn't get to it a while ago: not searched for again yet, that's costly)
            }
            if (container(bot, pos) instanceof RandomizableContainerBlockEntity chest && !rubbish(bot, chest, null).isEmpty()) {
                chests.add(pos);
            }
        }
    }

    public static boolean wanted(BotPlayer bot) {
        return Home.isNear(bot, 32) && !bot.memory().chests().isEmpty()
            && bot.every("clear junk", CHECK_INTERVAL, () -> {
                // Plenty of it, or the chests are about full and it takes up room
                int rubbish = rubbishAtHome(bot);
                return rubbish >= MIN_RUBBISH || rubbish > 0 && ChestTask.chestSpace(bot) <= 2;
            });
    }

    private static @Nullable RandomizableContainerBlockEntity container(BotPlayer bot, BlockPos pos) {
        if (Home.levelIfHere(bot) == null || !bot.level().isLoaded(pos)) {
            return null;
        }
        return bot.level().getBlockEntity(pos) instanceof RandomizableContainerBlockEntity chest ? chest : null;
    }

    /** Bulk blocks of a kind left at home: a few stacks, or (stone) all the big house will take until it's built. */
    private static int keep(BotPlayer bot, Item item) {
        if (isLimitedFood(new ItemStack(item))) {
            return FOOD_KEEP;
        }
        // (plus what it's to bring to the Great Build)
        int ordered = com.minebot.bot.build.GreatBuild.get(bot.level().getServer()).orderOf(bot.getUUID()).getOrDefault(item, 0);
        if (!bot.memory().houseDone() && Blueprint.isStone(new ItemStack(item))) {
            return Math.max(BULK_KEEP, HousePrepTask.stoneNeeded() + 32) + ordered;
        }
        return BULK_KEEP + ordered;
    }

    /** Bread and wheat kept at home: a stack of each; more is rubbish (and the wheat field is left alone). */
    static final int FOOD_KEEP = 64;

    static boolean isLimitedFood(ItemStack stack) {
        // (and potatoes, raw and baked together, and carrots)
        return stack.is(Items.BREAD) || stack.is(Items.WHEAT) || stack.is(Items.POTATO) || stack.is(Items.BAKED_POTATO)
            || stack.is(Items.CARROT);
    }

    /** What a limit counts it under: baked potatoes with the raw ones. */
    static Item limitKey(Item item) {
        return item == Items.BAKED_POTATO ? Items.POTATO : item;
    }

    /** Seeds kept at home, all kinds together: more than this is rubbish. */
    static final int SEEDS_KEEP = 64;
    /** Where the seeds' overflow goes in the "over the limit" map (all seeds count as one kind). */
    private static final Item SEEDS = Items.WHEAT_SEEDS;

    static boolean isSeeds(ItemStack stack) {
        return stack.is(Items.WHEAT_SEEDS) || stack.is(Items.BEETROOT_SEEDS) || stack.is(Items.MELON_SEEDS)
            || stack.is(Items.PUMPKIN_SEEDS) || stack.is(Items.TORCHFLOWER_SEEDS);
    }

    /** Seeds of every kind in all its chests. */
    static int seedsAtHome(BotPlayer bot) {
        int seeds = 0;
        for (BlockPos pos : bot.memory().chests()) {
            RandomizableContainerBlockEntity chest = container(bot, pos);
            for (int i = 0; chest != null && i < chest.getContainerSize(); i++) {
                if (isSeeds(chest.getItem(i))) {
                    seeds += chest.getItem(i).getCount();
                }
            }
        }
        return seeds;
    }

    /** Bread, wheat, potatoes (with the baked ones) and carrots in all its chests, by {@link #limitKey}. */
    static Map<Item, Integer> limitedFoodAtHome(BotPlayer bot) {
        Map<Item, Integer> food = new HashMap<>();
        for (Map.Entry<Item, Integer> entry : bulkAtHome(bot).entrySet()) {
            if (isLimitedFood(new ItemStack(entry.getKey()))) {
                food.put(entry.getKey(), entry.getValue());
            }
        }
        return food;
    }

    /** Bulk blocks of each kind in all its chests. */
    private static Map<Item, Integer> bulkAtHome(BotPlayer bot) {
        Map<Item, Integer> bulk = new HashMap<>();
        for (BlockPos pos : bot.memory().chests()) {
            RandomizableContainerBlockEntity chest = container(bot, pos);
            for (int i = 0; chest != null && i < chest.getContainerSize(); i++) {
                ItemStack stack = chest.getItem(i);
                if (Stash.isBulk(stack) || isLimitedFood(stack)) {
                    bulk.merge(limitKey(stack.getItem()), stack.getCount(), Integer::sum);
                }
            }
        }
        return bulk;
    }

    /** The one bow or crossbow kept in the chests: the best of them (the rest is rubbish). */
    private static @Nullable BlockPos keptRanged(BotPlayer bot, int[] slot) {
        BlockPos best = null;
        double bestScore = 0;
        for (BlockPos pos : bot.memory().chests()) {
            RandomizableContainerBlockEntity chest = container(bot, pos);
            for (int i = 0; chest != null && i < chest.getContainerSize(); i++) {
                double score = Ranged.score(chest.getItem(i));
                if (score > bestScore) {
                    best = pos;
                    bestScore = score;
                    slot[0] = i;
                }
            }
        }
        return best;
    }

    private static boolean isSpareRanged(BotPlayer bot, RandomizableContainerBlockEntity chest, int i) {
        if (!Ranged.is(chest.getItem(i))) {
            return false;
        }
        int[] slot = new int[1];
        BlockPos kept = keptRanged(bot, slot);
        return !(chest.getBlockPos().equals(kept) && slot[0] == i);
    }

    /** How much rubbish its chests hold altogether. */
    private static int rubbishAtHome(BotPlayer bot) {
        int total = 0;
        for (Map.Entry<Item, Integer> entry : bulkAtHome(bot).entrySet()) {
            total += Math.max(0, entry.getValue() - keep(bot, entry.getKey()));
        }
        total += Math.max(0, seedsAtHome(bot) - SEEDS_KEEP);
        for (BlockPos pos : bot.memory().chests()) {
            RandomizableContainerBlockEntity chest = container(bot, pos);
            for (int i = 0; chest != null && i < chest.getContainerSize(); i++) {
                if (Stash.isJunk(chest.getItem(i)) || isSpareRanged(bot, chest, i)) {
                    total += chest.getItem(i).getCount();
                }
            }
        }
        return total;
    }

    /**
     * Slots of this chest (and how many) to take out: all junk, and bulk blocks over what
     * stays at home; {@code excess} (bulk over the limit, per kind) is used up as it goes.
     */
    private static Map<Integer, Integer> rubbish(BotPlayer bot, RandomizableContainerBlockEntity chest, @Nullable Map<Item, Integer> excess) {
        Map<Item, Integer> over = excess;
        if (over == null) {
            over = new HashMap<>();
            for (Map.Entry<Item, Integer> entry : bulkAtHome(bot).entrySet()) {
                over.put(entry.getKey(), Math.max(0, entry.getValue() - keep(bot, entry.getKey())));
            }
            over.put(SEEDS, Math.max(0, seedsAtHome(bot) - SEEDS_KEEP));
        }
        Map<Integer, Integer> result = new HashMap<>();
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (Stash.isJunk(stack) || isSpareRanged(bot, chest, i)) {
                result.put(i, stack.getCount());
            } else if (isSeeds(stack) && over.getOrDefault(SEEDS, 0) > 0) {
                int take = Math.min(stack.getCount(), over.get(SEEDS));
                over.merge(SEEDS, -take, Integer::sum);
                result.put(i, take);
            } else if ((Stash.isBulk(stack) || isLimitedFood(stack)) && over.getOrDefault(limitKey(stack.getItem()), 0) > 0) {
                Item key = limitKey(stack.getItem());
                int take = Math.min(stack.getCount(), over.get(key));
                over.merge(key, -take, Integer::sum);
                result.put(i, take);
            }
        }
        return result;
    }

    @Override
    public Status tick() {
        if (dump != null) {
            Status status = dump.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            dump.stop();
            dump = null;
            if (status == Status.SUCCESS) {
                bot.every("clear junk", 0, () -> false); // (more left? it looks again next time round)
            }
            return status;
        }
        BlockPos next = chests.peek();
        if (next == null || Inv.freeSlots(bot) <= SPARE_SLOTS) {
            if (taken == 0) {
                bot.every("clear junk", 0, () -> false); // (none left after all: checked again in 10 minutes)
                return Status.FAILURE;
            }
            if (DumpTask.knownLava(bot) != null) {
                bot.debug("taking {} items of rubbish from the chests to the lava", taken);
                dump = new DumpTask(bot);
            } else {
                // No lava about: out of the way, away from the house, where it disappears in a few minutes
                bot.debug("taking {} items of rubbish from the chests away from the house", taken);
                dump = new DropRubbishTask(bot);
            }
            return Status.RUNNING;
        }
        RandomizableContainerBlockEntity chest = container(bot, next);
        if (chest == null) {
            chests.poll();
            approach = null;
            return Status.RUNNING;
        }
        if (approach == null || !approach.target().equals(next)) {
            approach = new Approach(bot, next);
        }
        Approach.Result reached = approach.tick();
        if (reached == Approach.Result.MOVING) {
            return Status.RUNNING;
        }
        chests.poll();
        approach = null;
        if (reached == Approach.Result.FAILED) {
            UNREACHABLE.computeIfAbsent(bot.getUUID(), id -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(next, bot.level().getGameTime() + UNREACHABLE_TICKS);
            return Status.RUNNING;
        }
        bot.controller().lookAt(Vec3.atCenterOf(next));
        for (Map.Entry<Integer, Integer> entry : rubbish(bot, chest, null).entrySet()) {
            if (Inv.freeSlots(bot) <= SPARE_SLOTS) {
                break;
            }
            ItemStack stack = chest.getItem(entry.getKey()).split(entry.getValue());
            taken += stack.getCount();
            Inv.give(bot, stack);
        }
        chest.setChanged();
        return Status.RUNNING;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        if (dump != null) {
            dump.stop();
        }
    }

    @Override
    public String describe() {
        return dump != null ? "burning rubbish from the chests: " + dump.describe() : "taking rubbish out of the chests";
    }
}
