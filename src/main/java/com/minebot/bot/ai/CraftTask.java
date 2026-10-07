package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.PlaceNearby;
import com.minebot.bot.craft.Crafting;
import com.minebot.bot.craft.Recipes;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Approach;
import com.minebot.bot.world.Stations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;


/**
 * Crafts a recipe a number of times. 3x3 recipes need a crafting table: the
 * home one, one placed nearby (and taken back afterwards), or a new one.
 */
public class CraftTask extends Task {
    /** Walk back to the home crafting table if it's at most this far. */
    private static final int HOME_TABLE_RANGE = 48;
    /** Walk to someone else's table if it's at most this far. */
    private static final int NEARBY_TABLE_RANGE = 24;

    private final Recipes.CraftOption option;
    private int remaining;
    private @Nullable BlockPos table;
    private boolean placedTable;
    private @Nullable Task child;
    private @Nullable BlockBreaker pickup;
    private int placeAttempts;
    private @Nullable PlaceNearby placer;
    private @Nullable Approach approach;
    /** Tables each bot couldn't get to (buried, walled in): not tried again. */
    private static final java.util.Map<java.util.UUID, java.util.Set<BlockPos>> UNREACHABLE = new java.util.HashMap<>();

    /** Forgotten when the bot leaves the server: back again, it may try those once more. */
    public static void forget(java.util.UUID bot) {
        UNREACHABLE.remove(bot);
    }
    /** Putting a crafting table in the home (the child task); if that fails, place one here. */
    private boolean furnishing;
    private boolean triedHome;

    public CraftTask(BotPlayer bot, Recipes.CraftOption option, int times) {
        super(bot);
        this.option = option;
        this.remaining = times;
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
            boolean wasFurnishing = furnishing;
            furnishing = false;
            if (status == Status.FAILURE && !wasFurnishing) {
                return Status.FAILURE;
            }
        }
        if (pickup != null) {
            return pickUpTable();
        }
        if (remaining <= 0) {
            return finish();
        }
        if (!option.needsTable()) {
            return craft();
        }

        ServerLevel level = bot.level();
        if (table != null && !level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
            table = null;
            placedTable = false;
        }
        if (table == null) {
            table = findTable(bot);
            if (table != null && UNREACHABLE.getOrDefault(bot.getUUID(), java.util.Set.of()).contains(table)) {
                table = null; // (tried that one already)
            }
        }
        if (table == null && !triedHome && bot.memory().craftingTable() == null && Home.isNear(bot, HOME_TABLE_RANGE)) {
            // Home close by but no table there: put one in for good, at the bed's level
            triedHome = true;
            furnishing = true;
            child = FurnishTask.only(bot, Items.CRAFTING_TABLE);
            return Status.RUNNING;
        }
        if (table == null) {
            if (bot.getInventory().countItem(Items.CRAFTING_TABLE) > 0) {
                if (placer == null) {
                    placer = new PlaceNearby(bot, stack -> stack.is(Items.CRAFTING_TABLE));
                }
                BlockPos spot = placer.tick();
                if (spot != null) {
                    placer = null;
                    table = spot;
                    placedTable = !isNearHome(spot);
                    if (!placedTable) {
                        bot.memory().setCraftingTable(spot);
                    }
                } else if (placer.failed()) {
                    return Status.FAILURE;
                }
                return Status.RUNNING;
            }
            child = new ObtainTask(bot, Target.of(Items.CRAFTING_TABLE, 1), 1);
            return Status.RUNNING;
        }
        if (approach == null || !approach.target().equals(table)) {
            approach = new Approach(bot, table);
        }
        Approach.Result reached = approach.tick();
        if (reached == Approach.Result.FAILED) {
            if (table.equals(bot.memory().craftingTable())) {
                // Walled in, buried (dirt filled in over it)...: no use as the home table; a new one goes in
                bot.debug("can't get to my crafting table at {}; forgetting it", table.toShortString());
                bot.memory().setCraftingTable(null);
            }
            UNREACHABLE.computeIfAbsent(bot.getUUID(), id -> new java.util.HashSet<>()).add(table);
            table = null;
            approach = null;
            return ++placeAttempts > 40 ? Status.FAILURE : Status.RUNNING;
        }
        if (reached == Approach.Result.MOVING) {
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().lookAt(Vec3.atCenterOf(table));
        return craft();
    }

    private Status craft() {
        if (!Crafting.craftOnce(bot, option)) {
            bot.debug("craft {} failed, missing {}", option.result(), Crafting.missing(bot, option, 1));
            return Status.FAILURE;
        }
        remaining--;
        return Status.RUNNING;
    }

    private Status finish() {
        if (placedTable && table != null && bot.level().getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
            // Take the table along again
            Stash.makeRoom(bot, 1); // (a full bag would leave it lying there)
            pickup = new BlockBreaker(bot);
            pickup.start(table);
            return Status.RUNNING;
        }
        return Status.SUCCESS;
    }

    private Status pickUpTable() {
        BlockBreaker.Result result = pickup.tick();
        if (result == BlockBreaker.Result.RUNNING) {
            return Status.RUNNING;
        }
        BlockPos at = table;
        pickup = null;
        placedTable = false;
        table = null;
        if (result == BlockBreaker.Result.SUCCESS && at != null) {
            child = new CollectItemsTask(bot, Vec3.atCenterOf(at), 3.0, 60);
        }
        return Status.RUNNING;
    }

    /** Can the bot craft 3x3 recipes right now (table in its bag, or one nearby)? */
    public static boolean hasTableAccess(BotPlayer bot) {
        return bot.getInventory().countItem(Items.CRAFTING_TABLE) > 0 || findTable(bot) != null;
    }

    private static @Nullable BlockPos findTable(BotPlayer bot) {
        ServerLevel level = bot.level();
        BlockPos home = bot.memory().craftingTable();
        if (home != null && level.dimension() == bot.memory().homeDimension()
            && level.getBlockState(home).is(Blocks.CRAFTING_TABLE)
            && home.closerThan(bot.blockPosition(), HOME_TABLE_RANGE)) {
            return home;
        }
        // Anyone's table nearby: a village's, another bot's... (not in a player's home)
        return Stations.nearest(bot, state -> state.is(Blocks.CRAFTING_TABLE), NEARBY_TABLE_RANGE);
    }

    private boolean isNearHome(BlockPos pos) {
        var home = bot.memory().home();
        return home != null && home.dimension() == bot.level().dimension() && home.pos().closerThan(pos, 8);
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        if (pickup != null) {
            pickup.cancel();
        }
        if (placedTable && table != null && bot.level().getBlockState(table).is(Blocks.CRAFTING_TABLE)) {
            bot.leftBehind().add(table); // interrupted before taking it back: fetch it later
        }
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
        String what = "crafting " + option.result().getHoverName().getString();
        return child != null ? what + ": " + child.describe() : what;
    }
}
