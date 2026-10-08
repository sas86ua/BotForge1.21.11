package com.minebot.bot.build;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The houses the bots build when they move out of their first hut (picked at random), read from
 * the schematics that come with the mod. Rare blocks become common look-alikes (bookshelves:
 * planks; stained glass: glass; stripped logs: logs...), and what they can't make is left out.
 */
public final class HouseSchematics {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** One house design: its plan, the layer that's level with the ground round it, and its front. */
    public record Design(String name, Schematic schematic, int groundLayer, Direction front) {
    }

    private static final String[][] FILES = {
        {"village house", "village_house.schematic"},
        {"village base", "village_base.schematic"},
        {"basic house", "basic_house.schematic"},
        {"simple base", "simple_base.schematic"},
        {"wooden cabin", "wooden_cabin.schem"}};

    private static List<Design> designs;

    private HouseSchematics() {
    }

    public static synchronized List<Design> all() {
        if (designs == null) {
            List<Design> loaded = new ArrayList<>();
            for (String[] file : FILES) {
                try (InputStream in = HouseSchematics.class.getResourceAsStream("/houses/" + file[1])) {
                    if (in == null) {
                        LOGGER.warn("House schematic {} missing", file[1]);
                        continue;
                    }
                    Schematic schematic = Schematic.load(in);
                    loaded.add(new Design(file[0], schematic, groundLayer(schematic), front(schematic)));
                } catch (Exception e) {
                    LOGGER.warn("Can't read house schematic {}", file[1], e);
                }
            }
            designs = List.copyOf(loaded);
        }
        return designs;
    }

    /** The highest layer that's mostly ground (dirt, grass: what's round the house): level with the land. */
    private static int groundLayer(Schematic schematic) {
        int area = schematic.width() * schematic.length();
        int layer = 0;
        for (int y = 0; y < schematic.height(); y++) {
            int ground = 0;
            for (int z = 0; z < schematic.length(); z++) {
                for (int x = 0; x < schematic.width(); x++) {
                    LegacyBlocks.Kind kind = schematic.at(x, y, z).kind();
                    if (kind == LegacyBlocks.Kind.GROUND || kind == LegacyBlocks.Kind.SOIL) {
                        ground++;
                    }
                }
            }
            if (ground * 100 >= area * 15) {
                layer = y;
            }
        }
        return layer;
    }

    /** Which way the house faces: out of its (first) door; north if it has none. */
    private static Direction front(Schematic schematic) {
        for (int i = 0; i < schematic.size(); i++) {
            LegacyBlocks.Spec spec = schematic.at(i);
            BlockState state = spec.state();
            if (spec.kind() == LegacyBlocks.Kind.EXACT && state != null && state.is(BlockTags.DOORS)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                // (a door faces the way its maker looked, standing outside: into the house)
                return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
            }
        }
        return Direction.NORTH;
    }

    /** Look-alikes for what the bots can't make; null: left out. */
    private static final Map<Block, Block> SUBSTITUTES = Map.ofEntries(
        Map.entry(Blocks.BOOKSHELF, Blocks.OAK_PLANKS),
        Map.entry(Blocks.CHISELED_BOOKSHELF, Blocks.SPRUCE_PLANKS),
        Map.entry(Blocks.ENDER_CHEST, Blocks.CHEST),
        Map.entry(Blocks.TRAPPED_CHEST, Blocks.CHEST),
        Map.entry(Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE, Blocks.STONE_PRESSURE_PLATE),
        Map.entry(Blocks.SOUL_LANTERN, Blocks.LANTERN),
        Map.entry(Blocks.SOUL_TORCH, Blocks.TORCH),
        Map.entry(Blocks.SOUL_WALL_TORCH, Blocks.WALL_TORCH),
        Map.entry(Blocks.NOTE_BLOCK, Blocks.OAK_PLANKS),
        Map.entry(Blocks.JUKEBOX, Blocks.OAK_PLANKS),
        Map.entry(Blocks.SPONGE, Blocks.HAY_BLOCK),
        Map.entry(Blocks.GLOWSTONE, Blocks.LANTERN),
        Map.entry(Blocks.SEA_LANTERN, Blocks.LANTERN),
        Map.entry(Blocks.OBSIDIAN, Blocks.POLISHED_DEEPSLATE),
        Map.entry(Blocks.MOSSY_STONE_BRICKS, Blocks.STONE_BRICKS),
        Map.entry(Blocks.CRACKED_STONE_BRICKS, Blocks.STONE_BRICKS),
        Map.entry(Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE),
        Map.entry(Blocks.MOSSY_STONE_BRICK_STAIRS, Blocks.STONE_BRICK_STAIRS),
        Map.entry(Blocks.MOSSY_STONE_BRICK_SLAB, Blocks.STONE_BRICK_SLAB),
        Map.entry(Blocks.MOSSY_COBBLESTONE_STAIRS, Blocks.COBBLESTONE_STAIRS),
        Map.entry(Blocks.MOSSY_COBBLESTONE_WALL, Blocks.COBBLESTONE_WALL),
        Map.entry(Blocks.DARK_OAK_PLANKS, Blocks.SPRUCE_PLANKS),
        Map.entry(Blocks.DARK_OAK_STAIRS, Blocks.SPRUCE_STAIRS),
        Map.entry(Blocks.DARK_OAK_SLAB, Blocks.SPRUCE_SLAB),
        Map.entry(Blocks.DARK_OAK_LOG, Blocks.SPRUCE_LOG),
        Map.entry(Blocks.DARK_OAK_FENCE, Blocks.SPRUCE_FENCE),
        Map.entry(Blocks.DARK_OAK_TRAPDOOR, Blocks.SPRUCE_TRAPDOOR),
        Map.entry(Blocks.DARK_OAK_DOOR, Blocks.SPRUCE_DOOR),
        Map.entry(Blocks.STRIPPED_OAK_LOG, Blocks.OAK_LOG),
        Map.entry(Blocks.STRIPPED_SPRUCE_LOG, Blocks.SPRUCE_LOG),
        Map.entry(Blocks.STRIPPED_BIRCH_LOG, Blocks.BIRCH_LOG),
        Map.entry(Blocks.STRIPPED_JUNGLE_LOG, Blocks.JUNGLE_LOG),
        Map.entry(Blocks.STRIPPED_ACACIA_LOG, Blocks.ACACIA_LOG),
        Map.entry(Blocks.STRIPPED_DARK_OAK_LOG, Blocks.DARK_OAK_LOG),
        Map.entry(Blocks.STRIPPED_OAK_WOOD, Blocks.OAK_WOOD),
        Map.entry(Blocks.STRIPPED_SPRUCE_WOOD, Blocks.SPRUCE_WOOD),
        Map.entry(Blocks.STRIPPED_BIRCH_WOOD, Blocks.BIRCH_WOOD),
        Map.entry(Blocks.STRIPPED_DARK_OAK_WOOD, Blocks.DARK_OAK_WOOD));

    /**
     * What the bots actually build in a house's cell: the plan's block, a look-alike, or null (left
     * out: buttons, signs, flower pots, anvils, gold, beds but the first - so no other bot moves in).
     */
    /** The kinds of wood a house can be built of (0: as the plan has it). */
    public static final String[] WOODS = {"", "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"};

    /** The plan's block, of the bot's own wood (the trees round its home) instead of the plan's. */
    public static @org.jetbrains.annotations.Nullable BlockState adapt(BlockState state, int wood) {
        BlockState adapted = adapt(state);
        if (adapted == null || wood <= 0 || wood >= WOODS.length) {
            return adapted;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(adapted.getBlock()).getPath();
        String prefix = path.startsWith("stripped_") ? "stripped_" : "";
        String rest = path.substring(prefix.length());
        for (int i = WOODS.length - 1; i > 0; i--) { // (dark_oak and pale_oak before oak)
            String from = WOODS[i] + "_";
            if (rest.startsWith(from) && !WOODS[i].equals(WOODS[wood])) {
                var id = net.minecraft.resources.Identifier.withDefaultNamespace(prefix + WOODS[wood] + "_" + rest.substring(from.length()));
                Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
                if (block == null) {
                    return adapted;
                }
                BlockState result = block.defaultBlockState();
                for (Property<?> property : adapted.getProperties()) {
                    result = copy(result, adapted, property);
                }
                return result;
            }
            if (rest.startsWith(from)) {
                return adapted;
            }
        }
        return adapted;
    }

    public static @org.jetbrains.annotations.Nullable BlockState adapt(BlockState state) {
        Block block = state.getBlock();
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (state.is(BlockTags.BUTTONS) || state.is(BlockTags.ALL_SIGNS) || state.is(BlockTags.FLOWER_POTS)
            || state.is(BlockTags.ANVIL) || state.is(BlockTags.CANDLES) || state.is(BlockTags.BANNERS)
            || state.is(Blocks.GOLD_BLOCK) || state.is(Blocks.DIAMOND_BLOCK) || state.is(Blocks.EMERALD_BLOCK)
            || state.is(Blocks.BREWING_STAND) || state.is(Blocks.ENCHANTING_TABLE) || state.is(Blocks.CAKE)
            || state.is(Blocks.LEVER) || state.is(Blocks.REDSTONE_WIRE) || state.is(Blocks.TRIPWIRE_HOOK)
            || state.is(Blocks.COBWEB) || state.is(Blocks.SPAWNER) || state.is(Blocks.BEACON) || state.is(Blocks.DECORATED_POT)) {
            return null;
        }
        Block substitute = SUBSTITUTES.get(block);
        if (substitute == null && path.endsWith("stained_glass_pane")) {
            substitute = Blocks.GLASS_PANE;
        } else if (substitute == null && path.endsWith("stained_glass")) {
            substitute = Blocks.GLASS;
        }
        if (substitute == null) {
            return state;
        }
        BlockState result = substitute.defaultBlockState();
        for (Property<?> property : state.getProperties()) {
            result = copy(result, state, property);
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState into, BlockState from, Property<T> property) {
        return into.hasProperty(property) ? into.setValue(property, from.getValue(property)) : into;
    }

    /** The lower half of a door (the upper comes with it). */
    static boolean isLowerDoor(BlockState state) {
        return state.is(BlockTags.DOORS) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
    }
}
