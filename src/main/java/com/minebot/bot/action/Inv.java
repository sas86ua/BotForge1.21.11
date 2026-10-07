package com.minebot.bot.action;

import com.minebot.bot.BotPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.function.Predicate;

/** Inventory helpers for bots. Slots 0-8 are the hotbar, 9-35 the main inventory. */
public final class Inv {
    public static final int MAIN_SIZE = 36;
    public static final int HOTBAR_SIZE = 9;

    /** Cheap blocks a bot may use for pillaring/bridging. */
    private static final Set<Item> SCAFFOLD = Set.of(
        Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.NETHERRACK,
        Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF);

    private Inv() {
    }

    public static boolean isScaffold(ItemStack stack) {
        return SCAFFOLD.contains(stack.getItem());
    }

    public static int count(BotPlayer bot, Predicate<ItemStack> matches) {
        Inventory inventory = bot.getInventory();
        int total = 0;
        for (int slot = 0; slot < MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && matches.test(stack)) {
                total += stack.getCount();
            }
        }
        // The offhand counts too (shield, but also e.g. food a player handed over)
        ItemStack offhand = bot.getOffhandItem();
        if (!offhand.isEmpty() && matches.test(offhand)) {
            total += offhand.getCount();
        }
        return total;
    }

    public static int count(BotPlayer bot, Item item) {
        return count(bot, stack -> stack.is(item));
    }

    public static int findSlot(BotPlayer bot, Predicate<ItemStack> matches) {
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && matches.test(stack)) {
                return slot;
            }
        }
        return -1;
    }

    public static int freeSlots(BotPlayer bot) {
        int free = 0;
        for (int slot = 0; slot < MAIN_SIZE; slot++) {
            if (bot.getInventory().getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** Puts a matching item in the main hand, moving it into the hotbar if needed. */
    public static boolean select(BotPlayer bot, Predicate<ItemStack> matches) {
        Inventory inventory = bot.getInventory();
        ItemStack held = inventory.getItem(inventory.getSelectedSlot());
        if (!held.isEmpty() && matches.test(held)) {
            return true;
        }
        int slot = findSlot(bot, matches);
        if (slot < 0) {
            return false;
        }
        if (slot >= HOTBAR_SIZE) {
            int target = hotbarSlotToReplace(bot);
            ItemStack moving = inventory.getItem(slot);
            inventory.setItem(slot, inventory.getItem(target));
            inventory.setItem(target, moving);
            slot = target;
        }
        inventory.setSelectedSlot(slot);
        return true;
    }

    /** Empties the main hand (e.g. so mining dirt doesn't wear out a sword). */
    public static void selectEmptyOrHarmless(BotPlayer bot) {
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.isDamageableItem()) {
                inventory.setSelectedSlot(slot);
                return;
            }
        }
    }

    private static int hotbarSlotToReplace(BotPlayer bot) {
        Inventory inventory = bot.getInventory();
        for (int slot = HOTBAR_SIZE - 1; slot >= 0; slot--) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        // Keep the weapons in the first slots; swap out the last hotbar slot
        return HOTBAR_SIZE - 1;
    }

    /** Selects the fastest tool for the block, or an empty/harmless hand if nothing helps. */
    public static void selectBestTool(BotPlayer bot, BlockState state) {
        Inventory inventory = bot.getInventory();
        int bestSlot = -1;
        float bestScore = 1.0F;
        for (int slot = 0; slot < MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            float score = toolScore(stack, state);
            // Used until it breaks: of two equally good tools the more worn one goes first
            if (score > bestScore || score == bestScore && bestSlot >= 0 && score > 1.0F
                && remaining(stack) < remaining(inventory.getItem(bestSlot))) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) {
            selectEmptyOrHarmless(bot);
        } else {
            int slot = bestSlot;
            select(bot, stack -> stack == inventory.getItem(slot));
        }
    }

    /** Uses left before it breaks (unbreakable things: a lot). */
    public static int remaining(ItemStack stack) {
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }

    /** Mining speed, heavily penalised when the tool wouldn't get the block's drops. */
    public static float toolScore(ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) {
            return Math.min(speed, 1.0F);
        }
        return speed;
    }

    /** Can any tool in the inventory get drops from this block? */
    public static boolean canHarvest(BotPlayer bot, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        return findSlot(bot, stack -> stack.isCorrectToolForDrops(state)) >= 0;
    }

    /** Best mining speed available for this block, for cost estimates. */
    public static float bestSpeed(BotPlayer bot, BlockState state) {
        float best = 1.0F;
        for (int slot = 0; slot < MAIN_SIZE; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                best = Math.max(best, toolScore(stack, state));
            }
        }
        return best;
    }

    public static boolean isFood(ItemStack stack) {
        return stack.has(DataComponents.FOOD);
    }

    public static int nutrition(ItemStack stack) {
        FoodProperties food = stack.get(DataComponents.FOOD);
        return food == null ? 0 : food.nutrition();
    }

    public static boolean isWeapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES);
    }

    /** Adds to the inventory, dropping whatever doesn't fit at the bot's feet. */
    public static void give(BotPlayer bot, ItemStack stack) {
        if (!bot.getInventory().add(stack) && !stack.isEmpty()) {
            bot.drop(stack, false);
        }
    }
}
