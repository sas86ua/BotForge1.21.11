package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockBreaker;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockRules;
import com.minebot.bot.world.BlockSearch;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Fields next to the bot's house (once it has one): wheat 7x7, and 5x5 of
 * potatoes or carrots if it has some. Makes a hoe, levels and tills the
 * ground, sows; later comes back to harvest what's ripe and sow again.
 */
public class FarmTask extends Task {
    private static final int WHEAT_SIZE = 7;
    private static final int ROOT_SIZE = 5;
    private static final int MIN_DISTANCE = 7;
    private static final int MAX_DISTANCE = 22;
    /** Look after the fields this often (by day). */
    private static final int TEND_INTERVAL = 20 * 60 * 5;
    private static final int MAX_SEED_GRASS = 60;

    private @Nullable BotMemory.Farm farm;
    private @Nullable Task child;
    private final BlockBreaker breaker;
    private @Nullable BlockPos working;
    private int workingTicks;
    private final List<BlockPos> skipped = new ArrayList<>();
    private int grassBroken;
    /** Putting the leftovers and the harvest away at the end. */
    private boolean storing;
    /** Watering the fields: bucket made (once a visit), the water to fill it at, fields given up on. */
    private boolean bucketTried;
    private boolean gettingBucket;
    private @Nullable BlockPos waterSource;
    private int waterFailures;
    private int waterTrips;
    private int waterTicks;
    private final java.util.Set<BlockPos> noWater = new java.util.HashSet<>();
    /** What each field gets sown with on this round (its own crop, or wheat if there's none of that). */
    private final java.util.Map<BlockPos, Item> sowAs = new java.util.HashMap<>();
    /** Seeds it already went to the chests for on this round (once each). */
    private final java.util.Set<Item> fromChest = new java.util.HashSet<>();
    private int farmIndex;

    public FarmTask(BotPlayer bot) {
        super(bot);
        this.breaker = new BlockBreaker(bot);
    }

    public static boolean wanted(BotPlayer bot) {
        if (!bot.memory().houseDone() || Home.isNight(bot) || !Home.isNear(bot, 64)) {
            return false;
        }
        if (missingCrop(bot) != null) {
            return true;
        }
        boolean anyToTend = bot.memory().farms().stream().anyMatch(farm -> !stocked(bot, farm.crop()));
        return anyToTend && bot.level().getGameTime() >= bot.nextFarmVisit();
    }

    /**
     * Enough of what this field grows at home (bag and chests), so it can wait: a stack of bread and
     * one of wheat; a stack of potatoes, raw and baked together; a stack of carrots.
     */
    static boolean stocked(BotPlayer bot, String crop) {
        int keep = JunkRunTask.FOOD_KEEP;
        return switch (crop) {
            case "wheat" -> count(bot, Items.BREAD) >= keep && count(bot, Items.WHEAT) >= keep;
            case "potato" -> count(bot, Items.POTATO) + count(bot, Items.BAKED_POTATO) >= keep;
            case "carrot" -> count(bot, Items.CARROT) >= keep;
            default -> false;
        };
    }

    private static int count(BotPlayer bot, Item item) {
        return Inv.count(bot, stack -> stack.is(item)) + ChestTask.stored(bot, Target.of(item, 1));
    }

    /** A crop the bot could grow but has no field for (wheat always; potatoes/carrots if it has some). */
    private static @Nullable String missingCrop(BotPlayer bot) {
        if (!hasFarm(bot, "wheat")) {
            return "wheat";
        }
        if (!hasFarm(bot, "potato") && have(bot, Items.POTATO)) {
            return "potato";
        }
        if (!hasFarm(bot, "carrot") && have(bot, Items.CARROT)) {
            return "carrot";
        }
        return null;
    }

    /** Some in the bag or in the chests. */
    private static boolean have(BotPlayer bot, Item item) {
        return Inv.count(bot, item) > 0 || ChestTask.stored(bot, Target.of(item, 1)) > 0;
    }

    private static boolean hasFarm(BotPlayer bot, String crop) {
        return bot.memory().farms().stream().anyMatch(farm -> farm.crop().equals(crop));
    }

    private static Item seedOf(String crop) {
        return switch (crop) {
            case "potato" -> Items.POTATO;
            case "carrot" -> Items.CARROT;
            default -> Items.WHEAT_SEEDS;
        };
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
                return Status.SUCCESS;
            }
            boolean wasBucket = gettingBucket;
            gettingBucket = false;
            if (status == Status.FAILURE && !wasBucket) {
                return Status.FAILURE; // (no bucket is no reason to stop: it farms without water)
            }
        }
        if (farm == null) {
            String crop = missingCrop(bot);
            if (crop != null) {
                farm = newFarm(crop);
                if (farm == null) {
                    bot.debug("farm: no flat spot for {} near the house", crop);
                    return Status.FAILURE;
                }
                bot.memory().addFarm(farm);
                bot.debug("farm: laying out a {}x{} field of {} at {}", farm.size(), farm.size(), crop, farm.origin().toShortString());
            } else if (farmIndex < bot.memory().farms().size()) {
                farm = bot.memory().farms().get(farmIndex++);
                if (stocked(bot, farm.crop())) {
                    farm = null; // (a stack of what it grows at home already: the field can wait)
                    return Status.RUNNING;
                }
            } else {
                bot.setNextFarmVisit(bot.level().getGameTime() + TEND_INTERVAL);
                return Status.SUCCESS;
            }
        }
        if (!Tools.has(bot, ItemTags.HOES, Tools.Tier.ANY)) {
            child = new ObtainTask(bot, Tools.target(ItemTags.HOES, Tools.Tier.ANY), 0);
            return Status.RUNNING;
        }
        Status status = work(farm);
        if (status != Status.RUNNING) {
            farm = null; // next field
            return missingCrop(bot) == null && farmIndex >= bot.memory().farms().size() ? finish() : Status.RUNNING;
        }
        return Status.RUNNING;
    }

    private Status finish() {
        bot.setNextFarmVisit(bot.level().getGameTime() + TEND_INTERVAL);
        if (!storing && !bot.memory().chests().isEmpty()) {
            // Seeds, potatoes and carrots left over and the harvest: into the chest (not carried about)
            storing = true;
            child = ChestTask.store(bot);
            return Status.RUNNING;
        }
        return Status.SUCCESS;
    }

    // ---- one field ----------------------------------------------------------------------------

    /** The next job on this field: level, till, harvest, sow. SUCCESS when there's nothing left to do. */
    private Status work(BotMemory.Farm field) {
        ServerLevel level = bot.level();
        Item seed = sowAs.computeIfAbsent(field.origin(), origin -> {
            Item own = seedOf(field.crop());
            // No potatoes or carrots for their field (none in the bag or the chests): wheat there meanwhile
            boolean have = Inv.count(bot, own) > 0 || ChestTask.stored(bot, Target.of(own, 1)) > 0;
            return have || own == Items.WHEAT_SEEDS ? own : Items.WHEAT_SEEDS;
        });
        Status watering = water(field);
        if (watering != null) {
            return watering;
        }
        BlockPos middle = middleOf(field);
        for (int dx = 0; dx < field.size(); dx++) {
            for (int dz = 0; dz < field.size(); dz++) {
                BlockPos soil = field.origin().offset(dx, 0, dz);
                BlockPos above = soil.above();
                if (soil.equals(middle)) {
                    continue; // (the water hole)
                }
                if (skipped.contains(soil) || !level.isLoaded(soil)) {
                    continue;
                }
                BlockState ground = level.getBlockState(soil);
                BlockState plant = level.getBlockState(above);
                // Ripe: harvest (it's replanted below on the next pass)
                if (plant.getBlock() instanceof CropBlock crop && crop.isMaxAge(plant)) {
                    return breakAt(above, true);
                }
                // Level: dig away a bump, fill a dip
                if (!plant.isAir() && !(plant.getBlock() instanceof CropBlock)) {
                    if (plant.canBeReplaced() || BlockRules.canBreak(level, above, plant)) {
                        return breakAt(above, false);
                    }
                    skip(soil);
                    continue;
                }
                if (ground.canBeReplaced()) {
                    // A dip: fill it, with dirt if it has some (it can be tilled)
                    return placeAt(soil, Inv.count(bot, Items.DIRT) > 0 ? stack -> stack.is(Items.DIRT) : Inv::isScaffold);
                }
                if (!ground.is(Blocks.FARMLAND)) {
                    if (ground.is(BlockTags.DIRT) || ground.is(Blocks.DIRT_PATH)) {
                        return till(soil);
                    }
                    skip(soil); // stone, cobblestone: stays a gap in the field
                    continue;
                }
                if (plant.isAir()) {
                    if (Inv.count(bot, seed) == 0) {
                        int stored = fromChest.add(seed) ? ChestTask.stored(bot, Target.of(seed, 1)) : 0;
                        if (stored > 0) {
                            // Seeds, potatoes, carrots kept in the chest: enough for the field (no more than there is)
                            child = ChestTask.withdraw(bot, Target.of(seed, Math.min(stored, Math.min(64, field.size() * field.size()))));
                            return Status.RUNNING;
                        }
                        if (seed != Items.WHEAT_SEEDS && Inv.count(bot, Items.WHEAT_SEEDS) > 0) {
                            return sow(soil, Items.WHEAT_SEEDS); // (its own crop ran out: wheat in this one)
                        }
                        if (seed == Items.WHEAT_SEEDS && grassBroken < MAX_SEED_GRASS) {
                            return gatherSeeds();
                        }
                        continue; // nothing to sow here; maybe next time
                    }
                    return sow(soil, seed);
                }
            }
        }
        return Status.SUCCESS;
    }

    private Status till(BlockPos soil) {
        if (!reach(soil)) {
            return Status.RUNNING;
        }
        if (!Inv.select(bot, stack -> stack.is(ItemTags.HOES))) {
            return Status.FAILURE;
        }
        use(soil);
        return Status.RUNNING;
    }

    private Status sow(BlockPos soil, Item seed) {
        if (!reach(soil)) {
            return Status.RUNNING;
        }
        Inv.select(bot, stack -> stack.is(seed));
        use(soil);
        return Status.RUNNING;
    }

    /** The water holes in the middle of every bot's fields: never scooped up for another field. */
    private static java.util.Set<BlockPos> fieldWater(ServerLevel level) {
        java.util.Set<BlockPos> middles = new java.util.HashSet<>();
        for (BotMemory memory : com.minebot.bot.BotRegistry.get(level.getServer()).all()) {
            for (BotMemory.Farm farm : memory.farms()) {
                middles.add(middleOf(farm));
            }
        }
        return middles;
    }

    /** The nearest open water source (air over it) within this far of the field, or null. */
    private static @Nullable BlockPos findWater(ServerLevel level, BlockPos middle, int radius, int height, java.util.Set<BlockPos> unreachable) {
        java.util.Set<BlockPos> holes = fieldWater(level);
        List<BlockPos> found = BlockSearch.find(level, middle, radius, middle.getY() - height, middle.getY() + height,
            // (plain water: kelp, seagrass and waterlogged blocks hold water a bucket can't scoop)
            state -> state.is(Blocks.WATER) && state.getFluidState().isSource(),
            (pos, state) -> level.getBlockState(pos.above()).isAir() && !holes.contains(pos) && unreachable.stream().noneMatch(bad -> bad.closerThan(pos, 4)), 1);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Water each bot couldn't get to (under a cliff, in a cave...): that and what's around it isn't tried again. */
    private static final java.util.Map<java.util.UUID, java.util.Set<BlockPos>> BAD_WATER = new java.util.concurrent.ConcurrentHashMap<>();

    private java.util.Set<BlockPos> badWater() {
        return BAD_WATER.computeIfAbsent(bot.getUUID(), id -> java.util.concurrent.ConcurrentHashMap.newKeySet());
    }

    /** The middle of a field: a water hole there keeps the whole field wet (4 blocks every way). */
    private static BlockPos middleOf(BotMemory.Farm field) {
        return field.origin().offset(field.size() / 2, 0, field.size() / 2);
    }

    /**
     * Water in the middle of the field: a bucket made (3 iron), filled at the nearest water,
     * a hole dug in the middle and the water poured in. Null once there's water, or if it can't
     * be done this time round (no iron, no water about): it farms without, and tries next visit.
     */
    private @Nullable Status water(BotMemory.Farm field) {
        ServerLevel level = bot.level();
        BlockPos middle = middleOf(field);
        if (level.getFluidState(middle).is(net.minecraft.tags.FluidTags.WATER) || noWater.contains(middle) || !level.isLoaded(middle)) {
            return null;
        }
        if (Inv.count(bot, stack -> stack.is(Items.WATER_BUCKET)) == 0) {
            if (Inv.count(bot, stack -> stack.is(Items.BUCKET)) == 0) {
                if (bucketTried) {
                    bot.debug("farm: no bucket for water");
                    noWater.add(middle);
                    return null;
                }
                bucketTried = true;
                gettingBucket = true;
                child = new ObtainTask(bot, Target.of(Items.BUCKET, 1), 0);
                return Status.RUNNING;
            }
            if (waterSource == null) {
                // The water it filled up at before, if it's still there
                BlockPos known = bot.farmWater();
                if (known != null && level.isLoaded(known) && level.getBlockState(known).is(Blocks.WATER) && level.getFluidState(known).isSource()
                    && !fieldWater(level).contains(known)) {
                    waterSource = known;
                }
            }
            if (waterSource == null || !level.getBlockState(waterSource).is(Blocks.WATER) || !level.getFluidState(waterSource).isSource()) {
                waterSource = findWater(level, middle, 48, 8, badWater());
                if (waterSource == null) {
                    waterSource = findWater(level, middle, 128, 24, badWater()); // (none close by: further off)
                }
                if (waterSource == null) {
                    bot.debug("farm: no water within 128 blocks of the field to fill a bucket at");
                    noWater.add(middle);
                    return null;
                }
                bot.setFarmWater(waterSource);
            }
            if (!bot.canUse(waterSource)) {
                // A walk to the water (it may be a fair way off): two minutes at most
                if (!bot.navigator().isActive()) {
                    bot.navigator().navigate(Goal.reachVisible(level, waterSource));
                }
                if (bot.navigator().tick().ended() && !bot.canUse(waterSource) && ++waterTrips > 3 || ++waterTicks > 20 * 120) {
                    bot.debug("farm: can't get to the water at {}", waterSource.toShortString());
                    badWater().add(waterSource); // (next time: water somewhere else)
                    bot.setFarmWater(null);
                    noWater.add(middle);
                    return null;
                }
                return Status.RUNNING;
            }
            bot.navigator().stop();
            // Scoop it up, as a player does: look at the water and use the bucket
            bot.controller().lookAt(Vec3.atCenterOf(waterSource));
            net.minecraft.world.level.block.state.BlockState source = level.getBlockState(waterSource);
            int empty = Inv.findSlot(bot, stack -> stack.is(Items.BUCKET));
            if (empty >= 0 && source.getBlock() instanceof net.minecraft.world.level.block.BucketPickup pickup) {
                ItemStack filled = pickup.pickupBlock(bot, level, waterSource, source);
                if (!filled.isEmpty()) {
                    bot.getInventory().getItem(empty).shrink(1);
                    Inv.give(bot, filled);
                    bot.swing(InteractionHand.MAIN_HAND);
                }
            }
            if (Inv.count(bot, stack -> stack.is(Items.WATER_BUCKET)) == 0 && ++waterFailures > 5) {
                bot.debug("farm: couldn't fill the bucket at {}", waterSource.toShortString());
                bot.setFarmWater(null);
                noWater.add(middle);
                return null;
            }
            return Status.RUNNING;
        }
        // A hole in the middle: the crop and the soil there come out
        if (!level.getBlockState(middle.above()).isAir()) {
            return breakAt(middle.above(), false);
        }
        if (!level.getBlockState(middle).isAir()) {
            return breakAt(middle, false);
        }
        if (!bot.canUse(middle.below())) {
            // Back to the field with the water (from a fair way off, maybe): two minutes at most
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.reachVisible(level, middle.below()));
            }
            if (bot.navigator().tick().ended() && !bot.canUse(middle.below()) && ++waterTrips > 6 || ++waterTicks > 20 * 240) {
                bot.debug("farm: can't get back to the middle of the field at {}", middle.toShortString());
                noWater.add(middle);
                return null;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().lookAt(Vec3.atCenterOf(middle.below()).add(0, 0.5, 0));
        int full = Inv.findSlot(bot, stack -> stack.is(Items.WATER_BUCKET));
        if (full >= 0 && bot.getInventory().getItem(full).getItem() instanceof net.minecraft.world.item.BucketItem bucket
            && bucket.emptyContents(bot, level, middle, null)) {
            bot.getInventory().setItem(full, new ItemStack(Items.BUCKET));
            bot.swing(InteractionHand.MAIN_HAND);
        }
        if (level.getFluidState(middle).is(net.minecraft.tags.FluidTags.WATER)) {
            bot.debug("farm: water in the middle of the field at {}", middle.toShortString());
        } else if (++waterFailures > 5) {
            bot.debug("farm: couldn't pour the water in at {}", middle.toShortString());
            noWater.add(middle);
            return null;
        }
        return Status.RUNNING;
    }

    /** Right-clicks the top of a block with what's in hand (a hoe tills, seeds get sown). */
    private void use(BlockPos pos) {
        Vec3 top = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        bot.controller().lookAt(top);
        bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND,
            new BlockHitResult(top, Direction.UP, pos, false));
        bot.swing(InteractionHand.MAIN_HAND);
    }

    /** A few seeds: short grass drops one now and then. */
    private Status gatherSeeds() {
        BlockPos center = bot.blockPosition();
        List<BlockPos> grass = BlockSearch.find(bot.level(), center, 16, center.getY() - 4, center.getY() + 4,
            state -> state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS), (pos, state) -> true, 1);
        if (grass.isEmpty()) {
            grassBroken = MAX_SEED_GRASS; // none around: sow what we have
            return Status.RUNNING;
        }
        grassBroken++;
        return breakAt(grass.get(0), true);
    }

    private Status breakAt(BlockPos pos, boolean collect) {
        if (!reach(pos)) {
            return Status.RUNNING;
        }
        if (!breaker.isBreaking(pos)) {
            breaker.start(pos);
        }
        BlockBreaker.Result result = breaker.tick();
        if (result == BlockBreaker.Result.SUCCESS && collect) {
            child = new CollectItemsTask(bot, Vec3.atCenterOf(pos), 3.0, 40);
        } else if (result == BlockBreaker.Result.FAILED) {
            skip(pos.below());
        }
        return Status.RUNNING;
    }

    private Status placeAt(BlockPos pos, java.util.function.Predicate<ItemStack> item) {
        if (Inv.count(bot, item) == 0) {
            skip(pos); // nothing to fill with: leave the dip
            return Status.RUNNING;
        }
        if (!reach(pos)) {
            return Status.RUNNING;
        }
        if (!BlockPlacer.place(bot, pos, item, null)) {
            skip(pos);
        }
        return Status.RUNNING;
    }

    /** Walks within reach; false until there. Gives up on a spot that takes too long. */
    private boolean reach(BlockPos pos) {
        if (!pos.equals(working)) {
            working = pos;
            workingTicks = 0;
        }
        if (++workingTicks > 20 * 20) {
            skip(pos);
            return false;
        }
        if (bot.canUse(pos)) {
            bot.navigator().stop();
            return true;
        }
        if (!bot.navigator().isActive()) {
            bot.navigator().navigate(Goal.reachVisible(bot.level(), pos));
        }
        bot.navigator().tick();
        return false;
    }

    private void skip(BlockPos soil) {
        skipped.add(soil);
        working = null;
        breaker.cancel();
    }

    // ---- laying out a field ---------------------------------------------------------------------

    private @Nullable BotMemory.Farm newFarm(String crop) {
        int size = crop.equals("wheat") ? WHEAT_SIZE : ROOT_SIZE;
        ServerLevel level = bot.level();
        BlockPos house = bot.memory().home().pos();
        List<BlockPos> built = BlockSearch.find(level, house, MAX_DISTANCE + size + 2, house.getY() - 6, house.getY() + 12,
            com.minebot.bot.world.BlockRules::isBuilt, (pos, state) -> true, 4000);
        BotMemory.Farm best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -MAX_DISTANCE; dx <= MAX_DISTANCE; dx += 2) {
            for (int dz = -MAX_DISTANCE; dz <= MAX_DISTANCE; dz += 2) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < MIN_DISTANCE || distance > MAX_DISTANCE) {
                    continue;
                }
                BlockPos corner = new BlockPos(house.getX() + dx, 0, house.getZ() + dz);
                Double score = fieldScore(level, corner, size, built);
                if (score != null && score + distance < bestScore) {
                    bestScore = score + distance;
                    best = new BotMemory.Farm(corner.atY(fieldY), size, crop);
                }
            }
        }
        return best;
    }

    private int fieldY;

    /** How much levelling a field needs (null: unsuitable); its ground height goes in {@link #fieldY}. */
    private @Nullable Double fieldScore(ServerLevel level, BlockPos corner, int size, List<BlockPos> built) {
        for (int[] c : new int[][] {{-1, -1}, {size, -1}, {-1, size}, {size, size}}) {
            if (level.getChunkSource().getChunkNow((corner.getX() + c[0]) >> 4, (corner.getZ() + c[1]) >> 4) == null) {
                return null;
            }
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, corner.getX() + size / 2, corner.getZ() + size / 2) - 1;
        double cost = 0;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int x = corner.getX() + dx;
                int z = corner.getZ() + dz;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (Math.abs(top - y) > 1) {
                    return null;
                }
                cost += Math.abs(top - y);
                BlockPos soil = new BlockPos(x, y, z);
                BlockState ground = level.getBlockState(soil);
                if (!ground.is(BlockTags.DIRT) && !ground.is(Blocks.FARMLAND) && top == y) {
                    return null; // sand, stone...: no field here
                }
                if (!level.getFluidState(soil).isEmpty() || !level.getFluidState(soil.above()).isEmpty()
                    || ProtectedAreas.isProtected(level, soil) || !bot.memory().inZone(level.dimension(), soil)) {
                    return null;
                }
                if (level.getBlockState(soil.above()).is(BlockTags.LOGS)) {
                    return null; // a tree
                }
            }
        }
        for (BlockPos pos : built) {
            if (pos.getX() >= corner.getX() - 2 && pos.getX() < corner.getX() + size + 2
                && pos.getZ() >= corner.getZ() - 2 && pos.getZ() < corner.getZ() + size + 2) {
                return null;
            }
        }
        for (BotMemory.Farm other : bot.memory().farms()) {
            if (Math.abs(other.origin().getX() - corner.getX()) < other.size() + size
                && Math.abs(other.origin().getZ() - corner.getZ()) < other.size() + size) {
                return null; // (fields keep a gap between them)
            }
        }
        fieldY = y;
        return cost;
    }

    @Override
    public void stop() {
        breaker.cancel();
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
        String what = farm != null ? "farming (" + farm.crop() + " at " + farm.origin().toShortString() + ")" : "farming";
        return child != null ? what + ": " + child.describe() : what;
    }
}
