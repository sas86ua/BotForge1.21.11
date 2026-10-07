package com.minebot.bot.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Areas set by an admin (a big village, a town) where bots don't dig, build,
 * hunt, take beds or open chests. They may walk through. Whole columns, any
 * height. Saved with the world.
 */
public class NoGoAreas extends SavedData {
    public record Area(ResourceKey<Level> dimension, int minX, int minZ, int maxX, int maxZ, String name) {
        public static final Codec<Area> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Area::dimension),
            Codec.INT.fieldOf("min_x").forGetter(Area::minX),
            Codec.INT.fieldOf("min_z").forGetter(Area::minZ),
            Codec.INT.fieldOf("max_x").forGetter(Area::maxX),
            Codec.INT.fieldOf("max_z").forGetter(Area::maxZ),
            Codec.STRING.optionalFieldOf("name", "").forGetter(Area::name)
        ).apply(i, Area::new));

        public static Area of(ResourceKey<Level> dimension, int x1, int z1, int x2, int z2, String name) {
            return new Area(dimension, Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2), name);
        }

        public boolean contains(ResourceKey<Level> dimension, int x, int z) {
            return this.dimension == dimension && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public String describe() {
            return (name.isEmpty() ? "" : name + ": ") + minX + " " + minZ + " .. " + maxX + " " + maxZ
                + " (" + dimension.identifier().getPath() + ")";
        }
    }

    private static final Codec<NoGoAreas> CODEC = RecordCodecBuilder.create(i -> i.group(
        Area.CODEC.listOf().fieldOf("areas").forGetter(data -> data.areas)
    ).apply(i, NoGoAreas::new));

    private static final SavedDataType<NoGoAreas> TYPE =
        new SavedDataType<>("minebot_no_go_areas", NoGoAreas::new, CODEC, null);

    /** Checked for every block the path finder wants to dig: keep a direct reference. */
    private static @Nullable NoGoAreas current;

    private final List<Area> areas;

    public NoGoAreas() {
        this(List.of());
    }

    private NoGoAreas(List<Area> areas) {
        this.areas = new ArrayList<>(areas);
    }

    public static NoGoAreas get(MinecraftServer server) {
        NoGoAreas data = server.overworld().getDataStorage().computeIfAbsent(TYPE);
        current = data;
        return data;
    }

    /** Server stopped: the next world may have other areas. */
    public static void forget() {
        current = null;
    }

    public static boolean contains(ServerLevel level, BlockPos pos) {
        NoGoAreas data = current != null ? current : get(level.getServer());
        for (Area area : data.areas) {
            if (area.contains(level.dimension(), pos.getX(), pos.getZ())) {
                return true;
            }
        }
        return false;
    }

    public List<Area> areas() {
        return Collections.unmodifiableList(areas);
    }

    public void add(Area area) {
        areas.add(area);
        setDirty();
    }

    /** @param index 0-based */
    public boolean remove(int index) {
        if (index < 0 || index >= areas.size()) {
            return false;
        }
        areas.remove(index);
        setDirty();
        return true;
    }
}
