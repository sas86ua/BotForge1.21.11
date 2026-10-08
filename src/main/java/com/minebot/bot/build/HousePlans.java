package com.minebot.bot.build;

import com.minebot.bot.BotMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The house plans by number, as a bot's memory keeps them: below {@link #SCHEMATIC_BASE} one of the
 * bots' own simple templates, from it on one of the schematic houses.
 */
public final class HousePlans {
    public static final int SCHEMATIC_BASE = 100;
    /** Plans made recently (a schematic house is thousands of cells: not worked out again every time it's asked). */
    private static final Map<String, HousePlan> CACHE = new ConcurrentHashMap<>();

    private HousePlans() {
    }

    /** Number of a schematic house: SCHEMATIC_BASE + design + 10 x wood (see HouseSchematics#WOODS). */
    public static int index(int design, int wood) {
        return SCHEMATIC_BASE + design + 10 * wood;
    }

    public static boolean isSchematic(int index) {
        return index >= SCHEMATIC_BASE && (index - SCHEMATIC_BASE) % 10 < HouseSchematics.all().size();
    }

    public static int designs() {
        return HouseSchematics.all().size();
    }

    public static HousePlan create(int index, BlockPos origin, Direction front) {
        if (isSchematic(index)) {
            return new SchematicHouse(HouseSchematics.all().get((index - SCHEMATIC_BASE) % 10), origin, front, (index - SCHEMATIC_BASE) / 10);
        }
        return new Blueprint(HouseTemplates.ALL.get(Math.floorMod(index, HouseTemplates.ALL.size())), origin, front);
    }

    /** The house this bot has (or is building), or null if none is planned. */
    public static @Nullable HousePlan of(BotMemory memory) {
        BlockPos origin = memory.houseOrigin();
        if (origin == null) {
            return null;
        }
        String key = memory.houseTemplate() + "@" + origin.asLong() + "/" + memory.houseFront();
        if (CACHE.size() > 64) {
            CACHE.clear();
        }
        return CACHE.computeIfAbsent(key, k -> create(memory.houseTemplate(), origin, memory.houseFront()));
    }
}
