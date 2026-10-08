package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Target;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Diamonds to spare once its armour is diamond all over: diamond tools, the most useful first - the
 * pickaxe (3), the sword (2), the axe (3), the shovel (1). One at a time, in that order: it saves up for
 * the pickaxe before a sword.
 */
final class DiamondTools {
    private record Tool(Item item, TagKey<Item> kind, Item better, int diamonds) {
    }

    private static final List<Tool> ORDER = List.of(
        new Tool(Items.DIAMOND_PICKAXE, ItemTags.PICKAXES, Items.NETHERITE_PICKAXE, 3),
        new Tool(Items.DIAMOND_SWORD, ItemTags.SWORDS, Items.NETHERITE_SWORD, 2),
        new Tool(Items.DIAMOND_AXE, ItemTags.AXES, Items.NETHERITE_AXE, 3),
        new Tool(Items.DIAMOND_SHOVEL, ItemTags.SHOVELS, Items.NETHERITE_SHOVEL, 1));
    private static final int CHECK_TICKS = 20 * 60 * 5;
    private static final List<EquipmentSlot> ARMOUR = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private DiamondTools() {
    }

    static boolean wanted(BotPlayer bot) {
        return bot.memory().autonomous() && Home.isNear(bot, 32) && bot.every("diamond tools", CHECK_TICKS, () -> next(bot) != null);
    }

    static Task task(BotPlayer bot) {
        Item tool = next(bot);
        bot.every("diamond tools", 0, () -> false); // (looked at again next time round)
        if (tool != null) {
            bot.debug("diamonds to spare: a {}", tool);
        }
        return new ObtainTask(bot, Target.of(tool != null ? tool : Items.DIAMOND_PICKAXE, tool != null ? 1 : 0), 0);
    }

    /** The next diamond tool to make, if its armour is all diamond and there are the diamonds for it; else null. */
    static @Nullable Item next(BotPlayer bot) {
        for (EquipmentSlot slot : ARMOUR) {
            ItemStack worn = bot.getItemBySlot(slot);
            String name = worn.getItem().toString();
            if (worn.isEmpty() || !name.contains("diamond") && !name.contains("netherite")) {
                return null; // (diamonds go into armour first)
            }
        }
        int diamonds = Inv.count(bot, stack -> stack.is(Items.DIAMOND)) + ChestTask.stored(bot, Target.of(Items.DIAMOND, 1));
        for (Tool tool : ORDER) {
            Target owned = new Target(tool.item().toString(), stack -> stack.is(tool.item()) || stack.is(tool.better()), 1);
            if (Inv.count(bot, owned.accepts()) > 0 || ChestTask.stored(bot, owned) > 0) {
                continue; // (has one: the next kind)
            }
            return diamonds >= tool.diamonds() ? tool.item() : null;
        }
        return null;
    }
}
