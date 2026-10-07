package com.minebot.bot.path;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * A list of feet positions, each with the move that leads to it.
 * {@code complete} is false when the search ran out of budget or the goal is
 * unreachable; the path then ends at the closest point found.
 */
public record Path(List<Step> steps, boolean complete) {
    public record Step(BlockPos pos, Move move) {
    }

    public int size() {
        return steps.size();
    }

    public Step get(int index) {
        return steps.get(index);
    }

    public BlockPos end() {
        return steps.get(steps.size() - 1).pos();
    }
}
