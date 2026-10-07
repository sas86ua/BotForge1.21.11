package com.minebot.bot.craft;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.function.Predicate;

/** "Have at least {@code count} items matching {@code accepts}." */
public record Target(String name, Predicate<ItemStack> accepts, int count) {
    public static Target of(Item item, int count) {
        return new Target(BuiltInRegistries.ITEM.getKey(item).getPath(), stack -> stack.is(item), count);
    }

    public static Target tag(TagKey<Item> tag, int count) {
        return new Target("#" + tag.location().getPath(), stack -> stack.is(tag), count);
    }

    public static Target ingredient(Ingredient ingredient, int count) {
        ItemStack example = Recipes.firstItem(ingredient);
        String name = example.isEmpty() ? "ingredient" : BuiltInRegistries.ITEM.getKey(example.getItem()).getPath() + "*";
        return new Target(name, ingredient::test, count);
    }

    public Target withCount(int newCount) {
        return new Target(name, accepts, newCount);
    }

    public int have(BotPlayer bot) {
        return Inv.count(bot, accepts);
    }

    public int missing(BotPlayer bot) {
        return Math.max(0, count - have(bot));
    }

    public boolean satisfied(BotPlayer bot) {
        return have(bot) >= count;
    }

    @Override
    public String toString() {
        return count + "x " + name;
    }
}
