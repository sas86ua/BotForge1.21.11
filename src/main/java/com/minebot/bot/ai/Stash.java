package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What a bot carries and what it leaves at home, and moving items between its
 * inventory and a chest.
 */
public final class Stash {
    /** Item groups the bot keeps on hand, with how many. Everything else goes in the chest. */
    private record Keep(String group, Predicate<ItemStack> matches, int amount) {
    }

    private static final Keep[] KEEP = {
        // Seeds, potatoes and carrots only while farming (taken from the chest for the field, back after);
        // before "food", so potatoes and carrots aren't kept as food
        new Keep("seeds", stack -> stack.is(Items.WHEAT_SEEDS) || stack.is(Items.POTATO) || stack.is(Items.CARROT), 0),
        new Keep("food", Inv::isFood, 16),
        new Keep("torches", stack -> stack.is(Items.TORCH), 16),
        new Keep("coal", stack -> stack.is(ItemTags.COALS), 16),
        new Keep("logs", stack -> stack.is(ItemTags.LOGS), 16),
        new Keep("planks", stack -> stack.is(ItemTags.PLANKS), 16),
        new Keep("sticks", stack -> stack.is(Items.STICK), 8),
        // (saplings aren't carried: planted straight away, or into the chest)
        new Keep("scaffold", Inv::isScaffold, 32),
        new Keep("arrows", stack -> stack.is(ItemTags.ARROWS), 64),
        new Keep("boat", stack -> stack.is(ItemTags.BOATS), 1),
        new Keep("fletching", stack -> stack.is(Items.FLINT) || stack.is(Items.FEATHER) || stack.is(Items.STRING), 48),
        new Keep("crafting table", stack -> stack.is(Items.CRAFTING_TABLE), 1),
        new Keep("furnace", stack -> stack.is(Items.FURNACE), 1),
        new Keep("campfire", stack -> stack.is(Items.CAMPFIRE), 1),
        new Keep("ladder", stack -> stack.is(Items.LADDER), 9),
    };

    /** Junk worth nothing to a bot; thrown away when the bag is full and there's no chest. */
    private static final Predicate<ItemStack> JUNK = stack -> stack.is(ItemTags.FLOWERS)
        || stack.is(ItemTags.LEAVES) // (leaf litter isn't rubbish: it burns in a furnace)
        || stack.is(Items.SHORT_GRASS) || stack.is(Items.TALL_GRASS) || stack.is(Items.FERN) || stack.is(Items.DEAD_BUSH)
        || stack.is(Items.GRAVEL)
        || stack.is(ItemTags.BANNERS) || stack.is(Items.OMINOUS_BOTTLE) || stack.is(Items.EGG) // (raid loot, eggs)
        || stack.is(Items.ROTTEN_FLESH) || stack.is(Items.POISONOUS_POTATO) || stack.is(Items.FIREFLY_BUSH);

    private Stash() {
    }

    /** Bulk blocks nobody needs piles of: dug out on the way, fine to burn beyond what it keeps. */
    private static final Predicate<ItemStack> BULK = stack -> stack.is(Items.DIRT) || stack.is(Items.COARSE_DIRT)
        || stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE) || stack.is(Items.ANDESITE)
        || stack.is(Items.DIORITE) || stack.is(Items.GRANITE) || stack.is(Items.TUFF) || stack.is(Items.CALCITE)
        || stack.is(Items.NETHERRACK) || stack.is(Items.SANDSTONE) || stack.is(Items.RED_SANDSTONE);

    /** Slots (and how many) of rubbish to burn: junk, and bulk blocks beyond what the bot keeps. */
    public static Map<Integer, Integer> disposable(BotPlayer bot) {
        Map<Integer, Integer> result = new HashMap<>();
        int seedsOver = -1; // (seeds over what's kept at home, bag and chests together; worked out when needed)
        for (Map.Entry<Integer, Integer> entry : toStore(bot).entrySet()) {
            ItemStack stack = bot.getInventory().getItem(entry.getKey());
            if (BULK.test(stack) && forGreatBuild(bot, stack)) {
                continue; // (cobblestone and the like it's to bring to the Great Build, or is building with)
            }
            if (JUNK.test(stack) || BULK.test(stack) || Ranged.is(stack) && isTool(stack) && isSpare(bot, entry.getKey())) {
                result.put(entry.getKey(), entry.getValue()); // (a bow or crossbow worse than the one it uses: rubbish too)
            } else if (JunkRunTask.isSeeds(stack)) {
                if (seedsOver < 0) {
                    seedsOver = Math.max(0, Inv.count(bot, JunkRunTask::isSeeds) + JunkRunTask.seedsAtHome(bot) - JunkRunTask.SEEDS_KEEP);
                }
                int over = Math.min(entry.getValue(), seedsOver);
                if (over > 0) {
                    result.put(entry.getKey(), over);
                    seedsOver -= over;
                }
            }
        }
        return result;
    }

    /** Inventory slots (0-35) whose contents should go to the chest. */
    public static Map<Integer, Integer> toStore(BotPlayer bot) {
        Inventory inventory = bot.getInventory();
        Map<Integer, Integer> result = new HashMap<>();
        Map<String, Integer> kept = new HashMap<>();
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (isTool(stack)) {
                if (isSpare(bot, slot)) {
                    result.put(slot, stack.getCount()); // spare, worse tools
                }
                continue;
            }
            Keep keep = keepRule(stack);
            if (keep == null) {
                result.put(slot, stack.getCount());
                continue;
            }
            int already = kept.getOrDefault(keep.group(), 0);
            int keepHere = Math.max(0, Math.min(stack.getCount(), keep.amount() - already));
            kept.put(keep.group(), already + keepHere);
            if (keepHere < stack.getCount()) {
                result.put(slot, stack.getCount() - keepHere);
            }
        }
        return result;
    }

    /** Ordered for the Great Build, or (away at it) a block it builds with. */
    static boolean forGreatBuild(BotPlayer bot, ItemStack stack) {
        com.minebot.bot.build.GreatBuild build = com.minebot.bot.build.GreatBuild.get(bot.level().getServer());
        return build.exists() && (build.orderOf(bot.getUUID()).containsKey(stack.getItem())
            || GreatBuildTask.isAway(bot) && build.isMaterial(stack.getItem()));
    }

    /**
     * Far from the chests with a full bag: throws rubbish away (junk first, then the bulk blocks beyond what it
     * keeps) until {@code wanted} slots are free, so what it digs or picks up next has somewhere to go.
     * @return whether there's that much room now
     */
    public static boolean makeRoom(BotPlayer bot, int wanted) {
        if (Inv.freeSlots(bot) >= wanted) {
            return true;
        }
        Map<Integer, Integer> rubbish = disposable(bot);
        for (boolean junkOnly : new boolean[] {true, false}) {
            for (Map.Entry<Integer, Integer> entry : rubbish.entrySet()) {
                ItemStack stack = bot.getInventory().getItem(entry.getKey());
                if (stack.isEmpty() || junkOnly && !JUNK.test(stack) || entry.getValue() < stack.getCount()) {
                    continue; // (only whole stacks free a slot)
                }
                var thrown = bot.drop(bot.getInventory().removeItemNoUpdate(entry.getKey()), false);
                if (thrown != null) {
                    thrown.setPickUpDelay(20 * 60); // (not straight back into the bag)
                }
                if (Inv.freeSlots(bot) >= wanted) {
                    bot.debug("threw rubbish away to make room");
                    return true;
                }
            }
        }
        return Inv.freeSlots(bot) >= wanted;
    }

    public static boolean isJunk(ItemStack stack) {
        return JUNK.test(stack);
    }

    /** Dirt, cobblestone and the like: useful by the stack, rubbish by the chestful. */
    public static boolean isBulk(ItemStack stack) {
        return BULK.test(stack);
    }

    /** Old tools it has better ones of, or armour it took off: they belong in the chest. */
    public static boolean hasSpareGear(BotPlayer bot) {
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && (isTool(stack) && isSpare(bot, slot) || Armor.slotOf(stack) != null)) {
                return true;
            }
        }
        // More arrows than it carries (made for the reserve in the chest), saplings it found no room for
        return Inv.count(bot, stack -> stack.is(ItemTags.ARROWS)) > 64
            || PlantTask.paused(bot) && Inv.count(bot, stack -> stack.is(ItemTags.SAPLINGS)) > 0
            // Seeds, potatoes, carrots outside farm work (not while it's at the fields: it sows them)
            || Inv.count(bot, stack -> stack.is(Items.WHEAT_SEEDS) || stack.is(Items.POTATO) || stack.is(Items.CARROT)) > 0
                && (bot.memory().farms().isEmpty() || bot.level().getGameTime() < bot.nextFarmVisit());
    }

    /** Is this something the bot keeps on hand, and does it have less of it than it likes to? */
    static boolean wantsMore(BotPlayer bot, ItemStack stack) {
        Keep keep = keepRule(stack);
        return keep != null && Inv.count(bot, keep.matches()) < keep.amount();
    }

    private static Keep keepRule(ItemStack stack) {
        for (Keep keep : KEEP) {
            if (keep.matches().test(stack)) {
                return keep;
            }
        }
        return null;
    }

    static boolean isTool(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS)
            || stack.is(ItemTags.SWORDS) || stack.is(ItemTags.HOES) || Ranged.is(stack) || stack.is(Items.SHIELD)
            || stack.is(Items.SHEARS) || stack.is(Items.FLINT_AND_STEEL);
    }

    /**
     * A pickaxe, axe, shovel, sword or shield better than any of its kind it carries (or it
     * has none): worth taking out of a chest, e.g. after dying and starting over at home.
     */
    public static boolean isToolUpgrade(BotPlayer bot, ItemStack stack) {
        boolean kind = stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS)
            || stack.is(ItemTags.SWORDS) || stack.is(Items.SHIELD);
        if (!kind) {
            return false;
        }
        if (stack.is(Items.SHIELD) && bot.getOffhandItem().is(Items.SHIELD)) {
            return false;
        }
        float score = toolScore(stack);
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            ItemStack carried = bot.getInventory().getItem(slot);
            if (!carried.isEmpty() && sameKind(stack, carried) && toolScore(carried) >= score) {
                return false;
            }
        }
        return true;
    }

    /**
     * A tool it has a better one of, so it can go in the chest (or the furnace): not the best
     * of its kind, and not a worn one being used up while a new one waits (that pair stays).
     */
    static boolean isSpare(BotPlayer bot, int slot) {
        ItemStack stack = bot.getInventory().getItem(slot);
        if (isBestOfKind(bot, slot)) {
            return false;
        }
        if (Tools.isWorn(stack)) {
            int rank = Tools.rank(stack.getItem());
            for (int other = 0; other < Inv.MAIN_SIZE; other++) {
                ItemStack candidate = bot.getInventory().getItem(other);
                if (other != slot && sameKind(stack, candidate) && !Tools.isWorn(candidate) && Tools.rank(candidate.getItem()) == rank) {
                    return !isFirstWorn(bot, slot); // (the one in use: the most worn of its kind)
                }
            }
        }
        return true;
    }

    private static boolean isFirstWorn(BotPlayer bot, int slot) {
        ItemStack stack = bot.getInventory().getItem(slot);
        for (int other = 0; other < Inv.MAIN_SIZE; other++) {
            ItemStack candidate = bot.getInventory().getItem(other);
            if (other != slot && sameKind(stack, candidate) && Tools.isWorn(candidate)
                && (Inv.remaining(candidate) < Inv.remaining(stack) || Inv.remaining(candidate) == Inv.remaining(stack) && other < slot)) {
                return false;
            }
        }
        return true;
    }

    /** The tool in this slot is the best (fastest/strongest) of its kind the bot carries. */
    static boolean isBestOfKind(BotPlayer bot, int slot) {
        Inventory inventory = bot.getInventory();
        ItemStack stack = inventory.getItem(slot);
        float score = toolScore(stack);
        for (int other = 0; other < Inv.MAIN_SIZE; other++) {
            ItemStack candidate = inventory.getItem(other);
            if (other == slot || candidate.isEmpty() || !sameKind(stack, candidate)) {
                continue;
            }
            float otherScore = toolScore(candidate);
            if (otherScore > score || otherScore == score && other < slot) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameKind(ItemStack a, ItemStack b) {
        return a.is(ItemTags.PICKAXES) && b.is(ItemTags.PICKAXES) || a.is(ItemTags.AXES) && b.is(ItemTags.AXES)
            || a.is(ItemTags.SHOVELS) && b.is(ItemTags.SHOVELS) || a.is(ItemTags.SWORDS) && b.is(ItemTags.SWORDS)
            || a.is(ItemTags.HOES) && b.is(ItemTags.HOES) || Ranged.is(a) && Ranged.is(b) || a.getItem() == b.getItem();
    }

    private static float toolScore(ItemStack stack) {
        if (Ranged.is(stack)) {
            return (float) Ranged.score(stack); // bows and crossbows: one kind, the best is kept
        }
        // Attack damage ranks weapons, mining speed on stone ranks tools; enough to tell tiers apart
        float speed = stack.getDestroySpeed(Blocks.STONE.defaultBlockState());
        float durability = stack.isDamageableItem() ? (stack.getMaxDamage() - stack.getDamageValue()) / 10000.0F : 0;
        return speed + stack.getMaxDamage() / 1000.0F + durability;
    }

    /** Moves up to {@code amount} items from an inventory slot into the container. @return how many moved */
    public static int deposit(BotPlayer bot, int slot, int amount, Container chest) {
        ItemStack stack = bot.getInventory().getItem(slot);
        int moved = 0;
        for (int i = 0; i < chest.getContainerSize() && moved < amount && !stack.isEmpty(); i++) {
            ItemStack there = chest.getItem(i);
            if (there.isEmpty()) {
                int count = Math.min(amount - moved, stack.getCount());
                chest.setItem(i, stack.split(count));
                moved += count;
            } else if (ItemStack.isSameItemSameComponents(there, stack) && there.getCount() < there.getMaxStackSize()) {
                int count = Math.min(Math.min(amount - moved, stack.getCount()), there.getMaxStackSize() - there.getCount());
                there.grow(count);
                stack.shrink(count);
                moved += count;
            }
        }
        if (moved > 0) {
            chest.setChanged();
        }
        return moved;
    }

    /** Takes up to {@code amount} matching items out of the container. @return how many taken */
    public static int withdraw(BotPlayer bot, Container chest, Predicate<ItemStack> matches, int amount) {
        int taken = 0;
        for (int i = 0; i < chest.getContainerSize() && taken < amount; i++) {
            ItemStack there = chest.getItem(i);
            if (there.isEmpty() || !matches.test(there)) {
                continue;
            }
            int count = Math.min(amount - taken, there.getCount());
            ItemStack moving = there.split(count);
            int before = moving.getCount();
            bot.getInventory().add(moving);
            int added = before - moving.getCount();
            if (!moving.isEmpty()) {
                there.grow(moving.getCount()); // didn't fit: put the rest back
            }
            taken += added;
            if (added == 0) {
                break; // inventory full
            }
        }
        if (taken > 0) {
            chest.setChanged();
        }
        return taken;
    }

    public static int count(Container chest, Predicate<ItemStack> matches) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (!stack.isEmpty() && matches.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
