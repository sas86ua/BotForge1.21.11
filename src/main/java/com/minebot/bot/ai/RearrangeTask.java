package com.minebot.bot.ai;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.build.HouseLayouts;
import com.minebot.bot.build.HousePlan;
import com.minebot.bot.build.HousePlans;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A house of a design with a layout ({@link HouseLayouts}): its furniture moved to the layout's places, one piece at a
 * time - a chest or furnace with all that's in it (picked up whole and set down whole: nothing spilt), a crafting table.
 * Its own chests, furnace and table from the old hut come in too, while there's a place for them.
 */
public class RearrangeTask extends Task {
    private static final List<Block> FURNITURE = List.of(Blocks.CHEST, Blocks.BARREL, Blocks.FURNACE, Blocks.SMOKER,
        Blocks.BLAST_FURNACE, Blocks.CRAFTING_TABLE);
    /** Its own furniture this far from the house is brought in too. */
    private static final int OWN_RANGE = 64;
    private static final int MAX_TICKS = 20 * 90;

    private record Move(BlockPos from, HouseLayouts.Spot to) {
    }

    private final @Nullable Move move;
    /** What it's carrying: the block and what was in it, once picked up. */
    private @Nullable BlockState carried;
    private @Nullable List<ItemStack> contents;
    private int ticks;
    private boolean walking;

    public RearrangeTask(BotPlayer bot) {
        super(bot);
        this.move = nextMove(bot);
    }

    /** Switched on by an admin (/bot house rearrange on), once the layout is seen to fit. */
    public static volatile boolean enabled;

    public static boolean wanted(BotPlayer bot) {
        BotMemory memory = bot.memory();
        return enabled && memory.autonomous() && memory.houseDone() && memory.houseOrigin() != null && !Home.isNight(bot)
            && !GreatBuildTask.isAway(bot) && !GreatBuildTask.sessionOn(bot) && Home.isNear(bot, 48)
            && bot.every("rearrange", 20 * 60, () -> nextMove(bot) != null);
    }

    /** The next piece to move: to a free place of the layout, the nearest piece of that kind standing anywhere else. */
    private static @Nullable Move nextMove(BotPlayer bot) {
        ServerLevel level = bot.level();
        HousePlan plan = HousePlans.of(bot.memory());
        if (!HouseLayouts.has(level.getServer(), plan)) {
            return null;
        }
        List<HouseLayouts.Spot> spots = HouseLayouts.spots(level.getServer(), (com.minebot.bot.build.SchematicHouse) plan);
        Set<BlockPos> placed = new HashSet<>();
        for (HouseLayouts.Spot spot : spots) {
            if (level.isLoaded(spot.pos()) && level.getBlockState(spot.pos()).is(spot.block())) {
                placed.add(spot.pos());
            }
        }
        // What stands elsewhere: in the house (not where the layout has it), and its own pieces round about (the hut's)
        List<BlockPos> loose = new ArrayList<>();
        BlockPos o = plan.origin();
        for (BlockPos pos : BlockPos.betweenClosed(o.offset(0, -1, 0), o.offset(plan.sizeX() - 1, plan.topY() - o.getY(), plan.sizeZ() - 1))) {
            if (level.isLoaded(pos) && FURNITURE.contains(level.getBlockState(pos).getBlock()) && !placed.contains(pos)) {
                loose.add(pos.immutable());
            }
        }
        BotMemory memory = bot.memory();
        List<BlockPos> own = new ArrayList<>(memory.chests());
        if (memory.furnace() != null) {
            own.add(memory.furnace());
        }
        if (memory.craftingTable() != null) {
            own.add(memory.craftingTable());
        }
        for (BlockPos pos : own) {
            if (!loose.contains(pos) && !placed.contains(pos) && !memory.borrowed().contains(pos) && pos.closerThan(o, OWN_RANGE)
                && level.isLoaded(pos) && FURNITURE.contains(level.getBlockState(pos).getBlock())) {
                loose.add(pos);
            }
        }
        for (HouseLayouts.Spot spot : spots) {
            if (placed.contains(spot.pos()) || !level.isLoaded(spot.pos()) || !level.getBlockState(spot.pos()).canBeReplaced()
                || level.getBlockState(spot.pos().below()).canBeReplaced()) {
                continue;
            }
            BlockPos best = null;
            for (BlockPos pos : loose) {
                if (level.getBlockState(pos).is(spot.block()) && (best == null || pos.distSqr(spot.pos()) < best.distSqr(spot.pos()))) {
                    best = pos;
                }
            }
            if (best != null) {
                return new Move(best, spot);
            }
        }
        return null;
    }

    @Override
    public Status tick() {
        if (move == null) {
            return Status.SUCCESS;
        }
        ServerLevel level = bot.level();
        if (++ticks > MAX_TICKS) {
            putBack();
            return Status.FAILURE;
        }
        BlockPos target = carried == null ? move.from() : move.to().pos();
        if (!bot.isWithinBlockInteractionRange(target, 0.5)) {
            if (!walking || !bot.navigator().isActive()) {
                walking = true;
                bot.navigator().navigate(Goal.reach(target));
            }
            if (bot.navigator().tick() == Navigator.Status.FAILED) {
                putBack();
                return Status.FAILURE;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        walking = false;
        bot.controller().lookAt(net.minecraft.world.phys.Vec3.atCenterOf(target));
        bot.swing(InteractionHand.MAIN_HAND);
        if (carried == null) {
            // Picked up whole: what's in it first (so nothing spills), then the block
            BlockState state = level.getBlockState(move.from());
            if (!state.is(move.to().block())) {
                return Status.FAILURE; // (gone or changed meanwhile)
            }
            contents = new ArrayList<>();
            if (level.getBlockEntity(move.from()) instanceof Container box) {
                for (int i = 0; i < box.getContainerSize(); i++) {
                    contents.add(box.getItem(i).copy());
                }
                box.clearContent();
            }
            carried = state;
            level.removeBlock(move.from(), false);
            bot.debug("rearranging: taking the {} from {} to {}", state.getBlock().getName().getString(), move.from().toShortString(),
                move.to().pos().toShortString());
            return Status.RUNNING;
        }
        // Set down whole, facing the way the layout has it
        BlockPos to = move.to().pos();
        if (!level.getBlockState(to).canBeReplaced()) {
            putBack();
            return Status.FAILURE;
        }
        BlockState state = carried.getBlock().defaultBlockState();
        Direction facing = move.to().facing();
        if (facing != null && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        }
        if (state.hasProperty(ChestBlock.TYPE)) {
            state = state.setValue(ChestBlock.TYPE, pairType(level, to, state));
        }
        level.setBlock(to, state, 3);
        if (level.getBlockEntity(to) instanceof Container box && contents != null) {
            for (int i = 0; i < Math.min(box.getContainerSize(), contents.size()); i++) {
                box.setItem(i, contents.get(i));
            }
            box.setChanged();
        }
        remember(move.from(), to, carried.getBlock());
        carried = null;
        contents = null;
        return Status.SUCCESS;
    }

    /** A chest set down beside a single one of its facing joins it into a double chest, as one put down by hand. */
    private static ChestType pairType(ServerLevel level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(ChestBlock.FACING);
        for (Direction side : new Direction[] {facing.getClockWise(), facing.getCounterClockWise()}) {
            BlockState other = level.getBlockState(pos.relative(side));
            if (other.is(Blocks.CHEST) && other.getValue(ChestBlock.FACING) == facing && other.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
                return side == facing.getClockWise() ? ChestType.LEFT : ChestType.RIGHT;
            }
        }
        return ChestType.SINGLE;
    }

    private void remember(BlockPos from, BlockPos to, Block block) {
        BotMemory memory = bot.memory();
        if (memory.chests().contains(from)) {
            memory.removeChest(from);
        }
        if ((block == Blocks.CHEST || block == Blocks.BARREL) && !memory.chests().contains(to)) {
            memory.addChest(to);
        }
        if (from.equals(memory.furnace()) || block == Blocks.FURNACE && memory.furnace() == null) {
            memory.setFurnace(to);
        }
        if (from.equals(memory.craftingTable()) || block == Blocks.CRAFTING_TABLE && memory.craftingTable() == null) {
            memory.setCraftingTable(to);
        }
    }

    /** Cut short while carrying it: back where it was (or, that taken meanwhile, its things dropped there). */
    private void putBack() {
        if (carried == null || move == null) {
            return;
        }
        ServerLevel level = bot.level();
        BlockPos from = move.from();
        if (level.getBlockState(from).canBeReplaced()) {
            level.setBlock(from, carried, 3);
            if (level.getBlockEntity(from) instanceof Container box && contents != null) {
                for (int i = 0; i < Math.min(box.getContainerSize(), contents.size()); i++) {
                    box.setItem(i, contents.get(i));
                }
                box.setChanged();
            }
        } else {
            List<ItemStack> drop = new ArrayList<>(contents != null ? contents : List.of());
            drop.add(new ItemStack(carried.getBlock().asItem()));
            for (ItemStack stack : drop) {
                if (!stack.isEmpty()) {
                    level.addFreshEntity(new ItemEntity(level, bot.getX(), bot.getY() + 0.5, bot.getZ(), stack));
                }
            }
        }
        carried = null;
        contents = null;
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        putBack();
    }

    @Override
    public String describe() {
        return move == null ? "rearranging the house" : "rearranging the house: the " + move.to().block().getName().getString()
            + (carried == null ? " at " + move.from().toShortString() : " to " + move.to().pos().toShortString());
    }
}
