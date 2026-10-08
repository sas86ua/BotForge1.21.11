package com.minebot.bot.build;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Block states by name, as the newer ".schem" files have them ("minecraft:oak_stairs[facing=east]"),
 * turned into what goes in a plan's cell: empty, ground, left alone, or a block to build.
 */
public final class ModernBlocks {
    private ModernBlocks() {
    }

    public static LegacyBlocks.Spec spec(String name) {
        BlockState state;
        try {
            state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, name, false).blockState();
        } catch (Exception e) {
            return LegacyBlocks.Spec.SKIP; // (a block this game doesn't have)
        }
        return spec(state);
    }

    public static LegacyBlocks.Spec spec(BlockState state) {
        if (state.isAir() || state.is(Blocks.STRUCTURE_VOID)) {
            return LegacyBlocks.Spec.AIR;
        }
        if (state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH)) {
            return LegacyBlocks.Spec.GROUND;
        }
        // Water, lava, fire; plants, leaves, snow, vines: left as they are
        if (!state.getFluidState().isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
            || state.is(BlockTags.LEAVES) || state.is(BlockTags.FIRE) || state.canBeReplaced()) {
            return LegacyBlocks.Spec.SKIP;
        }
        // The second half of a door, a bed, a tall plant comes with the first
        if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD
            || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return new LegacyBlocks.Spec(LegacyBlocks.Kind.COMPANION, state);
        }
        return new LegacyBlocks.Spec(LegacyBlocks.Kind.EXACT, state);
    }
}
