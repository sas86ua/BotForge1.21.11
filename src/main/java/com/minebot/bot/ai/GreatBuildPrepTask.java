package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.build.GreatBuild;
import com.minebot.bot.craft.Target;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Between the Great Build's sessions, in between its other work: gets its share of the materials
 * ready (a stack at a time, into the chests at home), stone and ore from a mine of its own,
 * underground, so the land around stays as it was.
 */
public class GreatBuildPrepTask extends Task {
    /** Looked at this often. */
    private static final int CHECK_TICKS = 20 * 60 * 2;
    /** Something it couldn't get is left alone this long (a game day). */
    private static final long RETRY_TICKS = GreatBuild.DAY;
    /** Each bot's mine: where it got to (not saved: after a restart it starts one near home). */
    private static final Map<UUID, MiningRule> MINES = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<Item, Long>> FAILED = new ConcurrentHashMap<>();
    /** Items whose stone (or ore) is cooking in its furnaces: left alone until then. */
    private static final Map<UUID, Map<Item, Long>> WAIT = new ConcurrentHashMap<>();

    /** Bots that have tried putting a second furnace in the workshop (once per server run). */
    private static final java.util.Set<UUID> SPARE_TRIED = ConcurrentHashMap.newKeySet();

    private final @Nullable Item item;
    private @Nullable Task child;
    private boolean sideErrand;
    private boolean chestTried;

    public GreatBuildPrepTask(BotPlayer bot) {
        super(bot);
        this.item = shortfall(bot);
    }

    public static boolean wanted(BotPlayer bot) {
        return bot.memory().autonomous() && Home.isNear(bot, 48) && !GreatBuildTask.isAway(bot) && !GreatBuildTask.sessionOn(bot)
            && bot.every("great build prep", CHECK_TICKS, () -> shortfall(bot) != null);
    }

    /** The first item of its order it hasn't got enough of yet (bag and chests at home). */
    private static @Nullable Item shortfall(BotPlayer bot) {
        GreatBuild build = GreatBuild.get(bot.level().getServer());
        if (!build.exists() || build.plan(bot.level().getServer()) == null) {
            return null;
        }
        long now = bot.level().getGameTime();
        Map<Item, Long> failed = FAILED.getOrDefault(bot.getUUID(), Map.of());
        Map<Item, Long> waiting = WAIT.getOrDefault(bot.getUUID(), Map.of());
        for (Map.Entry<Item, Integer> entry : build.orderOf(bot.getUUID()).entrySet()) {
            Item item = entry.getKey();
            if (failed.getOrDefault(item, 0L) > now || waiting.getOrDefault(item, 0L) > now) {
                continue;
            }
            if (have(bot, item) < entry.getValue()) {
                return item;
            }
        }
        return null;
    }

    private static int have(BotPlayer bot, Item item) {
        return Inv.count(bot, stack -> stack.is(item)) + ChestTask.stored(bot, Target.of(item, 1));
    }

    private MiningRule mine() {
        ServerLevel level = bot.level();
        MiningRule rule = MINES.get(bot.getUUID());
        BlockPos home = bot.memory().home() != null ? bot.memory().home().pos() : bot.blockPosition();
        if (rule == null || rule.centre().distManhattan(home) > 160) {
            // A new mine a little way from the house, well under the ground
            BlockPos start = home.offset(12, 0, 12);
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, start.getX(), start.getZ());
            rule = MiningRule.underground(level, start.atY(surface - 16));
            MINES.put(bot.getUUID(), rule);
        }
        return rule;
    }

    @Override
    public Status tick() {
        if (item == null) {
            return Status.SUCCESS;
        }
        if (child == null && !SPARE_TRIED.contains(bot.getUUID()) && bot.memory().furnace() != null && bot.memory().workshop() != null
            && SmeltTask.otherFurnaces(bot).stream().noneMatch(pos -> pos.closerThan(bot.memory().furnace(), 24))) {
            // A second furnace in the workshop (or, no room there, in the house) for the stacks of stone to smelt
            SPARE_TRIED.add(bot.getUUID());
            bot.debug("great build: a second furnace for the workshop");
            sideErrand = true;
            child = FurnishTask.spareFurnace(bot);
        }
        if (child == null && !chestTried && !bot.memory().chests().isEmpty() && ChestTask.chestSpace(bot) < 3) {
            // The chests at home are full: one more for the stacks it's getting ready
            chestTried = true;
            bot.debug("great build: the chests are full, putting in another");
            sideErrand = true; // (a side errand: no matter if there's no room)
            child = new FurnishTask(bot, true);
        }
        if (child == null) {
            GreatBuild build = GreatBuild.get(bot.level().getServer());
            int order = build.orderOf(bot.getUUID()).getOrDefault(item, 0);
            int missing = order - have(bot, item);
            if (missing <= 0) {
                return Status.SUCCESS;
            }
            int stackSize = item.getDefaultMaxStackSize();
            int inBag = Inv.count(bot, stack -> stack.is(item));
            bot.debug("great build: getting {} {} ready ({} still to go)", Math.min(missing, stackSize), GreatBuild.key(item), missing);
            bot.setMiningRule(mine());
            bot.setLeaveWhileSmelting(true);
            child = new ObtainTask(bot, Target.of(item, inBag + Math.min(missing, stackSize)), 0).notFromChests();
        }
        Status status = child.tick();
        int cooking = bot.takeLeftCooking();
        if (status == Status.RUNNING && cooking > 0 && !sideErrand) {
            // The furnaces are loaded and will take minutes: off to other things, back when they're done
            bot.debug("great build: the furnaces need {} s; back for it then", cooking / 20);
            WAIT.computeIfAbsent(bot.getUUID(), u -> new HashMap<>()).put(item, bot.level().getGameTime() + cooking + 100);
            bot.every("great build prep", 0, () -> false); // (nothing else to do for it till then: look again later)
            status = Status.SUCCESS;
        }
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        child.stop();
        child = null;
        bot.setMiningRule(null);
        bot.setLeaveWhileSmelting(false);
        if (sideErrand) {
            sideErrand = false; // (a second furnace, another chest: no matter if there was no room)
            return Status.RUNNING;
        }
        if (status == Status.FAILURE) {
            bot.debug("great build: can't get {} for now", GreatBuild.key(item));
            FAILED.computeIfAbsent(bot.getUUID(), u -> new HashMap<>()).put(item, bot.level().getGameTime() + RETRY_TICKS);
            GreatBuild.get(bot.level().getServer()).failed(item);
        }
        return status;
    }

    @Override
    public void stop() {
        if (child != null) {
            child.stop();
            child = null;
        }
        bot.setMiningRule(null);
        bot.setLeaveWhileSmelting(false);
    }

    @Override
    public @Nullable Target wanted() {
        return child != null ? child.wanted() : null;
    }

    @Override
    public String describe() {
        return child != null ? "great build prep: " + child.describe() : "great build prep";
    }
}
