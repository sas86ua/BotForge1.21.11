package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Approach;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Its furnaces (and smoker) at home looked after: what's done in them taken out, and a store of
 * fuel kept in each - whatever burns it can spare (coal, charcoal, saplings, spare chests...), enough
 * for a couple of stacks - so a batch left to cook never stops halfway for want of fuel.
 */
public class FuelFurnacesTask extends Task {
    /** The store each furnace keeps: sixteen coal's worth (two stacks of stone). */
    public static final int RESERVE_TICKS = 16 * 1600;
    /** Below half of that it's topped up. */
    private static final int LOW_TICKS = RESERVE_TICKS / 2;
    /** Coal, and wood, kept in the bag (for torches, for crafting); the rest may go in the furnaces. */
    private static final int KEEP_COAL = 8;
    private static final int KEEP_WOOD = 16;
    private static final int CHECK_TICKS = 20 * 60 * 3;
    private static final int SLOT_INPUT = 0;
    private static final int SLOT_FUEL = 1;
    private static final int SLOT_RESULT = 2;

    private final List<BlockPos> furnaces = new ArrayList<>();
    private @Nullable Approach approach;
    private @Nullable Task child;
    private boolean fetched;

    public FuelFurnacesTask(BotPlayer bot) {
        super(bot);
        furnaces.addAll(needCare(bot));
    }

    public static boolean wanted(BotPlayer bot) {
        return Home.isNear(bot, 32) && bot.every("furnace care", CHECK_TICKS, () -> !needCare(bot).isEmpty());
    }

    /** How many of this fuel it can put in a furnace's store (keeping some coal and wood in the bag). */
    public static int spareFuel(BotPlayer bot, ItemStack stack) {
        if (stack.is(ItemTags.COALS)) {
            return Math.max(0, Inv.count(bot, s -> s.is(ItemTags.COALS)) - KEEP_COAL);
        }
        if (stack.is(ItemTags.PLANKS) || stack.is(ItemTags.LOGS) || stack.is(Items.STICK)) {
            return Math.max(0, Inv.count(bot, s -> s.is(stack.getItem())) - KEEP_WOOD);
        }
        return stack.getCount();
    }

    /** Its furnaces at home with something done in them, or fuel running low. */
    private static List<BlockPos> needCare(BotPlayer bot) {
        List<BlockPos> result = new ArrayList<>();
        if (Home.levelIfHere(bot) == null) {
            return result;
        }
        List<BlockPos> own = new ArrayList<>(SmeltTask.otherFurnaces(bot));
        if (bot.memory().furnace() != null) {
            own.add(0, bot.memory().furnace());
        }
        BlockPos smoker = SmeltTask.ownSmoker(bot);
        if (smoker != null) {
            own.add(smoker);
        }
        for (BlockPos pos : own) {
            if (bot.level().isLoaded(pos) && bot.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace
                && (!furnace.getItem(SLOT_RESULT).isEmpty() || fuelTicks(bot, furnace) < LOW_TICKS)) {
                result.add(pos);
            }
        }
        return result;
    }

    private static int fuelTicks(BotPlayer bot, AbstractFurnaceBlockEntity furnace) {
        ItemStack fuel = furnace.getItem(SLOT_FUEL);
        return fuel.isEmpty() ? 0 : bot.level().fuelValues().burnDuration(fuel) * fuel.getCount();
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
        }
        if (furnaces.isEmpty()) {
            bot.every("furnace care", 0, () -> false); // (looked at again next time round)
            return Status.SUCCESS;
        }
        if (!fetched) {
            // Fuel from the chests for it if the bag has little: coal and charcoal first, then saplings and leaf litter
            fetched = true;
            int spare = 0;
            for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
                ItemStack stack = bot.getInventory().getItem(slot);
                if (!stack.isEmpty() && bot.level().fuelValues().burnDuration(stack) > 0 && !Stash.isTool(stack)) {
                    spare += Math.min(stack.getCount(), spareFuel(bot, stack)) * bot.level().fuelValues().burnDuration(stack);
                }
            }
            if (spare < RESERVE_TICKS) {
                Target coal = Target.tag(ItemTags.COALS, Inv.count(bot, s -> s.is(ItemTags.COALS)) + 32);
                if (ChestTask.stored(bot, coal) > 0) {
                    child = ChestTask.withdraw(bot, coal);
                    return Status.RUNNING;
                }
                Target kindling = new Target("kindling", s -> s.is(ItemTags.SAPLINGS) || s.is(Items.LEAF_LITTER), 64);
                if (ChestTask.stored(bot, kindling) > 0) {
                    child = ChestTask.withdraw(bot, kindling);
                    return Status.RUNNING;
                }
            }
        }
        BlockPos pos = furnaces.get(0);
        if (!(bot.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            furnaces.remove(0);
            return Status.RUNNING;
        }
        if (approach == null || !approach.target().equals(pos)) {
            approach = new Approach(bot, pos);
        }
        Approach.Result reached = approach.tick();
        if (reached == Approach.Result.MOVING) {
            return Status.RUNNING;
        }
        approach = null;
        furnaces.remove(0);
        if (reached == Approach.Result.FAILED) {
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().lookAt(Vec3.atCenterOf(pos));
        ItemStack done = furnace.getItem(SLOT_RESULT);
        if (!done.isEmpty() && Inv.freeSlots(bot) > 0) {
            bot.debug("took {} {} out of my furnace at {}", done.getCount(), done.getItem(), pos.toShortString());
            furnace.setItem(SLOT_RESULT, ItemStack.EMPTY);
            Inv.give(bot, done);
            furnace.awardUsedRecipesAndPopExperience(bot);
        }
        topUp(furnace);
        furnace.setChanged();
        return Status.RUNNING;
    }

    /** Fuel into its fuel slot up to the store: the same kind as is there, or else the best it can spare. */
    private void topUp(AbstractFurnaceBlockEntity furnace) {
        ItemStack slot = furnace.getItem(SLOT_FUEL);
        int added = 0;
        while (fuelTicks(bot, furnace) < RESERVE_TICKS) {
            int from = -1;
            int bestRank = Integer.MAX_VALUE;
            for (int i = 0; i < Inv.MAIN_SIZE; i++) {
                ItemStack stack = bot.getInventory().getItem(i);
                if (stack.isEmpty() || bot.level().fuelValues().burnDuration(stack) <= 0 || Stash.isTool(stack)
                    || stack.isDamageableItem() || spareFuel(bot, stack) <= 0 || stack.is(Items.LAVA_BUCKET) || stack.is(ItemTags.BOATS)
                    || !slot.isEmpty() && !ItemStack.isSameItemSameComponents(slot, stack)) {
                    continue;
                }
                if (!Fuel.isSurplusFurniture(bot, stack) && (stack.is(Items.CHEST) || stack.is(Items.CRAFTING_TABLE)
                    || stack.is(ItemTags.WOODEN_DOORS) || stack.is(ItemTags.WOOL) || stack.is(Items.LADDER) || stack.is(Items.BOW)
                    || stack.is(Items.CROSSBOW) || stack.is(Items.CAMPFIRE) || stack.is(ItemTags.BEDS))) {
                    continue; // (what it has a better use for)
                }
                int rank = stack.is(ItemTags.COALS) ? 0 : stack.is(ItemTags.SAPLINGS) || stack.is(Items.LEAF_LITTER) ? 1 : 2;
                if (rank < bestRank) {
                    bestRank = rank;
                    from = i;
                }
            }
            if (from < 0) {
                break;
            }
            ItemStack stack = bot.getInventory().getItem(from);
            int perItem = Math.max(1, bot.level().fuelValues().burnDuration(stack));
            int want = (RESERVE_TICKS - fuelTicks(bot, furnace) + perItem - 1) / perItem;
            int room = (slot.isEmpty() ? stack.getMaxStackSize() : slot.getMaxStackSize() - slot.getCount());
            int amount = Math.min(Math.min(want, room), Math.min(stack.getCount(), spareFuel(bot, stack)));
            if (amount <= 0) {
                break;
            }
            if (slot.isEmpty()) {
                furnace.setItem(SLOT_FUEL, stack.split(amount));
                slot = furnace.getItem(SLOT_FUEL);
            } else {
                slot.grow(stack.split(amount).getCount());
            }
            added += amount;
        }
        if (added > 0) {
            bot.debug("put {} {} in my furnace at {} for fuel", added, slot.getItem(), furnace.getBlockPos().toShortString());
        }
    }

    @Override
    public void stop() {
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
        return "seeing to its furnaces";
    }
}
