package com.minebot.bot.ai;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.craft.Recipes;
import com.minebot.bot.craft.Sources;
import com.minebot.bot.craft.Target;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Keeps a stock of cooked food: hunts whatever edible animal is nearest, then
 * roasts the meat on the bot's campfire.
 */
public class FoodTask extends Task {
    private static final int MAX_FAILURES = 3;

    private final int stock;
    private final boolean cook;
    /** Food from chests round about too (not when keeping a player company: it stays by them). */
    private final boolean useChests;
    /** How far afield it may go looking for animals (explore trips). */
    private final int maxExplores;
    /** How far round the bot it hunts (0: as far as HuntTask looks). */
    private int huntRadius;
    private @Nullable Task child;
    private int failures;
    private boolean checkedChests;
    /** The last hunt found nothing more: what raw meat it has gets cooked. */
    private boolean huntFailed;

    /**
     * @param stock how many items to end up with
     * @param cook  false: raw meat is good enough (starving, eat now)
     */
    public FoodTask(BotPlayer bot, int stock, boolean cook) {
        this(bot, stock, cook, true, 3);
    }

    /** Only the animals close by and no chests: hunting while keeping a player company. */
    public FoodTask(BotPlayer bot, int stock, boolean cook, boolean useChests, int maxExplores) {
        super(bot);
        this.stock = stock;
        this.cook = cook;
        this.useChests = useChests;
        this.maxExplores = maxExplores;
    }

    public static List<Sources.Kill> meatAnimals() {
        return Sources.kills().stream().filter(kill -> Inv.isFood(new ItemStack(kill.drop()))).toList();
    }

    /** Hunting only this close to the bot. */
    public FoodTask huntingWithin(int radius) {
        this.huntRadius = radius;
        return this;
    }

    @Override
    public Status tick() {
        if (child != null) {
            Status status = child.tick();
            if (status == Status.RUNNING) {
                return Status.RUNNING;
            }
            if (status == Status.FAILURE) {
                bot.debug("food: {} failed", child.describe());
                if (child instanceof HuntTask) {
                    huntFailed = true; // (no more animals about: cook what it has)
                }
            }
            child.stop();
            child = null;
                if (status == Status.FAILURE && ++failures > MAX_FAILURES) {
                return Status.FAILURE;
            }
        }
        int cooked = Inv.count(bot, Food::isCooked);
        if (!cook) {
            if (Inv.count(bot, Inv::isFood) >= stock) {
                return Status.SUCCESS;
            }
        } else if (cooked >= stock) {
            return Status.SUCCESS;
        }

        int raw = Inv.count(bot, this::isCookable);
        if (!checkedChests && useChests) {
            // First see if there's food in chests nearby (own, other bots', villages'...)
            checkedChests = true;
            Target ready = cook ? new Target("cooked food", Food::isCooked, stock) : new Target("any food", Inv::isFood, stock);
            Target rawMeat = new Target("raw meat", this::isCookable, raw + 4);
            if (ChestTask.stored(bot, ready) > 0) {
                child = ChestTask.withdraw(bot, ready);
                return Status.RUNNING;
            }
            if (cook && ChestTask.stored(bot, rawMeat) > 0) {
                child = ChestTask.withdraw(bot, rawMeat);
                return Status.RUNNING;
            }
        }
        // Hunt first, until there is meat enough (or no more animals), then cook it all at one fire
        if (cook && raw > 0 && (raw >= stock - cooked || huntFailed)) {
            int slot = Inv.findSlot(bot, this::isCookable);
            ItemStack sample = bot.getInventory().getItem(slot);
            Recipes.CookOption option = Recipes.campfireOf(bot.level().getServer(), sample).orElseThrow();
            int ofThisKind = Inv.count(bot, option.input()::test);
            Target target = new Target("cooked food", Food::isCooked, cooked + Math.min(ofThisKind, Math.max(4, stock - cooked)));
            child = new CampfireTask(bot, option, target);
            return Status.RUNNING;
        }
        // One animal at a time, whichever is nearest
        Target meat = new Target("raw meat", this::isCookable, raw + 1);
        HuntTask hunt = new HuntTask(bot, meatAnimals(), meat, maxExplores);
        child = huntRadius > 0 ? hunt.within(huntRadius) : hunt;
        return Status.RUNNING;
    }

    private boolean isCookable(ItemStack stack) {
        return Inv.isFood(stack) && Recipes.campfireOf(bot.level().getServer(), stack).isPresent();
    }

    @Override
    public void stop() {
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
        String what = cook ? "stocking up on cooked food" : "finding something to eat";
        return child != null ? what + ": " + child.describe() : what;
    }
}
