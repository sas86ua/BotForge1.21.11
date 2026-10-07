package com.minebot.bot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** Up to {@link #MAX} places where bots appear and respawn. Saved with the world. */
public class BotSpawnPoints extends SavedData {
    public static final int MAX = 5;

    public record Point(GlobalPos pos, float yaw) {
        public static final Codec<Point> CODEC = RecordCodecBuilder.create(i -> i.group(
            GlobalPos.CODEC.fieldOf("pos").forGetter(Point::pos),
            Codec.FLOAT.fieldOf("yaw").forGetter(Point::yaw)
        ).apply(i, Point::new));

        public Vec3 position() {
            return Vec3.atBottomCenterOf(pos.pos());
        }
    }

    /** A spawn point resolved to a loaded level. */
    public record Location(ServerLevel level, Vec3 pos, float yaw) {
    }

    private static final Codec<BotSpawnPoints> CODEC = RecordCodecBuilder.create(i -> i.group(
        Point.CODEC.listOf().fieldOf("points").forGetter(data -> data.points)
    ).apply(i, BotSpawnPoints::new));

    // Forge allows a null data fixer for mod data
    private static final SavedDataType<BotSpawnPoints> TYPE =
        new SavedDataType<>("minebot_spawn_points", BotSpawnPoints::new, CODEC, null);

    private final List<Point> points;

    public BotSpawnPoints() {
        this(List.of());
    }

    private BotSpawnPoints(List<Point> points) {
        this.points = new ArrayList<>(points);
    }

    public static BotSpawnPoints get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public List<Point> points() {
        return Collections.unmodifiableList(points);
    }

    public boolean add(Point point) {
        if (points.size() >= MAX) {
            return false;
        }
        points.add(point);
        setDirty();
        return true;
    }

    /** @param index 0-based */
    public boolean remove(int index) {
        if (index < 0 || index >= points.size()) {
            return false;
        }
        points.remove(index);
        setDirty();
        return true;
    }

    public @Nullable Location pickRandom(MinecraftServer server, RandomSource random) {
        return pickRandom(server, random, location -> true);
    }

    /** A random spawn point among those {@code allowed} (e.g. inside a bot's zone). */
    public @Nullable Location pickRandom(MinecraftServer server, RandomSource random, Predicate<Location> allowed) {
        List<Location> available = new ArrayList<>();
        for (Point point : points) {
            ServerLevel level = server.getLevel(point.pos().dimension());
            if (level != null && allowed.test(new Location(level, point.position(), point.yaw()))) {
                available.add(new Location(level, point.position(), point.yaw()));
            }
        }
        return available.isEmpty() ? null : available.get(random.nextInt(available.size()));
    }
}
