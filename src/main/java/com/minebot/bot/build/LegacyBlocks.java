package com.minebot.bot.build;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;

/**
 * Old (pre-1.13) numeric block ids and data values, as in MCEdit/WorldEdit ".schematic" files,
 * turned into what the bots build. Rare blocks become common ones of a like colour (obsidian:
 * polished deepslate; quartz: polished diorite...); what they can't or shouldn't build (water,
 * lava, TNT, signs, crops, vines) is skipped.
 */
public final class LegacyBlocks {
    /** What goes in one cell of a plan. */
    public enum Kind {
        /** Must be empty (dug out if there's anything). */
        AIR,
        /** Left as it is. */
        SKIP,
        /** Solid ground under the surface: any solid block will do (filled with dirt or stone if empty). */
        GROUND,
        /** The top of the ground: grass or dirt. */
        SOIL,
        /** Exactly this block. */
        EXACT,
        /** The second half of a door or bed: comes with the first. */
        COMPANION
    }

    public record Spec(Kind kind, @Nullable BlockState state) {
        static final Spec AIR = new Spec(Kind.AIR, null);
        static final Spec SKIP = new Spec(Kind.SKIP, null);
        static final Spec GROUND = new Spec(Kind.GROUND, null);

        static Spec of(BlockState state) {
            return new Spec(Kind.EXACT, state);
        }

        static Spec of(Block block) {
            return of(block.defaultBlockState());
        }
    }

    private static final Block[] WOOL = {Blocks.WHITE_WOOL, Blocks.ORANGE_WOOL, Blocks.MAGENTA_WOOL, Blocks.LIGHT_BLUE_WOOL,
        Blocks.YELLOW_WOOL, Blocks.LIME_WOOL, Blocks.PINK_WOOL, Blocks.GRAY_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.CYAN_WOOL,
        Blocks.PURPLE_WOOL, Blocks.BLUE_WOOL, Blocks.BROWN_WOOL, Blocks.GREEN_WOOL, Blocks.RED_WOOL, Blocks.BLACK_WOOL};
    private static final Block[] CARPET = {Blocks.WHITE_CARPET, Blocks.ORANGE_CARPET, Blocks.MAGENTA_CARPET,
        Blocks.LIGHT_BLUE_CARPET, Blocks.YELLOW_CARPET, Blocks.LIME_CARPET, Blocks.PINK_CARPET, Blocks.GRAY_CARPET,
        Blocks.LIGHT_GRAY_CARPET, Blocks.CYAN_CARPET, Blocks.PURPLE_CARPET, Blocks.BLUE_CARPET, Blocks.BROWN_CARPET,
        Blocks.GREEN_CARPET, Blocks.RED_CARPET, Blocks.BLACK_CARPET};
    private static final Block[] TERRACOTTA = {Blocks.WHITE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA,
        Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIME_TERRACOTTA, Blocks.PINK_TERRACOTTA,
        Blocks.GRAY_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.PURPLE_TERRACOTTA,
        Blocks.BLUE_TERRACOTTA, Blocks.BROWN_TERRACOTTA, Blocks.GREEN_TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.BLACK_TERRACOTTA};
    private static final Block[] PLANKS = {Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
        Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS};
    private static final Block[] WOOD_SLABS = {Blocks.OAK_SLAB, Blocks.SPRUCE_SLAB, Blocks.BIRCH_SLAB, Blocks.JUNGLE_SLAB,
        Blocks.ACACIA_SLAB, Blocks.DARK_OAK_SLAB};
    private static final Block[] LOGS = {Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG};
    private static final Block[] LOGS2 = {Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG};
    /** Stone slabs by old type: smooth stone, sandstone, wood, cobblestone, brick, stone brick, nether brick, quartz. */
    private static final Block[] STONE_SLABS = {Blocks.SMOOTH_STONE_SLAB, Blocks.SANDSTONE_SLAB, Blocks.OAK_SLAB,
        Blocks.COBBLESTONE_SLAB, Blocks.BRICK_SLAB, Blocks.STONE_BRICK_SLAB, Blocks.BRICK_SLAB, Blocks.POLISHED_DIORITE_SLAB};
    /** A double slab is built as the full block it looks like. */
    private static final Block[] DOUBLE_SLABS = {Blocks.SMOOTH_STONE, Blocks.SANDSTONE, Blocks.OAK_PLANKS, Blocks.COBBLESTONE,
        Blocks.BRICKS, Blocks.STONE_BRICKS, Blocks.BRICKS, Blocks.POLISHED_DIORITE};

    private LegacyBlocks() {
    }

    /** @return the spec, or null for an id it doesn't know (left as it is, and counted) */
    public static @Nullable Spec of(int id, int data) {
        return switch (id) {
            case 0 -> Spec.AIR;
            case 1 -> Spec.of(switch (data) {
                case 1 -> Blocks.GRANITE;
                case 2 -> Blocks.POLISHED_GRANITE;
                case 3 -> Blocks.DIORITE;
                case 4 -> Blocks.POLISHED_DIORITE;
                case 5 -> Blocks.ANDESITE;
                case 6 -> Blocks.POLISHED_ANDESITE;
                default -> Blocks.STONE;
            });
            case 2, 3 -> Spec.GROUND; // grass, dirt (grass on top: see Schematic)
            case 4 -> Spec.of(Blocks.COBBLESTONE);
            case 5 -> Spec.of(PLANKS[Math.min(data & 7, 5)]);
            case 7 -> Spec.GROUND; // bedrock
            case 12 -> Spec.of(data == 1 ? Blocks.RED_SAND : Blocks.SAND);
            case 13 -> Spec.of(Blocks.GRAVEL);
            case 14, 15, 16, 21, 56, 73, 74, 129 -> Spec.GROUND; // ores
            case 17 -> Spec.of(axis(LOGS[data & 3].defaultBlockState(), data));
            case 162 -> Spec.of(axis(LOGS2[data & 1].defaultBlockState(), data));
            case 19 -> Spec.of(Blocks.SPONGE);
            case 20 -> Spec.of(Blocks.GLASS);
            case 22 -> Spec.of(Blocks.LAPIS_BLOCK);
            case 24 -> Spec.of(switch (data) {
                case 1 -> Blocks.CHISELED_SANDSTONE;
                case 2 -> Blocks.CUT_SANDSTONE;
                default -> Blocks.SANDSTONE;
            });
            case 26 -> bed(data);
            case 35 -> Spec.of(WOOL[data & 15]);
            case 41 -> Spec.of(Blocks.GOLD_BLOCK);
            case 42 -> Spec.of(Blocks.IRON_BLOCK);
            case 43 -> Spec.of(DOUBLE_SLABS[data & 7]);
            case 44 -> Spec.of(slab(STONE_SLABS[data & 7], data));
            case 125 -> Spec.of(PLANKS[Math.min(data & 7, 5)]); // double wooden slab
            case 126 -> Spec.of(slab(WOOD_SLABS[Math.min(data & 7, 5)], data));
            case 45 -> Spec.of(Blocks.BRICKS);
            case 47 -> Spec.of(Blocks.BOOKSHELF);
            case 48 -> Spec.of(Blocks.MOSSY_COBBLESTONE);
            case 49 -> Spec.of(Blocks.POLISHED_DEEPSLATE); // obsidian: same dark colour, not rare
            case 50 -> torch(data);
            case 53 -> stairs(Blocks.OAK_STAIRS, data);
            case 67 -> stairs(Blocks.COBBLESTONE_STAIRS, data);
            case 108 -> stairs(Blocks.BRICK_STAIRS, data);
            case 109 -> stairs(Blocks.STONE_BRICK_STAIRS, data);
            case 114 -> stairs(Blocks.BRICK_STAIRS, data); // nether brick
            case 128 -> stairs(Blocks.SANDSTONE_STAIRS, data);
            case 134 -> stairs(Blocks.SPRUCE_STAIRS, data);
            case 135 -> stairs(Blocks.BIRCH_STAIRS, data);
            case 136 -> stairs(Blocks.JUNGLE_STAIRS, data);
            case 156 -> stairs(Blocks.POLISHED_DIORITE_STAIRS, data); // quartz
            case 163 -> stairs(Blocks.ACACIA_STAIRS, data);
            case 164 -> stairs(Blocks.DARK_OAK_STAIRS, data);
            case 54 -> Spec.of(facing(Blocks.CHEST.defaultBlockState(), data));
            case 58 -> Spec.of(Blocks.CRAFTING_TABLE);
            case 60 -> new Spec(Kind.SOIL, null); // farmland: the crops on it are skipped
            case 61, 62 -> Spec.of(facing(Blocks.FURNACE.defaultBlockState(), data));
            case 64 -> door(Blocks.OAK_DOOR, data);
            case 71 -> door(Blocks.IRON_DOOR, data);
            case 65 -> Spec.of(facing(Blocks.LADDER.defaultBlockState(), data));
            case 70 -> Spec.of(Blocks.STONE_PRESSURE_PLATE);
            case 72 -> Spec.of(Blocks.OAK_PRESSURE_PLATE);
            case 147 -> Spec.of(Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE);
            case 148 -> Spec.of(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE);
            case 77 -> Spec.of(Blocks.STONE_BUTTON);
            case 143 -> Spec.of(Blocks.OAK_BUTTON);
            case 80 -> Spec.of(Blocks.SNOW_BLOCK);
            case 82 -> Spec.of(Blocks.CLAY);
            case 84 -> Spec.of(Blocks.NOTE_BLOCK); // jukebox: a diamond each
            case 25 -> Spec.of(Blocks.NOTE_BLOCK);
            case 85, 113 -> Spec.of(Blocks.OAK_FENCE); // (nether brick fence too)
            case 188 -> Spec.of(Blocks.SPRUCE_FENCE);
            case 189 -> Spec.of(Blocks.BIRCH_FENCE);
            case 86 -> Spec.of(Blocks.CARVED_PUMPKIN);
            case 89 -> Spec.of(Blocks.LANTERN); // glowstone: from the Nether
            case 91 -> Spec.of(Blocks.JACK_O_LANTERN);
            case 95 -> Spec.of(Blocks.GLASS); // stained glass
            case 160 -> Spec.of(Blocks.GLASS_PANE);
            case 98 -> Spec.of(switch (data) {
                case 1 -> Blocks.MOSSY_STONE_BRICKS;
                case 2 -> Blocks.CRACKED_STONE_BRICKS;
                case 3 -> Blocks.CHISELED_STONE_BRICKS;
                default -> Blocks.STONE_BRICKS;
            });
            case 101 -> Spec.of(Blocks.IRON_BARS);
            case 102 -> Spec.of(Blocks.GLASS_PANE);
            case 103 -> Spec.of(Blocks.MELON);
            case 107 -> gate(Blocks.OAK_FENCE_GATE, data);
            case 112 -> Spec.of(Blocks.BRICKS); // nether bricks
            case 118 -> Spec.of(Blocks.CAULDRON);
            case 139 -> Spec.of(data == 1 ? Blocks.MOSSY_COBBLESTONE_WALL : Blocks.COBBLESTONE_WALL);
            case 140 -> Spec.of(Blocks.FLOWER_POT);
            case 145 -> Spec.of(Blocks.ANVIL);
            case 155 -> Spec.of(Blocks.POLISHED_DIORITE); // quartz: from the Nether
            case 159 -> Spec.of(TERRACOTTA[data & 15]);
            case 171 -> Spec.of(CARPET[data & 15]);
            case 172 -> Spec.of(Blocks.TERRACOTTA);
            case 170 -> Spec.of(Blocks.HAY_BLOCK);
            case 173 -> Spec.of(Blocks.COAL_BLOCK);
            // Left alone: water, lava, fire, TNT, signs, leaves, plants and crops, vines, torches of redstone,
            // the enchanting table (obsidian and diamonds), spawners, portals...
            case 8, 9, 10, 11, 51, 46, 63, 68, 18, 161, 6, 31, 32, 37, 38, 39, 40, 59, 83, 104, 105, 106, 111, 115,
                 141, 142, 175, 30, 55, 75, 76, 93, 94, 116, 52, 90, 119, 120, 69 -> Spec.SKIP;
            default -> null;
        };
    }

    private static BlockState axis(BlockState log, int data) {
        Direction.Axis axis = switch ((data >> 2) & 3) {
            case 1 -> Direction.Axis.X;
            case 2 -> Direction.Axis.Z;
            default -> Direction.Axis.Y;
        };
        return log.setValue(BlockStateProperties.AXIS, axis);
    }

    private static BlockState slab(Block slab, int data) {
        return slab.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, (data & 8) != 0 ? SlabType.TOP : SlabType.BOTTOM);
    }

    /** Stairs: 0 east, 1 west, 2 south, 3 north; +4 upside down. */
    private static Spec stairs(Block stairs, int data) {
        Direction facing = switch (data & 3) {
            case 0 -> Direction.EAST;
            case 1 -> Direction.WEST;
            case 2 -> Direction.SOUTH;
            default -> Direction.NORTH;
        };
        return Spec.of(stairs.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
            .setValue(BlockStateProperties.HALF, (data & 4) != 0 ? Half.TOP : Half.BOTTOM));
    }

    /** Torches: 1-4 on a wall, pointing east, west, south, north; else standing. */
    private static Spec torch(int data) {
        Direction facing = switch (data) {
            case 1 -> Direction.EAST;
            case 2 -> Direction.WEST;
            case 3 -> Direction.SOUTH;
            case 4 -> Direction.NORTH;
            default -> null;
        };
        return facing == null ? Spec.of(Blocks.TORCH)
            : Spec.of(Blocks.WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
    }

    /** Chests, furnaces, ladders: 2 north, 3 south, 4 west, 5 east. */
    private static BlockState facing(BlockState state, int data) {
        Direction facing = switch (data) {
            case 3 -> Direction.SOUTH;
            case 4 -> Direction.WEST;
            case 5 -> Direction.EAST;
            default -> Direction.NORTH;
        };
        return state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
    }

    /** Doors: the lower half (0 east, 1 south, 2 west, 3 north) is placed; the upper (+8) comes with it. */
    private static Spec door(Block door, int data) {
        if ((data & 8) != 0) {
            return new Spec(Kind.COMPANION, door.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        }
        Direction facing = switch (data & 3) {
            case 0 -> Direction.EAST;
            case 1 -> Direction.SOUTH;
            case 2 -> Direction.WEST;
            default -> Direction.NORTH;
        };
        return Spec.of(door.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
    }

    /** Beds: the foot (0 south, 1 west, 2 north, 3 east: towards the head) is placed; the head (+8) comes with it. */
    private static Spec bed(int data) {
        Direction facing = switch (data & 3) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
        BlockState state = Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        return (data & 8) != 0 ? new Spec(Kind.COMPANION, state.setValue(BlockStateProperties.BED_PART, BedPart.HEAD))
            : Spec.of(state);
    }

    /** Fence gates: 0 south, 1 west, 2 north, 3 east (built closed). */
    private static Spec gate(Block gate, int data) {
        Direction facing = switch (data & 3) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
        return Spec.of(gate.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing));
    }
}
