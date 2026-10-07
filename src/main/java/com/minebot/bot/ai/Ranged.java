package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.enchantment.Enchantment;

/** Bows and crossbows: which is best, and keeping that one on the hotbar to shoot with. */
public final class Ranged {
    private Ranged() {
    }

    public static boolean is(ItemStack stack) {
        return stack.getItem() instanceof ProjectileWeaponItem;
    }

    /** How good it is: a crossbow hits a little harder, enchantments count, a worn one less. */
    public static double score(ItemStack stack) {
        if (!is(stack)) {
            return 0;
        }
        double score = stack.is(Items.CROSSBOW) ? 1.2 : 1.0;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
            score += 0.5 * entry.getIntValue();
        }
        if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
            score *= 0.75 + 0.25 * (1.0 - (double) stack.getDamageValue() / stack.getMaxDamage());
        }
        return score;
    }

    /** The bag slot (0-35) of the best one it carries, or -1. */
    public static int bestSlot(BotPlayer bot) {
        Inventory inventory = bot.getInventory();
        int best = -1;
        double bestScore = 0;
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            double score = score(inventory.getItem(slot));
            if (score > bestScore) {
                best = slot;
                bestScore = score;
            }
        }
        return best;
    }

    public static boolean has(BotPlayer bot) {
        return bestSlot(bot) >= 0;
    }

    /** Better than the best it carries (or it has none)? */
    public static boolean isUpgrade(BotPlayer bot, ItemStack stack) {
        int best = bestSlot(bot);
        return is(stack) && score(stack) > (best < 0 ? 0 : score(bot.getInventory().getItem(best))) + 0.01;
    }

    /** Is there a bow or crossbow in the chests it may take from? */
    public static boolean inChests(BotPlayer bot) {
        return ChestTask.stored(bot, new com.minebot.bot.craft.Target("bow or crossbow", Ranged::is, 1)) > 0;
    }

    /**
     * The best sword or axe it has goes on the hotbar (where it's grabbed in a fight), in place
     * of the one there: a diamond sword picked up into the bag, a new one after the old broke.
     */
    public static void weaponOnHotbar(BotPlayer bot) {
        Inventory inventory = bot.getInventory();
        int best = -1;
        int hotbar = -1;
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isMelee(stack)) {
                continue;
            }
            if (best < 0 || meleeBetter(stack, inventory.getItem(best))) {
                best = slot;
            }
            if (slot < 9 && (hotbar < 0 || meleeBetter(stack, inventory.getItem(hotbar)))) {
                hotbar = slot;
            }
        }
        if (best < 9 || hotbar >= 0 && !meleeBetter(inventory.getItem(best), inventory.getItem(hotbar))) {
            return;
        }
        int target = hotbar >= 0 ? hotbar : inventory.getItem(0).isEmpty() || !is(inventory.getItem(0)) ? 0 : 8;
        ItemStack moved = inventory.getItem(target);
        inventory.setItem(target, inventory.getItem(best));
        inventory.setItem(best, moved);
        bot.debug("took {} onto the hotbar", inventory.getItem(target).getItem());
    }

    private static boolean isMelee(ItemStack stack) {
        return stack.is(net.minecraft.tags.ItemTags.SWORDS) || stack.is(net.minecraft.tags.ItemTags.AXES);
    }

    /** Swords before axes, then the better material; of equals the more worn (used up first). */
    public static boolean meleeBetter(ItemStack a, ItemStack b) {
        int kindA = a.is(net.minecraft.tags.ItemTags.SWORDS) ? 1 : 0;
        int kindB = b.is(net.minecraft.tags.ItemTags.SWORDS) ? 1 : 0;
        if (kindA != kindB) {
            return kindA > kindB;
        }
        int rankA = Tools.rank(a.getItem());
        int rankB = Tools.rank(b.getItem());
        if (rankA != rankB) {
            return rankA > rankB;
        }
        return Inv.remaining(a) < Inv.remaining(b);
    }

    /** The best one goes on the hotbar (where it's grabbed in a fight), in place of a worse one if need be. */
    public static void equipBest(BotPlayer bot) {
        int best = bestSlot(bot);
        if (best < 0 || best < 9) {
            return;
        }
        Inventory inventory = bot.getInventory();
        int target = -1;
        for (int slot = 0; slot < 9 && target < 0; slot++) {
            if (is(inventory.getItem(slot))) {
                target = slot;
            }
        }
        for (int slot = 0; slot < 9 && target < 0; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                target = slot;
            }
        }
        if (target < 0) {
            target = 8;
        }
        ItemStack moved = inventory.getItem(target);
        inventory.setItem(target, inventory.getItem(best));
        inventory.setItem(best, moved);
        bot.debug("took {} onto the hotbar", inventory.getItem(target).getItem());
    }
}
