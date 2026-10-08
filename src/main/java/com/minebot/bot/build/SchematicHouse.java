package com.minebot.bot.build;

import com.minebot.bot.action.Inv;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A house from a schematic ({@link HouseSchematics}), turned to face {@code front} and placed with
 * its lowest layer's north-west corner at {@code origin}. One bed only (the bot's own, moved in
 * last), and a crafting table; a house without them gets a spot for each picked inside.
 */
public final class SchematicHouse implements HousePlan {
    private final HouseSchematics.Design design;
    private final BlockPos origin;
    private final Direction front;
    private final int steps;
    private final List<Blueprint.Cell> cells = new ArrayList<>();
    private final Set<Item> items = new HashSet<>();

    public SchematicHouse(HouseSchematics.Design design, BlockPos origin, Direction front) {
        this.design = design;
        this.origin = origin;
        this.front = front;
        this.steps = Math.floorMod(front.get2DDataValue() - design.front().get2DDataValue(), 4);
        Rotation rotation = Rotation.values()[steps];
        Schematic s = design.schematic();
        int w = s.width();
        int h = s.height();
        int l = s.length();
        char[] kinds = new char[s.size()];
        BlockState[] states = new BlockState[s.size()];
        boolean bed = false;
        boolean table = false;
        for (int i = 0; i < s.size(); i++) {
            LegacyBlocks.Spec spec = s.at(i);
            kinds[i] = ' ';
            switch (spec.kind()) {
                case AIR -> kinds[i] = '.';
                case GROUND -> kinds[i] = 'g';
                case SOIL -> kinds[i] = 's';
                case EXACT -> {
                    BlockState state = HouseSchematics.adapt(spec.state());
                    if (state == null) {
                        break;
                    }
                    states[i] = state;
                    if (state.is(BlockTags.BEDS)) {
                        kinds[i] = bed ? ' ' : 'B'; // (one bed: the bot's own)
                        bed = true;
                    } else if (state.is(Blocks.CRAFTING_TABLE) && !table) {
                        kinds[i] = 'T';
                        table = true;
                    } else {
                        kinds[i] = 'X';
                    }
                }
                default -> {
                }
            }
        }
        if (!bed || !table) {
            furnish(kinds, states, bed, table);
        }
        for (int y = 0; y < h; y++) {
            List<Blueprint.Cell> layer = new ArrayList<>();
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    int i = s.index(x, y, z);
                    if (kinds[i] == ' ') {
                        continue;
                    }
                    BlockState state = states[i] != null ? states[i].rotate(rotation) : null;
                    layer.add(new Blueprint.Cell(toWorld(x, y, z), kinds[i], y, state));
                    if (state != null) {
                        items.add(state.getBlock().asItem());
                    }
                }
            }
            // Outside in: every block of a ceiling or roof then has a neighbour to be placed against
            BlockPos middle = toWorld(w / 2, y, l / 2);
            layer.sort(Comparator.comparingDouble((Blueprint.Cell cell) -> cell.pos().distSqr(middle)).reversed());
            cells.addAll(layer);
        }
    }

    /**
     * No bed or crafting table in the plan: a spot for each inside, on the lowest floor near the
     * middle - an empty cell over a solid one, room to stand above it, a roof over it, not by a door.
     */
    private void furnish(char[] kinds, BlockState[] states, boolean bed, boolean table) {
        Schematic s = design.schematic();
        int w = s.width();
        int l = s.length();
        int cx = w / 2;
        int cz = l / 2;
        int bestFoot = -1;
        Direction bestDir = null;
        double bestScore = Double.MAX_VALUE;
        for (int y = design.groundLayer() + 1; y < s.height() - 2; y++) {
            for (int z = 1; z < l - 1; z++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = s.index(x, y, z);
                    if (!spot(kinds, states, x, y, z)) {
                        continue;
                    }
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        int hx = x + dir.getStepX();
                        int hz = z + dir.getStepZ();
                        if (hx <= 0 || hz <= 0 || hx >= w - 1 || hz >= l - 1 || !spot(kinds, states, hx, y, hz)) {
                            continue;
                        }
                        double score = y * 1000 + Math.abs(x - cx) + Math.abs(z - cz);
                        if (score < bestScore) {
                            bestScore = score;
                            bestFoot = i;
                            bestDir = dir;
                        }
                    }
                }
            }
        }
        if (bestFoot < 0) {
            return;
        }
        int footX = bestFoot % w;
        int footZ = (bestFoot / w) % l;
        int footY = bestFoot / (w * l);
        int headI = s.index(footX + bestDir.getStepX(), footY, footZ + bestDir.getStepZ());
        if (!bed) {
            kinds[bestFoot] = 'B';
            states[bestFoot] = Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, bestDir);
        }
        if (!table) {
            // Nearest free spot to the bed, not where the bed goes
            int bestTable = -1;
            double tableScore = Double.MAX_VALUE;
            for (int z = 1; z < l - 1; z++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = s.index(x, footY, z);
                    if (i == bestFoot || i == headI || !spot(kinds, states, x, footY, z)) {
                        continue;
                    }
                    double score = Math.abs(x - footX) + Math.abs(z - footZ);
                    if (score >= 2 && score < tableScore) {
                        tableScore = score;
                        bestTable = i;
                    }
                }
            }
            if (bestTable >= 0) {
                kinds[bestTable] = 'T';
                states[bestTable] = Blocks.CRAFTING_TABLE.defaultBlockState();
            }
        }
    }

    /** An empty cell inside the house to put something on the floor: solid under it, room over it, a roof, no door by it. */
    private boolean spot(char[] kinds, BlockState[] states, int x, int y, int z) {
        Schematic s = design.schematic();
        int i = s.index(x, y, z);
        if (kinds[i] != '.' || y + 1 >= s.height() || kinds[s.index(x, y + 1, z)] != '.' || y == 0) {
            return false;
        }
        int below = s.index(x, y - 1, z);
        if (kinds[below] != 'X' || states[below] == null
            || states[below].getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
            return false;
        }
        boolean roof = false;
        for (int up = y + 2; up < Math.min(s.height(), y + 9) && !roof; up++) {
            roof = kinds[s.index(x, up, z)] == 'X';
        }
        if (!roof) {
            return false;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int nx = x + dir.getStepX();
            int nz = z + dir.getStepZ();
            if (nx >= 0 && nz >= 0 && nx < s.width() && nz < s.length()) {
                BlockState side = states[s.index(nx, y, nz)];
                if (side != null && (side.is(BlockTags.DOORS) || side.is(BlockTags.BEDS))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Local (x, layer, z) to world, turned by {@link #steps} quarter turns clockwise. */
    private BlockPos toWorld(int x, int y, int z) {
        int w = design.schematic().width();
        int l = design.schematic().length();
        return switch (steps) {
            case 1 -> origin.offset(l - 1 - z, y, x);
            case 2 -> origin.offset(w - 1 - x, y, l - 1 - z);
            case 3 -> origin.offset(z, y, w - 1 - x);
            default -> origin.offset(x, y, z);
        };
    }

    public HouseSchematics.Design design() {
        return design;
    }

    @Override
    public String name() {
        return design.name();
    }

    @Override
    public BlockPos origin() {
        return origin;
    }

    @Override
    public Direction front() {
        return front;
    }

    @Override
    public List<Blueprint.Cell> cells() {
        return cells;
    }

    @Override
    public int sizeX() {
        return steps % 2 == 0 ? design.schematic().width() : design.schematic().length();
    }

    @Override
    public int sizeZ() {
        return steps % 2 == 0 ? design.schematic().length() : design.schematic().width();
    }

    @Override
    public int floorY() {
        return origin.getY() + design.groundLayer();
    }

    @Override
    public int topY() {
        return origin.getY() + design.schematic().height() - 1;
    }

    @Override
    public BlockPos center() {
        return toWorld(design.schematic().width() / 2, design.groundLayer() + 1, design.schematic().length() / 2);
    }

    @Override
    public HousePlan atFloor(int floorY) {
        return new SchematicHouse(design, origin.atY(floorY - design.groundLayer()), front);
    }

    @Override
    public boolean fromSchematic() {
        return true;
    }

    @Override
    public Predicate<ItemStack> material(Blueprint.Cell cell) {
        return switch (cell.kind()) {
            case 'g' -> Inv::isScaffold;
            case 's' -> stack -> stack.is(Items.DIRT) || stack.is(Items.GRASS_BLOCK) || stack.is(Items.COARSE_DIRT);
            case 'B' -> stack -> stack.is(ItemTags.BEDS);
            case 'T' -> stack -> stack.is(Items.CRAFTING_TABLE);
            case 'X' -> {
                Item item = cell.state().getBlock().asItem();
                yield stack -> stack.is(item);
            }
            default -> stack -> false;
        };
    }

    @Override
    public boolean isDone(Blueprint.Cell cell, BlockState state) {
        return switch (cell.kind()) {
            case '.' -> Blueprint.isDone('.', state);
            case 'g' -> !state.canBeReplaced() && state.getFluidState().isEmpty();
            case 's' -> !state.canBeReplaced() && state.getFluidState().isEmpty(); // (grass or dirt, or the rock that's there)
            case 'B' -> state.is(BlockTags.BEDS);
            case 'T' -> state.is(Blocks.CRAFTING_TABLE);
            case 'X' -> GreatBuild.isDone(new LegacyBlocks.Spec(LegacyBlocks.Kind.EXACT, cell.state()), state);
            default -> true;
        };
    }

    @Override
    public Direction bedHead(Blueprint.Cell foot) {
        return foot.state() != null && foot.state().hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            ? foot.state().getValue(BlockStateProperties.HORIZONTAL_FACING) : front.getOpposite();
    }

    @Override
    public boolean uses(Item item) {
        return items.contains(item);
    }
}
