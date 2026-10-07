package com.minebot.bot;

import com.minebot.bot.action.Inv;
import com.minebot.bot.path.Goal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import com.minebot.bot.ai.Ranged;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fights hostile mobs that come close, and anything (players included) that
 * attacks the bot. Melee with sword/axe, bow at range, shield while the
 * melee attack recharges.
 */
public class BotCombat {
    private static final double HOSTILE_SCAN_RANGE = 12.0;
    private static final double CHASE_RANGE = 24.0;
    private static final long AGGRESSOR_MEMORY_TICKS = 30 * 20;
    private static final double MELEE_REACH = 2.9;
    private static final double MELEE_APPROACH_DISTANCE = 1.5;
    private static final double BOW_MIN_RANGE = 6.0;
    private static final double BOW_STAND_RANGE = 16.0;
    private static final int BOW_DRAW_TICKS = 20;
    private static final double ARROW_SPEED = 3.0;
    private static final double ARROW_GRAVITY = 0.05;
    private static final double CREEPER_FLEE_RANGE = 6.0;
    private static final int GIVE_UP_TICKS = 100;
    private static final long IGNORE_TICKS = 10 * 20;
    private static final int RESCAN_INTERVAL = 10;
    /** Guarding a player: monsters this close to them get fought. */
    private static final double GUARD_RANGE = 10.0;
    /** That player hitting the bot this many times in this many ticks is no accident. */
    private static final int FRIENDLY_FIRE_HITS = 3;
    private static final long FRIENDLY_FIRE_WINDOW = 10 * 20;
    /** Stuck this long below an enemy (on a tower, a ledge): find a way up with the path finder. */
    private static final int CLIMB_AFTER_TICKS = 20;
    /** Against people: a moment to react before hitting back, so a duel isn't hopeless. */
    private static final int MIN_REACTION_TICKS = 2;
    private static final int MAX_REACTION_TICKS = 7;
    /** Against people: how often the bot raises its shield while its attack recharges. */
    private static final float BLOCK_CHANCE = 0.55F;

    private final BotPlayer bot;

    private @Nullable LivingEntity aggressor;
    private long aggressorUntil;
    private @Nullable LivingEntity target;
    private int rescanIn;
    private double bestDistance;
    private int ticksWithoutProgress;
    /** Combat raised the shield or drew the bow (as opposed to, say, eating). */
    private boolean usingItem;
    /** Targets we couldn't reach, so we don't keep running into the same wall. */
    private final Map<UUID, Long> ignoredUntil = new HashMap<>();
    /** Chasing the enemy with the path finder (pillaring up if need be) instead of straight at it. */
    private boolean climbing;
    private @Nullable Player protectee;
    private final java.util.List<Long> protecteeHits = new java.util.ArrayList<>();
    private int repathIn;
    /** Against people: when the next hit may land, and whether / when to block after it. */
    private long strikeAt = -1;
    private boolean blockThisRound = true;
    private long blockAt;

    public BotCombat(BotPlayer bot) {
        this.bot = bot;
    }

    public @Nullable LivingEntity target() {
        return target;
    }

    /** The player the bot keeps company ("/bot come"): it guards them and forgives a stray hit. */
    public void setProtectee(@Nullable Player player) {
        protectee = player;
        protecteeHits.clear();
    }

    void onAttacked(LivingEntity attacker) {
        if (attacker == bot) {
            return;
        }
        if (attacker instanceof BotPlayer other && !other.combat().isTargeting(bot)) {
            return; // no infighting between bots over a sword sweep hitting a neighbour; but one out to get it is fought
        }
        if (attacker instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return;
        }
        if (attacker == protectee) {
            // Fighting side by side, a swing can land on the bot by mistake: only hit back if it keeps happening
            long now = now();
            protecteeHits.removeIf(time -> now - time > FRIENDLY_FIRE_WINDOW);
            protecteeHits.add(now);
            if (protecteeHits.size() < FRIENDLY_FIRE_HITS) {
                bot.debug("{} hit me, probably by accident", attacker.getName().getString());
                return;
            }
            bot.debug("{} keeps hitting me: fighting back", attacker.getName().getString());
        }
        aggressor = attacker;
        aggressorUntil = now() + AGGRESSOR_MEMORY_TICKS;
        ignoredUntil.remove(attacker.getUUID());
        rescanIn = 0;
    }

    /** Is it fighting this one (on purpose, not just catching it in a sweep)? */
    public boolean isTargeting(LivingEntity entity) {
        return target == entity;
    }

    /** An admin's order ("/bot kill"): treat this one as an attacker for the next {@code ticks}. */
    public void order(LivingEntity enemy, int ticks) {
        if (aggressor != enemy) {
            rescanIn = 0;
        }
        aggressor = enemy;
        aggressorUntil = now() + ticks;
        ignoredUntil.remove(enemy.getUUID());
    }

    void reset() {
        aggressor = null;
        setTarget(null);
        stopUsingItem();
        stopClimbing();
    }

    /** Done chasing by path: hand the navigator back, and remember the pillar to take it down. */
    private void stopClimbing() {
        if (!climbing) {
            return;
        }
        climbing = false;
        bot.navigator().stop();
        bot.pillars().addAll(bot.navigator().stopPillarLog());
        bot.brain().restartTask();
    }

    /** @return true while fighting; movement inputs then belong to combat */
    boolean tick() {
        if (--rescanIn <= 0 || target == null || !isValidTarget(target, CHASE_RANGE)) {
            rescanIn = RESCAN_INTERVAL;
            LivingEntity picked = pickTarget();
            if (picked == null && target != null && isValidTarget(target, CHASE_RANGE)
                && !ignoredUntil.containsKey(target.getUUID())) {
                picked = target; // out of sight for a moment (behind a tower, a corner): keep at it
            }
            setTarget(picked);
        }
        if (target == null) {
            stopUsingItem();
            stopClimbing();
            return false;
        }
        fight(target);
        return true;
    }

    private @Nullable LivingEntity pickTarget() {
        if (aggressor != null) {
            if (now() < aggressorUntil && isValidTarget(aggressor, CHASE_RANGE)) {
                return aggressor;
            }
            aggressor = null;
        }
        long now = now();
        ignoredUntil.values().removeIf(until -> until <= now);

        ServerLevel level = bot.level();
        LivingEntity nearest = level.getEntitiesOfClass(Mob.class, bot.getBoundingBox().inflate(HOSTILE_SCAN_RANGE),
                mob -> isHostile(mob) && isValidTarget(mob, HOSTILE_SCAN_RANGE)
                    && !ignoredUntil.containsKey(mob.getUUID()) && bot.hasLineOfSight(mob))
            .stream()
            .min(Comparator.comparingDouble(bot::distanceToSqr))
            .orElse(null);
        if (nearest == null && protectee != null && protectee.isAlive() && protectee.level() == level) {
            // Guarding a player: monsters near them, especially ones after them
            Player guarded = protectee;
            nearest = level.getEntitiesOfClass(Mob.class, guarded.getBoundingBox().inflate(GUARD_RANGE),
                    mob -> (mob instanceof Enemy && mob.getTarget() == guarded || isHostile(mob))
                        && isValidTarget(mob, CHASE_RANGE) && !ignoredUntil.containsKey(mob.getUUID()))
                .stream()
                .min(Comparator.comparingDouble(guarded::distanceToSqr))
                .orElse(null);
        }
        return nearest;
    }

    private boolean isHostile(Mob mob) {
        if (!(mob instanceof Enemy)) {
            return false;
        }
        // Endermen, zombified piglins etc. are only a threat once angry
        if (mob instanceof NeutralMob neutral) {
            return mob.getTarget() == bot || neutral.isAngryAt(bot, bot.level());
        }
        return true;
    }

    private boolean isValidTarget(LivingEntity entity, double range) {
        if (!entity.isAlive() || entity.level() != bot.level() || bot.distanceToSqr(entity) > range * range) {
            return false;
        }
        return !(entity instanceof Player player) || (!player.isCreative() && !player.isSpectator());
    }

    private void setTarget(@Nullable LivingEntity newTarget) {
        if (newTarget != target) {
            target = newTarget;
            if (newTarget != null) {
                bot.debug("fighting {} at {}", newTarget.getName().getString(), newTarget.blockPosition().toShortString());
            }
            bestDistance = Double.MAX_VALUE;
            ticksWithoutProgress = 0;
            strikeAt = -1;
            blockThisRound = true;
        }
    }

    /** A real person (not a bot, not a mob): fights them with human-like timing. */
    private static boolean isPerson(LivingEntity entity) {
        return entity instanceof Player && !(entity instanceof BotPlayer);
    }

    private void fight(LivingEntity enemy) {
        if (bot.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat boat) {
            fightFromBoat(enemy, boat);
            return;
        }
        double distance = bot.distanceTo(enemy);

        if (enemy instanceof Creeper creeper && creeper.getSwellDir() > 0 && distance < CREEPER_FLEE_RANGE) {
            stopUsingItem();
            Vec3 away = bot.position().subtract(creeper.position()).normalize().scale(4.0);
            bot.controller().steer(bot.position().add(away), 0.0);
            return;
        }

        int bowSlot = bestRangedSlot();
        ItemStack ranged = bowSlot >= 0 ? bot.getInventory().getItem(bowSlot) : ItemStack.EMPTY;
        boolean canShoot = bowSlot >= 0 && (CrossbowItem.isCharged(ranged) || !bot.getProjectile(ranged).isEmpty());
        int meleeSlot = bestMeleeSlot();

        boolean preferBow = distance > BOW_MIN_RANGE || enemy instanceof Creeper && distance > 3.0;
        if (canShoot && (preferBow || meleeSlot < 0)) {
            shoot(enemy, bowSlot, distance);
        } else {
            melee(enemy, meleeSlot, distance);
        }
    }

    /** Rowing speed towards an enemy (blocks a tick), as when the navigator rows. */
    private static final double BOAT_SPEED = 0.35;
    /** Get out of the boat this close to the enemy (on the bank by now). */
    private static final double BOAT_LANDING = 3.0;

    /**
     * Attacked while in a boat (pillagers shooting from the bank): rows over to them, gets out
     * where the boat runs aground (taking the boat along) and fights on land. Afterwards the task
     * it was on plans its way again, back in the boat if there's water to cross.
     */
    private void fightFromBoat(LivingEntity enemy, net.minecraft.world.entity.vehicle.boat.AbstractBoat boat) {
        stopUsingItem();
        bot.controller().lookAt(enemy.getEyePosition());
        double dx = enemy.getX() - boat.getX();
        double dz = enemy.getZ() - boat.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < BOAT_LANDING || !boat.isInWater()) {
            getOutOfBoat(boat);
            return;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        boat.setYRot(yaw);
        double speed = Math.min(BOAT_SPEED, flat);
        boat.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(dx / flat * speed, 0, dz / flat * speed));
        if (boat.horizontalCollision) {
            getOutOfBoat(boat); // aground on the bank
        }
    }

    private void getOutOfBoat(net.minecraft.world.entity.vehicle.boat.AbstractBoat boat) {
        bot.debug("getting out of the boat to fight");
        bot.stopRiding();
        // The boat comes along (as the navigator does it), if there's room for it
        if (boat.isAlive() && boat.getPassengers().isEmpty() && Inv.freeSlots(bot) > 0
            && !(boat instanceof net.minecraft.world.entity.vehicle.boat.AbstractChestBoat)) {
            ItemStack item = boat.getPickResult();
            boat.discard();
            if (item != null && !item.isEmpty()) {
                Inv.give(bot, item);
            }
        }
    }

    private void melee(LivingEntity enemy, int meleeSlot, double distance) {
        if (bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            stopUsingItem(); // was drawing the bow
        }
        if (meleeSlot >= 0) {
            bot.getInventory().setSelectedSlot(meleeSlot);
        }

        boolean above = enemy.getY() - bot.getY() > 1.2;
        if (climbing || above && ticksWithoutProgress > CLIMB_AFTER_TICKS) {
            // Straight at it doesn't work (it's up on a tower or a ledge): find a way, or pillar up
            chaseByPath(enemy);
        } else {
            bot.controller().steer(enemy.position(), MELEE_APPROACH_DISTANCE);
        }
        if (!strike(enemy)) {
            trackProgress(distance);
            return;
        }
        ticksWithoutProgress = 0;
        if (bot.getAttackStrengthScale(0.5F) < 1.0F && !bot.isUsingItem() && bot.getOffhandItem().is(Items.SHIELD)
            && blockThisRound && now() >= blockAt) {
            bot.startUsingItem(InteractionHand.OFF_HAND);
            usingItem = true;
        }
    }

    private void chaseByPath(LivingEntity enemy) {
        if (!climbing) {
            climbing = true;
            bot.debug("can't get at {} directly, looking for a way up", enemy.getName().getString());
            bot.navigator().startPillarLog();
            repathIn = 0;
        }
        if (--repathIn <= 0 || !bot.navigator().isActive()) {
            repathIn = 20; // the enemy moves; aim again now and then
            bot.navigator().navigate(Goal.near(enemy.blockPosition(), 1.5));
        }
        bot.navigator().tick();
    }

    /**
     * Looks at the entity and hits it if it is in reach and the attack has
     * recharged (full-damage hits only). Movement is up to the caller.
     * @return true if the entity is within reach
     */
    public boolean strike(LivingEntity enemy) {
        bot.controller().lookAt(enemy.getBoundingBox().getCenter());
        boolean inReach = enemy.getBoundingBox().distanceToSqr(bot.getEyePosition()) <= MELEE_REACH * MELEE_REACH;
        if (inReach && bot.getAttackStrengthScale(0.5F) >= 1.0F && isPerson(enemy)) {
            // People get a short, random moment to act: no inhumanly instant counter-hits
            if (strikeAt < 0) {
                strikeAt = now() + MIN_REACTION_TICKS + bot.getRandom().nextInt(MAX_REACTION_TICKS - MIN_REACTION_TICKS + 1);
            }
            if (now() < strikeAt) {
                return true;
            }
        }
        if (inReach && bot.getAttackStrengthScale(0.5F) >= 1.0F) {
            strikeAt = -1;
            // After the hit, the shield goes up (against people: not always, and not at once)
            blockThisRound = !isPerson(enemy) || bot.getRandom().nextFloat() < BLOCK_CHANCE;
            blockAt = isPerson(enemy) ? now() + 3 + bot.getRandom().nextInt(6) : 0;
            if (bot.isUsingItem()) {
                bot.stopUsingItem(); // shield down (or stop eating): hitting comes first
                usingItem = false;
            }
            bot.attack(enemy);
            bot.swing(InteractionHand.MAIN_HAND);
        }
        return inReach;
    }

    /** Puts the best sword/axe in hand, if the bot has one. */
    public void selectMeleeWeapon() {
        Inv.select(bot, Inv::isWeapon);
    }

    private void shoot(LivingEntity enemy, int bowSlot, double distance) {
        if (bot.getUsedItemHand() == InteractionHand.OFF_HAND && bot.isUsingItem()) {
            stopUsingItem(); // lower the shield
        }
        if (bot.getInventory().getSelectedSlot() != bowSlot) {
            stopUsingItem();
            bot.getInventory().setSelectedSlot(bowSlot);
        }

        if (distance > BOW_STAND_RANGE) {
            bot.controller().steer(enemy.position(), BOW_STAND_RANGE);
            trackProgress(distance);
        } else {
            bot.controller().releaseInputs();
            ticksWithoutProgress = 0;
        }
        aimBow(enemy);

        ItemStack weapon = bot.getInventory().getItem(bowSlot);
        if (weapon.getItem() instanceof CrossbowItem) {
            shootCrossbow(enemy, weapon);
            return;
        }
        if (!bot.isUsingItem()) {
            bot.startUsingItem(InteractionHand.MAIN_HAND);
            usingItem = true;
        } else if (bot.getTicksUsingItem() >= BOW_DRAW_TICKS && bot.hasLineOfSight(enemy)) {
            bot.releaseUsingItem();
            usingItem = false;
        }
    }

    /** A crossbow: wind it (load an arrow), then fire when the enemy is in sight. */
    private void shootCrossbow(LivingEntity enemy, ItemStack crossbow) {
        if (CrossbowItem.isCharged(crossbow)) {
            if (bot.hasLineOfSight(enemy)) {
                crossbow.use(bot.level(), bot, InteractionHand.MAIN_HAND);
            }
        } else if (!bot.isUsingItem()) {
            bot.startUsingItem(InteractionHand.MAIN_HAND);
            usingItem = true;
        } else if (bot.getTicksUsingItem() >= CrossbowItem.getChargeDuration(crossbow, bot)) {
            bot.releaseUsingItem(); // loaded
            usingItem = false;
        }
    }

    /** The best sword or axe on the hotbar (swords first, then the better material; the more worn of equals). */
    private int bestMeleeSlot() {
        int best = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if ((stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)) && (best < 0 || Ranged.meleeBetter(stack, bot.getInventory().getItem(best)))) {
                best = slot;
            }
        }
        return best;
    }

    /** The best bow or crossbow on the hotbar, or -1. */
    private int bestRangedSlot() {
        int best = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (Ranged.is(stack) && (best < 0 || Ranged.score(stack) > Ranged.score(bot.getInventory().getItem(best)))) {
                best = slot;
            }
        }
        return best;
    }

    /** Points the bot so a full-power arrow lands on the enemy, leading a moving target. */
    private void aimBow(LivingEntity enemy) {
        Vec3 eyes = bot.getEyePosition();
        Vec3 aimAt = enemy.getBoundingBox().getCenter();
        double flightTicks = eyes.distanceTo(aimAt) / ARROW_SPEED;
        aimAt = aimAt.add(enemy.getDeltaMovement().multiply(1.0, 0.0, 1.0).scale(flightTicks));

        double dx = aimAt.x - eyes.x;
        double dz = aimAt.z - eyes.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = aimAt.y - eyes.y;

        // Lowest launch angle that hits (x, y) with speed v under gravity g (air drag ignored)
        double v2 = ARROW_SPEED * ARROW_SPEED;
        double g = ARROW_GRAVITY;
        double discriminant = v2 * v2 - g * (g * horizontal * horizontal + 2 * dy * v2);
        double angle = discriminant < 0 || horizontal < 1.0e-3
            ? Math.atan2(dy, horizontal)
            : Math.atan((v2 - Math.sqrt(discriminant)) / (g * horizontal));

        bot.controller().lookAt(new Vec3(aimAt.x, eyes.y, aimAt.z));
        bot.setXRot((float) -Math.toDegrees(angle));
    }

    private void trackProgress(double distance) {
        if (distance < bestDistance - 0.1) {
            bestDistance = distance;
            ticksWithoutProgress = 0;
        } else if (++ticksWithoutProgress > (climbing ? GIVE_UP_TICKS * 3 : GIVE_UP_TICKS) && target != null) {
            // Can't reach it (walls, water, height); leave it alone for a while
            ignoredUntil.put(target.getUUID(), now() + IGNORE_TICKS);
            if (target == aggressor) {
                aggressor = null;
            }
            setTarget(null);
            bot.controller().releaseInputs();
        }
    }

    /** Lowers the bow or shield combat raised; leaves anything else (eating!) alone. */
    private void stopUsingItem() {
        if (usingItem && bot.isUsingItem()) {
            bot.stopUsingItem();
        }
        usingItem = false;
    }

    private long now() {
        return bot.level().getGameTime();
    }
}
