package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Approach;
import com.minebot.bot.world.Stations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Walks to chests and either stores what the bot doesn't need to carry (in
 * its own home chests only), or takes out items for a target - from its own
 * chests and from anyone's chests or barrels nearby (other bots', villages',
 * dungeon loot...). Opens the lid while it works, like a player would.
 */
public class ChestTask extends Task {
    private static final int OPEN_TICKS = 8;
    /** Look in other chests this close by. */
    private static final int NEARBY_RADIUS = 32;

    private final @Nullable Target withdraw;
    private final List<BlockPos> chests;
    private int index;
    private @Nullable RandomizableContainerBlockEntity open;
    private int openTicks;
    private @Nullable Approach approach;

    /** Store surplus items in the home chests. */
    public static ChestTask store(BotPlayer bot) {
        return new ChestTask(bot, null, new ArrayList<>(bot.memory().chests()));
    }

    /** Take items for {@code target} from the home chests and any chest nearby that has some. */
    public static ChestTask withdraw(BotPlayer bot, Target target) {
        return new ChestTask(bot, target, chestsWith(bot, target.accepts()));
    }

    private ChestTask(BotPlayer bot, @Nullable Target withdraw, List<BlockPos> chests) {
        super(bot);
        this.withdraw = withdraw;
        this.chests = chests;
    }

    /** How many matching items the bot can reach in chests: its own, plus anyone's nearby. */
    public static int stored(BotPlayer bot, Target target) {
        int total = 0;
        for (BlockPos pos : chestsWith(bot, target.accepts())) {
            RandomizableContainerBlockEntity chest = container(bot, pos);
            if (chest != null) {
                total += Stash.count(chest, target.accepts());
            }
        }
        return total;
    }

    /** Every chest/barrel the bot may take from: its own and anyone's nearby (loot generated). */
    public static List<RandomizableContainerBlockEntity> reachable(BotPlayer bot) {
        List<RandomizableContainerBlockEntity> result = new ArrayList<>();
        for (BlockPos pos : candidates(bot)) {
            RandomizableContainerBlockEntity chest = container(bot, pos);
            if (chest != null) {
                result.add(chest);
            }
        }
        return result;
    }

    private static Set<BlockPos> candidates(BotPlayer bot) {
        Set<BlockPos> candidates = new LinkedHashSet<>();
        if (Home.levelIfHere(bot) != null) {
            candidates.addAll(bot.memory().chests());
        }
        candidates.addAll(Stations.all(bot, state -> state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock,
            NEARBY_RADIUS, (pos, state) -> true, 24));
        return candidates;
    }

    /** Home chests and nearby chests/barrels holding something that matches, nearest first. */
    private static List<BlockPos> chestsWith(BotPlayer bot, Predicate<ItemStack> matches) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : candidates(bot)) {
            RandomizableContainerBlockEntity chest = container(bot, pos);
            if (chest != null && Stash.count(chest, matches) > 0) {
                result.add(pos);
            }
        }
        result.sort(Comparator.comparingDouble(pos -> pos.distSqr(bot.blockPosition())));
        return result;
    }

    /**
     * The container at pos, with any structure loot generated (as when a
     * player opens it), or null.
     */
    private static @Nullable RandomizableContainerBlockEntity container(BotPlayer bot, BlockPos pos) {
        ServerLevel level = bot.level();
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity chest)) {
            return null;
        }
        chest.unpackLootTable(bot);
        return chest;
    }

    @Override
    public Status tick() {
        if (withdraw != null && withdraw.satisfied(bot)) {
            return finish(Status.SUCCESS);
        }
        if (withdraw == null && Stash.toStore(bot).isEmpty()) {
            return finish(Status.SUCCESS);
        }
        if (index >= chests.size()) {
            return finish(withdraw == null && index > 0 ? Status.SUCCESS : Status.FAILURE);
        }
        BlockPos pos = chests.get(index);
        RandomizableContainerBlockEntity chest = container(bot, pos);
        if (chest == null) {
            index++;
            return Status.RUNNING;
        }
        if (approach == null || !approach.target().equals(pos)) {
            approach = new Approach(bot, pos);
        }
        Approach.Result reached = approach.tick();
        if (reached == Approach.Result.FAILED) {
            index++; // can't get at this one: the next chest
            approach = null;
            return Status.RUNNING;
        }
        if (reached == Approach.Result.MOVING) {
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().lookAt(Vec3.atCenterOf(pos));
        if (open != chest) {
            close();
            open = chest;
            chest.startOpen(bot);
            openTicks = 0;
        }
        if (++openTicks < OPEN_TICKS) {
            return Status.RUNNING; // lid opening
        }

        boolean done;
        if (withdraw != null) {
            int taken = Stash.withdraw(bot, chest, withdraw.accepts(), withdraw.missing(bot));
            if (taken > 0) {
                bot.debug("took {} for {} from the chest at {}", taken, withdraw, pos.toShortString());
            }
            done = true;
        } else {
            done = storeInto(chest);
        }
        if (done) {
            close();
            index++;
        }
        return Status.RUNNING;
    }

    /** Puts one slot per tick away. @return true when nothing more fits or nothing is left */
    private boolean storeInto(RandomizableContainerBlockEntity chest) {
        Map<Integer, Integer> surplus = Stash.toStore(bot);
        for (Map.Entry<Integer, Integer> entry : surplus.entrySet()) {
            if (Stash.deposit(bot, entry.getKey(), entry.getValue(), chest) > 0) {
                return false;
            }
        }
        return true;
    }

    private Status finish(Status status) {
        close();
        bot.navigator().stop();
        return status;
    }

    private void close() {
        if (open != null) {
            open.stopOpen(bot);
            open = null;
        }
    }

    @Override
    public void stop() {
        close();
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return withdraw != null ? "fetching " + withdraw + " from a chest" : "putting things away at home";
    }

    /** Free inventory slots are getting scarce. */
    public static boolean bagFull(BotPlayer bot) {
        return Inv.freeSlots(bot) < 4;
    }

    /** Free slots left in its own chests at home (loaded ones). */
    public static int chestSpace(BotPlayer bot) {
        int free = 0;
        if (Home.levelIfHere(bot) == null) {
            return 0;
        }
        for (BlockPos pos : bot.memory().chests()) {
            RandomizableContainerBlockEntity chest = container(bot, pos);
            for (int i = 0; chest != null && i < chest.getContainerSize(); i++) {
                if (chest.getItem(i).isEmpty()) {
                    free++;
                }
            }
        }
        return free;
    }

    /** No room left in its own chests. */
    public static boolean chestsFull(BotPlayer bot) {
        return chestSpace(bot) == 0;
    }
}
