package com.minebot.bot.ai;

import com.minebot.bot.BotManager;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Target;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Bots help each other: one that's out of food, or busy gathering something,
 * gets a share from a bot nearby that has plenty of it. The giver walks over
 * and drops the items at the other bot's feet; the other bot picks them up.
 */
public final class Sharing {
    /** Only bots this close help each other. */
    private static final int RANGE = 48;
    /** A giver keeps at least this much food for itself. */
    private static final int KEEP_FOOD = 4;
    /** The same pair doesn't share again for a while. */
    private static final int PAIR_COOLDOWN = 20 * 60 * 2;
    /** Dropped gifts are fetched if the receiver is this close and they're this recent. */
    private static final int GIFT_RANGE = 16;
    private static final int GIFT_TICKS = 20 * 30;

    public record Offer(BotPlayer receiver, String what, Predicate<ItemStack> items, int count) {
    }

    private record Gift(Vec3 pos, int tick) {
    }

    private static final Map<String, Integer> lastShared = new HashMap<>();
    private static final Map<UUID, Gift> gifts = new HashMap<>();

    private Sharing() {
    }

    /** Something this bot could give to a bot nearby right now, or null. */
    public static @Nullable Offer findOffer(BotPlayer giver) {
        int now = giver.level().getServer().getTickCount();
        for (BotPlayer other : BotManager.all()) {
            if (other == giver || other.level() != giver.level() || other.isDeadOrDying()
                || other.distanceTo(giver) > RANGE) {
                continue;
            }
            Integer last = lastShared.get(key(giver, other));
            if (last != null && now - last < PAIR_COOLDOWN) {
                continue;
            }
            Offer offer = offerFor(giver, other);
            if (offer != null) {
                return offer;
            }
        }
        return null;
    }

    private static @Nullable Offer offerFor(BotPlayer giver, BotPlayer receiver) {
        // Food first: a bot with nothing to eat
        if (Food.pickFood(receiver) < 0) {
            int spare = Inv.count(giver, Inv::isFood) - KEEP_FOOD;
            if (spare > 0) {
                return new Offer(receiver, "food", Inv::isFood, Math.min(spare, KEEP_FOOD));
            }
        }
        // Then whatever the other bot is out gathering, if we have more than we need
        Target wanted = receiver.brain().wanted();
        if (wanted == null) {
            return null;
        }
        int missing = wanted.missing(receiver);
        if (missing <= 0) {
            return null;
        }
        Target ownNeed = giver.brain().wanted();
        if (ownNeed != null && ownNeed.name().equals(wanted.name())) {
            return null; // we're after the same thing ourselves
        }
        int spare = surplus(giver, wanted.accepts());
        if (spare <= 0) {
            return null;
        }
        return new Offer(receiver, wanted.name(), wanted.accepts(), Math.min(spare, missing));
    }

    /** Matching items the bot carries beyond what it keeps for itself. */
    private static int surplus(BotPlayer bot, Predicate<ItemStack> matches) {
        if (Inv.count(bot, matches) == 0) {
            return 0;
        }
        int spare = 0;
        for (Map.Entry<Integer, Integer> entry : Stash.toStore(bot).entrySet()) {
            ItemStack stack = bot.getInventory().getItem(entry.getKey());
            if (matches.test(stack) && !Stash.isJunk(stack)) {
                spare += entry.getValue();
            }
        }
        return spare;
    }

    public static void shared(BotPlayer giver, BotPlayer receiver, Vec3 droppedAt) {
        lastShared.put(key(giver, receiver), giver.level().getServer().getTickCount());
        gifts.put(receiver.getUUID(), new Gift(droppedAt, giver.level().getServer().getTickCount()));
    }

    /** Where items were just dropped for this bot, if it should go and pick them up. */
    public static @Nullable Vec3 takeGift(BotPlayer bot) {
        Gift gift = gifts.get(bot.getUUID());
        if (gift == null) {
            return null;
        }
        gifts.remove(bot.getUUID());
        boolean fresh = bot.level().getServer().getTickCount() - gift.tick() < GIFT_TICKS;
        return fresh && gift.pos().distanceTo(bot.position()) < GIFT_RANGE ? gift.pos() : null;
    }

    private static String key(BotPlayer giver, BotPlayer receiver) {
        return giver.getUUID() + ">" + receiver.getUUID();
    }
}
