package com.minebot.bot.craft;

import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Index of the server's crafting and cooking recipes by result, so bots can
 * look up "how do I make X". Rebuilt automatically after /reload.
 */
public final class Recipes {
    /** A crafting recipe flattened to a grid of ingredients (row-major, width x height). */
    public record CraftOption(RecipeHolder<CraftingRecipe> holder, int width, int height,
                              List<Optional<Ingredient>> grid, ItemStack result, boolean needsTable) {
        public List<Ingredient> ingredients() {
            return grid.stream().flatMap(Optional::stream).toList();
        }
    }

    public record CookOption(RecipeHolder<? extends AbstractCookingRecipe> holder, Ingredient input, ItemStack result) {
        public int cookingTime() {
            return holder.value().cookingTime();
        }
    }

    private static RecipeManager indexedManager;
    private static List<CraftOption> crafting = List.of();
    private static List<CookOption> smelting = List.of();
    private static List<CookOption> campfire = List.of();

    private Recipes() {
    }

    public static List<CraftOption> craftingFor(MinecraftServer server, Predicate<ItemStack> result) {
        ensureIndex(server);
        return crafting.stream().filter(option -> result.test(option.result())).toList();
    }

    public static List<CookOption> smeltingFor(MinecraftServer server, Predicate<ItemStack> result) {
        ensureIndex(server);
        return smelting.stream().filter(option -> result.test(option.result())).toList();
    }

    public static List<CookOption> campfireFor(MinecraftServer server, Predicate<ItemStack> result) {
        ensureIndex(server);
        return campfire.stream().filter(option -> result.test(option.result())).toList();
    }

    /** What a single item cooks into in a furnace, or empty. */
    public static Optional<CookOption> smeltingOf(MinecraftServer server, ItemStack input) {
        ensureIndex(server);
        return smelting.stream().filter(option -> option.input().test(input)).findFirst();
    }

    public static Optional<CookOption> campfireOf(MinecraftServer server, ItemStack input) {
        ensureIndex(server);
        return campfire.stream().filter(option -> option.input().test(input)).findFirst();
    }

    @SuppressWarnings("unchecked")
    private static synchronized void ensureIndex(MinecraftServer server) {
        RecipeManager manager = server.getRecipeManager();
        if (manager == indexedManager) {
            return;
        }
        ServerLevel level = server.overworld();
        List<CraftOption> crafts = new ArrayList<>();
        List<CookOption> smelts = new ArrayList<>();
        List<CookOption> campfires = new ArrayList<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (holder.value() instanceof ShapedRecipe shaped) {
                index(crafts, (RecipeHolder<CraftingRecipe>) holder, shaped.getWidth(), shaped.getHeight(), shaped.getIngredients(), level);
            } else if (holder.value() instanceof ShapelessRecipe shapeless) {
                List<Ingredient> ingredients = shapeless.placementInfo().ingredients();
                int width = Math.min(ingredients.size(), 3);
                int height = (ingredients.size() + 2) / 3;
                List<Optional<Ingredient>> grid = new ArrayList<>();
                for (int i = 0; i < width * height; i++) {
                    grid.add(i < ingredients.size() ? Optional.of(ingredients.get(i)) : Optional.empty());
                }
                index(crafts, (RecipeHolder<CraftingRecipe>) holder, width, height, grid, level);
            } else if (holder.value() instanceof AbstractCookingRecipe cooking) {
                RecipeType<?> type = cooking.getType();
                if (type != RecipeType.SMELTING && type != RecipeType.CAMPFIRE_COOKING) {
                    continue;
                }
                ItemStack example = firstItem(cooking.input());
                if (example.isEmpty()) {
                    continue;
                }
                ItemStack result = cooking.assemble(new SingleRecipeInput(example), level.registryAccess());
                CookOption option = new CookOption((RecipeHolder<? extends AbstractCookingRecipe>) holder, cooking.input(), result);
                (type == RecipeType.SMELTING ? smelts : campfires).add(option);
            }
        }
        crafting = List.copyOf(crafts);
        smelting = List.copyOf(smelts);
        campfire = List.copyOf(campfires);
        indexedManager = manager;
    }

    private static void index(List<CraftOption> out, RecipeHolder<CraftingRecipe> holder, int width, int height,
                              List<Optional<Ingredient>> grid, ServerLevel level) {
        List<ItemStack> example = new ArrayList<>();
        for (Optional<Ingredient> ingredient : grid) {
            ItemStack stack = ingredient.map(Recipes::firstItem).orElse(ItemStack.EMPTY);
            if (ingredient.isPresent() && stack.isEmpty()) {
                return; // ingredient with no items (e.g. an empty tag)
            }
            example.add(stack);
        }
        CraftingInput input = CraftingInput.of(width, height, example);
        if (!holder.value().matches(input, level)) {
            return; // special recipe that doesn't work with plain items
        }
        ItemStack result = holder.value().assemble(input, level.registryAccess());
        if (result.isEmpty()) {
            return;
        }
        boolean needsTable = width > 2 || height > 2;
        out.add(new CraftOption(holder, width, height, List.copyOf(grid), result, needsTable));
    }

    public static ItemStack firstItem(Ingredient ingredient) {
        return ingredient.items().findFirst().map(Holder::value).map(Item::getDefaultInstance).orElse(ItemStack.EMPTY);
    }
}
