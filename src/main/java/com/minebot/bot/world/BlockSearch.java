package com.minebot.bot.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Finds blocks around a point, nearest first. Searches chunk rings outwards,
 * skips sections whose palette can't contain a match, and only reads chunks
 * that are already loaded.
 */
public final class BlockSearch {
    private BlockSearch() {
    }

    /**
     * @param matches cheap block-state test (also used to skip whole sections)
     * @param accept  extra per-position check (protected areas, natural trees...)
     */
    public static List<BlockPos> find(ServerLevel level, BlockPos center, int radius, int minY, int maxY,
                                      Predicate<BlockState> matches, BiPredicate<BlockPos, BlockState> accept, int limit) {
        List<BlockPos> found = new ArrayList<>();
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int maxRing = (radius >> 4) + 1;
        long radiusSq = (long) radius * radius;
        minY = Math.max(minY, level.getMinY());
        maxY = Math.min(maxY, level.getMaxY());

        for (int ring = 0; ring <= maxRing; ring++) {
            for (int cx = centerChunkX - ring; cx <= centerChunkX + ring; cx++) {
                for (int cz = centerChunkZ - ring; cz <= centerChunkZ + ring; cz++) {
                    if (Math.max(Math.abs(cx - centerChunkX), Math.abs(cz - centerChunkZ)) != ring) {
                        continue; // only the ring's border
                    }
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk != null) {
                        scanChunk(chunk, center, radiusSq, minY, maxY, matches, accept, found);
                    }
                }
            }
            // Blocks in later rings are at least (ring * 16) away; stop once we have enough closer ones
            if (found.size() >= limit) {
                double ringDistance = ring * 16.0;
                long closeEnough = found.stream().filter(pos -> horizontalDistSq(pos, center) <= ringDistance * ringDistance).count();
                if (closeEnough >= limit) {
                    break;
                }
            }
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    private static void scanChunk(LevelChunk chunk, BlockPos center, long radiusSq, int minY, int maxY,
                                  Predicate<BlockState> matches, BiPredicate<BlockPos, BlockState> accept, List<BlockPos> found) {
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            int sectionMinY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            if (section.hasOnlyAir() || sectionMinY + 15 < minY || sectionMinY > maxY || !section.maybeHas(matches)) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                int worldY = sectionMinY + y;
                if (worldY < minY || worldY > maxY) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!matches.test(state)) {
                            continue;
                        }
                        cursor.set(baseX + x, worldY, baseZ + z);
                        if (horizontalDistSq(cursor, center) + sq(worldY - center.getY()) <= radiusSq
                            && accept.test(cursor, state)) {
                            found.add(cursor.immutable());
                        }
                    }
                }
            }
        }
    }

    private static long horizontalDistSq(BlockPos pos, BlockPos center) {
        return sq(pos.getX() - center.getX()) + sq(pos.getZ() - center.getZ());
    }

    private static long sq(long value) {
        return value * value;
    }
}
