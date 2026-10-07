package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Mends its gear at an anvil in its house, paying in experience levels (from the mobs it
 * kills) and in material: an iron item with iron ingots, a diamond one with diamonds, and so on.
 * Only good gear (iron, gold, diamond, netherite, leather armour) worn down to half or less. With
 * no anvil in the house it makes one (31 iron) when it has iron to spare, and puts it by the bed.
 */
public class RepairTask extends Task {
    private static final int CHECK_INTERVAL = 20 * 60 * 5;
    /** Repaired when this much of it is used up. */
    private static final double WORN = 0.5;
    /** Levels it keeps in hand before spending them on a repair (one repair costs a few). */
    private static final int MIN_LEVELS = 3;
    /** Vanilla refuses repairs that would cost this many levels or more ("Too Expensive!"). */
    private static final int TOO_EXPENSIVE = 40;
    /** An anvil takes 31 iron; it's made only with this much more left over. */
    private static final int ANVIL_IRON = 31;
    private static final int IRON_LEFT = 16;
    private static final List<Item> MATERIALS = List.of(Items.IRON_INGOT, Items.DIAMOND, Items.NETHERITE_INGOT,
        Items.GOLD_INGOT, Items.LEATHER);
    private static final int MAX_TICKS = 20 * 60 * 3;

    private @Nullable Task child;
    private @Nullable BlockPos anvil;
    private boolean triedAnvil;
    private boolean placingAnvil;
    private boolean fetchedIron;
    private boolean fetchedBlocks;
    private int repaired;
    private int ticks;

    public RepairTask(BotPlayer bot) {
        super(bot);
    }

    public static boolean wanted(BotPlayer bot) {
        return bot.memory().houseDone() && bot.experienceLevel >= MIN_LEVELS && Home.isNear(bot, 32) && !Home.isNight(bot)
            && bot.every("repair", CHECK_INTERVAL, () -> worn(bot) != null && (findAnvil(bot) != null || canMakeAnvil(bot)));
    }

    /** Its most worn good item (bag, hands, armour) that has its repair material to hand. */
    private static @Nullable ItemStack worn(BotPlayer bot) {
        ItemStack best = null;
        for (ItemStack stack : gear(bot)) {
            if (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() * WORN || material(bot, stack) == null) {
                continue;
            }
            if (best == null || (long) stack.getDamageValue() * best.getMaxDamage() > (long) best.getDamageValue() * stack.getMaxDamage()) {
                best = stack;
            }
        }
        return best;
    }

    private static List<ItemStack> gear(BotPlayer bot) {
        List<ItemStack> all = new ArrayList<>(bot.getInventory().getNonEquipmentItems());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR || slot == EquipmentSlot.OFFHAND) {
                all.add(bot.getItemBySlot(slot));
            }
        }
        return all;
    }

    /** What mends this item, if it has some (in the bag or its chests). */
    private static @Nullable Item material(BotPlayer bot, ItemStack stack) {
        for (Item material : MATERIALS) {
            if (stack.isValidRepairItem(new ItemStack(material))
                && (Inv.count(bot, s -> s.is(material)) > 0 || ChestTask.stored(bot, Target.of(material, 1)) > 0)) {
                return material;
            }
        }
        return null;
    }

    /** An anvil in its house (by the bed), if there is one. */
    private static @Nullable BlockPos findAnvil(BotPlayer bot) {
        BlockPos bed = bot.memory().bed();
        if (bed == null || Home.levelIfHere(bot) == null) {
            return null;
        }
        List<BlockPos> found = BlockSearch.find(bot.level(), bed, 8, bed.getY() - 2, bed.getY() + 3,
            state -> state.is(BlockTags.ANVIL), (pos, state) -> true, 1);
        return found.isEmpty() ? null : found.get(0);
    }

    private static boolean canMakeAnvil(BotPlayer bot) {
        if (Inv.count(bot, s -> s.is(Items.ANVIL)) > 0) {
            return true;
        }
        Predicate<ItemStack> iron = s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON);
        int ironBlocks = Inv.count(bot, s -> s.is(Items.IRON_BLOCK)) + ChestTask.stored(bot, Target.of(Items.IRON_BLOCK, 1));
        return Inv.count(bot, iron) + ChestTask.stored(bot, new Target("iron", iron, 1)) + 9 * ironBlocks >= ANVIL_IRON + IRON_LEFT;
    }

    @Override
    public Status tick() {
        if (child != null) {
            Status status = child.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            child.stop();
            child = null;
            if (status == Status.FAILURE) {
                return finish();
            }
        }
        if (++ticks > MAX_TICKS) {
            return finish();
        }
        ServerLevel level = bot.level();
        if (anvil == null || !level.getBlockState(anvil).is(BlockTags.ANVIL)) {
            anvil = findAnvil(bot);
            if (anvil == null) {
                if (Inv.count(bot, s -> s.is(Items.ANVIL)) > 0) {
                    if (placingAnvil) {
                        return finish(); // (no room for it)
                    }
                    placingAnvil = true;
                    child = FurnishTask.only(bot, Items.ANVIL);
                    return Status.RUNNING;
                }
                if (triedAnvil || !canMakeAnvil(bot)) {
                    return finish();
                }
                // The iron out of the chests first: the planner doesn't make blocks of iron (for the anvil)
                // out of ingots that are still in a chest
                // (blocks of iron kept in the chests first: an anvil is three of them and four ingots)
                if (!fetchedBlocks && Inv.count(bot, s -> s.is(Items.IRON_BLOCK)) < 3 && IronStockTask.blocks(bot) > 0) {
                    fetchedBlocks = true;
                    child = ChestTask.withdraw(bot, Target.of(Items.IRON_BLOCK, 3));
                    return Status.RUNNING;
                }
                int ingotsNeeded = ANVIL_IRON - 9 * Math.min(3, Inv.count(bot, s -> s.is(Items.IRON_BLOCK)));
                if (!fetchedIron && Inv.count(bot, s -> s.is(Items.IRON_INGOT)) < ingotsNeeded) {
                    fetchedIron = true;
                    child = ChestTask.withdraw(bot, Target.of(Items.IRON_INGOT, ingotsNeeded));
                    return Status.RUNNING;
                }
                triedAnvil = true;
                bot.debug("repair: making an anvil for the house");
                child = new ObtainTask(bot, Target.of(Items.ANVIL, 1), 0);
                return Status.RUNNING;
            }
        }
        ItemStack item = worn(bot);
        if (item == null || bot.experienceLevel < 1) {
            return finish();
        }
        Item material = material(bot, item);
        int units = (int) Math.ceil(item.getDamageValue() / (item.getMaxDamage() / 4.0));
        if (Inv.count(bot, s -> s.is(material)) == 0) {
            child = ChestTask.withdraw(bot, Target.of(material, Math.min(units, 4)));
            return Status.RUNNING;
        }
        if (!bot.canUse(anvil)) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.reachVisible(level, anvil));
            }
            if (bot.navigator().tick().ended() && !bot.canUse(anvil)) {
                bot.debug("repair: can't get to the anvil at {}", anvil.toShortString());
                return finish();
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(anvil));
        return mend(level, item, material, units) ? Status.RUNNING : finish();
    }

    /** One go at the anvil, the way a player would do it. False if it couldn't (too dear, no levels). */
    private boolean mend(ServerLevel level, ItemStack item, Item material, int units) {
        int slot = findSlot(item);
        if (slot == NOWHERE) {
            return false;
        }
        int count = Math.min(units, Inv.count(bot, s -> s.is(material)));
        AnvilMenu menu = new AnvilMenu(0, bot.getInventory(), ContainerLevelAccess.create(level, anvil));
        ItemStack before = item.copy();
        menu.getSlot(AnvilMenu.INPUT_SLOT).set(item.copy());
        menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(material, count));
        menu.createResult();
        ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem().copy();
        int cost = menu.getCost();
        if (result.isEmpty() || cost <= 0 || cost >= TOO_EXPENSIVE || bot.experienceLevel < cost) {
            bot.debug("repair: {} would cost {} levels (has {})", before.getHoverName().getString(), cost, bot.experienceLevel);
            clear(menu);
            return false;
        }
        int used = menu.repairItemCountCost > 0 ? menu.repairItemCountCost : count;
        // Taking the result pays the levels and wears the anvil, as for a player
        menu.getSlot(AnvilMenu.RESULT_SLOT).onTake(bot, result);
        take(material, used);
        put(slot, result);
        clear(menu);
        repaired++;
        bot.debug("repair: mended {} with {} {} for {} levels ({}% worn before)", before.getHoverName().getString(), used,
            material, cost, before.getDamageValue() * 100 / before.getMaxDamage());
        return true;
    }

    private static final int NOWHERE = Integer.MIN_VALUE;
    /** Armour and offhand slots are numbered from here down (inventory slots are 0 up). */
    private static final int EQUIPMENT = -10;

    /** Where the item is: an inventory slot, an equipment slot (EQUIPMENT - ordinal), or NOWHERE. */
    private int findSlot(ItemStack item) {
        var items = bot.getInventory().getNonEquipmentItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == item) {
                return i;
            }
        }
        for (EquipmentSlot equipment : EquipmentSlot.values()) {
            if (bot.getItemBySlot(equipment) == item) {
                return EQUIPMENT - equipment.ordinal();
            }
        }
        return NOWHERE;
    }

    private void put(int slot, ItemStack result) {
        if (slot >= 0) {
            bot.getInventory().setItem(slot, result);
        } else {
            bot.setItemSlot(EquipmentSlot.values()[EQUIPMENT - slot], result);
        }
    }

    /** Takes this many of the material out of the bag. */
    private void take(Item material, int count) {
        var items = bot.getInventory().getNonEquipmentItems();
        for (int i = 0; i < items.size() && count > 0; i++) {
            ItemStack stack = items.get(i);
            if (stack.is(material)) {
                int n = Math.min(count, stack.getCount());
                stack.shrink(n);
                count -= n;
            }
        }
    }

    /** The menu was only borrowed: nothing left in it goes anywhere. */
    private static void clear(AnvilMenu menu) {
        menu.getSlot(AnvilMenu.INPUT_SLOT).set(ItemStack.EMPTY);
        menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(ItemStack.EMPTY);
    }

    private Status finish() {
        bot.navigator().stop();
        return repaired > 0 ? Status.SUCCESS : Status.FAILURE;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        if (child != null) {
            child.stop();
        }
    }

    @Override
    public @Nullable Target wanted() {
        return child != null ? child.wanted() : null;
    }

    @Override
    public String describe() {
        return child != null ? "mending its gear: " + child.describe() : "mending its gear at the anvil";
    }
}
