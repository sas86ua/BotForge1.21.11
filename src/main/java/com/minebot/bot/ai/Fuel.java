package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

/**
 * What a bot burns in a furnace: anything that burns and that it has no other
 * use for. Junk goes first (saplings, spare wooden tools, slabs...), then coal
 * and charcoal, and wood (sticks, planks, logs) only when nothing else is left,
 * since that's what it crafts with.
 */
public final class Fuel {
    /** Cheap fuel worth going out to get when the bot has nothing to burn. */
    public static final Predicate<ItemStack> GATHERABLE = stack -> stack.is(ItemTags.COALS)
        || stack.is(ItemTags.PLANKS) || stack.is(ItemTags.LOGS_THAT_BURN) || stack.is(Items.STICK);

    private Fuel() {
    }

    /**
     * Can the item in this slot be burnt?
     * @param keep what must not be burnt (the item being smelted)
     */
    public static boolean canBurn(BotPlayer bot, int slot, Predicate<ItemStack> keep) {
        ItemStack stack = bot.getInventory().getItem(slot);
        if (stack.isEmpty() || keep.test(stack) || bot.level().fuelValues().burnDuration(stack) <= 0) {
            return false;
        }
        if (Stash.isTool(stack) || stack.isDamageableItem()) {
            // Only tools it has a better one of (the old wooden pickaxe...)
            return Stash.isTool(stack) && Stash.isSpare(bot, slot);
        }
        return !isNeeded(stack);
    }

    /** Burnable things that have a better use. */
    private static boolean isNeeded(ItemStack stack) {
        return stack.is(Items.CRAFTING_TABLE) || stack.is(Items.CHEST) || stack.is(Items.TRAPPED_CHEST)
            || stack.is(Items.BARREL) || stack.is(ItemTags.WOODEN_DOORS) || stack.is(ItemTags.WOOL) // doors and beds
            || stack.is(Items.LAVA_BUCKET) || stack.is(Items.BLAZE_ROD) || stack.is(Items.LADDER)
            || stack.is(Items.SCAFFOLDING) || stack.is(Items.BOOKSHELF) || stack.is(Items.CAMPFIRE)
            || stack.is(ItemTags.BOATS) || stack.is(ItemTags.CHEST_BOATS); // (for crossing water)
    }

    /** Lower burns first. */
    private static int rank(ItemStack stack) {
        if (stack.is(ItemTags.COALS)) {
            return 1;
        }
        if (stack.is(Items.STICK)) {
            return 2;
        }
        if (stack.is(ItemTags.PLANKS)) {
            return 3;
        }
        if (stack.is(ItemTags.LOGS)) {
            return 4;
        }
        return 0; // junk
    }

    /** The inventory slot to burn next, or -1 if there's nothing to burn. */
    public static int pick(BotPlayer bot, Predicate<ItemStack> keep) {
        Inventory inventory = bot.getInventory();
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            if (canBurn(bot, slot, keep)) {
                int rank = rank(inventory.getItem(slot));
                if (rank < bestRank) {
                    best = slot;
                    bestRank = rank;
                }
            }
        }
        return best;
    }

    /** Total burn time of everything the bot could burn. */
    public static int ticks(BotPlayer bot, Predicate<ItemStack> keep) {
        int total = 0;
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            if (canBurn(bot, slot, keep)) {
                ItemStack stack = inventory.getItem(slot);
                total += bot.level().fuelValues().burnDuration(stack) * stack.getCount();
            }
        }
        return total;
    }
}
