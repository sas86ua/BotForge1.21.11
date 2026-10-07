package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.IntStream;

/** What and when a bot eats. */
public final class Food {
    /** Proper meals; the bot keeps a stock of these. */
    private static final Set<Item> COOKED = Set.of(
        Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON, Items.COOKED_CHICKEN, Items.COOKED_RABBIT,
        Items.COOKED_COD, Items.COOKED_SALMON, Items.BAKED_POTATO, Items.BREAD, Items.GOLDEN_CARROT);
    /** Only eaten when starving (hunger, poison or just poor). */
    private static final Set<Item> DESPERATE = Set.of(
        Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.CHICKEN, Items.PUFFERFISH);

    /** How much cooked food a bot tries to carry. */
    public static final int STOCK = 8;

    private Food() {
    }

    /**
     * Bread for the road (a journey, the Great Build): 8-16 loaves out of the chests at home, if it
     * has fewer than 8 on it and there are some; or null.
     */
    public static @org.jetbrains.annotations.Nullable Task packBread(BotPlayer bot) {
        int carried = Inv.count(bot, stack -> stack.is(Items.BREAD));
        if (carried >= 8 || !Home.isNear(bot, 64)) {
            return null;
        }
        int stored = ChestTask.stored(bot, com.minebot.bot.craft.Target.of(Items.BREAD, 1));
        if (stored <= 0) {
            return null;
        }
        int want = Math.min(carried + stored, 8 + bot.getRandom().nextInt(9));
        return ChestTask.withdraw(bot, com.minebot.bot.craft.Target.of(Items.BREAD, want));
    }

    public static boolean isCooked(ItemStack stack) {
        return COOKED.contains(stack.getItem());
    }

    public static boolean isHungry(BotPlayer bot) {
        int food = bot.getFoodData().getFoodLevel();
        // Eat when a few shanks are gone, or to heal when hurt
        return food <= 14 || bot.getHealth() < bot.getMaxHealth() * 0.7F && food < 20;
    }

    public static boolean isStarving(BotPlayer bot) {
        return bot.getFoodData().getFoodLevel() <= 6;
    }

    /** Inventory slot of the best food to eat now, or -1. */
    public static int pickFood(BotPlayer bot) {
        boolean starving = isStarving(bot);
        return IntStream.range(0, Inv.MAIN_SIZE)
            .filter(slot -> {
                ItemStack stack = bot.getInventory().getItem(slot);
                return Inv.isFood(stack) && (starving || !DESPERATE.contains(stack.getItem()));
            })
            .boxed()
            .max(Comparator.comparingInt((Integer slot) -> score(bot.getInventory().getItem(slot))))
            .orElse(-1);
    }

    private static int score(ItemStack stack) {
        // Prefer real meals; among them the most filling
        return Inv.nutrition(stack) + (DESPERATE.contains(stack.getItem()) ? -20 : 0) + (isCooked(stack) ? 10 : 0);
    }
}
