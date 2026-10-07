package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;

/**
 * Gets the bot a boat to carry, for crossing seas and wide rivers: a free one
 * lying around (on a shore, left by someone) within 128 blocks, knocked loose
 * and picked up; only when there is none (or it can't be reached) a new one
 * made from planks.
 */
public class BoatTask extends Task {
    private static final int SEARCH_RADIUS = 128;
    private static final int MAX_TICKS = 20 * 90;

    private @Nullable AbstractBoat target;
    private @Nullable Task child;
    private int ticks;
    private int hitCooldown;
    private boolean fetchFailed;

    public BoatTask(BotPlayer bot) {
        super(bot);
    }

    public static boolean hasBoat(BotPlayer bot) {
        return Inv.count(bot, stack -> stack.is(ItemTags.BOATS)) > 0;
    }

    /** Wood for one: 5 planks, and 4 more for a crafting table if it needs one (a log gives 4). */
    public static boolean canMake(BotPlayer bot) {
        return Inv.count(bot, stack -> stack.is(ItemTags.PLANKS)) + 4 * Inv.count(bot, stack -> stack.is(ItemTags.LOGS)) >= 9;
    }

    /** A plain boat (not one with a chest: its contents would spill) nobody sits in, outside protected places. */
    public static @Nullable AbstractBoat freeBoat(BotPlayer bot) {
        Vec3 center = bot.position();
        return bot.level().getEntitiesOfClass(AbstractBoat.class, bot.getBoundingBox().inflate(SEARCH_RADIUS, 32, SEARCH_RADIUS),
                boat -> boat.isAlive() && boat.getPassengers().isEmpty() && !(boat instanceof AbstractChestBoat)
                    && !ProtectedAreas.isProtected(bot.level(), boat.blockPosition()))
            .stream()
            .min(Comparator.comparingDouble(boat -> boat.distanceToSqr(center)))
            .orElse(null);
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
            return hasBoat(bot) ? Status.SUCCESS : Status.FAILURE;
        }
        if (hasBoat(bot)) {
            return Status.SUCCESS;
        }
        if (target == null && !fetchFailed) {
            target = freeBoat(bot);
        }
        if (target == null) {
            if (!canMake(bot)) {
                return Status.FAILURE;
            }
            child = new ObtainTask(bot, Target.tag(ItemTags.BOATS, 1), 0);
            return Status.RUNNING;
        }
        if (ticks == 0) {
            bot.debug("going for the boat at {}", target.blockPosition().toShortString());
        }
        if (!target.isAlive()) {
            // Knocked loose: it dropped as an item
            child = new CollectItemsTask(bot, target.position(), 4.0, 100);
            return Status.RUNNING;
        }
        if (++ticks > MAX_TICKS) {
            return giveUpFetching();
        }
        if (bot.distanceTo(target) > 2.5) {
            BlockPos at = target.blockPosition();
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(Goal.near(at, 2.0));
            }
            if (bot.navigator().tick().ended() && bot.distanceTo(target) > 2.5) {
                return giveUpFetching();
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().lookAt(target.getBoundingBox().getCenter());
        if (--hitCooldown <= 0) {
            hitCooldown = 6;
            bot.attack(target); // a few hits and it breaks into an item, as for a player
            bot.swing(InteractionHand.MAIN_HAND);
        }
        return Status.RUNNING;
    }

    /** Couldn't get to the free boat: make one instead, if there's the wood. */
    private Status giveUpFetching() {
        bot.navigator().stop();
        target = null;
        fetchFailed = true;
        return canMake(bot) ? Status.RUNNING : Status.FAILURE;
    }

    @Override
    public void stop() {
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
        String what = target != null ? "fetching a boat" : "getting a boat";
        return child != null ? what + ": " + child.describe() : what;
    }
}
