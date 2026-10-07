package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.path.Goal;
import com.minebot.bot.world.BlockSearch;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Burns rubbish (dirt, cobblestone, gravel, junk beyond what it keeps) in lava
 * when its bag is full and its chests aren't near. The bot remembers a lava
 * pool close to the centre of its zone and goes back to it.
 */
public class DumpTask extends Task {
    /** Lava further than this isn't worth the walk. */
    public static final int MAX_DISTANCE = 128;
    private static final int SCAN_RADIUS = 48;
    private static final int THROW_INTERVAL = 6;

    /** Lava each bot couldn't get to: not picked again (nor lava of the same pool). */
    private static final Map<java.util.UUID, java.util.Set<BlockPos>> UNREACHABLE = new java.util.HashMap<>();

    /** Forgotten when the bot leaves the server: back again, it may try that lava once more. */
    public static void forget(java.util.UUID bot) {
        UNREACHABLE.remove(bot);
    }

    private final @Nullable BlockPos lava;
    private int ticks;
    private int thrown;
    private int failures;

    public DumpTask(BotPlayer bot) {
        super(bot);
        this.lava = knownLava(bot);
    }

    /** Anything to burn, and lava to burn it in? */
    public static boolean possible(BotPlayer bot) {
        if (Stash.disposable(bot).isEmpty()) {
            return false;
        }
        if (knownLava(bot) == null) {
            scan(bot); // maybe there's some around here
        }
        return knownLava(bot) != null;
    }

    /** The remembered lava, if it's still there and not too far. */
    public static @Nullable BlockPos knownLava(BotPlayer bot) {
        GlobalPos lava = bot.memory().lava();
        if (lava == null || lava.dimension() != bot.level().dimension()
            || !lava.pos().closerThan(bot.blockPosition(), MAX_DISTANCE)) {
            return null;
        }
        ServerLevel level = bot.level();
        if (level.isLoaded(lava.pos()) && !isOpenLava(level, lava.pos(), level.getBlockState(lava.pos()))) {
            bot.memory().setLava(null); // dried up, covered...
            return null;
        }
        return lava.pos();
    }

    /**
     * Looks for lava out in the open around the bot and remembers the pool closest
     * to the centre of its zone. Called now and then; cheap where there's no lava.
     */
    public static void scan(BotPlayer bot) {
        ServerLevel level = bot.level();
        BlockPos center = bot.blockPosition();
        BlockPos anchor = bot.memory().anchor().pos();
        if (bot.memory().anchor().dimension() != level.dimension()) {
            return;
        }
        java.util.Set<BlockPos> unreachable = UNREACHABLE.getOrDefault(bot.getUUID(), java.util.Set.of());
        List<BlockPos> found = BlockSearch.find(level, center, SCAN_RADIUS, center.getY() - 24, center.getY() + 24,
            state -> state.getFluidState().is(FluidTags.LAVA),
            (pos, state) -> isOpenLava(level, pos, state) && bot.memory().inZone(level.dimension(), pos)
                && !ProtectedAreas.isProtected(level, pos)
                && unreachable.stream().noneMatch(bad -> bad.closerThan(pos, 8)),
            32);
        BlockPos best = found.stream().min(Comparator.comparingDouble(pos -> pos.distSqr(anchor))).orElse(null);
        GlobalPos known = bot.memory().lava();
        if (best != null && (known == null || known.dimension() != level.dimension()
            || best.distSqr(anchor) < known.pos().distSqr(anchor))) {
            bot.debug("found lava to burn rubbish in at {}", best.toShortString());
            bot.memory().setLava(GlobalPos.of(level.dimension(), best));
        }
    }

    /** A lava source with open air above it (items thrown in burn, the bot can see it). */
    private static boolean isOpenLava(ServerLevel level, BlockPos pos, BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA) && state.getFluidState().isSource()
            && level.getBlockState(pos.above()).isAir();
    }

    @Override
    public Status tick() {
        if (lava == null) {
            return Status.FAILURE;
        }
        Vec3 target = Vec3.atCenterOf(lava);
        double horizontal = Math.sqrt(Math.pow(bot.getX() - target.x, 2) + Math.pow(bot.getZ() - target.z, 2));
        boolean placed = horizontal >= 2.0 && horizontal <= 4.5 && Math.abs(bot.getY() - lava.getY()) <= 3
            && Goal.canSee(bot.level(), bot.getEyePosition(), lava) && noLavaNear(bot.getBlockX(), bot.getBlockY(), bot.getBlockZ());
        if (!placed) {
            if (!bot.navigator().isActive()) {
                bot.navigator().navigate(throwingSpot(lava));
            }
            if (bot.navigator().tick().ended() && ++failures > 3) {
                // No way there (deep in a cave, across a ravine): forgotten, another pool or none
                bot.debug("can't get to the lava at {}; forgetting it", lava.toShortString());
                UNREACHABLE.computeIfAbsent(bot.getUUID(), id -> new java.util.HashSet<>()).add(lava);
                bot.memory().setLava(null);
                return Status.FAILURE;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        bot.controller().releaseInputs();
        bot.controller().lookAt(target);
        if (++ticks % THROW_INTERVAL != 0) {
            return Status.RUNNING;
        }
        Map<Integer, Integer> rubbish = Stash.disposable(bot);
        if (rubbish.isEmpty()) {
            bot.debug("burnt {} stacks of rubbish in the lava at {}", thrown, lava.toShortString());
            return Status.SUCCESS;
        }
        var first = rubbish.entrySet().iterator().next();
        ItemStack stack = bot.getInventory().getItem(first.getKey()).split(first.getValue());
        throwInto(stack, target);
        thrown++;
        return Status.RUNNING;
    }

    /** Throws the stack in a little arc so it lands in the lava. */
    private void throwInto(ItemStack stack, Vec3 target) {
        Vec3 from = bot.getEyePosition().subtract(0, 0.3, 0);
        ItemEntity item = new ItemEntity(bot.level(), from.x, from.y, from.z, stack);
        item.setThrower(bot);
        item.setPickUpDelay(40);
        Vec3 flat = new Vec3(target.x - from.x, 0, target.z - from.z);
        // About 16 ticks in the air; drag slows it, hence a bit more than distance / time
        item.setDeltaMovement(flat.scale(1.0 / 13.0).add(0, 0.25, 0));
        bot.level().addFreshEntity(item);
        bot.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    /** Not a pool's edge: no lava (of this pool or any other) within two blocks of where it stands. */
    private boolean noLavaNear(int x, int y, int z) {
        for (BlockPos near : BlockPos.betweenClosed(x - 2, y - 2, z - 2, x + 2, y + 1, z + 2)) {
            if (bot.level().getFluidState(near).is(FluidTags.LAVA)) {
                return false;
            }
        }
        return true;
    }

    /** A spot 2-4 blocks from the lava (not right at the edge), from where it can be seen. */
    private Goal throwingSpot(BlockPos lava) {
        Goal ring = new Goal() {
            @Override
            public boolean isReached(int x, int y, int z) {
                double dx = x - lava.getX();
                double dz = z - lava.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                return d >= 2.0 && d <= 4.0 && Math.abs(y - lava.getY()) <= 3 && noLavaNear(x, y, z);
            }

            @Override
            public double distance(int x, int y, int z) {
                double dx = x - lava.getX();
                double dy = y - lava.getY();
                double dz = z - lava.getZ();
                return Math.max(0.0, Math.sqrt(dx * dx + dy * dy + dz * dz) - 3.0);
            }

            @Override
            public String toString() {
                return "beside the lava at " + lava.toShortString();
            }
        };
        return Goal.inSight(bot.level(), ring, lava);
    }

    @Override
    public void stop() {
        bot.navigator().stop();
    }

    @Override
    public String describe() {
        return "burning rubbish in lava";
    }
}
