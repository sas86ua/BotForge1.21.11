package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Crafting;
import com.minebot.bot.craft.Planner;
import com.minebot.bot.craft.Sources;
import com.minebot.bot.craft.Target;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gets {@link Target} items into the inventory by whatever means is cheapest:
 * mining, hunting, smelting or crafting. Missing ingredients and tools become
 * nested ObtainTasks; a way that fails is crossed off and the next one tried.
 */
public class ObtainTask extends Task {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_DECISIONS = 60;

    private final Target target;
    private final int depth;
    private final List<Planner.Strategy> failed = new ArrayList<>();

    private @Nullable Planner.Strategy strategy;
    private @Nullable Task child;
    private boolean childIsStep;
    private int decisions;
    /** Tools worn out mid-way (each replaced, up to a few). */
    private int brokenTools;
    private static final int MAX_BROKEN_TOOLS = 3;
    /** Asked for by a person: may dig for diamonds, emeralds or gold (otherwise only dug on journeys). */
    private boolean digValuables;

    public ObtainTask(BotPlayer bot, Target target, int depth) {
        super(bot);
        this.target = target;
        this.depth = depth;
    }

    /** Gets it some other way than out of the chests (when it's meant to fill them). */
    public ObtainTask notFromChests() {
        failed.add(new Planner.StoredStrategy());
        return this;
    }

    public ObtainTask digValuables() {
        digValuables = true;
        return this;
    }

    public Target target() {
        return target;
    }

    @Override
    public Status tick() {
        if (child != null) {
            Status status = child.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            String childState = child.describe();
            child.stop();
            child = null;
            if (status == Status.FAILURE && childIsStep && strategy instanceof Planner.MineStrategy mine
                && !mine.source().canHarvest(bot) && ++brokenTools <= MAX_BROKEN_TOOLS) {
                // The pickaxe broke while digging: a new one, and carry on the same way
                bot.debug("{}: tool broke, getting a new one", target);
            } else if (status == Status.FAILURE) {
                // A failed ingredient/tool or the strategy's own step: try another way
                bot.debug("{}: {} failed ({})", target, strategy, childState);
                failed.add(strategy);
                strategy = null;
            } else if (childIsStep) {
                strategy = null; // re-plan with the new inventory
            }
        }
        if (target.satisfied(bot)) {
            return Status.SUCCESS;
        }
        if (depth > MAX_DEPTH || ++decisions > MAX_DECISIONS) {
            bot.debug("{}: giving up (depth {}, decisions {})", target, depth, decisions);
            return Status.FAILURE;
        }
        if (strategy == null) {
            strategy = choose();
            if (strategy == null) {
                bot.debug("no way to get {}", target);
                return Status.FAILURE;
            }
            bot.debug("{}: using {}", target, strategy);
        }
        child = next(strategy);
        return Status.RUNNING;
    }

    private @Nullable Planner.Strategy choose() {
        for (Planner.Scored scored : new Planner(bot).options(target)) {
            if (!failed.contains(scored.strategy())) {
                return scored.strategy();
            }
        }
        if (digValuables) {
            for (Sources.Mine valuable : List.of(Sources.DIAMOND, Sources.EMERALD, Sources.GOLD)) {
                Planner.Strategy dig = new Planner.MineStrategy(valuable);
                if (target.accepts().test(new ItemStack(valuable.dropOf(valuable.sample()))) && !failed.contains(dig)) {
                    return dig;
                }
            }
        }
        return null;
    }

    /** The next sub-task for this strategy: an ingredient/tool to get, or the step itself. */
    private Task next(Planner.Strategy chosen) {
        childIsStep = true;
        switch (chosen) {
            case Planner.StoredStrategy stored -> {
                return ChestTask.withdraw(bot, target);
            }
            case Planner.MineStrategy mine -> {
                if (!mine.source().canHarvest(bot)) {
                    childIsStep = false;
                    return new ObtainTask(bot, mine.source().toolTarget(), depth + 1);
                }
                return new GatherTask(bot, mine.source(), target);
            }
            case Planner.KillStrategy kill -> {
                return new HuntTask(bot, kill.kill(), target);
            }
            case Planner.CookStrategy cook -> {
                return new SmeltTask(bot, cook.option(), target);
            }
            case Planner.CampfireStrategy campfire -> {
                return new CampfireTask(bot, campfire.option(), target);
            }
            case Planner.CraftStrategy craft -> {
                // Sort out the crafting table first: making one eats planks we may be counting on
                if (craft.option().needsTable() && !CraftTask.hasTableAccess(bot)) {
                    childIsStep = false;
                    return new ObtainTask(bot, Target.of(Items.CRAFTING_TABLE, 1), depth + 1);
                }
                int perCraft = craft.option().result().getCount();
                int times = (target.missing(bot) + perCraft - 1) / perCraft;
                Map<Ingredient, Integer> missing = Crafting.missing(bot, craft.option(), times);
                if (!missing.isEmpty()) {
                    Map.Entry<Ingredient, Integer> first = missing.entrySet().iterator().next();
                    childIsStep = false;
                    // Aim for the full amount needed, not just the shortfall
                    int total = Inv.count(bot, first.getKey()::test) + first.getValue();
                    return new ObtainTask(bot, Target.ingredient(first.getKey(), total), depth + 1);
                }
                return new CraftTask(bot, craft.option(), times);
            }
        }
    }

    @Override
    public void stop() {
        if (child != null) {
            child.stop();
            child = null;
        }
    }

    @Override
    public @Nullable Target wanted() {
        Target deeper = child != null ? child.wanted() : null;
        return deeper != null ? deeper : target;
    }

    @Override
    public String describe() {
        return child != null ? child.describe() : "getting " + target;
    }
}
