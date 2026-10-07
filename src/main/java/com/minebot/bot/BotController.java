package com.minebot.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Low-level movement inputs for a bot: which way it faces, whether it walks,
 * sprints and jumps. Higher layers (navigation, combat) decide where to go.
 */
public class BotController {
    private static final double SPRINT_DISTANCE = 6.0;

    private final BotPlayer bot;

    public BotController(BotPlayer bot) {
        this.bot = bot;
    }

    /**
     * Walks straight at {@code dest}, hopping over single-block steps. Used for
     * short, open-ground moves such as closing in on a mob.
     * @return true (and stands still) once within {@code stopDistance} horizontally
     */
    public boolean steer(Vec3 dest, double stopDistance) {
        double distance = horizontalDistance(dest);
        if (distance <= stopDistance) {
            releaseInputs();
            return true;
        }
        face(dest);
        bot.zza = 1.0F;
        bot.xxa = 0.0F;
        bot.setSprinting(distance > SPRINT_DISTANCE && !bot.isInWater() && !bot.isUsingItem());
        bot.setJumping(shouldJump());
        return false;
    }

    /** Walks towards {@code dest} this tick, slowing down for precise stops. */
    public void moveTowards(Vec3 dest, boolean sprint, boolean jump) {
        double distance = horizontalDistance(dest);
        face(dest);
        bot.zza = distance < 0.5 ? (float) Math.max(0.25, distance) : 1.0F;
        bot.xxa = 0.0F;
        bot.setSprinting(sprint && !bot.isInWater());
        bot.setJumping(jump);
    }

    /** Stays centred on a block (small corrections only), e.g. while pillaring. */
    public void hold(Vec3 center) {
        if (horizontalDistance(center) > 0.2) {
            float pitch = bot.getXRot();
            moveTowards(center, false, false);
            bot.setXRot(pitch);
        } else {
            releaseInputs();
        }
    }

    /** Turns head and body to look at a point (e.g. an enemy's eyes, a block). */
    public void lookAt(Vec3 point) {
        Vec3 eyes = bot.getEyePosition();
        double dx = point.x - eyes.x;
        double dy = point.y - eyes.y;
        double dz = point.z - eyes.z;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
        setYaw(yaw);
        bot.setXRot(pitch);
    }

    public void releaseInputs() {
        bot.zza = 0.0F;
        bot.xxa = 0.0F;
        bot.setJumping(false);
        bot.setSprinting(false);
    }

    private void face(Vec3 dest) {
        setYaw((float) (Mth.atan2(dest.z - bot.getZ(), dest.x - bot.getX()) * Mth.RAD_TO_DEG) - 90.0F);
        // Head up while walking, eyes on the way ahead (not stuck looking down after placing or digging)
        double distance = horizontalDistance(dest);
        float ahead = distance > 1.0 ? (float) Mth.clamp(-Mth.atan2(dest.y + 1.2 - bot.getEyeY(), distance) * Mth.RAD_TO_DEG, -25.0, 25.0) : 10.0F;
        bot.setXRot(bot.getXRot() + (ahead - bot.getXRot()) * 0.25F);
    }

    /** A glance about, as a player standing around does. */
    public void glance() {
        setYaw(bot.getYRot() + (bot.getRandom().nextFloat() - 0.5F) * 120.0F);
        bot.setXRot(-10.0F + bot.getRandom().nextFloat() * 25.0F);
    }

    private void setYaw(float yaw) {
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        bot.yBodyRot = yaw;
    }

    private double horizontalDistance(Vec3 dest) {
        double dx = dest.x - bot.getX();
        double dz = dest.z - bot.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean shouldJump() {
        if (bot.isInWater() || bot.isInLava()) {
            return true; // swim up instead of sinking
        }
        if (!bot.onGround()) {
            return false;
        }
        if (bot.horizontalCollision) {
            return true;
        }
        // Jump a little before touching a step so we don't stall against it
        double rad = bot.getYRot() * Mth.DEG_TO_RAD;
        Vec3 ahead = bot.position().add(-Math.sin(rad) * 0.6, 0, Math.cos(rad) * 0.6);
        BlockPos feet = BlockPos.containing(ahead);
        return isSolid(feet) && !isSolid(feet.above()) && !isSolid(feet.above(2));
    }

    private boolean isSolid(BlockPos pos) {
        BlockState state = bot.level().getBlockState(pos);
        return !state.getCollisionShape(bot.level(), pos).isEmpty();
    }
}
