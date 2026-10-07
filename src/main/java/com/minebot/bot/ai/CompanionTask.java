package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.Inv;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockSearch;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "/bot come": the bot keeps a player company. It stays 5-10 blocks away
 * (not on their heels), fights monsters that come for them, mines ore it
 * spots near them and hands it over, and now and then gives them food.
 * A stray hit from that player is forgiven (see {@code BotCombat}).
 */
public class CompanionTask extends Task {
    private static final double NEAR = 5.0;
    private static final double FAR = 10.0;
    /** Further than this, drop whatever it's doing and catch up. */
    private static final double LEAVE_BEHIND = 20.0;
    private static final int ORE_RADIUS = 10;
    private static final int ORE_SCAN_INTERVAL = 100;
    private static final int HAND_OVER_INTERVAL = 20 * 60;
    private static final int FOOD_INTERVAL = 20 * 60 * 3;
    private static final double HAND_OVER_DISTANCE = 2.5;
    /** Coal it mines for the player per "/bot come": plenty for torches, not a coal mine. */
    private static final int MAX_COAL = 24;
    /** Cooked food it keeps for the player: below FOOD_LOW it hunts and cooks up to FOOD_FULL. */
    private static final int FOOD_LOW = 12;
    private static final int FOOD_FULL = 24;
    /** Only animals this close to the player are worth it; the player further than HUNT_LEASH: back to them. */
    private static final double HUNT_RANGE = 36.0;
    private static final double HUNT_LEASH = 48.0;
    private static final int HUNT_RETRY = 20 * 60;

    private final ServerPlayer player;
    private final BlockBreaker breaker;
    /** What it mined for the player and still has to hand over. */
    private final Map<Item, Integer> forPlayer = new LinkedHashMap<>();
    private int coalMined;
    private @Nullable BlockPos ore;
    private int oreTicks;
    private @Nullable Item oreDrop;
    private int dropCountBefore;
    private @Nullable Task collect;
    private boolean handingOver;
    private @Nullable BlockPos heading;
    private long nextOreScan;
    private long nextHandOver;
    private long nextFood;
    /** Hunting and cooking to keep its stock of food up. */
    private @Nullable Task hunt;
    private long nextHunt;

    public CompanionTask(BotPlayer bot, ServerPlayer player) {
        super(bot);
        this.player = player;
        this.breaker = new BlockBreaker(bot);
        bot.combat().setProtectee(player);
    }

    @Override
    public Status tick() {
        if (player.isRemoved() || player.level() != bot.level()) {
            return Status.FAILURE;
        }
        long now = bot.level().getGameTime();
        double distance = bot.distanceTo(player);
        if (distance > LEAVE_BEHIND && (ore != null || collect != null)) {
            dropOre(); // catch up first
        }
        if (collect != null) {
            if (collect.tick() == Status.RUNNING) {
                return Status.RUNNING;
            }
            collect.stop();
            collect = null;
            noteMined();
        }
        if (hunt != null) {
            Status status = distance > HUNT_LEASH ? Status.FAILURE : hunt.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            hunt.stop();
            hunt = null;
            if (status == Status.FAILURE) {
                nextHunt = now + HUNT_RETRY; // (gone too far from the player, or nothing to hunt: later)
            }
        }
        if (ore != null) {
            return mine(now);
        }
        if (handingOver) {
            return handOver(distance);
        }
        if (distance > FAR) {
            follow();
            return Status.RUNNING;
        }
        bot.navigator().stop();
        heading = null;
        bot.controller().lookAt(player.getEyePosition());

        if (!forPlayer.isEmpty() && (now >= nextHandOver || Inv.freeSlots(bot) < 4 || total() >= 16)) {
            handingOver = true;
            return Status.RUNNING;
        }
        if (now >= nextFood && player.getFoodData().getFoodLevel() <= 12 && Inv.count(bot, Food::isCooked) > 6) {
            nextFood = now + FOOD_INTERVAL;
            giveFood();
            return Status.RUNNING;
        }
        if (now >= nextHunt && Inv.count(bot, Food::isCooked) < FOOD_LOW) {
            nextHunt = now + 20 * 10;
            if (Inv.count(bot, stack -> Inv.isFood(stack) && !Food.isCooked(stack)) > 0 || animalNearPlayer()) {
                bot.debug("companion: low on food for {}, hunting and cooking up to {}", player.getPlainTextName(), FOOD_FULL);
                hunt = new FoodTask(bot, FOOD_FULL, true, false, 0).huntingWithin((int) HUNT_RANGE);
                return Status.RUNNING;
            }
        }
        if (now >= nextOreScan) {
            nextOreScan = now + ORE_SCAN_INTERVAL;
            ore = findOre();
            if (ore != null) {
                BlockState state = bot.level().getBlockState(ore);
                oreDrop = dropOf(state);
                dropCountBefore = Inv.count(bot, oreDrop);
                oreTicks = 0;
                bot.debug("companion: {} near {}, mining it", state.getBlock().getName().getString(), player.getPlainTextName());
            }
        }
        return Status.RUNNING;
    }

    private void follow() {
        BlockPos target = player.blockPosition();
        if (heading == null || !bot.navigator().isActive() || heading.distSqr(target) > 16) {
            heading = target;
            bot.navigator().navigate(Goal.near(target, NEAR));
        }
        bot.navigator().tick();
    }

    // ---- mining for the player --------------------------------------------------------------

    /** Ore in plain view near the player that the bot's pickaxe can take. */
    private @Nullable BlockPos findOre() {
        ServerLevel level = bot.level();
        BlockPos center = player.blockPosition();
        List<BlockPos> found = BlockSearch.find(level, center, ORE_RADIUS, center.getY() - 6, center.getY() + 6,
            CompanionTask::isOre,
            (pos, state) -> isExposed(level, pos) && Inv.canHarvest(bot, state) && !ProtectedAreas.isProtected(level, pos)
                && !(state.is(BlockTags.COAL_ORES) && coalMined >= MAX_COAL)
                && pos.closerThan(bot.blockPosition(), 16),
            8);
        return found.stream().min(java.util.Comparator.comparingDouble(pos -> pos.distSqr(bot.blockPosition()))).orElse(null);
    }

    /** An animal worth the meat near the player (not off across the map). */
    private boolean animalNearPlayer() {
        // (the same choice the hunt makes: fair prey, from a herd that lives on, close by)
        for (com.minebot.bot.craft.Sources.Kill kill : FoodTask.meatAnimals()) {
            if (HuntTask.findPrey(bot, kill, java.util.Set.of(), (int) HUNT_RANGE) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOre(BlockState state) {
        return state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES)
            || state.is(BlockTags.GOLD_ORES) && !state.is(net.minecraft.world.level.block.Blocks.NETHER_GOLD_ORE)
            || state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES);
    }

    private static boolean isExposed(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /** The item an ore drops (without fortune or silk touch). */
    private Item dropOf(BlockState state) {
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(state, bot.level(), BlockPos.ZERO, null);
        return drops.isEmpty() ? state.getBlock().asItem() : drops.get(0).getItem();
    }

    private Status mine(long now) {
        BlockPos target = ore;
        if (!isOre(bot.level().getBlockState(target)) || ++oreTicks > 20 * 30) {
            dropOre();
            return Status.RUNNING;
        }
        if (!breaker.isBreaking(target)) {
            if (!bot.canUse(target)) {
                if (!bot.navigator().isActive()) {
                    bot.navigator().navigate(Goal.reachVisible(bot.level(), target));
                }
                if (bot.navigator().tick().ended() && !bot.canUse(target)) {
                    dropOre();
                }
                return Status.RUNNING;
            }
            bot.navigator().stop();
            breaker.start(target);
        }
        BlockBreaker.Result result = breaker.tick();
        if (result == BlockBreaker.Result.SUCCESS) {
            ore = null;
            collect = new CollectItemsTask(bot, Vec3.atCenterOf(target), 3.0, 60);
            nextOreScan = now + 20; // there's often more next to it
        } else if (result == BlockBreaker.Result.FAILED) {
            dropOre();
        }
        return Status.RUNNING;
    }

    /** What the last ore yielded goes on the list for the player. */
    private void noteMined() {
        if (oreDrop == null) {
            return;
        }
        int gained = Inv.count(bot, oreDrop) - dropCountBefore;
        if (gained > 0) {
            forPlayer.merge(oreDrop, gained, Integer::sum);
            if (oreDrop == net.minecraft.world.item.Items.COAL) {
                coalMined += gained;
            }
            if (nextHandOver == 0 || forPlayer.size() == 1) {
                nextHandOver = bot.level().getGameTime() + HAND_OVER_INTERVAL;
            }
        }
        oreDrop = null;
    }

    private void dropOre() {
        breaker.cancel();
        bot.navigator().stop();
        if (collect != null) {
            collect.stop();
            collect = null;
            noteMined();
        }
        ore = null;
    }

    private int total() {
        return forPlayer.values().stream().mapToInt(Integer::intValue).sum();
    }

    // ---- giving -------------------------------------------------------------------------------

    private Status handOver(double distance) {
        if (distance > HAND_OVER_DISTANCE) {
            BlockPos target = player.blockPosition();
            if (heading == null || !bot.navigator().isActive() || heading.distSqr(target) > 4) {
                heading = target;
                bot.navigator().navigate(Goal.near(target, 1.5));
            }
            if (bot.navigator().tick() == com.minebot.bot.path.Navigator.Status.FAILED) {
                handingOver = false;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().lookAt(player.getEyePosition());
        Inventory inventory = bot.getInventory();
        for (Map.Entry<Item, Integer> entry : forPlayer.entrySet()) {
            int left = entry.getValue();
            for (int slot = 0; slot < Inv.MAIN_SIZE && left > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.is(entry.getKey())) {
                    int amount = Math.min(left, stack.getCount());
                    bot.drop(stack.split(amount), false, true);
                    left -= amount;
                }
            }
            bot.debug("companion: gave {} {} to {}", entry.getValue() - left, entry.getKey(), player.getPlainTextName());
        }
        forPlayer.clear();
        handingOver = false;
        nextHandOver = bot.level().getGameTime() + HAND_OVER_INTERVAL;
        return Status.RUNNING;
    }

    /** Two or three pieces of cooked food, tossed over. */
    private void giveFood() {
        int slot = Inv.findSlot(bot, Food::isCooked);
        if (slot < 0) {
            return;
        }
        bot.controller().lookAt(player.getEyePosition());
        ItemStack stack = bot.getInventory().getItem(slot);
        int amount = Math.min(stack.getCount(), 2 + bot.getRandom().nextInt(2));
        bot.debug("companion: {} looks hungry, giving {} {}", player.getPlainTextName(), amount, stack.getItem());
        bot.drop(stack.split(amount), false, true);
    }

    @Override
    public void stop() {
        breaker.cancel();
        bot.navigator().stop();
        if (hunt != null) {
            hunt.stop();
        }
        if (collect != null) {
            collect.stop();
        }
        bot.combat().setProtectee(null);
    }

    @Override
    public String describe() {
        String what = "keeping " + player.getPlainTextName() + " company";
        if (ore != null) {
            return what + " (mining ore)";
        }
        if (hunt != null) {
            return what + " (" + hunt.describe() + ")";
        }
        return handingOver ? what + " (handing over ore)" : what;
    }
}
