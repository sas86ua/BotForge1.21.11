package com.minebot.bot.build;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a house of a design is furnished: chests, furnaces, the crafting table... where an admin put them in one house of
 * it, in the design's own coordinates (so any house of it, whichever way it faces, gets them in the same places). Saved
 * with /bot house layout; a file per design in minebot/layouts.
 */
public final class HouseLayouts {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** One piece: the block, where in the design (x, layer, z) and which way it faces there (null: no facing). */
    public record Piece(Block block, int x, int y, int z, @Nullable Direction facing) {
    }

    /** One placed piece in a house: where in the world, facing which way. */
    public record Spot(Block block, BlockPos pos, @Nullable Direction facing) {
    }

    private static final Map<String, List<Piece>> LAYOUTS = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    /** What's furniture: what the bots put in a house themselves. */
    private static final List<Block> FURNITURE = List.of(Blocks.CHEST, Blocks.BARREL, Blocks.FURNACE, Blocks.SMOKER,
        Blocks.BLAST_FURNACE, Blocks.CRAFTING_TABLE);

    private HouseLayouts() {
    }

    private static Path dir(MinecraftServer server) {
        return server.getServerDirectory().resolve("minebot").resolve("layouts");
    }

    private static String key(HouseSchematics.Design design) {
        return design.name().replace(' ', '_');
    }

    private static void load(MinecraftServer server) {
        if (loaded) {
            return;
        }
        loaded = true;
        try (var files = Files.list(dir(server))) {
            files.forEach(file -> {
                String name = file.getFileName().toString();
                if (!name.endsWith(".txt")) {
                    return;
                }
                List<Piece> pieces = new ArrayList<>();
                try {
                    for (String line : Files.readAllLines(file)) {
                        String[] part = line.trim().split("\\s+");
                        if (part.length < 4) {
                            continue;
                        }
                        Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(part[0])).orElse(null);
                        if (block == null) {
                            continue;
                        }
                        Direction facing = part.length > 4 ? Direction.byName(part[4]) : null;
                        pieces.add(new Piece(block, Integer.parseInt(part[1]), Integer.parseInt(part[2]), Integer.parseInt(part[3]), facing));
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("Can't read house layout {}", file, e);
                }
                LAYOUTS.put(name.substring(0, name.length() - 4), List.copyOf(pieces));
            });
        } catch (IOException e) {
            // (no layouts yet)
        }
    }

    public static List<Piece> of(MinecraftServer server, HouseSchematics.Design design) {
        load(server);
        return LAYOUTS.getOrDefault(key(design), List.of());
    }

    /** The pieces of this house's layout, in the world. */
    public static List<Spot> spots(MinecraftServer server, SchematicHouse house) {
        List<Spot> spots = new ArrayList<>();
        for (Piece piece : of(server, house.design())) {
            Direction facing = piece.facing() == null ? null : house.turn(piece.facing());
            spots.add(new Spot(piece.block(), house.localToWorld(piece.x(), piece.y(), piece.z()), facing));
        }
        return spots;
    }

    /**
     * Reads the furniture of the house of this design whose corner is at {@code origin} (the way it faces worked out from
     * how well each way fits what stands there) and saves it as the design's layout. @return a line for the admin
     */
    public static String save(MinecraftServer server, ServerLevel level, HouseSchematics.Design design, BlockPos origin) throws IOException {
        SchematicHouse best = null;
        int bestScore = -1;
        for (Direction front : Direction.Plane.HORIZONTAL) {
            for (int wood = 0; wood < HouseSchematics.WOODS.length; wood++) {
                SchematicHouse plan = new SchematicHouse(design, origin, front, wood);
                int score = 0;
                for (Blueprint.Cell cell : plan.cells()) {
                    if (cell.kind() == 'X' && cell.state() != null && level.getBlockState(cell.pos()).getBlock() == cell.state().getBlock()) {
                        score++;
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = plan;
                }
            }
        }
        List<Piece> pieces = new ArrayList<>();
        StringBuilder file = new StringBuilder();
        Schematic s = design.schematic();
        for (int y = 0; y < s.height(); y++) {
            for (int z = 0; z < s.length(); z++) {
                for (int x = 0; x < s.width(); x++) {
                    BlockPos pos = best.localToWorld(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!FURNITURE.contains(state.getBlock())) {
                        continue;
                    }
                    Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                        ? best.unturn(state.getValue(BlockStateProperties.HORIZONTAL_FACING)) : null;
                    pieces.add(new Piece(state.getBlock(), x, y, z, facing));
                    file.append(BuiltInRegistries.BLOCK.getKey(state.getBlock())).append(' ').append(x).append(' ').append(y).append(' ')
                        .append(z).append(facing != null ? " " + facing.getName() : "").append('\n');
                }
            }
        }
        Files.createDirectories(dir(server));
        Files.writeString(dir(server).resolve(key(design) + ".txt"), file.toString());
        load(server);
        LAYOUTS.put(key(design), List.copyOf(pieces));
        return design.name() + " facing " + best.front() + " (" + bestScore + " blocks fit): " + pieces.size() + " pieces of furniture saved";
    }

    /** A layout spot for this item in the bot's house that's free to put it in (and which way it faces), or null. */
    public static @Nullable Spot freeSpot(MinecraftServer server, ServerLevel level, @Nullable HousePlan plan, Item item) {
        if (!(plan instanceof SchematicHouse house)) {
            return null;
        }
        for (Spot spot : spots(server, house)) {
            if (spot.block().asItem() == item && level.isLoaded(spot.pos()) && level.getBlockState(spot.pos()).canBeReplaced()) {
                return spot;
            }
        }
        return null;
    }

    /** Is there a layout for this house's design? */
    public static boolean has(MinecraftServer server, @Nullable HousePlan plan) {
        return plan instanceof SchematicHouse house && !of(server, house.design()).isEmpty();
    }
}
