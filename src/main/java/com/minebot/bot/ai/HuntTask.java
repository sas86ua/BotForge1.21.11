package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.craft.Sources;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import com.minebot.bot.world.ProtectedAreas;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Hunts animals for their drops (meat, wool). Leaves alone babies, named,
 * leashed or tamed animals, animals near players' homes, and the last few of
 * a herd so they can breed back.
 */
public class HuntTask extends Task {
    private static final int SEARCH_RADIUS = 96;
    private static final int HERD_RADIUS = 24;
    private static final int MIN_HERD = 2;
    private static final int MAX_CHASE_TICKS = 20 * 45;

    private final List<Sources.Kill> kills;
    private final Target target;
    private final int maxExplores;
    /** How far round the bot it looks for prey. */
    private int radius = SEARCH_RADIUS;
    private final Set<UUID> givenUp = new HashSet<>();

    private @Nullable LivingEntity prey;
    private @Nullable BlockPos heading;
    private @Nullable Task child;
    private int chaseTicks;
    private int explores;

    public HuntTask(BotPlayer bot, Sources.Kill kill, Target target) {
        this(bot, List.of(kill), target, 2);
    }

    /** Hunts whichever of {@code kills} is nearest until the target is reached. */
    public HuntTask(BotPlayer bot, List<Sources.Kill> kills, Target target, int maxExplores) {
        super(bot);
        this.kills = kills;
        this.target = target;
        this.maxExplores = maxExplores;
        this.shearing = !kills.isEmpty() && kills.stream().allMatch(kill -> kill.type() == net.minecraft.world.entity.EntityType.SHEEP
            && new net.minecraft.world.item.ItemStack(kill.drop()).is(net.minecraft.tags.ItemTags.WOOL));
    }

    /** After wool: the sheep are shorn (1-3 wool each, and it grows back), not killed. */
    private final boolean shearing;
    private boolean shearsTried;

    private boolean hasShears() {
        return com.minebot.bot.action.Inv.count(bot, stack -> stack.is(net.minecraft.world.item.Items.SHEARS)) > 0;
    }

    /** Prey only this close (keeping a player company: not off across the land after some far chicken). */
    public HuntTask within(int radius) {
        this.radius = radius;
        return this;
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
        }
        if (target.satisfied(bot)) {
            return Status.SUCCESS;
        }
        if (shearing && !shearsTried && !hasShears()) {
            // (two iron: far better than a sheep a wool; without them it hunts them as before)
            shearsTried = true;
            bot.debug("wool to get: shears first, to shear the sheep");
            child = new ObtainTask(bot, Target.of(net.minecraft.world.item.Items.SHEARS, 1), 1);
            return Status.RUNNING;
        }
        boolean shear = shearing && hasShears();
        if (shear && prey instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep && sheep.isSheared()) {
            Vec3 at = prey.position();
            prey = null;
            if (!Home.isNear(bot, 48)) {
                Stash.makeRoom(bot, 2);
            }
            child = new CollectItemsTask(bot, at, 5.0, 100);
            return Status.RUNNING;
        }
        if (prey != null && !prey.isAlive()) {
            Vec3 at = prey.position();
            prey = null;
            if (!Home.isNear(bot, 48)) {
                Stash.makeRoom(bot, 2); // (a full bag would leave the drops lying)
            }
            child = new CollectItemsTask(bot, at, 5.0, 100);
            return Status.RUNNING;
        }
        if (prey == null) {
            prey = findPrey();
            heading = null;
            chaseTicks = 0;
            if (prey == null) {
                if (++explores > maxExplores) {
                    return Status.FAILURE;
                }
                bot.debug("no {} to hunt nearby, exploring", names());
                child = new ExploreTask(bot, 64);
                return Status.RUNNING;
            }
        }
        if (++chaseTicks > MAX_CHASE_TICKS || prey.level() != bot.level()) {
            givenUp.add(prey.getUUID());
            prey = null;
            bot.navigator().stop();
            return Status.RUNNING;
        }

        double distance = bot.distanceTo(prey);
        if (distance > 2.5) {
            BlockPos preyPos = prey.blockPosition();
            if (heading == null || !bot.navigator().isActive() || heading.distSqr(preyPos) > 9) {
                heading = preyPos;
                bot.navigator().navigate(Goal.near(preyPos, 1.5));
            }
            if (bot.navigator().tick() == Navigator.Status.FAILED) {
                givenUp.add(prey.getUUID());
                prey = null;
            }
            return Status.RUNNING;
        }
        bot.navigator().stop();
        heading = null;
        if (shear) {
            com.minebot.bot.action.Inv.select(bot, stack -> stack.is(net.minecraft.world.item.Items.SHEARS));
            bot.controller().lookAt(prey.getEyePosition());
            bot.interactOn(prey, net.minecraft.world.InteractionHand.MAIN_HAND);
            bot.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (prey instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep && !sheep.isSheared()) {
                givenUp.add(prey.getUUID()); // (couldn't shear it: some other one)
                prey = null;
            }
            return Status.RUNNING;
        }
        bot.combat().selectMeleeWeapon();
        bot.controller().steer(prey.position(), 1.2);
        bot.combat().strike(prey);
        return Status.RUNNING;
    }

    private @Nullable LivingEntity findPrey() {
        LivingEntity best = null;
        for (Sources.Kill kill : kills) {
            // (shearing harms none: a lone sheep too)
            LivingEntity found = shearing && hasShears() ? findAny(bot, kill, givenUp, radius) : findPrey(bot, kill, givenUp, radius);
            if (found != null && (best == null || bot.distanceToSqr(found) < bot.distanceToSqr(best))) {
                best = found;
            }
        }
        return best;
    }

    /** The nearest animal this bot may hunt for {@code kill}, or null. */
    public static @Nullable LivingEntity findPrey(BotPlayer bot, Sources.Kill kill, Set<UUID> exclude) {
        return findPrey(bot, kill, exclude, SEARCH_RADIUS);
    }

    /** The nearest fair prey of this kind within {@code radius} of the bot (in a herd: the herd lives on). */
    public static @Nullable LivingEntity findPrey(BotPlayer bot, Sources.Kill kill, Set<UUID> exclude, int radius) {
        ServerLevel level = bot.level();
        return level.getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(radius),
                entity -> isFairGame(bot, kill, entity) && !exclude.contains(entity.getUUID()))
            .stream()
            .filter(entity -> herdSize(bot, entity) >= MIN_HERD)
            // (wild sheep round a pen are to be brought into it, not eaten)
            .filter(entity -> !(entity instanceof net.minecraft.world.entity.animal.sheep.Sheep)
                || !SheepPenTask.nearPen(level, entity.blockPosition(), SheepPenTask.SPARE_RANGE))
            .min(Comparator.comparingDouble(bot::distanceToSqr))
            .orElse(null);
    }

    /** The nearest animal of this kind within {@code radius}, however small its herd (to shear, not to kill). */
    private static @Nullable LivingEntity findAny(BotPlayer bot, Sources.Kill kill, Set<UUID> exclude, int radius) {
        return bot.level().getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(radius),
                entity -> isFairGame(bot, kill, entity) && !exclude.contains(entity.getUUID()))
            .stream()
            .min(Comparator.comparingDouble(bot::distanceToSqr))
            .orElse(null);
    }

    private static boolean isFairGame(BotPlayer bot, Sources.Kill kill, LivingEntity entity) {
        if (entity.getType() != kill.type() || !kill.filter().test(entity) || !entity.isAlive() || entity.isBaby()) {
            return false;
        }
        if (entity.hasCustomName() || entity instanceof Mob mob && mob.isLeashed()
            || entity instanceof TamableAnimal tame && tame.isTame()) {
            return false; // someone's pet or livestock
        }
        ServerLevel level = bot.level();
        if (entity instanceof net.minecraft.world.entity.animal.sheep.Sheep && SheepPenTask.nearPen(level, entity.blockPosition(), 0)) {
            return false; // (someone's pen: theirs)
        }
        return bot.memory().inZone(level.dimension(), entity.blockPosition())
            && !ProtectedAreas.isProtected(level, entity.blockPosition());
    }

    private static int herdSize(BotPlayer bot, LivingEntity entity) {
        return bot.level().getEntitiesOfClass(LivingEntity.class, entity.getBoundingBox().inflate(HERD_RADIUS),
            other -> other.getType() == entity.getType() && other.isAlive() && !other.isBaby()).size();
    }

    private String names() {
        return String.join("/", kills.stream().map(Sources.Kill::name).toList());
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        if (child != null) {
            child.stop();
        }
    }

    @Override
    public Target wanted() {
        return target;
    }

    @Override
    public String describe() {
        if (child != null) {
            return "hunting " + names() + ": " + child.describe();
        }
        return "hunting " + (prey != null ? prey.getType().getDescription().getString() : names()) + (prey != null ? " (" + (int) bot.distanceTo(prey) + "m away)" : "");
    }
}
