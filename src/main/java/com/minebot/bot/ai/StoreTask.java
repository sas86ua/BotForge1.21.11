package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Clears out the bag: stores surplus in the home chests (adding a chest when
 * they're full), or without a home just throws junk away.
 */
public class StoreTask extends Task {
    private static final int MAX_CHESTS = 6;
    /** With a workshop (the old hut) there is room for more, stacked two high. */
    private static final int MAX_WORKSHOP_CHESTS = 24;

    private enum Phase { STORE, CHECK, STORE_AGAIN, DONE }

    /** Home chests further than this: burn rubbish in lava instead of walking all the way. */
    private static final int CHESTS_NEAR = 96;

    private Phase phase = Phase.STORE;
    private @Nullable Task child;
    private boolean dumping;
    private boolean dumped;

    public StoreTask(BotPlayer bot) {
        super(bot);
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
            if (dumping) {
                dumping = false;
            } else if (status == Status.FAILURE && phase != Phase.CHECK) {
                phase = Phase.DONE;
            }
        }
        boolean chestsNear = Home.isNear(bot, CHESTS_NEAR) && !bot.memory().chests().isEmpty();
        if (!chestsNear && !dumped && DumpTask.possible(bot)) {
            dumped = true;
            dumping = true;
            child = new DumpTask(bot);
            return Status.RUNNING;
        }
        if (dumped && !ChestTask.bagFull(bot)) {
            return Status.SUCCESS; // room again; the chests can wait
        }
        if (!Home.has(bot) || bot.memory().chests().isEmpty()) {
            dropJunk();
            return Status.SUCCESS;
        }
        switch (phase) {
            case STORE -> {
                phase = Phase.CHECK;
                child = ChestTask.store(bot);
            }
            case CHECK -> {
                // Something it meant to put away didn't fit: the chests are full (whether or not its bag is)
                boolean stillFull = !Stash.toStore(bot).isEmpty() && ChestTask.chestsFull(bot);
                int max = bot.memory().workshop() != null ? MAX_WORKSHOP_CHESTS : MAX_CHESTS;
                if (stillFull && bot.memory().chests().size() < max) {
                    // The chests are full: put down another one and store again
                    phase = Phase.STORE_AGAIN;
                    child = new FurnishTask(bot, true);
                } else {
                    phase = Phase.DONE;
                }
            }
            case STORE_AGAIN -> {
                phase = Phase.DONE;
                child = ChestTask.store(bot);
            }
            case DONE -> {
                dropJunk();
                return Status.SUCCESS;
            }
        }
        return Status.RUNNING;
    }

    private void dropJunk() {
        for (int slot = 0; slot < Inv.MAIN_SIZE; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (!stack.isEmpty() && Stash.isJunk(stack)) {
                bot.drop(bot.getInventory().removeItemNoUpdate(slot), false);
            }
        }
    }

    @Override
    public void stop() {
        if (child != null) {
            child.stop();
        }
    }

    @Override
    public String describe() {
        return child != null ? child.describe() : "sorting the inventory";
    }
}
