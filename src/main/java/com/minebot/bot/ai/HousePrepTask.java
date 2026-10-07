package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.build.Blueprint;
import com.minebot.bot.build.HouseTemplates;
import com.minebot.bot.craft.Target;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * In the last 10 days before the big house is due, the bot stocks up for it a
 * little at a time: a batch of stone, logs, glass or torches now and then, put
 * away in the chests (another chest goes down when they're full). When building
 * starts, most of it is waiting at home.
 */
public class HousePrepTask extends Task {
    private static final long DAY = 24000;
    private static final long PREP_TICKS = DAY * 10;
    private static final int CHECK_INTERVAL = 20 * 60 * 5;
    /** One go: no more than this of one material. */
    private static final int BATCH = 32;

    private record Stock(String name, Predicate<ItemStack> accepts, int amount, Target batch) {
    }

    private static @Nullable Map<Character, Integer> mostOfEach;

    private final @Nullable Stock stock;
    private @Nullable Task child;
    private boolean storing;
    private Status result = Status.SUCCESS;

    public HousePrepTask(BotPlayer bot) {
        super(bot);
        this.stock = shortage(bot);
    }

    public static boolean wanted(BotPlayer bot) {
        BotMemory memory = bot.memory();
        long start = HouseTask.startTime(memory);
        long now = bot.level().getGameTime();
        if (start < 0 || now < start - PREP_TICKS || now >= start || Home.levelIfHere(bot) == null
            || Home.isNight(bot) || !Home.isNear(bot, 64)) {
            return false;
        }
        return bot.every("house prep", CHECK_INTERVAL, () -> shortage(bot) != null);
    }

    /** The most cells of each kind any of the house plans has. */
    private static Map<Character, Integer> mostOfEach() {
        if (mostOfEach == null) {
            Map<Character, Integer> most = new HashMap<>();
            for (HouseTemplates.Template template : HouseTemplates.ALL) {
                Map<Character, Integer> count = new HashMap<>();
                for (Blueprint.Cell cell : new Blueprint(template, BlockPos.ZERO, Direction.NORTH).cells()) {
                    count.merge(cell.kind(), 1, Integer::sum);
                }
                count.forEach((kind, n) -> most.merge(kind, n, Math::max));
            }
            mostOfEach = most;
        }
        return mostOfEach;
    }

    /** Stone a house takes at most (what the chests keep back from the lava until it's built). */
    public static int stoneNeeded() {
        return mostOfEach().getOrDefault('C', 0) + 4;
    }

    private static List<Stock> stocks() {
        Map<Character, Integer> most = mostOfEach();
        int stone = stoneNeeded();
        // Planks are made from logs when building (a log gives 4), with some over for the door and chest
        int logs = most.getOrDefault('L', 0) + (most.getOrDefault('P', 0) + 3) / 4 + 4;
        int glass = most.getOrDefault('G', 0);
        int torches = most.getOrDefault('t', 0) + 4;
        Predicate<ItemStack> isGlass = s -> s.is(Items.GLASS) || s.is(Items.GLASS_PANE);
        return List.of(
            new Stock("stone for the house", Blueprint::isStone, stone, Target.of(Items.COBBLESTONE, 0)),
            new Stock("logs for the house", s -> s.is(ItemTags.LOGS), logs, Target.tag(ItemTags.LOGS, 0)),
            new Stock("glass for the house", isGlass, glass, Target.of(Items.GLASS, 0)),
            new Stock("torches for the house", s -> s.is(Items.TORCH), torches, Target.of(Items.TORCH, 0)));
    }

    /** The first material it hasn't enough of yet (bag and chests together), or null. */
    private static @Nullable Stock shortage(BotPlayer bot) {
        for (Stock stock : stocks()) {
            Target any = new Target(stock.name(), stock.accepts(), 1);
            if (Inv.count(bot, stock.accepts()) + ChestTask.stored(bot, any) < stock.amount()) {
                return stock;
            }
        }
        return null;
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
            if (storing) {
                return result;
            }
            if (status == Status.FAILURE) {
                result = Status.FAILURE;
            }
            // Into the chest with it (and whatever else it doesn't keep on hand)
            storing = true;
            child = new StoreTask(bot);
            return Status.RUNNING;
        }
        if (stock == null) {
            bot.every("house prep", 0, () -> false); // (nothing short after all: checked again in 5 minutes)
            return Status.SUCCESS;
        }
        Target any = new Target(stock.name(), stock.accepts(), 1);
        int have = Inv.count(bot, stock.accepts()) + ChestTask.stored(bot, any);
        int batch = Math.min(BATCH, stock.amount() - have);
        if (batch <= 0) {
            bot.every("house prep", 0, () -> false);
            return Status.SUCCESS;
        }
        bot.debug("house prep: {} ({} of {} put by)", stock.name(), have, stock.amount());
        Target gather = stock.batch();
        int inBag = Inv.count(bot, gather.accepts());
        child = new ObtainTask(bot, new Target(gather.name(), gather.accepts(), inBag + batch), 0).notFromChests();
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
        String what = stock != null ? "stocking up " + stock.name() : "stocking up for the house";
        return child != null ? what + ": " + child.describe() : what;
    }
}
