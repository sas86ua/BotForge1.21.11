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

    /**
     * What a block state is, worked out once and kept: the path search asks these of every block it looks at, each
     * answer was a handful of tag lookups, and together they were half the time a search took.
     */
    private static final java.util.Map<BlockState, Integer> FLAGS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int WATER = 0x1;
    private static final int LAVA = 0x2;
    private static final int DANGEROUS = 0x4;
    private static final int OPENABLE = 0x8;
    private static final int IRON_DOOR = 0x10;
    private static final int CLIMBABLE = 0x20;
    private static final int DOOR = 0x40;
    private static final int GATE = 0x80;
    /** Lava or fire. */
    private static final int HOT = 0x100;
    /** A door or gate standing open. */
    private static final int OPEN = 0x200;
    /** Nothing to stand on whatever its shape: fences, walls, gates, ladders, what hurts. */
    private static final int NO_STANDING = 0x400;
    /** Its shape is the same wherever it stands: the two answers below hold for the state. */
    private static final int FIXED_SHAPE = 0x800;
    private static final int PASSABLE = 0x1000;
    private static final int STANDABLE = 0x2000;

    private static int flags(BlockState state) {
        Integer known = FLAGS.get(state);
        if (known == null) {
            known = computeFlags(state);
            FLAGS.put(state, known);
        }
        return known;
    }

    private static int computeFlags(BlockState state) {
        boolean water = state.getFluidState().is(FluidTags.WATER);
        boolean lava = state.getFluidState().is(FluidTags.LAVA);
        boolean fire = state.is(BlockTags.FIRE);
        boolean dangerous = lava
            || fire
            || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.CACTUS)
            || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.WITHER_ROSE)
            || state.is(Blocks.POWDER_SNOW)
            || state.is(Blocks.COBWEB)
            || state.is(Blocks.POINTED_DRIPSTONE)
            || state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT);
        boolean gate = state.is(BlockTags.FENCE_GATES);
        boolean openable = state.is(BlockTags.WOODEN_DOORS) || gate;
        boolean ironDoor = state.is(Blocks.IRON_DOOR);
        boolean climbable = state.is(BlockTags.CLIMBABLE) && !state.is(Blocks.SCAFFOLDING);
        boolean open = (openable || ironDoor) && state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
        boolean noStanding = dangerous || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || gate || climbable;
        int flags = (water ? WATER : 0) | (lava ? LAVA : 0) | (dangerous ? DANGEROUS : 0) | (openable ? OPENABLE : 0)
            | (ironDoor ? IRON_DOOR : 0) | (climbable ? CLIMBABLE : 0) | (state.is(BlockTags.DOORS) ? DOOR : 0) | (gate ? GATE : 0)
            | (lava || fire ? HOT : 0) | (open ? OPEN : 0) | (noStanding ? NO_STANDING : 0);
        if (!state.getBlock().hasDynamicShape()) {
            // (the shape the game itself keeps for the state, and gives for any place)
            VoxelShape shape = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            flags |= FIXED_SHAPE;
            if (!dangerous && (open || climbable || steppedOver(shape))) {
                flags |= PASSABLE;
            }
            if (!noStanding && stoodOn(shape)) {
                flags |= STANDABLE;
            }
        }
        return flags;
    }

    /** Thin things like carpets and snow layers are simply stepped over. */
    private static boolean steppedOver(VoxelShape shape) {
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.2;
    }

    private static boolean stoodOn(VoxelShape shape) {
        if (shape.isEmpty()) {
            return false;
        }
        double top = shape.max(Direction.Axis.Y);
        return top >= 0.5 && top <= 1.0;
    }

    public static boolean isWater(BlockState state) {
        return (flags(state) & WATER) != 0;
    }

    public static boolean isLava(BlockState state) {
        return (flags(state) & LAVA) != 0;
    }

    /** Lava or fire. */
    public static boolean isHot(BlockState state) {
        return (flags(state) & HOT) != 0;
    }

    /** Blocks that hurt, trap or slow a bot badly when it walks into them. */
    public static boolean isDangerous(BlockState state) {
        return (flags(state) & DANGEROUS) != 0;
    }

    /** Wooden doors and fence gates: passable once opened. */
    public static boolean isOpenable(BlockState state) {
        return (flags(state) & OPENABLE) != 0;
    }

    /**
     * Opened by a bot here: wooden doors and gates anywhere, and iron doors on the site of the Great Build - no button or
     * plate wired to them while it is built, and the bots that worked in those rooms were shut in. Elsewhere an iron door
     * is the lock of whoever put it there, and stays one.
     */
    public static boolean isOpenable(BlockState state, BlockPos pos) {
        int flags = flags(state);
        return (flags & OPENABLE) != 0 || (flags & IRON_DOOR) != 0 && com.minebot.bot.build.GreatBuild.nearSite(pos);
    }

    public static boolean isIronDoor(BlockState state) {
        return (flags(state) & IRON_DOOR) != 0;
    }

    /** A door of any kind (wood, iron, copper). */
    public static boolean isDoor(BlockState state) {
        return (flags(state) & DOOR) != 0;
    }

    public static boolean isGate(BlockState state) {
        return (flags(state) & GATE) != 0;
    }

    /** The body of the bot can occupy this block (air, plants, water, carpet...). */
    public static boolean isPassable(BlockGetter level, BlockPos pos, BlockState state) {
        int flags = flags(state);
        if ((flags & FIXED_SHAPE) != 0) {
            return (flags & PASSABLE) != 0;
        }
        if ((flags & DANGEROUS) != 0) {
            return false;
        }
        // An open door is just a thin panel at the side; a ladder or vines too, walked into and climbed
        return (flags & (OPEN | CLIMBABLE)) != 0 || steppedOver(state.getCollisionShape(level, pos));
    }

    /** The bot can stand on top of this block. */
    public static boolean isStandable(BlockGetter level, BlockPos pos, BlockState state) {
        int flags = flags(state);
        if ((flags & FIXED_SHAPE) != 0) {
            return (flags & STANDABLE) != 0;
        }
        return (flags & NO_STANDING) == 0 && stoodOn(state.getCollisionShape(level, pos));
    }

    public static boolean isClimbable(BlockState state) {
        return (flags(state) & CLIMBABLE) != 0;
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

    /**
     * Answers by block state (the path search asks these for every block it looks at, a dozen bots
     * at once: two dozen tag lookups each time made a good part of the server's lag).
     */
    private static final java.util.Map<BlockState, Boolean> BUILT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<BlockState, Boolean> NATURAL = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean isBuilt(BlockState state) {
        Boolean known = BUILT.get(state);
        if (known == null) {
            known = computeBuilt(state);
            BUILT.put(state, known);
        }
        return known;
    }

    private static boolean computeBuilt(BlockState state) {
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
        Boolean known = NATURAL.get(state);
        if (known == null) {
            known = computeNatural(state);
            NATURAL.put(state, known);
        }
        return known;
    }

    private static boolean computeNatural(BlockState state) {
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
