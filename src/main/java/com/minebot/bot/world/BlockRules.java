package com.minebot.bot.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What a bot may walk through, stand on and break.
 * Breaking is limited to natural terrain so bots never take apart buildings.
 */
public final class BlockRules {
    private BlockRules() {
    }

    public static boolean isWater(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER);
    }

    public static boolean isLava(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA);
    }

    /** Blocks that hurt, trap or slow a bot badly when it walks into them. */
    public static boolean isDangerous(BlockState state) {
        return isLava(state)
            || state.is(BlockTags.FIRE)
            || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.CACTUS)
            || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.WITHER_ROSE)
            || state.is(Blocks.POWDER_SNOW)
            || state.is(Blocks.COBWEB)
            || state.is(Blocks.POINTED_DRIPSTONE)
            || state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT);
    }

    /** Wooden doors and fence gates: passable once opened. */
    public static boolean isOpenable(BlockState state) {
        return state.is(BlockTags.WOODEN_DOORS) || state.is(BlockTags.FENCE_GATES);
    }

    /** The bot's body can occupy this block (air, plants, water, carpet...). */
    public static boolean isPassable(BlockGetter level, BlockPos pos, BlockState state) {
        if (isDangerous(state)) {
            return false;
        }
        if (isOpenable(state) && state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN)) {
            return true; // an open door is just a thin panel at the side
        }
        if (isClimbable(state) && !state.is(Blocks.SCAFFOLDING)) {
            return true; // a ladder or vines: a thin panel at the side, walked into and climbed
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        // Thin things like carpets and snow layers are simply stepped over
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.2;
    }

    /** The bot can stand on top of this block. */
    public static boolean isStandable(BlockGetter level, BlockPos pos, BlockState state) {
        if (isDangerous(state) || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES) || isClimbable(state)) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        double top = shape.max(Direction.Axis.Y);
        return top >= 0.5 && top <= 1.0;
    }

    public static boolean isClimbable(BlockState state) {
        return state.is(BlockTags.CLIMBABLE) && !state.is(Blocks.SCAFFOLDING);
    }

    /** Blocks that only appear in buildings: player houses, villages, other bots' huts. */
    /**
     * Which side of {@code pos} has a wall a ladder can go on, for climbing it from {@code pos}
     * up one block: the bot's own building (built blocks, not a cliff), with a solid face at
     * {@code pos} and the block above. Null if none.
     */
    public static @org.jetbrains.annotations.Nullable net.minecraft.core.Direction ladderWall(net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos wall = pos.relative(direction);
            BlockState low = level.getBlockState(wall);
            BlockState high = level.getBlockState(wall.above());
            if ((isBuilt(low) || isBuilt(high)) && low.isFaceSturdy(level, wall, direction.getOpposite())
                && high.isFaceSturdy(level, wall.above(), direction.getOpposite())) {
                return direction;
            }
        }
        return null;
    }

    public static boolean isBuilt(BlockState state) {
        return state.is(BlockTags.PLANKS) || state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS)
            || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.WALLS)
            || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.BEDS)
            || state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS) || state.is(BlockTags.IMPERMEABLE)
            || state.is(Blocks.COBBLESTONE) || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.BRICKS)
            || state.is(Blocks.GLASS_PANE) || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE)
            || state.is(Blocks.CHEST) || state.is(Blocks.BARREL) || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
            || state.is(Blocks.LANTERN) || state.is(Blocks.BOOKSHELF) || state.is(Blocks.HAY_BLOCK);
    }

    /** Natural terrain a bot is allowed to dig through. */
    public static boolean isNaturalTerrain(BlockState state) {
        if (state.is(BlockTags.LEAVES)) {
            return !state.hasProperty(LeavesBlock.PERSISTENT) || !state.getValue(LeavesBlock.PERSISTENT);
        }
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
            || state.is(BlockTags.DIRT)
            || state.is(BlockTags.SAND)
            || state.is(Blocks.GRAVEL)
            || state.is(Blocks.CLAY)
            || state.is(Blocks.SANDSTONE)
            || state.is(Blocks.RED_SANDSTONE)
            || state.is(Blocks.SNOW_BLOCK)
            || state.is(Blocks.SNOW)
            || state.is(Blocks.CALCITE)
            || state.is(Blocks.SMOOTH_BASALT)
            || state.is(BlockTags.BADLANDS_TERRACOTTA)
            || state.is(BlockTags.COAL_ORES)
            || state.is(BlockTags.IRON_ORES)
            || state.is(BlockTags.COPPER_ORES)
            || state.is(BlockTags.GOLD_ORES)
            || state.is(BlockTags.REDSTONE_ORES)
            || state.is(BlockTags.LAPIS_ORES)
            || state.is(BlockTags.DIAMOND_ORES)
            || state.is(BlockTags.EMERALD_ORES)
            || state.is(BlockTags.REPLACEABLE) && state.getFluidState().isEmpty(); // grass, flowers
    }

    /**
     * May this bot break the block? Natural terrain only, never inside a
     * protected area, and never next to fluids (no flooding tunnels with
     * water or lava).
     */
    public static boolean canBreak(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || !isNaturalTerrain(state) || state.hasBlockEntity()) {
            return false;
        }
        if (state.getDestroySpeed(level, pos) < 0) {
            return false;
        }
        if (touchesFluid(level, pos)) {
            return false;
        }
        return !ProtectedAreas.isProtected(level, pos);
    }

    /** A sand/gravel block above would fall into the gap. */
    public static boolean hasFallingBlockAbove(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos.above()).getBlock() instanceof FallingBlock;
    }

    private static boolean touchesFluid(BlockGetter level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (direction != Direction.DOWN && !level.getFluidState(pos.relative(direction)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** A log that belongs to a natural tree (has natural leaves nearby), not a log cabin. */
    public static boolean isNaturalTreeLog(BlockGetter level, BlockPos pos, BlockState state) {
        if (!state.is(BlockTags.LOGS)) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 8; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    BlockState leaves = level.getBlockState(cursor);
                    if (leaves.is(BlockTags.LEAVES) && leaves.hasProperty(LeavesBlock.PERSISTENT)
                        && !leaves.getValue(LeavesBlock.PERSISTENT)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
