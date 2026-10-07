package com.minebot.bot.ai;

import com.minebot.bot.BotManager;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Picks up useful things lying around: drops from mobs, blocks broken by
 * someone, saplings from decayed leaves, better armour a fallen bot dropped
 * (from further away). Never what a player or another bot
 * threw (gifts, a player's things), nor anything right next to a player.
 */
public class LootTask extends Task {
    private static final int RANGE = 16;
    /** Armour better than what it wears (from a fallen bot or player) is worth a longer walk. */
    private static final int ARMOR_RANGE = 48;
    private static final int PLAYER_DISTANCE = 8;
    private static final int MAX_TICKS = 20 * 30;
    private static final int FRESH_TICKS = 20 * 10;

    /** Items a bot couldn't get to, so it doesn't keep trying. */
    private static final Map<UUID, Set<UUID>> UNREACHABLE = new HashMap<>();

    /** Forgotten when the bot leaves the server: back again, it may try those once more. */
    public static void forget(java.util.UUID bot) {
        UNREACHABLE.remove(bot);
    }

    private final @Nullable ItemEntity target;
    private int ticks;

    public LootTask(BotPlayer bot) {
        super(bot);
        this.target = find(bot);
    }

    public static boolean wanted(BotPlayer bot) {
        // Not in the middle of felling a tree: what falls from it gets picked up after
        return bot.fellingTree() == null && find(bot) != null;
    }

    private static @Nullable ItemEntity find(BotPlayer bot) {
        if (bot.getInventory().getFreeSlot() < 0) {
            return null;
        }
        Set<UUID> unreachable = UNREACHABLE.getOrDefault(bot.getUUID(), Set.of());
        AABB area = bot.getBoundingBox().inflate(ARMOR_RANGE, 8, ARMOR_RANGE);
        return bot.level().getEntitiesOfClass(ItemEntity.class, area, item -> item.isAlive()
                && item.getAge() > FRESH_TICKS // just dropped: the task that mined it picks it up
                && !item.isInWater() // no diving after it
                && !unreachable.contains(item.getUUID())
                && item.getOwner() == null // thrown on purpose by a player or a bot
                && (bot.distanceToSqr(item) <= RANGE * RANGE ? isUseful(bot, item.getItem()) : Armor.isUpgrade(bot, item.getItem()) || Ranged.isUpgrade(bot, item.getItem()))
                && !ProtectedAreas.isProtected(bot.level(), item.blockPosition())
                && !nearPlayer(bot, item))
            .stream()
            .min(Comparator.comparingDouble(bot::distanceToSqr))
            .orElse(null);
    }

    /** Worth a detour: things it keeps on hand and is short of, valuables, gear. */
    static boolean isUseful(BotPlayer bot, ItemStack stack) {
        if (stack.isEmpty() || Stash.isJunk(stack)) {
            return false;
        }
        return Stash.wantsMore(bot, stack)
            || Stash.isTool(stack) || stack.isDamageableItem() // tools, weapons, armour
            || stack.is(Items.RAW_IRON) || stack.is(Items.RAW_GOLD) || stack.is(Items.RAW_COPPER)
            || stack.is(Items.IRON_INGOT) || stack.is(Items.GOLD_INGOT) || stack.is(Items.COPPER_INGOT)
            || stack.is(Items.DIAMOND) || stack.is(Items.EMERALD)
            || stack.is(ItemTags.WOOL) || stack.is(Items.LEATHER) || stack.is(Items.STRING) || stack.is(Items.FEATHER)
            || stack.is(Items.FLINT) || stack.is(ItemTags.SAPLINGS); // (saplings: to plant)
    }

    private static boolean nearPlayer(BotPlayer bot, ItemEntity item) {
        for (ServerPlayer player : bot.level().players()) {
            if (!BotManager.isBot(player.getUUID()) && !player.isSpectator()
                && player.distanceToSqr(item) < PLAYER_DISTANCE * PLAYER_DISTANCE) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Status tick() {
        if (target == null || !target.isAlive()) {
            return Status.SUCCESS; // picked up (by us or someone else)
        }
        if (!bot.navigator().isActive()) {
            bot.navigator().navigate(Goal.atItem(bot.level(), target.position()));
        }
        Navigator.Status status = bot.navigator().tick();
        if (status == Navigator.Status.FAILED || ++ticks > MAX_TICKS) {
            giveUp();
            return Status.FAILURE;
        }
        if (status == Navigator.Status.SUCCESS && target.isAlive() && bot.distanceToSqr(target) > 2.0) {
            giveUp(); // got there but it's out of reach (stuck in a block, on a ledge)
            return Status.FAILURE;
        }
        return Status.RUNNING;
    }

    private void giveUp() {
        bot.debug("can't get to the {} lying at {}", target.getItem().getItem(), target.blockPosition().toShortString());
        Set<UUID> unreachable = UNREACHABLE.computeIfAbsent(bot.getUUID(), uuid -> new HashSet<>());
        if (unreachable.size() > 100) {
            unreachable.clear(); // items despawn after 5 minutes anyway
        }
        unreachable.add(target.getUUID());
    }

    @Override
    public void stop() {
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return target != null ? "picking up " + target.getItem().getHoverName().getString() : "picking up items";
    }
}
