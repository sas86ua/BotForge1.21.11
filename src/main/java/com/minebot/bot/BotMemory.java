package com.minebot.bot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a bot remembers between deaths and server restarts: where it started
 * (the centre of its working area), its home and the blocks it placed there.
 */
public class BotMemory {
    /** Bots work within this many blocks of their anchor (their spawn point). */
    public static final int ZONE_RADIUS = 2000;

    public static final Codec<BotMemory> CODEC = RecordCodecBuilder.create(i -> i.group(
        UUIDUtil.CODEC.fieldOf("uuid").forGetter(m -> m.uuid),
        Codec.STRING.fieldOf("name").forGetter(m -> m.name),
        GlobalPos.CODEC.fieldOf("anchor").forGetter(m -> m.anchor),
        Codec.BOOL.optionalFieldOf("online", false).forGetter(m -> m.online),
        GlobalPos.CODEC.optionalFieldOf("home").forGetter(m -> Optional.ofNullable(m.home)),
        Codec.BOOL.optionalFieldOf("built_home", false).forGetter(m -> m.builtHome),
        BlockPos.CODEC.optionalFieldOf("bed").forGetter(m -> Optional.ofNullable(m.bed)),
        BlockPos.CODEC.listOf().optionalFieldOf("chests", List.of()).forGetter(m -> m.chests),
        BlockPos.CODEC.optionalFieldOf("crafting_table").forGetter(m -> Optional.ofNullable(m.craftingTable)),
        BlockPos.CODEC.optionalFieldOf("furnace").forGetter(m -> Optional.ofNullable(m.furnace)),
        BlockPos.CODEC.optionalFieldOf("campfire").forGetter(m -> Optional.ofNullable(m.campfire)),
        Codec.BOOL.optionalFieldOf("autonomous", true).forGetter(m -> m.autonomous),
        GlobalPos.CODEC.optionalFieldOf("home_site").forGetter(m -> Optional.ofNullable(m.homeSite)),
        Extra.CODEC.optionalFieldOf("extra").forGetter(m -> Optional.of(m.extra))
    ).apply(i, BotMemory::new));

    /** Newer things to remember (the main codec can only take 16 fields). */
    public static final class Extra {
        static final Codec<Extra> CODEC = RecordCodecBuilder.create(i -> i.group(
            GlobalPos.CODEC.optionalFieldOf("lava").forGetter(e -> Optional.ofNullable(e.lava)),
            Codec.LONG.optionalFieldOf("home_since", -1L).forGetter(e -> e.homeSince),
            Codec.LONG.optionalFieldOf("next_journey", -1L).forGetter(e -> e.nextJourney),
            BlockPos.CODEC.optionalFieldOf("house_origin").forGetter(e -> Optional.ofNullable(e.houseOrigin)),
            Direction.CODEC.optionalFieldOf("house_front", Direction.NORTH).forGetter(e -> e.houseFront),
            Codec.INT.optionalFieldOf("house_template", 0).forGetter(e -> e.houseTemplate),
            Codec.BOOL.optionalFieldOf("house_done", false).forGetter(e -> e.houseDone),
            BlockPos.CODEC.optionalFieldOf("workshop").forGetter(e -> Optional.ofNullable(e.workshop)),
            Farm.CODEC.listOf().optionalFieldOf("farms", List.of()).forGetter(e -> e.farms),
            BlockPos.CODEC.listOf().optionalFieldOf("scaffold", List.of()).forGetter(e -> e.scaffold),
            BlockPos.CODEC.listOf().optionalFieldOf("borrowed", List.of()).forGetter(e -> e.borrowed),
            Codec.LONG.optionalFieldOf("rebuild_at", -1L).forGetter(e -> e.rebuildAt),
            BlockPos.CODEC.optionalFieldOf("pen").forGetter(e -> Optional.ofNullable(e.pen)),
            BlockPos.CODEC.optionalFieldOf("pen_gate").forGetter(e -> Optional.ofNullable(e.penGate))
        ).apply(i, Extra::new));

        /** Lava near its zone's centre, to burn rubbish in. */
        private @Nullable GlobalPos lava;
        /** Game time it got its (first) home, or -1. */
        private long homeSince;
        /** Game time of its next journey, or -1 if not planned yet. */
        private long nextJourney;
        /** The bigger house: where (lowest north-west corner of the floor), which way, which plan, finished? */
        private @Nullable BlockPos houseOrigin;
        private Direction houseFront;
        private int houseTemplate;
        private boolean houseDone;
        /** The first hut, kept as a workshop (chests, furnace) once the bot moved into the house. */
        private @Nullable BlockPos workshop;
        private final List<Farm> farms;
        /** Blocks and ladders it put up to climb on, still to take down (see DismantleTask). */
        private final List<BlockPos> scaffold;
        /** Furniture in someone else's house it uses till it has a house of its own (then it's left there). */
        private final List<BlockPos> borrowed;
        /** When it builds a new house (a schematic one) in place of its old template house; -1: not set yet. */
        private long rebuildAt;
        /** Its sheep pen: the inside's north-west corner where the sheep stand (6x6), and the gate in its fence. */
        private @Nullable BlockPos pen;
        private @Nullable BlockPos penGate;

        Extra() {
            this(Optional.empty(), -1L, -1L, Optional.empty(), Direction.NORTH, 0, false, Optional.empty(), List.of(), List.of(), List.of(), -1L, Optional.empty(), Optional.empty());
        }

        private Extra(Optional<GlobalPos> lava, long homeSince, long nextJourney, Optional<BlockPos> houseOrigin,
                      Direction houseFront, int houseTemplate, boolean houseDone, Optional<BlockPos> workshop,
                      List<Farm> farms, List<BlockPos> scaffold, List<BlockPos> borrowed, long rebuildAt, Optional<BlockPos> pen,
                      Optional<BlockPos> penGate) {
            this.lava = lava.orElse(null);
            this.homeSince = homeSince;
            this.nextJourney = nextJourney;
            this.houseOrigin = houseOrigin.orElse(null);
            this.houseFront = houseFront;
            this.houseTemplate = houseTemplate;
            this.houseDone = houseDone;
            this.workshop = workshop.orElse(null);
            this.farms = new ArrayList<>(farms);
            this.scaffold = new ArrayList<>(scaffold);
            this.borrowed = new ArrayList<>(borrowed);
            this.rebuildAt = rebuildAt;
            this.pen = pen.orElse(null);
            this.penGate = penGate.orElse(null);
        }
    }

    /** A field the bot planted: lowest north-west corner, size (square), what grows there. */
    public record Farm(BlockPos origin, int size, String crop) {
        static final Codec<Farm> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("origin").forGetter(Farm::origin),
            Codec.INT.fieldOf("size").forGetter(Farm::size),
            Codec.STRING.fieldOf("crop").forGetter(Farm::crop)
        ).apply(i, Farm::new));
    }

    private final UUID uuid;
    private final String name;
    private GlobalPos anchor;
    private boolean online;
    /** Centre of the bot's home; its dimension is also where bed/chests/etc. are. */
    private @Nullable GlobalPos home;
    private boolean builtHome;
    private @Nullable BlockPos bed;
    private final List<BlockPos> chests;
    private @Nullable BlockPos craftingTable;
    private @Nullable BlockPos furnace;
    private @Nullable BlockPos campfire;
    /** Lives on its own (gathers, crafts, builds) when no command is given. */
    private boolean autonomous;
    /** Where a hut is being built (so an interrupted build resumes in the same place). */
    private @Nullable GlobalPos homeSite;
    private final Extra extra;

    private Runnable onChange = () -> { };

    public BotMemory(UUID uuid, String name, GlobalPos anchor) {
        this(uuid, name, anchor, false, Optional.empty(), false, Optional.empty(), List.of(),
            Optional.empty(), Optional.empty(), Optional.empty(), true, Optional.empty(), Optional.empty());
    }

    private BotMemory(UUID uuid, String name, GlobalPos anchor, boolean online, Optional<GlobalPos> home,
                      boolean builtHome, Optional<BlockPos> bed, List<BlockPos> chests, Optional<BlockPos> craftingTable,
                      Optional<BlockPos> furnace, Optional<BlockPos> campfire, boolean autonomous,
                      Optional<GlobalPos> homeSite, Optional<Extra> extra) {
        this.uuid = uuid;
        this.name = name;
        this.anchor = anchor;
        this.online = online;
        this.home = home.orElse(null);
        this.builtHome = builtHome;
        this.bed = bed.orElse(null);
        this.chests = new ArrayList<>(chests);
        this.craftingTable = craftingTable.orElse(null);
        this.furnace = furnace.orElse(null);
        this.campfire = campfire.orElse(null);
        this.autonomous = autonomous;
        this.homeSite = homeSite.orElse(null);
        this.extra = extra.orElseGet(Extra::new);
    }

    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    private void changed() {
        onChange.run();
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public GlobalPos anchor() {
        return anchor;
    }

    public void setAnchor(GlobalPos anchor) {
        this.anchor = anchor;
        changed();
    }

    public boolean online() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
        changed();
    }

    public @Nullable GlobalPos home() {
        return home;
    }

    public @Nullable ResourceKey<Level> homeDimension() {
        return home == null ? null : home.dimension();
    }

    public boolean builtHome() {
        return builtHome;
    }

    /** How far from a new home what it had before (furnace, crafting table, campfire, workshop) is still its own. */
    private static final int KEEP_RANGE = 48;

    /**
     * The bed is lost (broken, burnt): the home goes with it, but not what was its own there - the chests stay
     * its store wherever it lives next, and the furnace, table and workshop too if the new home is near them.
     */
    public void loseHome() {
        this.home = null;
        this.builtHome = false;
        this.bed = null;
        changed();
    }

    public void setHome(@Nullable GlobalPos home, boolean built) {
        if (home != null && (this.home == null || this.home.dimension() == home.dimension())) {
            BlockPos at = home.pos();
            if (craftingTable != null && !craftingTable.closerThan(at, KEEP_RANGE)) {
                craftingTable = null;
            }
            if (furnace != null && !furnace.closerThan(at, KEEP_RANGE)) {
                furnace = null;
            }
            if (campfire != null && !campfire.closerThan(at, KEEP_RANGE)) {
                campfire = null;
            }
            if (extra.workshop != null && !extra.workshop.closerThan(at, KEEP_RANGE)) {
                extra.workshop = null;
            }
        }
        this.home = home;
        this.builtHome = built;
        if (home == null) {
            bed = null;
            chests.clear();
            craftingTable = null;
            furnace = null;
            campfire = null;
            extra.workshop = null; // (the hut that went with it)
        }
        changed();
    }

    public @Nullable BlockPos bed() {
        return bed;
    }

    public void setBed(@Nullable BlockPos bed) {
        this.bed = bed;
        changed();
    }

    public List<BlockPos> chests() {
        return chests;
    }

    public void addChest(BlockPos pos) {
        chests.add(pos.immutable());
        changed();
    }

    public void removeChest(BlockPos pos) {
        chests.remove(pos);
        changed();
    }

    public @Nullable BlockPos craftingTable() {
        return craftingTable;
    }

    public void setCraftingTable(@Nullable BlockPos pos) {
        craftingTable = pos;
        changed();
    }

    public @Nullable BlockPos furnace() {
        return furnace;
    }

    public void setFurnace(@Nullable BlockPos pos) {
        furnace = pos;
        changed();
    }

    public @Nullable BlockPos campfire() {
        return campfire;
    }

    public void setCampfire(@Nullable BlockPos pos) {
        campfire = pos;
        changed();
    }

    public @Nullable GlobalPos homeSite() {
        return homeSite;
    }

    public void setHomeSite(@Nullable GlobalPos site) {
        this.homeSite = site;
        changed();
    }

    public boolean autonomous() {
        return autonomous;
    }

    public void setAutonomous(boolean autonomous) {
        this.autonomous = autonomous;
        changed();
    }

    /** Is this position inside the bot's working area? */
    public @Nullable GlobalPos lava() {
        return extra.lava;
    }

    public void setLava(@Nullable GlobalPos lava) {
        extra.lava = lava;
        changed();
    }

    /** Game time it first had a home, or -1. */
    public long homeSince() {
        return extra.homeSince;
    }

    public void setHomeSince(long gameTime) {
        extra.homeSince = gameTime;
        changed();
    }

    public @Nullable BlockPos houseOrigin() {
        return extra.houseOrigin;
    }

    public Direction houseFront() {
        return extra.houseFront;
    }

    public int houseTemplate() {
        return extra.houseTemplate;
    }

    public boolean houseDone() {
        return extra.houseDone;
    }

    /** Where the bigger house goes (null: not planned or given up). */
    public void setHouseSite(@Nullable BlockPos origin, Direction front, int template) {
        extra.houseOrigin = origin;
        extra.houseFront = front;
        extra.houseTemplate = template;
        changed();
    }

    public void setHouseDone(boolean done) {
        extra.houseDone = done;
        changed();
    }

    public @Nullable BlockPos workshop() {
        return extra.workshop;
    }

    public void setWorkshop(@Nullable BlockPos workshop) {
        extra.workshop = workshop;
        changed();
    }

    /** What it put up to climb on and hasn't taken down yet (kept over sleeps and restarts). */
    public long rebuildAt() {
        return extra.rebuildAt;
    }

    public void setRebuildAt(long gameTime) {
        extra.rebuildAt = gameTime;
        changed();
    }

    /** Furniture in someone else's house it uses (see Home#adoptStorage). */
    public List<BlockPos> borrowed() {
        changed();
        return extra.borrowed;
    }

    public List<BlockPos> scaffold() {
        changed(); // (the list is changed in place by whoever has it)
        return extra.scaffold;
    }

    public @Nullable BlockPos pen() {
        return extra.pen;
    }

    public @Nullable BlockPos penGate() {
        return extra.penGate;
    }

    public void setPen(@Nullable BlockPos pen, @Nullable BlockPos gate) {
        extra.pen = pen;
        extra.penGate = gate;
        changed();
    }

    public List<Farm> farms() {
        return java.util.Collections.unmodifiableList(extra.farms);
    }

    public void addFarm(Farm farm) {
        extra.farms.add(farm);
        changed();
    }

    public void removeFarm(Farm farm) {
        extra.farms.remove(farm);
        changed();
    }

    public long nextJourney() {
        return extra.nextJourney;
    }

    public void setNextJourney(long gameTime) {
        extra.nextJourney = gameTime;
        changed();
    }

    public boolean inZone(ResourceKey<Level> dimension, BlockPos pos) {
        if (dimension != anchor.dimension()) {
            return false;
        }
        long dx = pos.getX() - anchor.pos().getX();
        long dz = pos.getZ() - anchor.pos().getZ();
        return dx * dx + dz * dz <= (long) ZONE_RADIUS * ZONE_RADIUS
            || dimension == Level.OVERWORLD && com.minebot.bot.build.GreatBuild.nearSite(pos); // (everyone's)
    }
}
