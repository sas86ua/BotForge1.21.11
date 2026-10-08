package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.build.HousePlan;
import com.minebot.bot.build.Villages;
import com.minebot.bot.craft.Target;
import com.minebot.bot.path.Goal;
import com.minebot.bot.path.Navigator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Its village's church or library built: a path from its house to it, once. It walks over from home
 * the way it would anyway and turns the grass it treads on into a path with its shovel, as a player does.
 */
public class TrailTask extends Task {
    private static final int CHECK_TICKS = 20 * 60 * 5;

    private enum Stage { SHOVEL, HOME, WALK }

    private Stage stage = Stage.SHOVEL;
    private @Nullable Villages.Project project;
    private @Nullable Task child;
    private @Nullable BlockPos lastFloor;
    private int made;

    public TrailTask(BotPlayer bot) {
        super(bot);
    }

    public static boolean wanted(BotPlayer bot) {
        return bot.memory().autonomous() && bot.memory().houseDone() && Home.levelIfHere(bot) != null && !Home.isNight(bot)
            && bot.every("trail", CHECK_TICKS, () -> Villages.get(bot.level().getServer()).trailToMake(bot) != null);
    }

    @Override
    public Status tick() {
        if (project == null) {
            project = Villages.get(bot.level().getServer()).trailToMake(bot);
            if (project == null) {
                return Status.SUCCESS;
            }
        }
        if (child != null) {
            Status status = child.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            child.stop();
            child = null;
            if (status == Status.FAILURE) {
                return Status.FAILURE;
            }
        }
        switch (stage) {
            case SHOVEL -> {
                stage = Stage.HOME;
                if (Inv.count(bot, stack -> stack.is(ItemTags.SHOVELS)) == 0) {
                    child = new ObtainTask(bot, Tools.target(ItemTags.SHOVELS, Tools.Tier.ANY), 0);
                }
            }
            case HOME -> {
                stage = Stage.WALK;
                if (!Home.isNear(bot, 12)) {
                    BlockPos home = bot.memory().home().pos();
                    child = new GoToTask(bot, Goal.column(home.getX(), home.getZ(), 4)); // (the path starts at its door)
                }
            }
            case WALK -> {
                return walk();
            }
        }
        return Status.RUNNING;
    }

    private Status walk() {
        HousePlan plan = Villages.plan(project);
        BlockPos target = plan.center();
        Navigator navigator = bot.navigator();
        if (!navigator.isActive()) {
            navigator.navigate(Goal.near(target, Math.max(plan.sizeX(), plan.sizeZ()) / 2.0 + 2));
        }
        Navigator.Status status = navigator.tick();
        if (bot.onGround() && !bot.isInWater()) {
            BlockPos floor = bot.blockPosition().below();
            if (!floor.equals(lastFloor)) {
                lastFloor = floor;
                flatten(floor);
            }
        }
        if (status == Navigator.Status.SUCCESS) {
            bot.debug("village: a path of {} blocks from my house to the {}", made, plan.name());
            Villages.get(bot.level().getServer()).trailMade(bot, project);
            return Status.SUCCESS;
        }
        return status == Navigator.Status.FAILED ? Status.FAILURE : Status.RUNNING;
    }

    /** Grass or earth underfoot (with air over it): a path, with the shovel. */
    private void flatten(BlockPos floor) {
        ServerLevel level = bot.level();
        BlockState state = level.getBlockState(floor);
        if (!(state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL)
            || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT)) || !level.getBlockState(floor.above()).isAir()) {
            return;
        }
        if (!Inv.select(bot, stack -> stack.is(ItemTags.SHOVELS))) {
            return;
        }
        Vec3 top = Vec3.atCenterOf(floor).add(0, 0.5, 0);
        bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(top, Direction.UP, floor, false));
        if (level.getBlockState(floor).is(Blocks.DIRT_PATH)) {
            made++;
        }
    }

    @Override
    public void stop() {
        bot.navigator().stop();
        if (child != null) {
            child.stop();
        }
    }

    @Override
    public @Nullable Target wanted() {
        return child != null ? child.wanted() : null;
    }

    @Override
    public String describe() {
        return "making a path to the village's " + (project != null ? Villages.name(project.building()) : "building");
    }
}
