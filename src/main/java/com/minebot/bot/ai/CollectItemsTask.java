package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Walks over dropped items around a spot so they get picked up. */
public class CollectItemsTask extends Task {
    /** The items are a few steps away: a small search does (a big one for a stuck item made the server stutter). */
    private static final int SEARCH_NODES = 3000;
    /** Walked up to it this many times and it's still lying there: out of reach (in a block, under water). */
    private static final int MAX_TRIES = 3;

    /** Items each bot couldn't get to: not tried again (they despawn in five minutes anyway). */
    private static final Map<UUID, Set<UUID>> UNREACHABLE = new HashMap<>();

    /** Forgotten when the bot leaves the server. */
    public static void forget(UUID bot) {
        UNREACHABLE.remove(bot);
    }

    private final Vec3 center;
    private final double radius;
    private final int maxTicks;
    private int ticks;
    private @Nullable ItemEntity heading;
    private int tries;

    public CollectItemsTask(BotPlayer bot, Vec3 center, double radius, int maxTicks) {
        super(bot);
        this.center = center;
        this.radius = radius;
        this.maxTicks = maxTicks;
    }

    @Override
    public Status tick() {
        if (++ticks > maxTicks || bot.getInventory().getFreeSlot() < 0) {
            return Status.SUCCESS; // good enough; don't get stuck on an unreachable item
        }
        // Items need a moment to land (and have a pickup delay)
        if (ticks < 8) {
            return Status.RUNNING;
        }
        if (heading == null || !heading.isAlive()) {
            Set<UUID> unreachable = UNREACHABLE.getOrDefault(bot.getUUID(), Set.of());
            List<ItemEntity> items = bot.level().getEntitiesOfClass(ItemEntity.class, new AABB(center, center).inflate(radius),
                item -> item.isAlive() && !item.getItem().isEmpty() && !unreachable.contains(item.getUUID())
                    && item.getOwner() != bot); // (not what it threw away itself)
            ItemEntity next = items.stream().min(Comparator.comparingDouble(bot::distanceToSqr)).orElse(null);
            if (next == null) {
                return Status.SUCCESS;
            }
            tries = next == lastTried ? tries + 1 : 1;
            lastTried = next;
            if (tries > MAX_TRIES) {
                giveUp(next); // walked up to it again and again and it's still there
                lastTried = null;
                return Status.RUNNING;
            }
            heading = next;
            bot.navigator().navigate(Goal.atItem(bot.level(), heading.position()), SEARCH_NODES);
        }
        Navigator.Status status = bot.navigator().tick();
        if (status == Navigator.Status.FAILED) {
            giveUp(heading);
            heading = null;
            return Status.RUNNING; // (the others may still be in reach)
        }
        if (status == Navigator.Status.SUCCESS) {
            heading = null; // standing on it; it gets picked up, or we look again
        }
        return Status.RUNNING;
    }

    /** The item gone for last, and how many times running (to give up on one that can't be picked up). */
    private @Nullable ItemEntity lastTried;

    private void giveUp(ItemEntity item) {
        bot.debug("can't get to the {} lying at {}", item.getItem().getItem(), item.blockPosition().toShortString());
        Set<UUID> unreachable = UNREACHABLE.computeIfAbsent(bot.getUUID(), uuid -> new HashSet<>());
        if (unreachable.size() > 100) {
            unreachable.clear();
        }
        unreachable.add(item.getUUID());
    }

    @Override
    public void stop() {
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return "picking up items";
    }
}
