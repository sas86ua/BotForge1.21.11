package com.minebot.bot;

import com.minebot.bot.action.Inv;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

/** Starting gear for a freshly spawned or respawned bot: random weapons and armor, food, a campfire. */
public final class BotLoadout {
    static final int MELEE_SLOT = 0;
    static final int BOW_SLOT = 1;
    static final int ARROW_SLOT = 2;
    /** Chance for each piece of gear to drop when the bot dies. */
    static final float GEAR_DROP_CHANCE = 0.1F;

    private static final Item[] SWORDS = {Items.WOODEN_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD};
    private static final Item[] AXES = {Items.WOODEN_AXE, Items.IRON_AXE, Items.DIAMOND_AXE};
    private static final Item[] LEATHER = {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
    private static final Item[] CHAINMAIL = {Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS};
    private static final Item[] IRON = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private BotLoadout() {
    }

    public static void equip(BotPlayer bot) {
        RandomSource random = bot.getRandom();

        // Always a melee weapon: sword or axe, mostly iron, sometimes wood, rarely diamond
        Item[] weapons = random.nextBoolean() ? SWORDS : AXES;
        bot.getInventory().setItem(MELEE_SLOT, new ItemStack(weapons[rollMaterial(random)]));

        if (random.nextFloat() < 0.5F) {
            bot.getInventory().setItem(BOW_SLOT, new ItemStack(Items.BOW));
            bot.getInventory().setItem(ARROW_SLOT, new ItemStack(Items.ARROW, 16 + random.nextInt(17)));
        }

        if (random.nextFloat() < 0.5F) {
            bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        }

        float armorRoll = random.nextFloat();
        Item[] armor = armorRoll < 0.45F ? LEATHER : armorRoll < 0.75F ? CHAINMAIL : IRON; // 45% / 30% / 25%
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            if (random.nextFloat() < 0.75F) {
                bot.setItemSlot(ARMOR_SLOTS[i], new ItemStack(armor[i]));
            }
        }

        // Something to eat, and what it takes to cook more
        Item[] meals = {Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON, Items.COOKED_CHICKEN};
        Inv.give(bot, new ItemStack(meals[random.nextInt(meals.length)], 6 + random.nextInt(5)));
        Inv.give(bot, new ItemStack(Items.CAMPFIRE));
        Inv.give(bot, new ItemStack(Items.FLINT_AND_STEEL));

        bot.getInventory().setSelectedSlot(MELEE_SLOT);
    }

    /** Weapons, ammo, armor and shield: the kind of items {@link #equip} hands out. */
    static boolean isGear(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.ARROWS)
            || stack.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem || stack.is(Items.SHIELD)
            || equippable != null && equippable.slot().isArmor();
    }

    /** 0 = wood (30%), 1 = iron (60%), 2 = diamond (10%). */
    private static int rollMaterial(RandomSource random) {
        float roll = random.nextFloat();
        if (roll < 0.6F) {
            return 1;
        }
        return roll < 0.9F ? 0 : 2;
    }
}
