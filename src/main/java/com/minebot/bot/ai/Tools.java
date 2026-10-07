package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Target;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.function.Predicate;

/** Tool kinds and material tiers the bot works its way up through. */
public final class Tools {
    public enum Tier {
        ANY(Set.of()),
        STONE(Set.of("stone", "copper", "iron", "diamond", "netherite")),
        IRON(Set.of("iron", "diamond", "netherite"));

        private final Set<String> materials;

        Tier(Set<String> materials) {
            this.materials = materials;
        }

        boolean accepts(Item item) {
            if (this == ANY) {
                return true;
            }
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            int underscore = path.indexOf('_');
            return underscore > 0 && materials.contains(path.substring(0, underscore));
        }
    }

    private Tools() {
    }

    /** A tool of this kind and tier that isn't about to break (a worn one is used up, but a new one is made in time). */
    public static Predicate<ItemStack> tool(TagKey<Item> kind, Tier tier) {
        return stack -> stack.is(kind) && tier.accepts(stack.getItem()) && (!isWorn(stack) || rank(stack.getItem()) > tier.ordinal());
    }

    /**
     * 0 wood or gold, 1 stone or copper, 2 iron, 3 diamond or better. A worn tool still counts for
     * the tiers below its own (a worn iron pickaxe is a pickaxe, and better than stone): only its
     * own tier's need makes a new one, not wooden and stone ones first.
     */
    public static int rank(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        return path.startsWith("stone_") || path.startsWith("copper_") ? 1
            : path.startsWith("iron_") ? 2
            : path.startsWith("diamond_") || path.startsWith("netherite_") ? 3 : 0;
    }

    /** Less than a tenth of its durability left. */
    public static boolean isWorn(ItemStack stack) {
        return stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() < Math.max(4, stack.getMaxDamage() / 10);
    }

    /** The tier a new tool of this rank is made at (a worn iron one is replaced by iron, not wood). */
    public static Tier tierOf(int rank) {
        return rank >= 2 ? Tier.IRON : rank == 1 ? Tier.STONE : Tier.ANY;
    }

    /**
     * Its best tool of this kind is worn (a tenth or less left) and there's no sound one
     * as good to follow it: time to make one, carried along until the old one breaks.
     */
    public static boolean needsReplacing(BotPlayer bot, TagKey<Item> kind) {
        int worn = -1;
        int sound = -1;
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (stack.is(kind)) {
                if (isWorn(stack)) {
                    worn = Math.max(worn, rank(stack.getItem()));
                } else {
                    sound = Math.max(sound, rank(stack.getItem()));
                }
            }
        }
        return worn >= 0 && sound < worn;
    }

    /** A new one for the worn tool of this kind, of its tier. */
    public static Target replacement(BotPlayer bot, TagKey<Item> kind) {
        int worn = 0;
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (stack.is(kind) && isWorn(stack)) {
                worn = Math.max(worn, rank(stack.getItem()));
            }
        }
        return target(kind, tierOf(worn));
    }

    public static boolean has(BotPlayer bot, TagKey<Item> kind, Tier tier) {
        return Inv.count(bot, tool(kind, tier)) > 0;
    }

    public static Target target(TagKey<Item> kind, Tier tier) {
        String name = tier.name().toLowerCase() + " " + kind.location().getPath();
        return new Target(name, tool(kind, tier), 1);
    }

    public static boolean hasWeapon(BotPlayer bot) {
        return Inv.count(bot, stack -> stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)) > 0;
    }
}
