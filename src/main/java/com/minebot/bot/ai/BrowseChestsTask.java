package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.path.Approach;
import com.minebot.bot.world.Stations;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * On its travels the bot has a look in chests and barrels it passes (other
 * bots', villages', dungeons'...) and takes armour better than what it wears.
 * Each container is looked into once.
 */
public class BrowseChestsTask extends Task {
    private static final int RADIUS = 16;
    /** Only away from home: its own and neighbours' chests aren't "on the way". */
    private static final int HOME_DISTANCE = 48;
    /** Not more often than this. */
    private static final int INTERVAL_TICKS = 20 * 60;
    private static final int OPEN_TICKS = 10;

    private final @Nullable BlockPos chest;
    /** One of the chests at home (its own or a neighbour's), looked into for armour stored there. */
    private final boolean atHome;
    private int openTicks;
    /** A wild chest (dungeon, ruin): rubbish goes in, valuables come out. */
    private boolean wild;
    private @Nullable Approach approach;

    public BrowseChestsTask(BotPlayer bot) {
        super(bot);
        this.chest = find(bot);
        this.atHome = false;
    }

    /** Taking the better armour out of this chest at home. */
    public BrowseChestsTask(BotPlayer bot, @Nullable BlockPos chest) {
        super(bot);
        this.chest = chest;
        this.atHome = true;
    }

    /**
     * A chest it may take from (its own, or anyone's nearby) holding armour better than
     * what it wears, or null.
     */
    public static @Nullable BlockPos storedUpgrade(BotPlayer bot) {
        for (RandomizableContainerBlockEntity container : ChestTask.reachable(bot)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (isGearUpgrade(bot, container.getItem(i))) {
                    return container.getBlockPos();
                }
            }
        }
        return null;
    }

    public static boolean wanted(BotPlayer bot) {
        if (bot.level().getGameTime() < bot.nextChestBrowse()) {
            return false;
        }
        if (Home.isNear(bot, HOME_DISTANCE)) {
            // (near home only wild chests count, and its neighbours' chests are many: a look now and then)
            return bot.every("wild chest", 20 * 30, () -> find(bot) != null);
        }
        return find(bot) != null;
    }

    private static boolean isContainer(BlockState state) {
        return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock;
    }

    /** A chest to look into: any on its travels, and near home only wild ones (a dungeon's...). */
    private static @Nullable BlockPos find(BotPlayer bot) {
        boolean nearHome = Home.isNear(bot, HOME_DISTANCE);
        return Stations.nearest(bot, BrowseChestsTask::isContainer, RADIUS,
            (pos, state) -> !bot.browsedChests().contains(pos.asLong()) && !bot.memory().chests().contains(pos)
                && (!nearHome || isWild(bot, pos)));
    }

    /**
     * A chest nobody keeps: by a spawner (a dungeon), in a structure (temple, shipwreck,
     * stronghold...) or with its loot not yet rolled; never a bot's, nor in a protected place.
     * The bot leaves its rubbish in those and takes what's worth having.
     */
    static boolean isWild(BotPlayer bot, BlockPos pos) {
        net.minecraft.server.level.ServerLevel level = bot.level();
        if (com.minebot.bot.world.ProtectedAreas.isProtected(level, pos)) {
            return false;
        }
        for (com.minebot.bot.BotMemory memory : com.minebot.bot.BotRegistry.get(level.getServer()).all()) {
            if (memory.chests().contains(pos)) {
                return false;
            }
        }
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container && container.getLootTable() != null) {
            return true;
        }
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-6, -4, -6), pos.offset(6, 4, 6))) {
            if (level.getBlockState(near).is(net.minecraft.world.level.block.Blocks.SPAWNER)) {
                return true;
            }
        }
        return !level.structureManager().getAllStructuresAt(pos).isEmpty();
    }

    /** Worth taking from a wild chest: valuables, and what it uses and is short of (not gear: see isGearUpgrade). */
    private static boolean isValuable(BotPlayer bot, ItemStack stack) {
        if (stack.isEmpty() || stack.isDamageableItem()) {
            return false;
        }
        return LootTask.isUseful(bot, stack) || stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)
            || stack.is(Items.ENCHANTED_BOOK) || stack.is(Items.NAME_TAG) || stack.is(Items.SADDLE)
            || stack.is(Items.TOTEM_OF_UNDYING) || stack.is(net.minecraft.tags.ItemTags.ARROWS)
            || stack.is(Items.WHEAT_SEEDS) || stack.is(Items.MELON_SEEDS) || stack.is(Items.PUMPKIN_SEEDS)
            || stack.is(Items.BEETROOT_SEEDS) || stack.is(Items.BREAD) || stack.is(Items.COAL) || stack.is(Items.LAPIS_LAZULI);
    }

    @Override
    public Status tick() {
        if (chest == null) {
            return Status.FAILURE;
        }
        if (!(bot.level().getBlockEntity(chest) instanceof RandomizableContainerBlockEntity container)) {
            return done();
        }
        if (openTicks == 0) {
            if (approach == null) {
                approach = new Approach(bot, chest);
            }
            Approach.Result reached = approach.tick();
            if (reached == Approach.Result.MOVING) {
                return Status.RUNNING;
            }
            if (reached == Approach.Result.FAILED) {
                // (at home: put off for a while like any failed need, not tried again right away)
                return atHome ? Status.FAILURE : done();
            }
        }
        bot.navigator().stop();
        bot.controller().lookAt(Vec3.atCenterOf(chest));
        if (openTicks++ == 0) {
            wild = !atHome && isWild(bot, chest); // (before the loot is rolled: that counts too)
            container.unpackLootTable(bot); // (a dungeon chest fills when first opened)
            container.startOpen(bot);
        }
        if (openTicks < OPEN_TICKS) {
            return Status.RUNNING;
        }
        if (wild) {
            // A dungeon's or a ruin's chest: rubbish in, valuables out
            for (java.util.Map.Entry<Integer, Integer> rubbish : Stash.disposable(bot).entrySet()) {
                Stash.deposit(bot, rubbish.getKey(), rubbish.getValue(), container);
            }
            for (int i = 0; i < container.getContainerSize() && Inv.freeSlots(bot) > 2; i++) {
                ItemStack stack = container.getItem(i);
                if (isValuable(bot, stack)) {
                    bot.debug("took {} {} from a chest at {}", stack.getCount(), stack.getItem(), chest.toShortString());
                    Inv.give(bot, container.removeItemNoUpdate(i));
                }
            }
            container.setChanged();
        }
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            EquipmentSlot slot = Armor.slotOf(stack);
            // From someone's chest on the way only armour (as asked); tools, bows and the like only from its
            // own chests at home and from wild ones (a dungeon's, a ruin's)
            boolean better = slot != null ? Armor.isUpgrade(bot, stack) && betterThanCarried(stack, slot)
                : (atHome || wild) && isGearUpgrade(bot, stack);
            if (better && Inv.freeSlots(bot) > 0) {
                bot.debug("found {} in a chest at {}", stack.getItem(), chest.toShortString());
                Inv.give(bot, container.removeItemNoUpdate(i));
                container.setChanged();
            }
        }
        container.stopOpen(bot);
        Armor.equipBest(bot);
        return done();
    }

    /** Armour, a bow or crossbow, or a tool better than its own (or one it lacks altogether). */
    static boolean isGearUpgrade(BotPlayer bot, ItemStack stack) {
        return Armor.isUpgrade(bot, stack) || Ranged.isUpgrade(bot, stack) || Stash.isToolUpgrade(bot, stack);
    }

    /** Not a second, worse piece for a slot it already picked something up for. */
    private boolean betterThanCarried(ItemStack stack, EquipmentSlot slot) {
        for (int i = 0; i < Inv.MAIN_SIZE; i++) {
            ItemStack carried = bot.getInventory().getItem(i);
            if (Armor.slotOf(carried) == slot && Armor.score(carried) >= Armor.score(stack)) {
                return false;
            }
        }
        return true;
    }

    private Status done() {
        if (atHome) {
            bot.every("stored gear", 0, () -> false); // (the need looks into the chests again next time round)
            return Status.SUCCESS;
        }
        bot.browsedChests().add(chest.asLong());
        bot.setNextChestBrowse(bot.level().getGameTime() + INTERVAL_TICKS);
        return Status.SUCCESS;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return atHome ? "taking gear from the chest" : "looking into a chest on the way";
    }
}
