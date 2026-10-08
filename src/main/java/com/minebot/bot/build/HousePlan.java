package com.minebot.bot.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Predicate;

/**
 * A house placed in the world, block by block: one of the bots' own simple templates
 * ({@link Blueprint}) or a house read from a schematic ({@link SchematicHouse}).
 *
 * <p>Cell kinds: '.' must be empty, 'g' ground (any solid block), 's' the top of the ground (dirt),
 * 'X' exactly the cell's state, 'B' the bed's foot, 'T' the crafting table; the templates' own
 * letters (see {@link HouseTemplates}) for those.
 */
public interface HousePlan {
    String name();

    /** Where it stands: for a template the floor's lowest north-west corner; for a schematic its lowest layer's. */
    BlockPos origin();

    Direction front();

    /** All blocks in the order they're built: bottom up, each layer from the outside in. */
    List<Blueprint.Cell> cells();

    int sizeX();

    int sizeZ();

    /** The floor (the top of the ground under the house): where the ground is levelled to. */
    int floorY();

    int topY();

    /** The middle of the house, where the bot stands inside. */
    BlockPos center();

    /** The same plan, moved up or down so its floor is at {@code floorY}. */
    HousePlan atFloor(int floorY);

    boolean fromSchematic();

    /** What this cell is built from. */
    Predicate<ItemStack> material(Blueprint.Cell cell);

    /** Is the cell as the plan has it already? */
    boolean isDone(Blueprint.Cell cell, BlockState state);

    /** The way from the bed's foot (a 'B' cell) to its head. */
    Direction bedHead(Blueprint.Cell foot);

    /** Does the house take this item (to keep it in the bag while building)? */
    boolean uses(Item item);

    default boolean inFootprint(BlockPos pos) {
        return pos.getX() >= origin().getX() && pos.getX() < origin().getX() + sizeX()
            && pos.getZ() >= origin().getZ() && pos.getZ() < origin().getZ() + sizeZ();
    }
}
