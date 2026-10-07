package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Target;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * Iron kept at home partly as blocks: 3-6 blocks of iron and 12-36 ingots. Ingots over 36 go into
 * blocks (while there are fewer than 6), and with fewer than 12 ingots a block is taken apart again
 * (not below 3); for armour it takes apart as many as the pieces need.
 */
public class IronStockTask extends Task {
    public static final int MIN_BLOCKS = 3;
    public static final int MAX_BLOCKS = 6;
    public static final int MIN_INGOTS = 12;
    public static final int MAX_INGOTS = 36;
    private static final int CHECK_INTERVAL = 20 * 60 * 5;

    /** Blocks to make (> 0) or take apart (< 0); for armour, the ingots wanted instead. */
    private final int blocks;
    private final int ingotsWanted;
    private @Nullable Task child;
    private boolean fetched;
    private boolean crafted;

    private IronStockTask(BotPlayer bot, int blocks, int ingotsWanted) {
        super(bot);
        this.blocks = blocks;
        this.ingotsWanted = ingotsWanted;
    }

    public IronStockTask(BotPlayer bot) {
        this(bot, change(bot), 0);
    }

    /** Ingots to hand (bag and chests) for crafting: blocks taken apart for them if need be. */
    public static IronStockTask ingotsFor(BotPlayer bot, int ingots) {
        return new IronStockTask(bot, 0, ingots);
    }

    public static boolean wanted(BotPlayer bot) {
        return Home.isNear(bot, 32) && bot.every("iron storage", CHECK_INTERVAL, () -> change(bot) != 0);
    }

    public static int ingots(BotPlayer bot) {
        return Inv.count(bot, stack -> stack.is(Items.IRON_INGOT)) + ChestTask.stored(bot, Target.of(Items.IRON_INGOT, 1));
    }

    public static int blocks(BotPlayer bot) {
        return Inv.count(bot, stack -> stack.is(Items.IRON_BLOCK)) + ChestTask.stored(bot, Target.of(Items.IRON_BLOCK, 1));
    }

    /** All its iron in ingots: ingots, raw iron (to smelt) and blocks (nine each), bag and chests. */
    public static int total(BotPlayer bot) {
        return ingots(bot) + 9 * blocks(bot) + Inv.count(bot, stack -> stack.is(Items.RAW_IRON))
            + ChestTask.stored(bot, Target.of(Items.RAW_IRON, 1));
    }

    /** Blocks to make (> 0) or take apart (< 0) to keep 3-6 blocks and 12-36 ingots. */
    private static int change(BotPlayer bot) {
        int ingots = ingots(bot);
        int blocks = blocks(bot);
        if (ingots > MAX_INGOTS && blocks < MAX_BLOCKS) {
            return Math.min(MAX_BLOCKS - blocks, (ingots - MAX_INGOTS + 8) / 9);
        }
        if (ingots < MIN_INGOTS && blocks > MIN_BLOCKS) {
            return -Math.min(blocks - MIN_BLOCKS, (MIN_INGOTS - ingots + 8) / 9);
        }
        return 0;
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
                return Status.FAILURE;
            }
        }
        int apart = ingotsWanted > 0 ? (Math.max(0, ingotsWanted - ingots(bot)) + 8) / 9 : Math.max(0, -blocks);
        if (apart > 0) {
            return takeApart(apart);
        }
        if (blocks > 0) {
            return make(blocks);
        }
        return Status.SUCCESS;
    }

    /** Blocks of iron out of the chests, each made back into nine ingots (in the bag's crafting grid). */
    private Status takeApart(int count) {
        int inBag = Inv.count(bot, stack -> stack.is(Items.IRON_BLOCK));
        if (inBag < count && !fetched) {
            fetched = true;
            child = ChestTask.withdraw(bot, Target.of(Items.IRON_BLOCK, count));
            return Status.RUNNING;
        }
        int done = 0;
        var items = bot.getInventory().getNonEquipmentItems();
        for (int i = 0; i < items.size() && done < count; i++) {
            ItemStack stack = items.get(i);
            while (stack.is(Items.IRON_BLOCK) && !stack.isEmpty() && done < count) {
                stack.shrink(1);
                Inv.give(bot, new ItemStack(Items.IRON_INGOT, 9));
                done++;
            }
        }
        bot.debug("iron: took {} blocks of iron apart into ingots", done);
        return done > 0 ? Status.SUCCESS : Status.FAILURE;
    }

    /** Nine ingots a block, on a crafting table; the ingots out of the chests first. */
    private Status make(int count) {
        if (crafted) {
            bot.debug("iron: made {} blocks of iron", count);
            return Status.SUCCESS;
        }
        int ingots = Inv.count(bot, stack -> stack.is(Items.IRON_INGOT));
        if (ingots < 9 * count && !fetched) {
            fetched = true;
            child = ChestTask.withdraw(bot, Target.of(Items.IRON_INGOT, 9 * count));
            return Status.RUNNING;
        }
        crafted = true;
        int have = Inv.count(bot, stack -> stack.is(Items.IRON_BLOCK));
        child = new ObtainTask(bot, Target.of(Items.IRON_BLOCK, have + Math.min(count, ingots / 9)), 0).notFromChests();
        return Status.RUNNING;
    }

    @Override
    public void stop() {
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
        return ingotsWanted > 0 ? "taking iron blocks apart for ingots" : blocks > 0 ? "putting iron into blocks" : "taking iron blocks apart";
    }
}
