package com.minebot.bot.ai;

import com.minebot.bot.build.GreatBuild;
import com.minebot.bot.craft.Sources;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Where stone and ore may be dug while it's in force: only underground (out of sight, no holes
 * in the landscape), from one mine (looked for around {@code centre}, which follows the digging).
 * Logs, sand, gravel and cobwebs are got as usual.
 */
public final class MiningRule {
    /** At least this far under the surface. */
    private static final int DEPTH = 8;

    private final ServerLevel level;
    private BlockPos centre;
    private final int ceiling;

    private MiningRule(ServerLevel level, BlockPos centre, int ceiling) {
        this.level = level;
        this.centre = centre;
        this.ceiling = ceiling;
    }

    /** Anywhere underground, from a mine around {@code centre}. */
    public static MiningRule underground(ServerLevel level, BlockPos centre) {
        return new MiningRule(level, centre, Integer.MAX_VALUE);
    }

    /** Under the Great Build's site, below its cellars. */
    public static MiningRule underSite(ServerLevel level, GreatBuild build) {
        int ceiling = build.mineCeiling();
        return new MiningRule(level, build.centre().atY(ceiling - 8), ceiling);
    }

    public boolean appliesTo(Sources.Mine source) {
        return source != Sources.LOGS && source != Sources.SAND && source != Sources.GRAVEL && source != Sources.COBWEB
            && source != Sources.FLOWERS && source != Sources.CACTUS; // (those grow on top)
    }

    public boolean allows(BlockPos pos) {
        return pos.getY() <= ceiling
            && pos.getY() <= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - DEPTH;
    }

    public BlockPos centre() {
        return centre;
    }

    /** The mine goes on from where it got to. */
    public void dugAt(BlockPos pos) {
        centre = pos;
    }
}
