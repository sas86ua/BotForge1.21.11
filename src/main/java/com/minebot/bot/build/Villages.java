package com.minebot.bot.build;

import com.minebot.bot.BotManager;
import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.BotRegistry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bots' villages and what they build together. Bots whose homes are within {@link #RADIUS} of
 * each other (one to the next) make a village; once all of them on the server live in a house of
 * their own (a schematic one), they build the village's buildings one after another - an old
 * church, then an old library - near the middle of the village, each doing a part.
 */
public final class Villages extends SavedData {
    public static final int RADIUS = 250;

    /** One of a village's buildings: which, where (its lowest layer's north-west corner), which way, finished? */
    public record Project(int building, BlockPos origin, Direction front, boolean done) {
        static final Codec<Project> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("building").forGetter(Project::building),
            BlockPos.CODEC.fieldOf("origin").forGetter(Project::origin),
            Direction.CODEC.fieldOf("front").forGetter(Project::front),
            Codec.BOOL.optionalFieldOf("done", false).forGetter(Project::done)
        ).apply(i, Project::new));
    }

    private static final Codec<Villages> CODEC = RecordCodecBuilder.create(i -> i.group(
        Project.CODEC.listOf().optionalFieldOf("projects", List.of()).forGetter(v -> v.projects)
    ).apply(i, Villages::new));
    private static final SavedDataType<Villages> TYPE = new SavedDataType<>("minebot_villages", Villages::new, CODEC, null);

    /** The village buildings, in the order they're built. */
    private static final String[][] FILES = {{"old church", "old_church.schematic"}, {"old library", "old_library.schematic"}};
    private static List<HouseSchematics.Design> buildings;
    private static final Map<Project, HousePlan> PLANS = new ConcurrentHashMap<>();

    private final List<Project> projects;

    public Villages() {
        this(List.of());
    }

    private Villages(List<Project> projects) {
        this.projects = new ArrayList<>(projects);
    }

    public static Villages get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static synchronized List<HouseSchematics.Design> buildings() {
        if (buildings == null) {
            buildings = HouseSchematics.load("village", FILES);
        }
        return buildings;
    }

    public List<Project> projects() {
        return projects;
    }

    /** A project's plan (of the building's own wood; no bed in it: nobody moves in). */
    public static HousePlan plan(Project project) {
        return PLANS.computeIfAbsent(project, p -> new SchematicHouse(buildings().get(p.building()), p.origin(), p.front(), 0, false));
    }

    public static String name(int building) {
        return building < buildings().size() ? buildings().get(building).name() : "building";
    }

    public void add(Project project) {
        projects.add(project);
        setDirty();
    }

    public void finish(Project project) {
        int index = projects.indexOf(project);
        if (index >= 0) {
            projects.set(index, new Project(project.building(), project.origin(), project.front(), true));
            setDirty();
        }
    }

    // ---- who lives where ------------------------------------------------------------------------

    /** Its home's spot (in the overworld), or null. */
    private static @Nullable BlockPos homeOf(BotMemory memory) {
        return memory.home() != null && memory.home().dimension() == Level.OVERWORLD ? memory.home().pos() : null;
    }

    /** The bots (all of them, on the server or not) whose homes make one village with this bot's. */
    public static List<BotMemory> villageOf(BotPlayer bot) {
        List<BotMemory> village = new ArrayList<>();
        BlockPos own = homeOf(bot.memory());
        if (own == null) {
            return village;
        }
        List<BotMemory> all = new ArrayList<>();
        for (BotMemory memory : BotRegistry.get(bot.level().getServer()).all()) {
            if (homeOf(memory) != null) {
                all.add(memory);
            }
        }
        village.add(bot.memory());
        for (int i = 0; i < village.size(); i++) {
            BlockPos from = homeOf(village.get(i));
            for (BotMemory other : all) {
                if (!village.contains(other) && homeOf(other).closerThan(from, RADIUS)) {
                    village.add(other);
                }
            }
        }
        return village;
    }

    /** Its villagers on the server now. */
    public static List<BotPlayer> onlineOf(List<BotMemory> village) {
        List<BotPlayer> online = new ArrayList<>();
        for (BotPlayer bot : BotManager.all()) {
            if (village.contains(bot.memory())) {
                online.add(bot);
            }
        }
        return online;
    }

    /** The middle of the village: the average of its homes. */
    public static BlockPos middle(List<BotMemory> village) {
        long x = 0;
        long y = 0;
        long z = 0;
        for (BotMemory memory : village) {
            BlockPos home = homeOf(memory);
            x += home.getX();
            y += home.getY();
            z += home.getZ();
        }
        int n = Math.max(1, village.size());
        return new BlockPos((int) (x / n), (int) (y / n), (int) (z / n));
    }

    /** Two or more of them on the server, and all of those in a (schematic) house of their own: time to build together. */
    /** Villages an admin told to start building now (by one of their bots), houses or no houses. */
    private static final java.util.Set<java.util.UUID> STARTED = ConcurrentHashMap.newKeySet();

    public static void startNow(BotPlayer bot) {
        STARTED.add(bot.getUUID());
    }

    public static boolean started(List<BotMemory> village) {
        for (BotMemory memory : village) {
            if (STARTED.contains(memory.uuid())) {
                return true;
            }
        }
        return false;
    }

    public static boolean ready(List<BotMemory> village) {
        if (started(village)) {
            return true;
        }
        List<BotPlayer> online = onlineOf(village);
        if (online.size() < 2) {
            return false;
        }
        for (BotPlayer bot : online) {
            if (!bot.memory().houseDone() || !HousePlans.isSchematic(bot.memory().houseTemplate())) {
                return false;
            }
        }
        return true;
    }

    /** The village's project under way (near its middle), or null. */
    public @Nullable Project current(BlockPos middle) {
        for (Project project : projects) {
            if (!project.done() && near(project, middle)) {
                return project;
            }
        }
        return null;
    }

    /** The next building the village hasn't built yet, or -1 if it has them all. */
    public int next(BlockPos middle) {
        for (int building = 0; building < buildings().size(); building++) {
            boolean built = false;
            for (Project project : projects) {
                if (project.building() == building && near(project, middle)) {
                    built = true;
                }
            }
            if (!built) {
                return building;
            }
        }
        return -1;
    }

    private static boolean near(Project project, BlockPos middle) {
        return project.origin().closerThan(middle, RADIUS);
    }
}
