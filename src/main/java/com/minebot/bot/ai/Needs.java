package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Sources;
import com.minebot.bot.craft.Target;
import net.minecraft.core.BlockPos;
import com.minebot.bot.path.Goal;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What a bot living on its own wants, most important first. The brain works
 * on the first one that applies.
 */
public final class Needs {
    public record Need(String id, Predicate<BotPlayer> wanted, Function<BotPlayer, Task> task) {
    }

    /** Start hunting/cooking when cooked food drops below this. */
    private static final int LOW_FOOD = 3;
    /** Arrows kept in the chest on top of the 64 carried. */
    private static final int ARROW_RESERVE = 64;
    /** Flint and feathers it gets in one go: eight sets (32 arrows). */
    private static final int ARROW_MATERIALS = 8;
    /** Iron (ingots and raw) at home and in the bag that counts as a stock. */
    private static final int IRON_STOCK = 32;
    /** Loaves baked in one go (63 wheat). */
    private static final int MAX_LOAVES = 21;
    /** Raw potatoes kept back for sowing a 5x5 field (24 cells round the water). */
    private static final int SEED_POTATOES = 24;

    /** Chests and nearby blocks are looked at this often for the stock checks below (5 minutes). */
    private static final int STOCK_CHECK = 20 * 60 * 5;

    public static final List<Need> ALL = List.of(
        // Trapped underground (a flooded cave, no way found out): back to the surface first of all
        new Need("escape", EscapeTask::wanted, EscapeTask::new),
        // Food comes before everything else
        // Hungry with nothing to eat: anything edible, raw meat is fine
        new Need("urgent food", bot -> Food.isHungry(bot) && Food.pickFood(bot) < 0,
            bot -> new FoodTask(bot, 3, false)),
        // Somehow outside its zone (teleported...): walk back in first
        new Need("back to zone", Needs::outsideZone, bot -> new GoToTask(bot, Goal.column(
            bot.memory().anchor().pos().getX(), bot.memory().anchor().pos().getZ(), BotMemory.ZONE_RADIUS - 100))),
        new Need("food stock", bot -> !JourneyTask.isTravelling(bot) && !GreatBuildTask.isAway(bot) // (a journey sees to its own food)
                && (Inv.count(bot, Food::isCooked) < LOW_FOOD // (and before one sets off, enough for the road)
                    || JourneyTask.isDue(bot) && Inv.count(bot, Food::isCooked) < JourneyTask.ROAD_FOOD),
            bot -> new FoodTask(bot, Food.STOCK, true)),
        // Help another bot out with food or materials we have plenty of
        new Need("share", bot -> !GreatBuildTask.isAway(bot) && Sharing.findOffer(bot) != null, GiveTask::forBestOffer),
        // Take back a crafting table, furnace or campfire put down somewhere for a moment
        new Need("pick up", PickUpTask::wanted, PickUpTask::new),
        // A tree it started felling and got interrupted on: finish it
        new Need("finish tree", Needs::treeUnfinished, bot -> new GatherTask(bot, Sources.LOGS,
            new Target("the rest of the tree", stack -> stack.is(ItemTags.LOGS),
                Inv.count(bot, stack -> stack.is(ItemTags.LOGS)) + bot.trunk().size()))),
        // Take down the pillar it climbed to reach an enemy or a treetop
        new Need("dismantle", DismantleTask::wanted, DismantleTask::new),
        // Something useful lying on the ground nearby
        new Need("loot", LootTask::wanted, LootTask::new),
        // Ready gear in a chest at home, better than its own (or missing: just died and started over):
        // armour, a bow or crossbow, pickaxe, axe, shovel, sword, shield. Taken out before crafting anything new
        new Need("stored gear", bot -> !GreatBuildTask.isAway(bot) && bot.every("stored gear", 20 * 60 * 5, () -> BrowseChestsTask.storedUpgrade(bot) != null),
            bot -> new BrowseChestsTask(bot, BrowseChestsTask.storedUpgrade(bot))), // (none any more: fails right away)
        obtain("pickaxe", bot -> !Tools.has(bot, ItemTags.PICKAXES, Tools.Tier.ANY),
            bot -> Tools.target(ItemTags.PICKAXES, Tools.Tier.ANY)),
        obtain("stone pickaxe", bot -> !Tools.has(bot, ItemTags.PICKAXES, Tools.Tier.STONE),
            bot -> Tools.target(ItemTags.PICKAXES, Tools.Tier.STONE)),
        // An axe early on: trees go down much faster
        obtain("axe", bot -> !Tools.has(bot, ItemTags.AXES, Tools.Tier.ANY),
            bot -> Tools.target(ItemTags.AXES, Tools.Tier.ANY)),
        obtain("weapon", bot -> !Tools.hasWeapon(bot), bot -> Target.of(Items.STONE_SWORD, 1)),
        // Sword, pickaxe or axe nearly worn out (a tenth left): a new one of the same tier to follow it
        obtain("new sword", bot -> Tools.needsReplacing(bot, ItemTags.SWORDS), bot -> Tools.replacement(bot, ItemTags.SWORDS)),
        obtain("new pickaxe", bot -> Tools.needsReplacing(bot, ItemTags.PICKAXES), bot -> Tools.replacement(bot, ItemTags.PICKAXES)),
        obtain("new axe", bot -> Tools.needsReplacing(bot, ItemTags.AXES), bot -> Tools.replacement(bot, ItemTags.AXES)),
        // A boat to carry, for crossing seas: made from wood, or a free one from within 128 blocks
        new Need("boat", bot -> !BoatTask.hasBoat(bot) && !bot.isPassenger() && !bot.isInWater() // (not out of its own boat mid-crossing)
                && bot.every("boat", STOCK_CHECK, () -> BoatTask.canMake(bot) || BoatTask.freeBoat(bot) != null),
            BoatTask::new),
        // Every few days: off on a journey for food or ore (no sleeping meanwhile)
        // Every 15 days, two days at the Great Build with everyone else (see GreatBuild)
        new Need("great build", GreatBuildTask::wanted, GreatBuildTask::new),
        new Need("journey", JourneyTask::wanted, JourneyTask::new),
        new Need("sleep", SleepTask::wanted, SleepTask::new),
        new Need("home", bot -> !Home.has(bot) && !HouseTask.movingIn(bot), HomeTask::new), // (not while carrying its bed to its house)
        // A bed out in the open gets walls and a roof around it
        new Need("shelter", bot -> BuildHomeTask.needsShelter(bot) && !HouseTask.movingIn(bot), BuildHomeTask::shelter),
        new Need("furnish", Home::needsFurniture, FurnishTask::new),
        new Need("clear trees", ClearTreesTask::wanted, ClearTreesTask::new),
        new Need("store", ChestTask::bagFull, StoreTask::new),
        // At home: old tools and armour it took off go in the chest (not carried around)
        new Need("tidy up", bot -> bot.level().getGameTime() >= bot.nextTidy() && Home.isNear(bot, 32)
            && !bot.memory().chests().isEmpty() && Stash.hasSpareGear(bot),
            bot -> {
                bot.setNextTidy(bot.level().getGameTime() + 20 * 60 * 5); // (not again right away if the chests are full)
                return new StoreTask(bot);
            }),
        // On its travels: a look into chests it passes, for better armour
        new Need("browse chests", BrowseChestsTask::wanted, BrowseChestsTask::new),
        // Rubbish piled up in the chests at home goes into the lava it knows of
        new Need("clear junk", JunkRunTask::wanted, JunkRunTask::new),
        // Saplings it didn't burn go in the ground: new trees for later
        // With iron in stock at home (no need to go for more): flint and feathers for arrows, near home.
        // Gravel dug for flint is put back and dug again, so the deposit stays
        new Need("arrow materials", bot -> Ranged.has(bot) && Home.isNear(bot, 48)
                && bot.every("arrow materials", STOCK_CHECK, () -> arrowMaterialsWanted(bot)),
            bot -> {
                bot.every("arrow materials", 0, () -> false); // (look again next time round)
                return Inv.count(bot, stack -> stack.is(Items.FLINT)) < ARROW_MATERIALS
                    ? new GatherTask(bot, Sources.GRAVEL, Target.of(Items.FLINT, ARROW_MATERIALS))
                    : new ObtainTask(bot, Target.of(Items.FEATHER, ARROW_MATERIALS), 0);
            }),
        new Need("plant", PlantTask::wanted, PlantTask::new),
        obtain("stone axe", bot -> !Tools.has(bot, ItemTags.AXES, Tools.Tier.STONE),
            bot -> Tools.target(ItemTags.AXES, Tools.Tier.STONE)),
        obtain("stone shovel", bot -> !Tools.has(bot, ItemTags.SHOVELS, Tools.Tier.STONE),
            bot -> Tools.target(ItemTags.SHOVELS, Tools.Tier.STONE)),
        obtain("iron pickaxe", bot -> !Tools.has(bot, ItemTags.PICKAXES, Tools.Tier.IRON),
            bot -> Tools.target(ItemTags.PICKAXES, Tools.Tier.IRON)),
        obtain("iron shovel", bot -> !Tools.has(bot, ItemTags.SHOVELS, Tools.Tier.IRON),
            bot -> Tools.target(ItemTags.SHOVELS, Tools.Tier.IRON)),
        obtain("iron axe", bot -> !Tools.has(bot, ItemTags.AXES, Tools.Tier.IRON),
            bot -> Tools.target(ItemTags.AXES, Tools.Tier.IRON)),
        // A shield for the off hand (planks and an iron ingot)
        obtain("shield", bot -> !bot.getOffhandItem().is(Items.SHIELD) && Inv.count(bot, stack -> stack.is(Items.SHIELD)) == 0,
            bot -> Target.of(Items.SHIELD, 1)),
        // Cobwebs close by: cut them with the sword for string (bows)
        new Need("string", Needs::cobwebsWanted, bot -> new GatherTask(bot, Sources.COBWEB,
            Target.of(Items.STRING, Inv.count(bot, stack -> stack.is(Items.STRING)) + 4))),
        // Enough string collected (bag or chests) and no bow or crossbow anywhere: a bow
        obtain("bow", bot -> !Ranged.has(bot) // (one in a chest is taken out instead: "stored gear")
                && bot.every("bow", STOCK_CHECK, () -> have(bot, Items.STRING) >= 3 && !Ranged.inChests(bot)),
            bot -> Target.of(Items.BOW, 1)),
        // A bow, and flint and feathers for arrows: arrows
        // (64 carried, and a stack more kept in the chest; short in the bag, it takes from that first)
        new Need("arrows", bot -> Ranged.has(bot) && bot.every("arrows", STOCK_CHECK, () -> {
                int bag = Inv.count(bot, stack -> stack.is(ItemTags.ARROWS));
                int chest = ChestTask.stored(bot, Target.tag(ItemTags.ARROWS, 1));
                boolean materials = have(bot, Items.FLINT) >= 1 && have(bot, Items.FEATHER) >= 1;
                return bag < 32 && (chest > 0 || materials) || chest < ARROW_RESERVE && materials;
            }),
            bot -> {
                int bag = Inv.count(bot, stack -> stack.is(ItemTags.ARROWS));
                int chest = ChestTask.stored(bot, Target.tag(ItemTags.ARROWS, 1));
                bot.every("arrows", 0, () -> false); // (look again next time round)
                if (bag < 32 && chest > 0) {
                    return new ObtainTask(bot, Target.tag(ItemTags.ARROWS, Math.min(64, bag + chest)), 0); // (out of the chest)
                }
                int sets = Math.min(have(bot, Items.FLINT), have(bot, Items.FEATHER));
                int wanted = 64 + Math.max(0, ARROW_RESERVE - chest); // (what's over 64 goes in the chest at the next tidy-up)
                return new ObtainTask(bot, Target.of(Items.ARROW, Math.min(wanted, bag + 4 * Math.max(1, sets))), 0).notFromChests();
            }),
        // Gear worn to half, levels from killing mobs and its repair material: mended at an anvil in the house
        new Need("repair", RepairTask::wanted, RepairTask::new),
        // Diamonds or iron in stock: better armour (the old one goes in the chest)
        new Need("armor", bot -> Armor.craftableUpgrade(bot) != null, bot -> {
            com.minebot.bot.craft.Target piece = Armor.craftableUpgrade(bot);
            bot.setArmorCheck(bot.level().getGameTime() + 20 * 60 * 5, null); // next look in 5 minutes
            return new ArmorTask(bot, piece != null ? piece : Target.of(Items.LEATHER_HELMET, 0)); // (all it has the material for)
        }),
        // The last 10 days before that: stocks up for it a little at a time, in the chests
        new Need("house prep", HousePrepTask::wanted, HousePrepTask::new),
        // After 20-30 days in the first hut: a proper house next to it
        new Need("house", HouseTask::wanted, HouseTask::new),
        // Then fields by the house: wheat, and potatoes / carrots if it has some
        new Need("farm", FarmTask::wanted, FarmTask::new),
        // Wheat from the fields (bag and chests): bread, three wheat a loaf
        // Potatoes beyond what the field takes to sow: baked (in the smoker if the house has one), eaten like any meal
        new Need("bake potatoes", bot -> Home.isNear(bot, 32)
                && bot.every("bake potatoes", STOCK_CHECK, () -> have(bot, Items.POTATO) > SEED_POTATOES
                    && have(bot, Items.BAKED_POTATO) < JunkRunTask.FOOD_KEEP), // (a stack baked is plenty)
            bot -> {
                bot.every("bake potatoes", 0, () -> false); // (look again next time round)
                int batch = Math.max(1, Math.min(Math.min(have(bot, Items.POTATO) - SEED_POTATOES, 32),
                    JunkRunTask.FOOD_KEEP - have(bot, Items.BAKED_POTATO)));
                return new CookAwayTask(bot, new ObtainTask(bot,
                    Target.of(Items.BAKED_POTATO, Inv.count(bot, stack -> stack.is(Items.BAKED_POTATO)) + batch), 0).notFromChests());
            }),
        // Gold at home and carrots to spare: golden carrots (a carrot and 8 nuggets each), the best food there is
        new Need("golden carrots", bot -> Home.isNear(bot, 32)
                && bot.every("golden carrots", STOCK_CHECK, () -> goldenCarrotsToMake(bot) > 0),
            bot -> {
                bot.every("golden carrots", 0, () -> false); // (look again next time round)
                int make = Math.max(1, goldenCarrotsToMake(bot));
                return new ObtainTask(bot, Target.of(Items.GOLDEN_CARROT, Inv.count(bot, stack -> stack.is(Items.GOLDEN_CARROT)) + make), 0)
                    .notFromChests();
            }),
        new Need("bake bread", bot -> Home.isNear(bot, 32)
                && bot.every("bake bread", STOCK_CHECK, () -> have(bot, Items.WHEAT) >= 3
                    && have(bot, Items.BREAD) < JunkRunTask.FOOD_KEEP), // (a stack of bread is plenty)
            bot -> {
                bot.every("bake bread", 0, () -> false); // (look again next time round)
                int loaves = Math.min(Math.min(have(bot, Items.WHEAT) / 3, MAX_LOAVES),
                    Math.max(1, JunkRunTask.FOOD_KEEP - have(bot, Items.BREAD)));
                return new ObtainTask(bot, Target.of(Items.BREAD, Inv.count(bot, stack -> stack.is(Items.BREAD)) + loaves), 0)
                    .notFromChests();
            }),
        // Coal or charcoal (logs burnt in a furnace) for torches and smelting
        obtain("coal stock", bot -> Inv.count(bot, stack -> stack.is(ItemTags.COALS)) < 4,
            bot -> Target.tag(ItemTags.COALS, 8)),
        obtain("wood stock", bot -> Inv.count(bot, stack -> stack.is(ItemTags.LOGS)) < 8,
            bot -> Target.tag(ItemTags.LOGS, 16)),
        // Little pits in the ground near where it is: filled in with dirt (low priority)
        // In between its other work: its share of the Great Build's materials, mined underground
        new Need("great build prep", GreatBuildPrepTask::wanted, GreatBuildPrepTask::new),
        new Need("fill holes", FillHoleTask::wanted, FillHoleTask::new),
        // A few ladders to carry: up the walls of its buildings (they stay), not a pillar beside them
        obtain("ladders", bot -> Home.has(bot) && Inv.count(bot, stack -> stack.is(Items.LADDER)) < 3
                && bot.every("ladders", STOCK_CHECK, () -> true),
            bot -> Target.of(Items.LADDER, 3)),
        // Last of all: output left in its own furnace (a smelting job cut short) is taken out
        new Need("furnace output", FurnaceOutputTask::wanted, FurnaceOutputTask::new),
        // Iron at home: 3-6 blocks of iron and 12-36 ingots (ingots into blocks, or blocks back into ingots)
        new Need("iron storage", IronStockTask::wanted, IronStockTask::new)
    );

    private Needs() {
    }

    /** How many of an item the bot has in its bag and in chests it can use. */
    /** Iron in stock, arrows short (bag and chest) and no flint or feathers to make more. */
    private static boolean arrowMaterialsWanted(BotPlayer bot) {
        int arrows = Inv.count(bot, stack -> stack.is(ItemTags.ARROWS)) + ChestTask.stored(bot, Target.tag(ItemTags.ARROWS, 1));
        if (arrows >= 64 + ARROW_RESERVE || have(bot, Items.FLINT) >= 1 && have(bot, Items.FEATHER) >= 1) {
            return false; // (enough, or the "arrows" need makes them)
        }
        return IronStockTask.total(bot) >= IRON_STOCK; // (blocks of iron count nine each)
    }

    /** Golden carrots kept at most, and made in one go. */
    private static final int GOLDEN_CARROTS = 16;
    private static final int GOLDEN_BATCH = 8;

    /** How many golden carrots to make now: as the carrots beyond the seed ones and the gold (bag and chests) allow. */
    private static int goldenCarrotsToMake(BotPlayer bot) {
        // A batch from one kind of gold at a time (the planner doesn't mix loose nuggets with ingots broken up):
        // as many as the loose nuggets make, or else one from an ingot
        int nuggets = have(bot, Items.GOLD_NUGGET);
        int fromGold = nuggets >= 8 ? nuggets / 8 : have(bot, Items.GOLD_INGOT) > 0 ? 1 : 0;
        int carrots = have(bot, Items.CARROT) - SEED_POTATOES; // (as many kept back for sowing as potatoes)
        int room = GOLDEN_CARROTS - have(bot, Items.GOLDEN_CARROT);
        return Math.max(0, Math.min(GOLDEN_BATCH, Math.min(room, Math.min(carrots, fromGold))));
    }

    private static int have(BotPlayer bot, net.minecraft.world.item.Item item) {
        return Inv.count(bot, stack -> stack.is(item)) + ChestTask.stored(bot, Target.of(item, 1));
    }

    /** Cobwebs within 8 blocks, a sword to cut them, and not much string yet. */
    private static boolean cobwebsWanted(BotPlayer bot) {
        if (Inv.count(bot, stack -> stack.is(Items.STRING)) >= 16 || Inv.count(bot, stack -> stack.is(ItemTags.SWORDS)) == 0) {
            return false;
        }
        return bot.every("cobwebs", 20 * 30, () -> !com.minebot.bot.world.BlockSearch.find(bot.level(), bot.blockPosition(), 8,
            bot.getBlockY() - 4, bot.getBlockY() + 4, state -> state.is(net.minecraft.world.level.block.Blocks.COBWEB),
            (pos, state) -> true, 1).isEmpty());
    }

    private static boolean treeUnfinished(BotPlayer bot) {
        BlockPos tree = bot.fellingTree();
        if (tree != null && (!tree.closerThan(bot.blockPosition(), 48) || bot.trunk().isEmpty())) {
            bot.setFellingTree(null); // gone too far, or nothing left of it
            return false;
        }
        return tree != null;
    }

    private static boolean outsideZone(BotPlayer bot) {
        return bot.level().dimension() == bot.memory().anchor().dimension() && !GreatBuildTask.isAway(bot)
            && !bot.memory().inZone(bot.level().dimension(), bot.blockPosition());
    }

    private static Need obtain(String id, Predicate<BotPlayer> wanted, Function<BotPlayer, Target> target) {
        return new Need(id, wanted, bot -> new ObtainTask(bot, target.apply(bot), 0));
    }
}
