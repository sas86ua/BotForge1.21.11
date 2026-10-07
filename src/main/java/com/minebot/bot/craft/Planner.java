package com.minebot.bot.craft;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.ai.ChestTask;
import com.minebot.bot.ai.HuntTask;
import com.minebot.bot.ai.Stash;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.world.BlockSearch;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Estimates how expensive each way of getting an item is (mine, hunt, smelt,
 * craft from ingredients...) and orders the options cheapest first. Cycles
 * like "iron pickaxe needs iron needs a pickaxe" are cut off, so the bot
 * naturally climbs wood -> stone -> iron.
 */
public class Planner {
    public sealed interface Strategy permits StoredStrategy, MineStrategy, KillStrategy, CookStrategy, CampfireStrategy, CraftStrategy {
    }

    public record StoredStrategy() implements Strategy {
        @Override
        public String toString() {
            return "take from the home chest";
        }
    }

    public record MineStrategy(Sources.Mine source) implements Strategy {
        @Override
        public String toString() {
            return "mine " + source.name();
        }
    }

    public record KillStrategy(Sources.Kill kill) implements Strategy {
        @Override
        public String toString() {
            return "hunt " + kill.name();
        }
    }

    public record CookStrategy(Recipes.CookOption option) implements Strategy {
        @Override
        public String toString() {
            return "smelt into " + option.result().getHoverName().getString();
        }
    }

    public record CampfireStrategy(Recipes.CookOption option) implements Strategy {
        @Override
        public String toString() {
            return "cook on a campfire into " + option.result().getHoverName().getString();
        }
    }

    public record CraftStrategy(Recipes.CraftOption option) implements Strategy {
        @Override
        public String toString() {
            return "craft " + option.result().getHoverName().getString();
        }
    }

    public record Scored(Strategy strategy, double cost) {
    }

    private static final double INF = Double.POSITIVE_INFINITY;
    private static final int MAX_DEPTH = 14;
    private static final double MINE_COST = 8;
    private static final double KILL_COST = 25;
    private static final double SMELT_COST = 10;
    private static final double SMOKER_COST = 2;
    /** Campfires need no fuel, so they win for food. */
    private static final double CAMPFIRE_COST = 4;
    private static final double CRAFT_COST = 1;
    /** Diamonds go on armour first: a tool that can be had of iron as well is made of iron. */
    private static final double DIAMOND_COST = 200;
    /** Extra cost when no such block is in the loaded area around the bot. */
    private static final double NOT_NEARBY = 80;
    private static final int NEARBY_RADIUS = 48;

    private final BotPlayer bot;
    private final MinecraftServer server;
    private final Map<String, Double> memo = new HashMap<>();
    private final Map<String, Boolean> nearby = new HashMap<>();
    private @Nullable List<RandomizableContainerBlockEntity> chests;

    public Planner(BotPlayer bot) {
        this.bot = bot;
        this.server = bot.level().getServer();
    }

    /** All ways to get the target, cheapest first; impossible ones left out. */
    public List<Scored> options(Target target) {
        List<Scored> result = new ArrayList<>();
        int missing = target.missing(bot);
        Set<String> visiting = new HashSet<>();
        visiting.add(target.name());

        if (stored(target) > 0) {
            add(result, new StoredStrategy(), storedCost());
        }
        Sources.Mine mine = Sources.mineFor(target);
        if (mine != null) {
            add(result, new MineStrategy(mine), mineCost(mine, target, missing, 1, visiting));
        }
        for (Sources.Kill kill : Sources.killsFor(target)) {
            add(result, new KillStrategy(kill), killCost(kill, missing));
        }
        for (Recipes.CookOption cook : Recipes.smeltingFor(server, target.accepts())) {
            add(result, new CookStrategy(cook), cookCost(cook, missing, 1, visiting));
        }
        for (Recipes.CookOption cook : Recipes.campfireFor(server, target.accepts())) {
            add(result, new CampfireStrategy(cook), campfireCost(cook, missing, 1, visiting));
        }
        for (Recipes.CraftOption craft : Recipes.craftingFor(server, target.accepts())) {
            add(result, new CraftStrategy(craft), craftCost(craft, missing, 1, visiting));
        }
        result.sort(Comparator.comparingDouble(Scored::cost));
        return result;
    }

    private static void add(List<Scored> result, Strategy strategy, double cost) {
        if (cost < INF) {
            result.add(new Scored(strategy, cost));
        }
    }

    public double cost(Target target, int depth, Set<String> visiting) {
        int missing = target.missing(bot);
        if (missing == 0) {
            return 0;
        }
        if (depth > MAX_DEPTH || !visiting.add(target.name())) {
            return INF; // too deep, or a cycle
        }
        String key = target.name() + "#" + missing;
        Double cached = memo.get(key);
        if (cached != null) {
            visiting.remove(target.name());
            return cached;
        }
        double best = stored(target) >= missing ? storedCost() : INF;
        Sources.Mine mine = Sources.mineFor(target);
        if (mine != null) {
            best = Math.min(best, mineCost(mine, target, missing, depth, visiting));
        }
        for (Sources.Kill kill : Sources.killsFor(target)) {
            best = Math.min(best, killCost(kill, missing));
        }
        for (Recipes.CookOption cook : Recipes.smeltingFor(server, target.accepts())) {
            best = Math.min(best, cookCost(cook, missing, depth, visiting));
        }
        for (Recipes.CookOption cook : Recipes.campfireFor(server, target.accepts())) {
            best = Math.min(best, campfireCost(cook, missing, depth, visiting));
        }
        for (Recipes.CraftOption craft : Recipes.craftingFor(server, target.accepts())) {
            best = Math.min(best, craftCost(craft, missing, depth, visiting));
        }
        visiting.remove(target.name());
        memo.put(key, best);
        return best;
    }

    private double mineCost(Sources.Mine mine, Target target, int missing, int depth, Set<String> visiting) {
        double cost = missing * MINE_COST;
        if (!mine.canHarvest(bot)) {
            cost += cost(mine.toolTarget(), depth + 1, visiting);
        }
        if (!isNearby(mine, target)) {
            cost += NOT_NEARBY;
        }
        return cost;
    }

    /** Matching items in chests the bot may take from (chests looked up once per plan). */
    private int stored(Target target) {
        if (chests == null) {
            chests = ChestTask.reachable(bot);
        }
        int total = 0;
        for (var chest : chests) {
            total += Stash.count(chest, target.accepts());
        }
        return total;
    }

    /** Walking home and opening a chest: cheap unless home is far away. */
    private double storedCost() {
        var home = bot.memory().home();
        double distance = home == null ? 0 : Math.sqrt(home.pos().distSqr(bot.blockPosition()));
        if (distance > 128 && com.minebot.bot.ai.GreatBuildTask.isAway(bot)) {
            return INF; // (away at the Great Build: no running home for something, it's got on the spot)
        }
        return 2 + distance / 10;
    }

    private double killCost(Sources.Kill kill, int missing) {
        double cost = missing * KILL_COST;
        boolean seen = nearby.computeIfAbsent("kill|" + kill.name(),
            key -> HuntTask.findPrey(bot, kill, Set.of()) != null);
        return seen ? cost : cost + NOT_NEARBY;
    }

    private double cookCost(Recipes.CookOption cook, int missing, int depth, Set<String> visiting) {
        if (Inv.isFood(cook.result()) && com.minebot.bot.ai.SmeltTask.ownSmoker(bot) != null) {
            // Its own smoker at home: twice as fast as a furnace, no standing by a camp fire, the furnace stays free
            return cost(Target.ingredient(cook.input(), missing), depth + 1, visiting) + missing * SMOKER_COST;
        }
        double cost = cost(Target.ingredient(cook.input(), missing), depth + 1, visiting) + missing * SMELT_COST;
        if (bot.memory().furnace() == null && bot.getInventory().countItem(Items.FURNACE) == 0 && !furnaceNearby()) {
            cost += cost(Target.of(Items.FURNACE, 1), depth + 1, visiting);
        }
        return cost;
    }

    /** Someone's furnace close by (a village's, another bot's) will do too. */
    private boolean furnaceNearby() {
        if (furnaceNearby == null) {
            furnaceNearby = com.minebot.bot.world.Stations.nearest(bot,
                state -> state.is(net.minecraft.world.level.block.Blocks.FURNACE), 24) != null;
        }
        return furnaceNearby;
    }

    private @Nullable Boolean furnaceNearby;

    private double campfireCost(Recipes.CookOption cook, int missing, int depth, Set<String> visiting) {
        double cost = cost(Target.ingredient(cook.input(), missing), depth + 1, visiting) + missing * CAMPFIRE_COST;
        if (bot.memory().houseDone() && com.minebot.bot.ai.Home.isNear(bot, 64)) {
            cost += NOT_NEARBY; // the house has a smoker for that
        }
        if (bot.memory().campfire() == null && bot.getInventory().countItem(Items.CAMPFIRE) == 0) {
            cost += cost(Target.of(Items.CAMPFIRE, 1), depth + 1, visiting);
        }
        return cost;
    }

    private double craftCost(Recipes.CraftOption craft, int missing, int depth, Set<String> visiting) {
        int times = (missing + craft.result().getCount() - 1) / craft.result().getCount();
        if (isPacking(craft) && !Crafting.missing(bot, craft, times).isEmpty()) {
            // 9 nuggets into an ingot, 9 ingots into a block...: only with what's already here. Getting
            // the nuggets means unpacking ingots, which means packing nuggets, which...
            return INF;
        }
        double cost = CRAFT_COST * times;
        if (craft.ingredients().stream().anyMatch(ingredient -> ingredient.test(new net.minecraft.world.item.ItemStack(Items.DIAMOND)))) {
            cost += DIAMOND_COST;
        }
        if (craft.needsTable() && !hasTableAccess()) {
            cost += cost(Target.of(Items.CRAFTING_TABLE, 1), depth + 1, visiting);
        }
        for (Map.Entry<Ingredient, Integer> entry : Crafting.missing(bot, craft, times).entrySet()) {
            int total = Inv.count(bot, entry.getKey()::test) + entry.getValue();
            cost += cost(Target.ingredient(entry.getKey(), total), depth + 1, visiting);
            if (cost == INF) {
                return INF;
            }
        }
        return cost;
    }

    /**
     * A recipe that just packs many of one thing into one, and can be unpacked
     * again (nuggets and ingots, ingots and blocks, wheat and hay): all cells the
     * same ingredient, and a one-cell recipe turning the result back into it.
     * (Four planks make a crafting table, but a table doesn't give planks back.)
     */
    private boolean isPacking(Recipes.CraftOption craft) {
        List<Ingredient> ingredients = craft.ingredients();
        if (ingredients.size() < 4) {
            return false;
        }
        Ingredient first = ingredients.get(0);
        for (Ingredient other : ingredients) {
            if (!other.equals(first)) {
                return false;
            }
        }
        for (Recipes.CraftOption back : Recipes.craftingFor(server, first::test)) {
            List<Ingredient> backIngredients = back.ingredients();
            if (backIngredients.size() == 1 && backIngredients.get(0).test(craft.result())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTableAccess() {
        return bot.memory().craftingTable() != null || bot.getInventory().countItem(Items.CRAFTING_TABLE) > 0;
    }

    /** Is there a block of this source (that the target accepts) in the loaded area nearby? */
    private boolean isNearby(Sources.Mine mine, Target target) {
        return nearby.computeIfAbsent(mine.name() + "|" + target.name(), key -> {
            boolean logs = mine == Sources.LOGS;
            BlockPos center = bot.blockPosition();
            if (logs && com.minebot.bot.ai.EscapeTask.underground(bot)) {
                // (down a mine: trees are up on the surface above it)
                center = bot.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center);
            }
            int minY = logs ? center.getY() - 16 : bot.level().getMinY();
            int maxY = center.getY() + (logs ? 24 : 16);
            return !BlockSearch.find(bot.level(), center, NEARBY_RADIUS, minY, maxY, mine.blocksFor(target),
                (pos, state) -> !ProtectedAreas.isProtected(bot.level(), pos)
                    && (!logs || BlockRules.isNaturalTreeLog(bot.level(), pos, state)), 1).isEmpty();
        });
    }
}
