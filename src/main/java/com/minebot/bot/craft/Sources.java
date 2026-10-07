package com.minebot.bot.craft;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Where raw materials come from: which blocks to mine and which animals to hunt. */
public final class Sources {
    /** Blocks that drop an item when mined. */
    public record Mine(String name, Predicate<BlockState> blocks, BlockState sample) {
        /** The item a block of this source drops (ignoring fortune/silk touch). */
        public Item dropOf(BlockState state) {
            if (state.is(Blocks.STONE)) {
                return Items.COBBLESTONE;
            }
            if (state.is(Blocks.DEEPSLATE)) {
                return Items.COBBLED_DEEPSLATE;
            }
            if (state.is(BlockTags.COAL_ORES)) {
                return Items.COAL;
            }
            if (state.is(BlockTags.IRON_ORES)) {
                return Items.RAW_IRON;
            }
            if (state.is(BlockTags.GOLD_ORES)) {
                return Items.RAW_GOLD;
            }
            if (state.is(BlockTags.DIAMOND_ORES)) {
                return Items.DIAMOND;
            }
            if (state.is(BlockTags.EMERALD_ORES)) {
                return Items.EMERALD;
            }
            if (state.is(BlockTags.LAPIS_ORES)) {
                return Items.LAPIS_LAZULI;
            }
            if (state.is(Blocks.COBWEB)) {
                return Items.STRING; // (cut with a sword)
            }
            if (state.is(Blocks.GRAVEL)) {
                return Items.FLINT; // (now and then; usually just gravel)
            }
            return state.getBlock().asItem();
        }

        /** Blocks of this source whose drop the target wants. */
        public Predicate<BlockState> blocksFor(Target target) {
            return state -> blocks.test(state) && target.accepts().test(new ItemStack(dropOf(state)));
        }

        public boolean canHarvest(BotPlayer bot) {
            return Inv.canHarvest(bot, sample);
        }

        /** A tool good enough to get this source's drops. */
        public Target toolTarget() {
            return new Target("tool for " + name, stack -> stack.isCorrectToolForDrops(sample), 1);
        }
    }

    /** Animals that drop an item when killed. */
    public record Kill(String name, EntityType<?> type, Item drop, Predicate<LivingEntity> filter) {
    }

    public static final Mine LOGS = new Mine("logs", state -> state.is(BlockTags.LOGS), Blocks.OAK_LOG.defaultBlockState());
    public static final Mine STONE = new Mine("stone", state -> state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE),
        Blocks.STONE.defaultBlockState());
    public static final Mine COAL = new Mine("coal ore", state -> state.is(BlockTags.COAL_ORES), Blocks.COAL_ORE.defaultBlockState());
    public static final Mine IRON = new Mine("iron ore", state -> state.is(BlockTags.IRON_ORES), Blocks.IRON_ORE.defaultBlockState());
    public static final Mine SAND = new Mine("sand", state -> state.is(Blocks.SAND) || state.is(Blocks.RED_SAND),
        Blocks.SAND.defaultBlockState());
    /** Flowers, for dyes (not the wither rose: it hurts to touch). */
    public static final Mine FLOWERS = new Mine("flowers", state -> state.is(BlockTags.SMALL_FLOWERS) && !state.is(Blocks.WITHER_ROSE)
        || state.is(Blocks.SUNFLOWER) || state.is(Blocks.LILAC) || state.is(Blocks.ROSE_BUSH) || state.is(Blocks.PEONY),
        Blocks.POPPY.defaultBlockState());
    /** Cactus, smelted into green dye. */
    public static final Mine CACTUS = new Mine("cactus", state -> state.is(Blocks.CACTUS), Blocks.CACTUS.defaultBlockState());
    /** Lapis ore: blue dye. */
    public static final Mine LAPIS = new Mine("lapis ore", state -> state.is(BlockTags.LAPIS_ORES), Blocks.LAPIS_ORE.defaultBlockState());
    public static final List<Mine> MINES = List.of(LOGS, STONE, COAL, IRON, SAND, FLOWERS, CACTUS, LAPIS);
    /** Valuables the bot only digs for on its journeys (never needed for crafting). */
    public static final Mine GOLD = new Mine("gold ore", state -> state.is(BlockTags.GOLD_ORES), Blocks.GOLD_ORE.defaultBlockState());
    public static final Mine DIAMOND = new Mine("diamond ore", state -> state.is(BlockTags.DIAMOND_ORES), Blocks.DIAMOND_ORE.defaultBlockState());
    public static final Mine EMERALD = new Mine("emerald ore", state -> state.is(BlockTags.EMERALD_ORES), Blocks.EMERALD_ORE.defaultBlockState());
    /** Cobwebs cut with a sword give string (for bows). */
    public static final Mine COBWEB = new Mine("cobweb", state -> state.is(Blocks.COBWEB), Blocks.COBWEB.defaultBlockState());
    /** Gravel, dug for the flint it sometimes drops. */
    public static final Mine GRAVEL = new Mine("gravel", state -> state.is(Blocks.GRAVEL), Blocks.GRAVEL.defaultBlockState());

    private static List<Kill> kills;

    private Sources() {
    }

    /** Sample drops for each mine source, to test which ones a target accepts. */
    public static List<Item> dropsOf(Mine mine) {
        if (mine == LOGS) {
            List<Item> logs = new ArrayList<>();
            BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.LOGS).forEach(holder -> logs.add(holder.value()));
            return logs;
        }
        if (mine == STONE) {
            return List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE);
        }
        if (mine == COAL) {
            return List.of(Items.COAL);
        }
        if (mine == SAND) {
            return List.of(Items.SAND, Items.RED_SAND); // (glass for windows)
        }
        if (mine == FLOWERS) {
            List<Item> flowers = new ArrayList<>(List.of(Items.SUNFLOWER, Items.LILAC, Items.ROSE_BUSH, Items.PEONY));
            BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.SMALL_FLOWERS).forEach(holder -> {
                if (holder.value() != Items.WITHER_ROSE) {
                    flowers.add(holder.value());
                }
            });
            return flowers;
        }
        if (mine == CACTUS) {
            return List.of(Items.CACTUS);
        }
        if (mine == LAPIS) {
            return List.of(Items.LAPIS_LAZULI);
        }
        return List.of(Items.RAW_IRON);
    }

    public static @Nullable Mine mineFor(Target target) {
        for (Mine mine : MINES) {
            for (Item drop : dropsOf(mine)) {
                if (target.accepts().test(new ItemStack(drop))) {
                    return mine;
                }
            }
        }
        return null;
    }

    public static List<Kill> kills() {
        if (kills == null) {
            List<Kill> list = new ArrayList<>();
            list.add(new Kill("cow", EntityType.COW, Items.BEEF, entity -> true));
            list.add(new Kill("pig", EntityType.PIG, Items.PORKCHOP, entity -> true));
            list.add(new Kill("chicken", EntityType.CHICKEN, Items.CHICKEN, entity -> true));
            list.add(new Kill("chicken for feathers", EntityType.CHICKEN, Items.FEATHER, entity -> true)); // (arrows)
            list.add(new Kill("rabbit", EntityType.RABBIT, Items.RABBIT, entity -> true));
            list.add(new Kill("sheep", EntityType.SHEEP, Items.MUTTON, entity -> true));
            // (only the colours sheep come in: other wool is dyed, see the dye sources above)
            for (DyeColor color : new DyeColor[] {DyeColor.WHITE, DyeColor.LIGHT_GRAY, DyeColor.GRAY, DyeColor.BLACK,
                DyeColor.BROWN, DyeColor.PINK}) {
                Item wool = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(color.getSerializedName() + "_wool"));
                list.add(new Kill(color.getSerializedName() + " sheep", EntityType.SHEEP, wool,
                    entity -> entity instanceof Sheep sheep && sheep.getColor() == color && !sheep.isSheared()));
            }
            kills = List.copyOf(list);
        }
        return kills;
    }

    /** Every animal whose drop the target accepts (e.g. any sheep colour for "wool"). */
    public static List<Kill> killsFor(Target target) {
        return kills().stream().filter(kill -> target.accepts().test(new ItemStack(kill.drop()))).toList();
    }
}
