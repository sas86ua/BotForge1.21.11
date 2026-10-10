package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Recipes;
import com.minebot.bot.craft.Sources;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * A bot's outing: every few days it sets off 200-500 blocks from home to
 * bring back food, iron ore or coal (and a few diamonds, emeralds or gold if
 * it comes across them), then smelts and cooks what it brought and puts it
 * in its chests. On a journey it doesn't go to bed at night.
 */
public class JourneyTask extends Task {
    private static final long DAY = 24000;
    /** Sets off before noon. */
    private static final long MORNING_END = 6000;
    private static final long MAX_INTERVAL = DAY * 6;
    private static final long SHORTEST_INTERVAL = DAY * 112 / 100;
    private static final long LONGEST_INTERVAL = DAY * 336 / 100;
    private static final int MIN_DISTANCE = 200;
    private static final int MAX_DISTANCE = 500;
    /** Food and arrow materials are to be had near home: a shorter walk for those. */
    private static final int NEAR_MIN_DISTANCE = 60;
    private static final int NEAR_MAX_DISTANCE = 150;
    /** A journey that drags on this long ends: back home with whatever it has. */
    private static final long MAX_TICKS = DAY * 3 / 2;
    private static final int VALUABLES_RADIUS = 16;

    enum Goal_ { FOOD, IRON, COAL, ARROWS, DIAMONDS }

    /** More iron than this at home (bag and chests): no more iron journeys, diamonds instead. */
    private static final int ENOUGH_IRON = 64;
    /** Chance a journey is for diamonds (4-8), with an iron pickaxe to mine them. */
    private static final float DIAMOND_CHANCE = 0.15F;

    /** Arrows made per journey for them: 8 flint and 8 feathers make 32. */
    private static final int ARROW_SETS = 8;

    enum Stage { PREPARE, OUT, GATHER, COOK, VALUABLES, ARROW_SUPPLY, BACK, PROCESS, STORE, DONE }

    /** Logs taken along (with a crafting table): a new wooden or stone tool if one breaks down a mine. */
    private static final int SPARE_LOGS = 4;
    /** Fewer cooked meals than this out there: cook, hunt or head home. */
    private static final int LOW_FOOD = 3;
    private static final int FOOD_CHECK_TICKS = 200;
    private static final int MAX_FOOD_TRIES = 3;

    /** On any journey, flint or feathers are picked up on the side when gravel or chickens are this close. */
    private static final int SUPPLY_RADIUS = 32;

    /** Where a journey stands; kept on the bot so an interrupted journey goes on where it left off. */
    public static final class Journey {
        final Goal_ goal;
        final int amount;
        final BlockPos destination;
        final long started;
        Stage stage = Stage.PREPARE;
        int valuablesTried;
        boolean tableTried;
        boolean logsTried;
        boolean boatTried;
        boolean breadTried;
        int foodTries;
        // (arrows journey: flint and feathers each tried once; arrows crafted once back home)
        boolean flintTried;
        boolean feathersTried;
        boolean arrowsMade;

        Journey(Goal_ goal, int amount, BlockPos destination, long started) {
            this.goal = goal;
            this.amount = amount;
            this.destination = destination;
            this.started = started;
        }

        @Override
        public String toString() {
            return switch (goal) {
                case FOOD -> amount + " food";
                case IRON -> amount + " iron ore";
                case COAL -> amount + " coal";
                case ARROWS -> "flint and feathers for " + amount + " arrows";
                case DIAMONDS -> amount + " diamonds";
            } + " from around " + destination.getX() + " " + destination.getZ();
        }
    }

    private final Journey journey;
    private @Nullable Task child;
    /** The child is a meal break (cooking, hunting), not a step of the journey. */
    private boolean eating;
    private long nextFoodCheck;

    public JourneyTask(BotPlayer bot) {
        super(bot);
        Journey current = bot.journey();
        if (current == null) {
            current = plan(bot);
            bot.setJourney(current);
            bot.debug("setting off on a journey: {}", current);
        } else {
            bot.debug("carrying on with the journey ({}): {}", current.stage.name().toLowerCase(), current);
        }
        this.journey = current;
    }

    /** Time for a journey? (The first one is planned some days after moving in.) */
    public static boolean wanted(BotPlayer bot) {
        Journey going = bot.journey();
        if (going != null) {
            if (going.stage.ordinal() < Stage.BACK.ordinal() && bot.every("journey order check", 20 * 30, () -> GreatBuildTask.orderShort(bot))) {
                // (the order for the Great Build is still short: home to see to it, not on and on after diamonds - Calcite)
                bot.debug("journey: the Great Build order isn't ready; heading home");
                going.stage = Stage.BACK;
            }
            return true;
        }
        BotMemory memory = bot.memory();
        if (GreatBuildTask.isAway(bot) || GreatBuildTask.sessionOn(bot)) {
            return false; // (the Great Build's days: no journeys)
        }
        if (bot.every("journey order check", 20 * 30, () -> GreatBuildTask.orderShort(bot))) {
            return false; // (the order first)
        }
        if (!Home.isNear(bot, 128) || !Tools.has(bot, ItemTags.PICKAXES, Tools.Tier.STONE) || !Tools.hasWeapon(bot)
            || Inv.count(bot, Food::isCooked) < ROAD_FOOD) { // (not without food for the road)
            return false;
        }
        long now = bot.level().getDayTime();
        if (memory.nextJourney() < 0) {
            memory.setNextJourney(now + nextInterval(bot.getRandom()));
            return false;
        }
        checkTimer(bot);
        // Set off in the morning (any time before noon), with most of the day ahead
        return now >= memory.nextJourney() && now % DAY < MORNING_END;
    }

    /**
     * The timer counts the days players see (nights slept through count too). A timer
     * set further ahead than a journey interval can be is from before that: counted in
     * server ticks (kept as the time it had left), or from before someone turned the
     * clock back with /time set (started over).
     */
    private static void checkTimer(BotPlayer bot) {
        long now = bot.level().getDayTime();
        long next = bot.memory().nextJourney();
        if (next - now > LONGEST_INTERVAL && next - now <= MAX_INTERVAL) {
            // Set when the wait was longer: a new one as it is now
            bot.memory().setNextJourney(now + nextInterval(bot.getRandom()));
            return;
        }
        if (next - now <= MAX_INTERVAL) {
            return;
        }
        long ticks = bot.level().getGameTime();
        long left = next - ticks;
        bot.memory().setNextJourney(Math.abs(left) <= MAX_INTERVAL ? now + Math.max(0, left) : now + nextInterval(bot.getRandom()));
    }

    /** 1.12-3.36 days (2-6, shortened by 30%, then by 20%). */
    private static long nextInterval(RandomSource random) {
        return SHORTEST_INTERVAL + random.nextInt((int) (LONGEST_INTERVAL - SHORTEST_INTERVAL));
    }

    private static Journey plan(BotPlayer bot) {
        RandomSource random = bot.getRandom();
        // Materials for arrows only with a bow to shoot them and not many arrows left
        boolean arrows = Ranged.has(bot) && Inv.count(bot, s -> s.is(ItemTags.ARROWS)) < 32;
        List<Goal_> pool = new java.util.ArrayList<>(List.of(Goal_.FOOD, Goal_.COAL));
        int iron = IronStockTask.total(bot); // (ingots, raw iron and blocks of iron, nine each)
        if (iron <= ENOUGH_IRON) {
            pool.add(Goal_.IRON);
        } else if (Tools.has(bot, ItemTags.PICKAXES, Tools.Tier.IRON)) {
            pool.add(Goal_.DIAMONDS); // plenty of iron: something better
        }
        if (arrows) {
            pool.add(Goal_.ARROWS);
        }
        Goal_ goal = pool.get(random.nextInt(pool.size()));
        if (random.nextFloat() < DIAMOND_CHANCE && Tools.has(bot, ItemTags.PICKAXES, Tools.Tier.IRON)) {
            goal = Goal_.DIAMONDS; // (now and then, whatever its iron: a trip down deep for diamonds)
        }
        int amount = switch (goal) {
            case FOOD -> 12 + random.nextInt(13);
            case ARROWS -> ARROW_SETS * 4;
            case DIAMONDS -> 4 + random.nextInt(5);
            default -> 12 + random.nextInt(21);
        };
        BlockPos home = bot.memory().bed() != null ? bot.memory().bed() : bot.blockPosition();
        BlockPos anchor = bot.memory().anchor().pos();
        BlockPos destination = home;
        boolean near = goal == Goal_.FOOD || goal == Goal_.ARROWS;
        int minDistance = near ? NEAR_MIN_DISTANCE : MIN_DISTANCE;
        int maxDistance = near ? NEAR_MAX_DISTANCE : MAX_DISTANCE;
        for (int attempt = 0; attempt < 20; attempt++) {
            float angle = random.nextFloat() * Mth.TWO_PI;
            int distance = minDistance + random.nextInt(maxDistance - minDistance + 1);
            BlockPos candidate = home.offset((int) (Mth.cos(angle) * distance), 0, (int) (Mth.sin(angle) * distance));
            destination = candidate;
            if (candidate.closerThan(anchor, BotMemory.ZONE_RADIUS - 100)) {
                break;
            }
        }
        return new Journey(goal, amount, destination, bot.level().getGameTime());
    }

    /** A journey is due (it waits for food for the road: 6 meals; "food stock" sees to that first). */
    public static boolean isDue(BotPlayer bot) {
        BotMemory memory = bot.memory();
        return bot.journey() == null && memory.nextJourney() >= 0 && bot.level().getDayTime() >= memory.nextJourney()
            && Home.has(bot);
    }

    /** Enough cooked food to set off with. */
    public static final int ROAD_FOOD = 6;

    /** On a journey, the bot doesn't go to bed. */
    public static boolean isTravelling(BotPlayer bot) {
        Journey journey = bot.journey();
        return journey != null && journey.stage.ordinal() < Stage.PROCESS.ordinal();
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
            if (eating) {
                eating = false; // (fed: the step it was on starts again)
            } else {
                next(status);
            }
        }
        if (journey.stage.ordinal() < Stage.BACK.ordinal()
            && bot.level().getGameTime() - journey.started > MAX_TICKS) {
            bot.debug("the journey is taking too long; heading home");
            journey.stage = Stage.BACK;
        }
        if (checkFood()) {
            return Status.RUNNING;
        }
        switch (journey.stage) {
            case PREPARE -> {
                child = prepare();
                if (child == null) {
                    journey.stage = Stage.OUT;
                }
            }
            case OUT -> child = new GoToTask(bot, Goal.column(journey.destination.getX(), journey.destination.getZ(), 24));
            case GATHER -> {
                child = gather();
                if (child == null) {
                    journey.stage = Stage.VALUABLES;
                }
            }
            case COOK -> {
                child = cookOnTheSpot();
                if (child == null) {
                    journey.stage = Stage.ARROW_SUPPLY;
                }
            }
            case VALUABLES -> {
                child = valuables();
                if (child == null) {
                    journey.stage = Stage.ARROW_SUPPLY;
                }
            }
            case ARROW_SUPPLY -> {
                child = arrowSupply();
                if (child == null) {
                    journey.stage = Stage.BACK;
                }
            }
            case BACK -> child = new GoToTask(bot, Goal.near(
                bot.memory().bed() != null ? bot.memory().bed() : bot.memory().anchor().pos(), 6));
            case PROCESS -> {
                child = process();
                if (child == null) {
                    journey.stage = Stage.STORE;
                }
            }
            case STORE -> child = ChestTask.store(bot);
            case DONE -> {
                bot.setJourney(null);
                bot.memory().setNextJourney(bot.level().getDayTime() + nextInterval(bot.getRandom()));
                bot.debug("back from the journey");
                return Status.SUCCESS;
            }
        }
        return Status.RUNNING;
    }

    /** Before setting off: a crafting table and a few logs, for a new tool if the old one breaks far from home. */
    private @Nullable Task prepare() {
        if (!journey.breadTried) {
            journey.breadTried = true; // (8-16 loaves for the road, if there are some at home)
            Task bread = Food.packBread(bot);
            if (bread != null) {
                return bread;
            }
        }
        if (!journey.boatTried && !BoatTask.hasBoat(bot)) {
            journey.boatTried = true; // (a boat along: journeys often cross a sea or a wide river)
            return new BoatTask(bot);
        }
        if (!journey.tableTried && Inv.count(bot, s -> s.is(Items.CRAFTING_TABLE)) == 0) {
            journey.tableTried = true;
            return new ObtainTask(bot, Target.of(Items.CRAFTING_TABLE, 1), 0);
        }
        int logs = Inv.count(bot, s -> s.is(ItemTags.LOGS));
        if (!journey.logsTried && logs < SPARE_LOGS) {
            journey.logsTried = true;
            return new ObtainTask(bot, Target.tag(ItemTags.LOGS, SPARE_LOGS), 0);
        }
        return null;
    }

    /**
     * Every few seconds out there: running short of cooked food, it cooks the raw meat it
     * has on a camp fire, or hunts if there are animals about, or else turns for home.
     * @return true if it's seeing to food now
     */
    private boolean checkFood() {
        Stage stage = journey.stage;
        long now = bot.level().getGameTime();
        if (eating || stage.ordinal() < Stage.OUT.ordinal() || stage.ordinal() >= Stage.BACK.ordinal() || now < nextFoodCheck) {
            return false;
        }
        nextFoodCheck = now + FOOD_CHECK_TICKS;
        if (Inv.count(bot, Food::isCooked) >= LOW_FOOD) {
            return false;
        }
        Task food = journey.foodTries++ < MAX_FOOD_TRIES ? foodOnTheWay() : null;
        if (child != null) {
            child.stop();
            child = null;
        }
        if (food == null) {
            bot.debug("journey: running out of food; heading home");
            journey.stage = Stage.BACK;
            return false;
        }
        child = food;
        eating = true;
        return true;
    }

    private @Nullable Task foodOnTheWay() {
        Task cook = cookOnTheSpot();
        if (cook != null) {
            return cook;
        }
        Target meat = new Target("food", Inv::isFood, 1);
        for (Sources.Kill kill : Sources.killsFor(meat)) {
            if (HuntTask.findPrey(bot, kill, java.util.Set.of()) != null) {
                bot.debug("journey: short of food, hunting nearby");
                return new FoodTask(bot, Inv.count(bot, Inv::isFood) + 4, false, false, 0); // (not back to the chests at home)
            }
        }
        return null;
    }

    /** A step finished: on to the next one. Failures don't stop a journey, they just skip ahead. */
    private void next(Status status) {
        Stage stage = journey.stage;
        if (status == Status.FAILURE) {
            bot.debug("journey: {} didn't work out", stage.name().toLowerCase());
        }
        journey.stage = switch (stage) {
            case PREPARE -> Stage.PREPARE; // the next thing to take along (each tried once)
            case OUT -> Stage.GATHER;
            case GATHER -> journey.goal == Goal_.FOOD ? Stage.COOK
                : journey.goal == Goal_.ARROWS ? Stage.GATHER // the other material next
                : Stage.VALUABLES;
            case COOK -> status == Status.FAILURE ? Stage.ARROW_SUPPLY : Stage.COOK; // the next kind of raw meat
            case VALUABLES -> Stage.VALUABLES; // look again (up to a few kinds)
            case ARROW_SUPPLY -> Stage.ARROW_SUPPLY; // the other material next (each tried once)
            case BACK -> Stage.PROCESS;
            case PROCESS -> status == Status.FAILURE ? Stage.STORE : Stage.PROCESS; // the next kind of raw stuff
            case STORE, DONE -> Stage.DONE;
        };
    }

    private @Nullable Task gather() {
        return switch (journey.goal) {
            case ARROWS -> arrowMaterials();
            case DIAMONDS -> new GatherTask(bot, Sources.DIAMOND, Target.of(Items.DIAMOND, Inv.count(bot, s -> s.is(Items.DIAMOND)) + journey.amount));
            case FOOD -> new FoodTask(bot, Inv.count(bot, Inv::isFood) + journey.amount, false, false, 3); // (hunting there, not chests)
            case IRON -> new GatherTask(bot, Sources.IRON, Target.of(Items.RAW_IRON, Inv.count(bot, s -> s.is(Items.RAW_IRON)) + journey.amount));
            case COAL -> new GatherTask(bot, Sources.COAL, Target.of(Items.COAL, Inv.count(bot, s -> s.is(Items.COAL)) + journey.amount));
        };
    }

    /**
     * Out hunting: the raw meat goes on a camp fire right there (put down for the
     * job and taken back after, by the "pick up" need), one kind at a time.
     */
    private @Nullable Task cookOnTheSpot() {
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (stack.isEmpty() || !Inv.isFood(stack)) {
                continue;
            }
            Optional<Recipes.CookOption> option = Recipes.campfireOf(bot.level().getServer(), stack);
            if (option.isPresent() && Inv.isFood(option.get().result())) {
                ItemStack result = option.get().result();
                int raw = Inv.count(bot, s -> option.get().input().test(s));
                int have = Inv.count(bot, s -> ItemStack.isSameItem(s, result));
                bot.debug("journey: cooking {} {} on a camp fire", raw, stack.getItem());
                return new CampfireTask(bot, option.get(), Target.of(result.getItem(), have + raw));
            }
        }
        return null;
    }

    /** Diamonds, emeralds or gold in sight while out mining: a few of each. */
    private @Nullable Task valuables() {
        List<Sources.Mine> kinds = List.of(Sources.DIAMOND, Sources.EMERALD, Sources.GOLD);
        while (journey.valuablesTried < kinds.size()) {
            Sources.Mine kind = kinds.get(journey.valuablesTried++);
            if (!kind.canHarvest(bot)) {
                continue; // (an iron pickaxe at least)
            }
            ServerLevel level = bot.level();
            BlockPos center = bot.blockPosition();
            List<BlockPos> found = BlockSearch.find(level, center, VALUABLES_RADIUS, center.getY() - VALUABLES_RADIUS,
                center.getY() + VALUABLES_RADIUS, kind.blocks(), (pos, state) -> true, 1);
            if (found.isEmpty()) {
                continue;
            }
            ItemStack drop = new ItemStack(kind.dropOf(kind.sample()));
            int have = Inv.count(bot, s -> ItemStack.isSameItem(s, drop));
            int more = 2 + bot.getRandom().nextInt(4);
            bot.debug("journey: {} nearby, getting a few", kind.name());
            return new GatherTask(bot, kind, Target.of(drop.getItem(), have + more));
        }
        return null;
    }

    /**
     * On the way, whatever the journey is for: with a bow or crossbow and few arrows, flint
     * from gravel and feathers from chickens if there are some close by (each tried once).
     */
    private @Nullable Task arrowSupply() {
        if (journey.goal == Goal_.ARROWS || !Ranged.has(bot) || Inv.count(bot, s -> s.is(ItemTags.ARROWS)) >= 32) {
            return null;
        }
        ServerLevel level = bot.level();
        BlockPos center = bot.blockPosition();
        int flint = Inv.count(bot, s -> s.is(Items.FLINT));
        if (!journey.flintTried && flint < ARROW_SETS) {
            journey.flintTried = true;
            if (!BlockSearch.find(level, center, SUPPLY_RADIUS, center.getY() - 8, center.getY() + 8,
                    state -> state.is(net.minecraft.world.level.block.Blocks.GRAVEL), (pos, state) -> true, 1).isEmpty()) {
                bot.debug("journey: gravel nearby, digging for flint");
                return new GatherTask(bot, Sources.GRAVEL, Target.of(Items.FLINT, Math.min(ARROW_SETS, flint + 4)));
            }
        }
        int feathers = Inv.count(bot, s -> s.is(Items.FEATHER));
        if (!journey.feathersTried && feathers < ARROW_SETS) {
            journey.feathersTried = true;
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.animal.chicken.Chicken.class,
                    bot.getBoundingBox().inflate(SUPPLY_RADIUS, 8, SUPPLY_RADIUS)).isEmpty()) {
                bot.debug("journey: chickens nearby, hunting for feathers");
                return new ObtainTask(bot, Target.of(Items.FEATHER, ARROW_SETS), 0).notFromChests();
            }
        }
        return null;
    }

    /** Flint from gravel (one block in ten gives some), then feathers from chickens; each tried once. */
    private @Nullable Task arrowMaterials() {
        int flint = Inv.count(bot, s -> s.is(Items.FLINT));
        if (!journey.flintTried && flint < ARROW_SETS) {
            journey.flintTried = true;
            bot.debug("journey: digging gravel for flint");
            return new GatherTask(bot, Sources.GRAVEL, Target.of(Items.FLINT, ARROW_SETS));
        }
        int feathers = Inv.count(bot, s -> s.is(Items.FEATHER));
        if (!journey.feathersTried && feathers < ARROW_SETS) {
            journey.feathersTried = true;
            bot.debug("journey: hunting chickens for feathers");
            return new ObtainTask(bot, Target.of(Items.FEATHER, ARROW_SETS), 0);
        }
        return null;
    }

    /** Back home: smelt ore and cook meat it brought, one kind at a time. */
    private @Nullable Task process() {
        if (journey.goal == Goal_.ARROWS && !journey.arrowsMade) {
            // Home: as many arrows as the flint and feathers make (sticks are easy)
            journey.arrowsMade = true;
            int sets = Math.min(Inv.count(bot, s -> s.is(Items.FLINT)), Inv.count(bot, s -> s.is(Items.FEATHER)));
            int arrows = Inv.count(bot, s -> s.is(ItemTags.ARROWS));
            if (sets > 0 && arrows < 64) {
                return new ObtainTask(bot, Target.of(Items.ARROW, Math.min(64, arrows + 4 * sets)), 0);
            }
        }
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            Optional<Recipes.CookOption> option = stack.isEmpty() ? Optional.empty()
                : Recipes.smeltingOf(bot.level().getServer(), stack);
            boolean worthIt = stack.is(Items.RAW_IRON) || stack.is(Items.RAW_GOLD)
                || Inv.isFood(stack) && option.isPresent() && Inv.isFood(option.get().result()); // raw meat
            if (worthIt) {
                {
                    ItemStack result = option.get().result();
                    int count = Inv.count(bot, s -> option.get().input().test(s));
                    int have = Inv.count(bot, s -> ItemStack.isSameItem(s, result));
                    return new SmeltTask(bot, option.get(), Target.of(result.getItem(), have + count));
                }
            }
        }
        return null;
    }

    @Override
    public void stop() {
        if (child != null) {
            child.stop();
            child = null;
        }
    }

    @Override
    public @Nullable Target wanted() {
        return child != null ? child.wanted() : null;
    }

    @Override
    public String describe() {
        String what = "journey (" + journey.stage.name().toLowerCase() + "): " + journey;
        return child != null ? what + ": " + child.describe() : what;
    }
}
