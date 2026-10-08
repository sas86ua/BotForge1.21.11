package com.minebot.bot.build;

import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.BlockPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Putting a block of a plan down the way the plan has it: clicked on the right face, looking the
 * right way (stairs, doors, beds, chests face by how the player stands), then turned if it still
 * came out wrong (upside-down stairs, top slabs, logs on their side).
 */
public final class Placement {
    private Placement() {
    }

    /** Places {@code wanted} at {@code pos} from the bag; false if nothing was placed (yet). */
    public static boolean place(BotPlayer bot, BlockPos pos, BlockState wanted) {
        Item item = wanted.getBlock().asItem();
        Direction facing = wanted.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            ? wanted.getValue(BlockStateProperties.HORIZONTAL_FACING) : null;
        Direction face = null;
        Direction look = null;
        if (wanted.is(Blocks.WALL_TORCH) || wanted.is(Blocks.LADDER)) {
            face = facing.getOpposite(); // (against the wall behind it)
        } else if (wanted.hasProperty(BlockStateProperties.HANGING) && wanted.getValue(BlockStateProperties.HANGING)) {
            face = Direction.UP; // (a lantern hung from the block above)
        } else if (wanted.is(Blocks.TORCH) || wanted.is(Blocks.LANTERN) || wanted.is(BlockTags.PRESSURE_PLATES)
            || wanted.is(BlockTags.WOOL_CARPETS) || wanted.is(Blocks.FLOWER_POT)) {
            face = Direction.DOWN;
        } else if (wanted.is(Blocks.CHEST) || wanted.is(Blocks.FURNACE) || wanted.is(Blocks.SMOKER) || wanted.is(Blocks.BLAST_FURNACE)) {
            look = facing != null ? facing.getOpposite() : null; // (they face whoever puts them down)
        } else {
            look = facing; // stairs, doors, beds, gates: the way the player looks
        }
        if (!BlockPlacer.place(bot, pos, stack -> stack.is(item), face, look)) {
            return false;
        }
        turn(bot.level(), pos, wanted);
        return true;
    }

    /** Placed the wrong way round (upside-down stairs, top slabs, logs on their side...): turned the right way. */
    public static void turn(ServerLevel level, BlockPos pos, BlockState wanted) {
        BlockState placed = level.getBlockState(pos);
        if (!placed.is(wanted.getBlock()) || wanted.is(BlockTags.BEDS) || wanted.is(BlockTags.DOORS)) {
            return;
        }
        BlockState turned = placed;
        for (Property<?> property : GreatBuild.ORIENTATION) {
            turned = copy(turned, wanted, property);
        }
        if (turned != placed) {
            level.setBlock(pos, Block.updateFromNeighbourShapes(turned, level, pos), Block.UPDATE_ALL);
        }
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState into, BlockState from, Property<T> property) {
        return from.hasProperty(property) && into.hasProperty(property) ? into.setValue(property, from.getValue(property)) : into;
    }
}
