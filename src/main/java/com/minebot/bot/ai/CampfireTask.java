package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockPlacer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.action.PlaceNearby;
import com.minebot.bot.action.PlaceSpots;
import com.minebot.bot.craft.Recipes;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import com.minebot.bot.world.ProtectedAreas;
import com.minebot.bot.world.Stations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Cooks food on a campfire: puts raw items on it, waits by the fire and picks
 * the cooked food up when it pops off.
 *
 * Which campfire: the bot's own if it's near; else anyone's free campfire
 * nearby (relit with flint and steel if it went out); else it puts one down -
 * for good next to its bed when at home, or just for this meal when out and
 * about. A temporary one is taken down once the bot is done cooking (by
 * {@link PickUpTask}; that gives charcoal, enough to craft the next one).
 */
public class CampfireTask extends Task {
    private static final int OWN_CAMPFIRE_RANGE = 48;
    private static final int SHARED_CAMPFIRE_RANGE = 24;
    private @Nullable BlockPos homeSpot;

    /** Close enough to home to set up a permanent campfire there. */
    private static final int HOME_RANGE = 32;
    private static final int MAX_IDLE_TICKS = 20 * 50;
    /** Ticks cooked food has been lying around the fire without being picked up. */
    private int lyingTicks;

    private final Recipes.CookOption option;
    private final Target target;

    private @Nullable BlockPos campfire;
    /** Put down just for this meal; taken down when done. */
    private boolean temporary;
    private @Nullable Task child;
    private @Nullable PlaceNearby placer;
    private int idleTicks;
    private int failures;
    private int lastHave;

    public CampfireTask(BotPlayer bot, Recipes.CookOption option, Target target) {
        super(bot);
        this.option = option;
        this.target = target;
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
                return Status.FAILURE; // couldn't get the food or a campfire: let the planner try another way
            }
        }
        ServerLevel level = bot.level();
        if (campfire != null && !(level.getBlockEntity(campfire) instanceof CampfireBlockEntity)) {
            campfire = null;
            temporary = false;
        }
        if (campfire == null && !locateCampfire()) {
            return failures > 3 ? Status.FAILURE : Status.RUNNING;
        }
        CampfireBlockEntity entity = (CampfireBlockEntity) level.getBlockEntity(campfire);
        int onFire = (int) entity.getItems().stream().filter(stack -> !stack.isEmpty()).count();
        // Cooked food that just popped off counts too: it gets picked up in a moment
        int lying = cookedLyingAround();
        if (lying > 0 && ++lyingTicks > 20) {
            // Popped off to the far side (or into a dip) and not picked up: go and get it
            lyingTicks = 0;
            idleTicks = 0;
            child = new CollectItemsTask(bot, Vec3.atCenterOf(campfire), 3.0, 100);
            return Status.RUNNING;
        } else if (lying == 0) {
            lyingTicks = 0;
        }
        int stillNeeded = target.missing(bot) - onFire - lying;
        if (target.satisfied(bot) && onFire == 0) {
            return finish();
        }
        if (stillNeeded > 0 && Inv.count(bot, option.input()::test) < Math.min(stillNeeded, 4)) {
            // A campfire holds four: fetch raw food in batches
            child = new ObtainTask(bot, Target.ingredient(option.input(), Math.min(stillNeeded, 4)), 1);
            return Status.RUNNING;
        }

        // Stand right next to the fire: cooked food pops out on top of it and gets picked up from there
        if (!besideCampfire()) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.near(campfire, 1.5));
            }
            Navigator.Status status = bot.navigator().tick();
            if (status == Navigator.Status.FAILED || ++idleTicks > MAX_IDLE_TICKS) {
                // Can't get next to this one (e.g. it ended up on a ledge): use another
                bot.debug("can't get next to the campfire at {}", campfire.toShortString());
                campfire = null;
                idleTicks = 0;
                failures++;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        if (bot.getBoundingBox().intersects(new AABB(campfire))) {
            // Touching the fire's block: it burns (a bot died that way). Back off first
            BlockPos stand = bot.blockPosition().equals(campfire) ? campfire.relative(bot.getDirection().getOpposite())
                : bot.blockPosition();
            bot.controller().moveTowards(Vec3.atBottomCenterOf(stand), false, false);
            return Status.RUNNING;
        }
        bot.controller().lookAt(Vec3.atCenterOf(campfire));

        boolean progressed = false;
        if (!level.getBlockState(campfire).getValue(CampfireBlock.LIT)) {
            if (!Inv.select(bot, stack -> stack.is(Items.FLINT_AND_STEEL))) {
                campfire = null; // went out and we can't relight it
                failures++;
                return Status.RUNNING;
            }
            click(level, bot.getMainHandItem());
            progressed = true;
        } else if (stillNeeded > 0 && onFire < 4 && Inv.select(bot, option.input()::test)) {
            click(level, bot.getMainHandItem());
            progressed = true;
        }
        int have = target.have(bot);
        if (have != lastHave) {
            lastHave = have;
            progressed = true;
        }
        if (progressed) {
            idleTicks = 0;
        } else if (++idleTicks > MAX_IDLE_TICKS) {
            return Status.FAILURE;
        }
        return Status.RUNNING;
    }

    /** Next to the fire: within a step sideways, and close enough to reach it and pick up what pops off. */
    private boolean besideCampfire() {
        Vec3 center = Vec3.atBottomCenterOf(campfire);
        double dx = bot.getX() - center.x;
        double dz = bot.getZ() - center.z;
        return dx * dx + dz * dz <= 1.6 * 1.6 && Math.abs(bot.getY() - center.y) <= 1.0
            && bot.canUse(campfire);
    }

    private int cookedLyingAround() {
        return bot.level().getEntitiesOfClass(ItemEntity.class, new AABB(campfire).inflate(2.0),
                item -> ItemStack.isSameItem(item.getItem(), option.result()))
            .stream().mapToInt(item -> item.getItem().getCount()).sum();
    }

    /** Right-clicks the campfire with the held item (raw food goes on it, flint and steel lights it). */
    private void click(ServerLevel level, ItemStack held) {
        Vec3 hit = Vec3.atCenterOf(campfire);
        bot.gameMode.useItemOn(bot, level, held, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, campfire, false));
        bot.swing(InteractionHand.MAIN_HAND);
    }

    private boolean locateCampfire() {
        ServerLevel level = bot.level();
        boolean canLight = Inv.count(bot, stack -> stack.is(Items.FLINT_AND_STEEL)) > 0;

        BlockPos own = bot.memory().campfire();
        if (own != null && level.getBlockEntity(own) instanceof CampfireBlockEntity
            && (canLight || level.getBlockState(own).getValue(CampfireBlock.LIT))
            && own.closerThan(bot.blockPosition(), OWN_CAMPFIRE_RANGE)) {
            campfire = own;
            return true;
        }
        BlockPos shared = Stations.nearest(bot, state -> state.getBlock() instanceof CampfireBlock, SHARED_CAMPFIRE_RANGE,
            (pos, state) -> (canLight || state.getValue(CampfireBlock.LIT))
                && level.getBlockEntity(pos) instanceof CampfireBlockEntity entity
                && entity.getItems().stream().anyMatch(ItemStack::isEmpty));
        if (shared != null) {
            campfire = shared;
            return true;
        }

        if (bot.getInventory().countItem(Items.CAMPFIRE) == 0) {
            child = new ObtainTask(bot, Target.of(Items.CAMPFIRE, 1), 1);
            return false;
        }
        GlobalPos home = bot.memory().home();
        boolean nearHome = home != null && home.dimension() == level.dimension()
            && home.pos().closerThan(bot.blockPosition(), HOME_RANGE);
        if (nearHome) {
            // The permanent one goes outside, by the house (smoke and fire don't belong indoors)
            if (homeSpot == null || !PlaceSpots.isFreeGroundSpot(level, homeSpot)) {
                homeSpot = outdoorSpot(level, home.pos());
            }
            if (homeSpot != null) {
                return placeAtHome(level, homeSpot);
            }
            nearHome = false; // nowhere outside: a temporary one, put away after
        }
        if (placer == null) {
            placer = new PlaceNearby(bot, stack -> stack.is(Items.CAMPFIRE));
        }
        BlockPos spot = placer.tick();
        if (spot != null) {
            placer = null;
            campfire = spot;
            temporary = !nearHome;
            if (nearHome) {
                bot.memory().setCampfire(spot);
            }
            bot.debug("put down a {} campfire at {}", temporary ? "temporary" : "home", spot.toShortString());
            return true;
        }
        if (placer.failed()) {
            bot.debug("no room to put a campfire down");
            placer = null;
            failures = Integer.MAX_VALUE / 2;
        }
        return false;
    }

    private Status finish() {
        if (temporary && campfire != null) {
            // Left standing for the next batch; taken down once the bot is done cooking
            // (the "pick up" need, right after food in priority)
            bot.leftBehind().add(campfire);
            temporary = false;
        }
        return Status.SUCCESS;
    }

    /** Puts the home campfire down at its spot outside; true once it's there. */
    private boolean placeAtHome(ServerLevel level, BlockPos spot) {
        if (!bot.canUse(spot)) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.reachVisible(level, spot));
            }
            if (bot.navigator().tick().ended() && !bot.canUse(spot)) {
                failures++;
                homeSpot = null;
            }
            return false;
        }
        bot.navigator().stop();
        if (BlockPlacer.place(bot, spot, stack -> stack.is(Items.CAMPFIRE), Direction.DOWN, null)) {
            campfire = spot;
            temporary = false;
            bot.memory().setCampfire(spot);
            bot.debug("put down a home campfire outside at {}", spot.toShortString());
            return true;
        }
        failures++;
        homeSpot = null;
        return false;
    }

    /** Open ground under the sky 3-8 blocks from home: not in the house, not in the doorway, not in a field. */
    static @Nullable BlockPos outdoorSpot(ServerLevel level, BlockPos home) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < 3 || distance > 8 || distance >= bestDistance) {
                    continue;
                }
                int x = home.getX() + dx;
                int z = home.getZ() + dz;
                BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (Math.abs(pos.getY() - home.getY()) > 3 || !level.canSeeSky(pos) || !PlaceSpots.isFreeGroundSpot(level, pos)
                    || level.getBlockState(pos.below()).is(Blocks.FARMLAND) || ProtectedAreas.isProtected(level, pos)) {
                    continue;
                }
                boolean byDoor = false;
                for (BlockPos near : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 2, 2))) {
                    if (level.getBlockState(near).is(BlockTags.DOORS)) {
                        byDoor = true;
                        break;
                    }
                }
                if (!byDoor) {
                    best = pos;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        if (placer != null) {
            placer.cancel();
        }
        if (child != null) {
            child.stop();
        }
        if (temporary && campfire != null && bot.level().getBlockEntity(campfire) instanceof CampfireBlockEntity) {
            bot.leftBehind().add(campfire); // interrupted: come back for it
        }
    }

    @Override
    public @Nullable Target wanted() {
        return child != null ? child.wanted() : null;
    }

    @Override
    public String describe() {
        String what = "cooking " + target + " on a campfire";
        return child != null ? what + ": " + child.describe() : what;
    }
}
