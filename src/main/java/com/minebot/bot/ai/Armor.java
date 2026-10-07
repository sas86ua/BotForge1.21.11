package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import org.jetbrains.annotations.Nullable;

/** Wearing the best armour the bot has. */
public final class Armor {
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private Armor() {
    }

    /** The armour slot this piece goes in, or null if it isn't armour. */
    public static @Nullable EquipmentSlot slotOf(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null || equippable.slot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
            return null;
        }
        return score(stack) > 0 ? equippable.slot() : null; // (not a carved pumpkin or a head)
    }

    /** How good a piece is: armour points, toughness counts half, a little for enchantments. */
    public static double score(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        double score = 0;
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().value() == Attributes.ARMOR.value()) {
                score += entry.modifier().amount();
            } else if (entry.attribute().value() == Attributes.ARMOR_TOUGHNESS.value()) {
                score += entry.modifier().amount() * 0.5;
            }
        }
        if (score > 0 && stack.isEnchanted()) {
            score += 0.5;
        }
        // Nearly broken armour is worth less than a sound piece of the same kind
        if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
            score *= 0.75 + 0.25 * (1.0 - (double) stack.getDamageValue() / stack.getMaxDamage());
        }
        return score;
    }

    private record Piece(EquipmentSlot slot, net.minecraft.world.item.Item diamond, net.minecraft.world.item.Item iron,
                         net.minecraft.world.item.Item leather, int cost) {
    }

    private static final Piece[] PIECES = {
        // Most protection first
        new Piece(EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE, Items.IRON_CHESTPLATE, Items.LEATHER_CHESTPLATE, 8),
        new Piece(EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS, Items.IRON_LEGGINGS, Items.LEATHER_LEGGINGS, 7),
        new Piece(EquipmentSlot.HEAD, Items.DIAMOND_HELMET, Items.IRON_HELMET, Items.LEATHER_HELMET, 5),
        new Piece(EquipmentSlot.FEET, Items.DIAMOND_BOOTS, Items.IRON_BOOTS, Items.LEATHER_BOOTS, 4),
    };
    private static final int CHECK_INTERVAL = 20 * 60 * 5;

    /**
     * Armour better than what it wears that it has the diamonds, iron or leather for (in its
     * bag or chests nearby), or null. Checked every 5 minutes.
     */
    public static @Nullable com.minebot.bot.craft.Target craftableUpgrade(BotPlayer bot) {
        long now = bot.level().getGameTime();
        if (now < bot.nextArmorCheck()) {
            return bot.armorUpgrade();
        }
        com.minebot.bot.craft.Target found = findUpgrade(bot);
        bot.setArmorCheck(now + CHECK_INTERVAL, found);
        return found;
    }

    /** The same, worked out right now. */
    public static @Nullable com.minebot.bot.craft.Target findUpgrade(BotPlayer bot) {
        com.minebot.bot.craft.Target found = null;
        for (Piece piece : PIECES) {
            for (net.minecraft.world.item.Item item : new net.minecraft.world.item.Item[] {piece.diamond(), piece.iron(), piece.leather()}) {
                if (!worthMaking(bot.getItemBySlot(piece.slot()), item) || Inv.count(bot, stack -> stack.is(item)) > 0) {
                    continue;
                }
                net.minecraft.world.item.Item material = item == piece.diamond() ? Items.DIAMOND
                    : item == piece.iron() ? Items.IRON_INGOT : Items.LEATHER; // (leather only beats nothing at all)
                com.minebot.bot.craft.Target need = com.minebot.bot.craft.Target.of(material, piece.cost());
                int have = material == Items.IRON_INGOT ? IronStockTask.ingots(bot) + 9 * IronStockTask.blocks(bot) // (blocks taken apart for it)
                    : Inv.count(bot, stack -> stack.is(material)) + ChestTask.stored(bot, need);
                if (have >= piece.cost()) {
                    found = com.minebot.bot.craft.Target.of(item, 1);
                    break;
                }
            }
            if (found != null) {
                break;
            }
        }
        return found != null ? found : spare(bot);
    }

    /**
     * Better material than the piece it wears (wear and tear aside: a new iron chestplate is no
     * reason to make one while it wears an iron one), or the same once its piece is worn out.
     */
    private static boolean worthMaking(ItemStack worn, net.minecraft.world.item.Item item) {
        double fresh = score(new ItemStack(item));
        if (worn.isEmpty()) {
            return fresh > 0;
        }
        double current = score(new ItemStack(worn.getItem())) + (worn.isEnchanted() ? 0.5 : 0);
        return fresh > current + 0.01 || Tools.isWorn(worn) && fresh >= score(new ItemStack(worn.getItem())) - 0.01;
    }

    /** Spare iron pieces kept in the chest (beyond what it wears), when there's iron to spare. */
    private static final int MAX_SPARES = 2;
    private static final int SPARE_IRON = 64;

    /** Wearing iron and well off for iron: a spare chestplate, then leggings, for the chest. */
    private static @Nullable com.minebot.bot.craft.Target spare(BotPlayer bot) {
        java.util.function.Predicate<ItemStack> ironArmour = stack -> stack.is(Items.IRON_CHESTPLATE) || stack.is(Items.IRON_LEGGINGS)
            || stack.is(Items.IRON_HELMET) || stack.is(Items.IRON_BOOTS);
        int spares = Inv.count(bot, ironArmour) + ChestTask.stored(bot, new com.minebot.bot.craft.Target("spare armour", ironArmour, 1));
        if (spares >= MAX_SPARES) {
            return null;
        }
        com.minebot.bot.craft.Target iron = com.minebot.bot.craft.Target.of(Items.IRON_INGOT, 1);
        int ingots = IronStockTask.ingots(bot) + 9 * IronStockTask.blocks(bot);
        for (Piece piece : new Piece[] {PIECES[0], PIECES[1]}) {
            ItemStack worn = bot.getItemBySlot(piece.slot());
            boolean wearsIron = worn.is(piece.iron()) || worn.is(piece.diamond());
            boolean stored = ChestTask.stored(bot, com.minebot.bot.craft.Target.of(piece.iron(), 1)) > 0
                || Inv.count(bot, stack -> stack.is(piece.iron())) > 0;
            if (wearsIron && !stored && ingots >= SPARE_IRON + piece.cost()) {
                return com.minebot.bot.craft.Target.of(piece.iron(), 1);
            }
        }
        return null;
    }

    /** The iron ingots this piece takes, if it's an iron one (0 otherwise). */
    public static int ironCost(com.minebot.bot.craft.Target target) {
        for (Piece piece : PIECES) {
            if (target.accepts().test(new ItemStack(piece.iron()))) {
                return piece.cost();
            }
        }
        return 0;
    }

    /** Would this piece be better than what the bot wears in that slot? */
    public static boolean isUpgrade(BotPlayer bot, ItemStack stack) {
        EquipmentSlot slot = slotOf(stack);
        return slot != null && score(stack) > score(bot.getItemBySlot(slot)) + 0.01;
    }

    /**
     * Puts on any better armour from the inventory (the old piece goes into the
     * bag, and from there into a chest) and a shield in the off hand if it has none.
     */
    public static void equipBest(BotPlayer bot) {
        Inventory inventory = bot.getInventory();
        Ranged.equipBest(bot);
        Ranged.weaponOnHotbar(bot);
        for (EquipmentSlot slot : SLOTS) {
            int best = -1;
            double bestScore = score(bot.getItemBySlot(slot)) + 0.01;
            for (int i = 0; i < Inv.MAIN_SIZE; i++) {
                ItemStack stack = inventory.getItem(i);
                if (slotOf(stack) == slot && score(stack) > bestScore) {
                    best = i;
                    bestScore = score(stack);
                }
            }
            if (best >= 0) {
                ItemStack old = bot.getItemBySlot(slot);
                bot.setItemSlot(slot, inventory.getItem(best).copy());
                inventory.setItem(best, old.copy());
                bot.debug("put on {} instead of {}", bot.getItemBySlot(slot).getItem(), old.isEmpty() ? "nothing" : old.getItem());
            }
        }
        if (bot.getOffhandItem().isEmpty()) {
            int shield = Inv.findSlot(bot, stack -> stack.is(Items.SHIELD));
            if (shield >= 0) {
                bot.setItemSlot(EquipmentSlot.OFFHAND, inventory.getItem(shield).copy());
                inventory.setItem(shield, ItemStack.EMPTY);
            }
        }
    }
}
