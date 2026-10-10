package com.minebot.bot.action;

import com.minebot.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/** Places blocks by right-clicking a neighbouring block face, like a player. */
public final class BlockPlacer {
    private static final Direction[] SUPPORT_ORDER = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

    private BlockPlacer() {
    }

    /**
     * Places an item matching {@code item} at {@code target}.
     * @return true if a block now occupies {@code target}
     */
    public static boolean place(BotPlayer bot, BlockPos target, Predicate<ItemStack> item) {
        return place(bot, target, item, null);
    }

    /**
     * @param face direction from {@code target} to the neighbour to click
     *             (e.g. DOWN = put it on the floor), or null for any
     */
    public static boolean place(BotPlayer bot, BlockPos target, Predicate<ItemStack> item, @Nullable Direction face) {
        return place(bot, target, item, face, null);
    }

    /**
     * @param facing which way the bot looks while placing; decides how beds
     *               (head goes that way), doors, furnaces and chests are oriented
     */
    public static boolean place(BotPlayer bot, BlockPos target, Predicate<ItemStack> item, @Nullable Direction face,
                                @Nullable Direction facing) {
        ServerLevel level = bot.level();
        if (!level.getBlockState(target).canBeReplaced()) {
            return false;
        }
        BlockHitResult hit = findSupport(level, target, face, false);
        if (hit == null || !bot.isWithinBlockInteractionRange(hit.getBlockPos(), 0.5)) {
            return false;
        }
        if (!Inv.select(bot, item)) {
            return false;
        }
        if (bot.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem block
            && block.getBlock() instanceof net.minecraft.world.level.block.SlabBlock) {
            // (a slab clicked on the side of another slab joins it into a double one instead of going in the cell:
            // "did nothing", a dozen times over, and the wall beside it changed - Makena)
            BlockHitResult other = findSupport(level, target, face, true);
            if (other == null) {
                return false; // (nothing to put it against but slabs it would join: not placed, rather than a whole block made)
            }
            hit = other;
        }
        bot.controller().lookAt(hit.getLocation());
        if (facing != null) {
            bot.setYRot(facing.toYRot());
            bot.setYHeadRot(facing.toYRot());
        }
        // Sneak so clicking a chest/table/door places against it instead of using it (only then: put down
        // standing, a chest beside a single one facing the same way joins it into a double chest)
        boolean wasSneaking = bot.isShiftKeyDown();
        bot.setShiftKeyDown(isInteractive(level, hit.getBlockPos()));
        try {
            ItemStack stack = bot.getMainHandItem();
            var result = bot.gameMode.useItemOn(bot, level, stack, InteractionHand.MAIN_HAND, hit);
            if (level.getBlockState(target).canBeReplaced()) {
                bot.debug("placing {} at {} did nothing ({}, clicked {} {})", stack.getHoverName().getString(),
                    target.toShortString(), result, hit.getBlockPos().toShortString(), hit.getDirection());
            }
        } finally {
            bot.setShiftKeyDown(wasSneaking);
        }
        bot.swing(InteractionHand.MAIN_HAND);
        return !level.getBlockState(target).canBeReplaced();
    }

    /** Does clicking this block do something of its own (open, sleep, press)? */
    private static boolean isInteractive(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getMenuProvider(level, pos) != null || com.minebot.bot.world.BlockRules.isOpenable(state)
            || state.is(net.minecraft.tags.BlockTags.BEDS) || state.is(net.minecraft.tags.BlockTags.BUTTONS)
            || state.is(net.minecraft.world.level.block.Blocks.LEVER) || state.hasBlockEntity()
            || state.is(net.minecraft.tags.BlockTags.TRAPDOORS) || state.is(net.minecraft.world.level.block.Blocks.NOTE_BLOCK)
            || state.is(net.minecraft.world.level.block.Blocks.REPEATER) || state.is(net.minecraft.world.level.block.Blocks.COMPARATOR)
            || state.is(net.minecraft.world.level.block.Blocks.DAYLIGHT_DETECTOR) || state.is(net.minecraft.tags.BlockTags.CAULDRONS)
            || state.is(net.minecraft.tags.BlockTags.FLOWER_POTS);
    }

    /** A solid neighbour face to click, so the new block lands in {@code target}. */
    static @Nullable BlockHitResult findSupport(ServerLevel level, BlockPos target, @Nullable Direction face) {
        return findSupport(level, target, face, false);
    }

    /** As above; {@code forSlab}: not the side of another half-height slab (see place). */
    static @Nullable BlockHitResult findSupport(ServerLevel level, BlockPos target, @Nullable Direction face, boolean forSlab) {
        // (a plain block to click first: a trapdoor, door or button clicked opens or presses, not a place to put a block against)
        BlockHitResult plain = findSupport(level, target, face, forSlab, false);
        return plain != null ? plain : findSupport(level, target, face, forSlab, true);
    }

    private static @Nullable BlockHitResult findSupport(ServerLevel level, BlockPos target, @Nullable Direction face, boolean forSlab,
                                                       boolean anything) {
        for (Direction direction : SUPPORT_ORDER) {
            if (face != null && direction != face) {
                continue;
            }
            BlockPos neighbour = target.relative(direction);
            BlockState state = level.getBlockState(neighbour);
            if (state.canBeReplaced() || state.getCollisionShape(level, neighbour).isEmpty()) {
                continue;
            }
            if (!anything && isInteractive(level, neighbour)) {
                continue;
            }
            if (forSlab && state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock) {
                var type = state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE);
                var doubled = net.minecraft.world.level.block.state.properties.SlabType.DOUBLE;
                var bottom = net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
                // (on top of a bottom one, under a top one: the two join into a whole block, the cell stays empty;
                // on its side it's the height of the click that decides - see below)
                if (type != doubled && (direction == Direction.DOWN && type == bottom || direction == Direction.UP && type != bottom)) {
                    continue;
                }
            }
            Direction clickedFace = direction.getOpposite();
            Vec3 location = Vec3.atCenterOf(neighbour).add(Vec3.atLowerCornerOf(clickedFace.getUnitVec3i()).scale(0.5));
            if (forSlab && direction.getAxis().isHorizontal() && state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock) {
                // (a click on the side of a slab joins it unless it's on the other half: bottom one - lower half, top one - upper;
                // the new slab lies the same way, so a floor goes on in the row of the slabs already there)
                var type = state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE);
                if (type == net.minecraft.world.level.block.state.properties.SlabType.TOP) {
                    location = location.add(0, 0.4, 0);
                } else if (type == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM) {
                    location = location.add(0, -0.4, 0);
                } // (a double slab is a whole block: the middle of its face is fine)
            }
            return new BlockHitResult(location, clickedFace, neighbour, false);
        }
        return null;
    }

    /** Can something be placed at target at all (free spot with a neighbour to click)? */
    public static boolean canPlaceAt(ServerLevel level, BlockPos target) {
        return level.getBlockState(target).canBeReplaced() && findSupport(level, target, null) != null;
    }
}
