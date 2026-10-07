package com.minebot.bot.craft;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Crafts from the bot's inventory using real recipes. */
public final class Crafting {
    private Crafting() {
    }

    /**
     * Ingredients still missing to craft {@code option} {@code times} times,
     * grouped by ingredient with the number of items short.
     */
    public static Map<Ingredient, Integer> missing(BotPlayer bot, Recipes.CraftOption option, int times) {
        Map<Ingredient, Integer> needed = new LinkedHashMap<>();
        for (Ingredient ingredient : option.ingredients()) {
            needed.merge(ingredient, times, Integer::sum);
        }
        Map<Ingredient, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<Ingredient, Integer> entry : needed.entrySet()) {
            int have = Inv.count(bot, entry.getKey()::test);
            if (have < entry.getValue()) {
                missing.put(entry.getKey(), entry.getValue() - have);
            }
        }
        return missing;
    }

    /**
     * Crafts once. Takes one matching item per grid cell from the inventory,
     * checks the real recipe matches, then swaps ingredients for the result.
     * @return false if ingredients are missing or the recipe didn't match
     */
    public static boolean craftOnce(BotPlayer bot, Recipes.CraftOption option) {
        Inventory inventory = bot.getInventory();
        int[] remaining = new int[Inv.MAIN_SIZE];
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            remaining[slot] = inventory.getItem(slot).getCount();
        }

        List<ItemStack> grid = new ArrayList<>();
        List<Integer> usedSlots = new ArrayList<>();
        for (Optional<Ingredient> cell : option.grid()) {
            if (cell.isEmpty()) {
                grid.add(ItemStack.EMPTY);
                continue;
            }
            int slot = pickSlot(inventory, remaining, cell.get());
            if (slot < 0) {
                return false;
            }
            remaining[slot]--;
            usedSlots.add(slot);
            grid.add(inventory.getItem(slot).copyWithCount(1));
        }

        CraftingInput input = CraftingInput.of(option.width(), option.height(), grid);
        CraftingRecipe recipe = option.holder().value();
        if (!recipe.matches(input, bot.level())) {
            return false;
        }
        ItemStack result = recipe.assemble(input, bot.level().registryAccess());
        NonNullList<ItemStack> leftovers = recipe.getRemainingItems(input);

        for (int slot : usedSlots) {
            inventory.getItem(slot).shrink(1);
        }
        result.onCraftedBy(bot, result.getCount());
        Inv.give(bot, result);
        for (ItemStack leftover : leftovers) {
            if (!leftover.isEmpty()) {
                Inv.give(bot, leftover);
            }
        }
        return true;
    }

    /** The slot with the most of a matching item, so mixed stacks get used up evenly. */
    private static int pickSlot(Inventory inventory, int[] remaining, Ingredient ingredient) {
        int best = -1;
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            if (remaining[slot] > 0 && ingredient.test(inventory.getItem(slot))
                && (best < 0 || remaining[slot] > remaining[best])) {
                best = slot;
            }
        }
        return best;
    }
}
