package com.minebot.bot.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * A house template placed in the world: where each block goes and what it
 * must be. The template's front (local z = 0, with the door) faces
 * {@code front}; {@code origin} is the lowest north-west corner of the floor.
 */
public final class Blueprint {
    /** One block of the plan. */
    public record Cell(BlockPos pos, char kind, int layer) {
    }

    private final HouseTemplates.Template template;
    private final BlockPos origin;
    private final Direction front;
    private final List<Cell> cells = new ArrayList<>();

    public Blueprint(HouseTemplates.Template template, BlockPos origin, Direction front) {
        this.template = template;
        this.origin = origin;
        this.front = front;
        for (int y = 0; y < template.height(); y++) {
            List<Cell> layer = new ArrayList<>();
            for (int z = 0; z < HouseTemplates.Template.DEPTH; z++) {
                for (int x = 0; x < HouseTemplates.Template.WIDTH; x++) {
                    char kind = template.at(x, y, z);
                    if (kind != ' ') {
                        layer.add(new Cell(toWorld(x, y, z), kind, y));
                    }
                }
            }
            // Outside in: every block of a ceiling or roof then has a neighbour to be placed against
            BlockPos middle = toWorld(HouseTemplates.Template.WIDTH / 2, y, HouseTemplates.Template.DEPTH / 2);
            layer.sort(Comparator.comparingDouble((Cell cell) -> cell.pos().distSqr(middle)).reversed());
            cells.addAll(layer);
        }
        // Last of all, two torches outside by the door (they hang on the finished front wall)
        cells.add(new Cell(toWorld(2, 3, -1), 't', 3));
        cells.add(new Cell(toWorld(4, 3, -1), 't', 3));
    }

    public HouseTemplates.Template template() {
        return template;
    }

    public BlockPos origin() {
        return origin;
    }

    public Direction front() {
        return front;
    }

    /** All blocks, floor first, each layer from the outside in. */
    public List<Cell> cells() {
        return cells;
    }

    /** Local (x, layer, z) to world: layer 0 is the floor at origin's height. */
    public BlockPos toWorld(int x, int y, int z) {
        int w = HouseTemplates.Template.WIDTH - 1;
        int d = HouseTemplates.Template.DEPTH - 1;
        return switch (front) {
            case SOUTH -> origin.offset(w - x, y, d - z);
            case EAST -> origin.offset(d - z, y, x);
            case WEST -> origin.offset(z, y, w - x);
            default -> origin.offset(x, y, z); // NORTH
        };
    }

    /** The house's size along world x and z. */
    public int sizeX() {
        return front.getAxis() == Direction.Axis.Z ? HouseTemplates.Template.WIDTH : HouseTemplates.Template.DEPTH;
    }

    public int sizeZ() {
        return front.getAxis() == Direction.Axis.Z ? HouseTemplates.Template.DEPTH : HouseTemplates.Template.WIDTH;
    }

    /** Height of the floor (where the bot stands inside is one higher). */
    public int floorY() {
        return origin.getY();
    }

    public int topY() {
        return origin.getY() + template.height() - 1;
    }

    /** The middle of the room, where the bot stands. */
    public BlockPos center() {
        return toWorld(HouseTemplates.Template.WIDTH / 2, 1, HouseTemplates.Template.DEPTH / 2);
    }

    /** The doorway, outside the front wall (where you walk in). */
    public BlockPos doorstep() {
        return toWorld(3, 1, 0).relative(front);
    }

    public boolean inFootprint(BlockPos pos) {
        return pos.getX() >= origin.getX() && pos.getX() < origin.getX() + sizeX()
            && pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + sizeZ();
    }

    /** What a cell of this kind must be built from. */
    public static Predicate<ItemStack> material(char kind) {
        return switch (kind) {
            case 'C' -> Blueprint::isStone;
            case 'P' -> stack -> stack.is(ItemTags.PLANKS);
            case 'L' -> stack -> stack.is(ItemTags.LOGS);
            case 'G' -> stack -> stack.is(Items.GLASS_PANE) || stack.is(Items.GLASS);
            case 'D' -> stack -> stack.is(ItemTags.WOODEN_DOORS);
            case 'B' -> stack -> stack.is(ItemTags.BEDS);
            case 'T' -> stack -> stack.is(Items.CRAFTING_TABLE);
            case 'H' -> stack -> stack.is(Items.CHEST);
            case 'S' -> stack -> stack.is(Items.SMOKER);
            case 't' -> stack -> stack.is(Items.TORCH);
            default -> stack -> false;
        };
    }

    public static boolean isStone(ItemStack stack) {
        return stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE) || stack.is(Items.STONE)
            || stack.is(Items.STONE_BRICKS) || stack.is(Items.MOSSY_COBBLESTONE);
    }

    /** Is the cell built already? ('.' cells: is it empty?) */
    public static boolean isDone(char kind, BlockState state) {
        return switch (kind) {
            case '.' -> state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty()
                || state.is(BlockTags.BEDS) || state.is(Blocks.CRAFTING_TABLE) || state.is(BlockTags.DOORS);
            case 'C' -> isStone(new ItemStack(state.getBlock().asItem()));
            case 'P' -> state.is(BlockTags.PLANKS);
            case 'L' -> state.is(BlockTags.LOGS);
            case 'G' -> state.getBlock() instanceof IronBarsBlock || state.is(Blocks.GLASS) || state.is(BlockTags.IMPERMEABLE);
            case 'D' -> state.is(BlockTags.DOORS);
            case 'B' -> state.is(BlockTags.BEDS);
            case 'T' -> state.is(Blocks.CRAFTING_TABLE);
            case 'H' -> state.is(Blocks.CHEST);
            case 'S' -> state.is(Blocks.SMOKER);
            case 't' -> state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH);
            default -> true;
        };
    }
}
