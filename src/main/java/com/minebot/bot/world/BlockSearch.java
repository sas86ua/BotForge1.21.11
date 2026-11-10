package com.minebot.bot.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Finds blocks around a point, nearest first. Searches chunk rings outwards,
 * skips sections whose palette can't contain a match, and only reads chunks
 * that are already loaded.
 */
public final class BlockSearch {
    /** As far as a search reaches (what a found block is packed into allows for no more). */
    private static final int MAX_RADIUS = 4000;
    /** A block found, in one number: how far it is (squared) on top - so they sort nearest first - and where, from the centre. */
    private static final int DISTANCE_SHIFT = 38;
    private static final int X_SHIFT = 25;
    private static final int Z_SHIFT = 12;
    private static final int XZ_OFFSET = 4096;
    private static final int Y_OFFSET = 2048;

    private BlockSearch() {
    }

    /**
     * The {@code limit} nearest blocks that match and are accepted, nearest first.
     *
     * @param matches cheap block-state test (also used to skip whole sections)
     * @param accept  extra per-position check (protected areas, natural trees...): asked of the nearest ones only, one
     *                after the other till there are enough - not of every block that matches (looking for a dozen blocks
     *                of stone to dig, it was asked of every block of stone in nine chunks, each time one was dug: a third
     *                of the server's tick in a session of the Great Build, fifteen bots quarrying under it)
     */
    public static List<BlockPos> find(ServerLevel level, BlockPos center, int radius, int minY, int maxY,
                                      Predicate<BlockState> matches, BiPredicate<BlockPos, BlockState> accept, int limit) {
        List<BlockPos> found = new ArrayList<>();
        if (limit <= 0) {
            return found;
        }
        radius = Math.min(radius, MAX_RADIUS);
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int maxRing = (radius >> 4) + 1;
        long radiusSq = (long) radius * radius;
        minY = Math.max(minY, level.getMinY());
        maxY = Math.min(maxY, level.getMaxY());
        // From the centre to the nearest edge of its own chunk: with the rings looked at so far, every block this near is known
        int toEdge = Math.min(Math.min(center.getX() & 15, 15 - (center.getX() & 15)), Math.min(center.getZ() & 15, 15 - (center.getZ() & 15)));
        LongArrayList pending = new LongArrayList();

        for (int ring = 0; ring <= maxRing && found.size() < limit; ring++) {
            for (int cx = centerChunkX - ring; cx <= centerChunkX + ring; cx++) {
                for (int cz = centerChunkZ - ring; cz <= centerChunkZ + ring; cz++) {
                    if (Math.max(Math.abs(cx - centerChunkX), Math.abs(cz - centerChunkZ)) != ring) {
                        continue; // only the ring's border
                    }
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk != null) {
                        scanChunk(chunk, center, radiusSq, minY, maxY, matches, pending);
                    }
                }
            }
            // Blocks in later rings are further than this: those found nearer are in their final order, and are looked at now
            long known = ring * 16L + toEdge;
            long bound = ring == maxRing ? Long.MAX_VALUE : (known * known + 1) << DISTANCE_SHIFT; // (packed, anything that near is below it)
            takeNearest(level, center, pending, bound, accept, limit, found);
        }
        return found;
    }

    /** The blocks of a chunk that match, within the radius: noted, not yet asked about. */
    private static void scanChunk(LevelChunk chunk, BlockPos center, long radiusSq, int minY, int maxY,
                                  Predicate<BlockState> matches, LongArrayList pending) {
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            int sectionMinY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            if (section.hasOnlyAir() || sectionMinY + 15 < minY || sectionMinY > maxY || !section.maybeHas(matches)) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                int worldY = sectionMinY + y;
                long dy = worldY - center.getY();
                if (worldY < minY || worldY > maxY || dy * dy > radiusSq || dy < -Y_OFFSET || dy >= Y_OFFSET) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    long dz = baseZ + z - center.getZ();
                    long rowSq = dy * dy + dz * dz;
                    if (rowSq > radiusSq) {
                        continue;
                    }
                    for (int x = 0; x < 16; x++) {
                        long dx = baseX + x - center.getX();
                        long distanceSq = rowSq + dx * dx;
                        if (distanceSq <= radiusSq && matches.test(section.getBlockState(x, y, z))) {
                            pending.add(distanceSq << DISTANCE_SHIFT | (dx + XZ_OFFSET) << X_SHIFT | (dz + XZ_OFFSET) << Z_SHIFT | (dy + Y_OFFSET));
                        }
                    }
                }
            }
        }
    }

    /**
     * Of the blocks noted, those below {@code bound} (near enough): asked about nearest first and added to
     * {@code found}, till there are {@code limit}. They leave the list; the further ones wait for the next ring.
     */
    private static void takeNearest(ServerLevel level, BlockPos center, LongArrayList pending, long bound,
                                    BiPredicate<BlockPos, BlockState> accept, int limit, List<BlockPos> found) {
        long[] keys = pending.elements();
        int size = pending.size();
        // The near ones to the front
        int near = 0;
        for (int i = 0; i < size; i++) {
            if (keys[i] < bound) {
                long key = keys[i];
                keys[i] = keys[near];
                keys[near++] = key;
            }
        }
        // ...as a heap, nearest on top: only as many as it takes are ever put in order
        int heap = near;
        for (int i = heap / 2 - 1; i >= 0; i--) {
            siftDown(keys, heap, i);
        }
        LevelChunk chunk = null;
        while (heap > 0 && found.size() < limit) {
            long key = keys[0];
            keys[0] = keys[--heap];
            siftDown(keys, heap, 0);
            BlockPos pos = new BlockPos(center.getX() + (int) (key >> X_SHIFT & 0x1FFF) - XZ_OFFSET,
                center.getY() + (int) (key & 0xFFF) - Y_OFFSET, center.getZ() + (int) (key >> Z_SHIFT & 0x1FFF) - XZ_OFFSET);
            if (chunk == null || chunk.getPos().x != pos.getX() >> 4 || chunk.getPos().z != pos.getZ() >> 4) {
                chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            }
            if (chunk != null && accept.test(pos, chunk.getBlockState(pos))) {
                found.add(pos);
            }
        }
        pending.removeElements(0, near);
    }

    private static void siftDown(long[] heap, int size, int at) {
        long key = heap[at];
        while (true) {
            int child = 2 * at + 1;
            if (child >= size) {
                break;
            }
            if (child + 1 < size && heap[child + 1] < heap[child]) {
                child++;
            }
            if (heap[child] >= key) {
                break;
            }
            heap[at] = heap[child];
            at = child;
        }
        heap[at] = key;
    }
}
