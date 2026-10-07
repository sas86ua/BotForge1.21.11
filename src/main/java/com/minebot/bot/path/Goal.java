package com.minebot.bot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Where a path should end. Positions are the bot's feet block. */
public interface Goal {
    boolean isReached(int x, int y, int z);

    /** Estimated distance in blocks; used by A* to steer the search. */
    double distance(int x, int y, int z);

    default boolean isReached(BlockPos pos) {
        return isReached(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Out in the open: feet at the top of the ground (no rock above any more). */
    static Goal surface(net.minecraft.server.level.ServerLevel level) {
        return new Goal() {
            
            public boolean isReached(int x, int y, int z) {
                return y >= level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            }

            
            public double distance(int x, int y, int z) {
                return Math.max(0, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - y);
            }

            
            public String toString() {
                return "the surface";
            }
        };
    }

    /** Stand on exactly this block (feet position), or within {@code radius} of it. */
    /**
     * Next to an item on the ground. Lying on farmland, a bed, a path or a slab it is inside
     * that block (lower than a full one): the bot stands in the block above, not in the farmland.
     */
    static Goal atItem(net.minecraft.world.level.Level level, Vec3 item) {
        BlockPos at = BlockPos.containing(item);
        if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
            at = at.above();
        }
        return near(at, 0.9);
    }

    static Goal near(BlockPos target, double radius) {
        return new Goal() {
            @Override
            public boolean isReached(int x, int y, int z) {
                return distance(x, y, z) <= radius;
            }

            @Override
            public double distance(int x, int y, int z) {
                double dx = x - target.getX();
                double dy = y - target.getY();
                double dz = z - target.getZ();
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }

            @Override
            public String toString() {
                return "near " + target.toShortString();
            }
        };
    }

    /** Get close enough to interact with (mine, click) the block at {@code target}. */
    static Goal reach(BlockPos target) {
        Vec3 center = Vec3.atCenterOf(target);
        return new Goal() {
            @Override
            public boolean isReached(int x, int y, int z) {
                if (x == target.getX() && z == target.getZ() && (y == target.getY() || y + 1 == target.getY())) {
                    return false; // standing inside the block itself
                }
                double dx = x + 0.5 - center.x;
                double dy = y + 1.62 - center.y;
                double dz = z + 0.5 - center.z;
                return dx * dx + dy * dy + dz * dz <= 4.0 * 4.0;
            }

            @Override
            public double distance(int x, int y, int z) {
                double dx = x + 0.5 - center.x;
                double dy = y + 1.0 - center.y;
                double dz = z + 0.5 - center.z;
                return Math.max(0.0, Math.sqrt(dx * dx + dy * dy + dz * dz) - 3.5);
            }

            @Override
            public String toString() {
                return "reach " + target.toShortString();
            }
        };
    }

    /**
     * Like {@link #reach}, but the block must also be in plain sight: for using
     * chests, furnaces and tables, which a player can't open through a wall.
     * (Otherwise the bot happily digs a pit next to a house and reaches in
     * through the wall instead of walking in by the door.)
     */
    static Goal reachVisible(Level level, BlockPos target) {
        return inSight(level, reach(target), target);
    }

    /** {@code goal}, standing where {@code target} can be seen (not through a floor or wall). */
    static Goal inSight(Level level, Goal goal, BlockPos target) {
        return new Goal() {
            @Override
            public boolean isReached(int x, int y, int z) {
                if (!goal.isReached(x, y, z)) {
                    return false;
                }
                return canSee(level, new Vec3(x + 0.5, y + 1.62, z + 0.5), target);
            }

            @Override
            public double distance(int x, int y, int z) {
                return goal.distance(x, y, z);
            }

            @Override
            public String toString() {
                return goal + " (in sight)";
            }
        };
    }

    /**
     * Can a player with eyes here see (and so click) the block: its middle or the
     * middle of one of its faces? (A block of ground only shows its top face.)
     */
    static boolean canSee(Level level, Vec3 eye, BlockPos target) {
        Vec3 center = Vec3.atCenterOf(target);
        if (sees(level, eye, center, target)) {
            return true;
        }
        for (Direction face : Direction.values()) {
            Vec3 point = center.add(face.getStepX() * 0.45, face.getStepY() * 0.45, face.getStepZ() * 0.45);
            if (sees(level, eye, point, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sees(Level level, Vec3 eye, Vec3 point, BlockPos target) {
        BlockHitResult hit = level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    /** Get to this column, any height (long-distance travel). */
    static Goal column(int targetX, int targetZ, double radius) {
        return new Goal() {
            @Override
            public boolean isReached(int x, int y, int z) {
                return distance(x, y, z) <= radius;
            }

            @Override
            public double distance(int x, int y, int z) {
                double dx = x - targetX;
                double dz = z - targetZ;
                return Math.sqrt(dx * dx + dz * dz);
            }

            @Override
            public String toString() {
                return "column " + targetX + " " + targetZ;
            }
        };
    }
}
