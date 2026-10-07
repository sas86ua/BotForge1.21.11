package com.minebot.bot.build;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A building plan read from an MCEdit/WorldEdit ".schematic" file (the old format with numeric
 * block ids). Each cell holds an index into {@link #palette()}: what goes there (see
 * {@link LegacyBlocks.Kind}). Cells run x fastest, then z, then y (layer by layer, bottom up).
 */
public final class Schematic {
    private final int width;
    private final int height;
    private final int length;
    /** Where WorldEdit's paste point lies relative to the plan's lowest north-west corner. */
    private final BlockPos pastePoint;
    private final List<LegacyBlocks.Spec> palette;
    private final short[] cells;
    /** Old ids it didn't know, and how many cells of each (left as they are). */
    private final Map<Integer, Integer> unknown;

    private Schematic(int width, int height, int length, BlockPos pastePoint, List<LegacyBlocks.Spec> palette,
                      short[] cells, Map<Integer, Integer> unknown) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.pastePoint = pastePoint;
        this.palette = palette;
        this.cells = cells;
        this.unknown = unknown;
    }

    public static Schematic load(Path file) throws IOException {
        CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        int width = tag.getShortOr("Width", (short) 0);
        int height = tag.getShortOr("Height", (short) 0);
        int length = tag.getShortOr("Length", (short) 0);
        byte[] blocks = tag.getByteArray("Blocks").orElseThrow(() -> new IOException("no Blocks: not an old-style schematic"));
        byte[] data = tag.getByteArray("Data").orElse(new byte[blocks.length]);
        byte[] add = tag.getByteArray("AddBlocks").orElse(null);
        if (width <= 0 || height <= 0 || length <= 0 || blocks.length != width * height * length) {
            throw new IOException("bad size " + width + "x" + height + "x" + length);
        }
        // (WorldEdit: the paste point is where the player stood when copying, offset from the corner)
        BlockPos pastePoint = new BlockPos(-tag.getIntOr("WEOffsetX", 0), -tag.getIntOr("WEOffsetY", 0), -tag.getIntOr("WEOffsetZ", 0));

        List<LegacyBlocks.Spec> palette = new ArrayList<>();
        Map<LegacyBlocks.Spec, Short> indexOf = new HashMap<>();
        Map<Integer, Short> byRaw = new HashMap<>();
        Map<Integer, Integer> unknown = new TreeMap<>();
        short[] cells = new short[blocks.length];
        for (int i = 0; i < blocks.length; i++) {
            int id = blocks[i] & 0xFF;
            if (add != null) {
                int extra = add[i >> 1] & 0xFF;
                id |= ((i & 1) == 0 ? extra & 0x0F : extra >> 4) << 8;
            }
            int raw = id << 4 | data[i] & 0x0F;
            Short index = byRaw.get(raw);
            if (index == null) {
                LegacyBlocks.Spec spec = LegacyBlocks.of(id, data[i] & 0x0F);
                if (spec == null) {
                    spec = LegacyBlocks.Spec.SKIP;
                }
                index = indexOf.computeIfAbsent(spec, s -> {
                    palette.add(s);
                    return (short) (palette.size() - 1);
                });
                byRaw.put(raw, index);
            }
            if (LegacyBlocks.of(id, data[i] & 0x0F) == null) {
                unknown.merge(id, 1, Integer::sum);
            }
            cells[i] = index;
        }
        // Ground with nothing over it is the top of the ground: grass (or dirt), not just any rock
        short soil = (short) palette.size();
        palette.add(new LegacyBlocks.Spec(LegacyBlocks.Kind.SOIL, null));
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int i = (y * length + z) * width + x;
                    if (palette.get(cells[i]).kind() == LegacyBlocks.Kind.GROUND
                        && (y == height - 1 || palette.get(cells[i + width * length]).kind() == LegacyBlocks.Kind.AIR)) {
                        cells[i] = soil;
                    }
                }
            }
        }
        return new Schematic(width, height, length, pastePoint, List.copyOf(palette), cells, unknown);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int length() {
        return length;
    }

    public BlockPos pastePoint() {
        return pastePoint;
    }

    public List<LegacyBlocks.Spec> palette() {
        return palette;
    }

    public Map<Integer, Integer> unknown() {
        return unknown;
    }

    public int index(int x, int y, int z) {
        return (y * length + z) * width + x;
    }

    public LegacyBlocks.Spec at(int index) {
        return palette.get(cells[index]);
    }

    /** Which palette entry a cell holds. */
    public int paletteIndex(int index) {
        return cells[index];
    }

    public LegacyBlocks.Spec at(int x, int y, int z) {
        return at(index(x, y, z));
    }

    public int size() {
        return cells.length;
    }
}
