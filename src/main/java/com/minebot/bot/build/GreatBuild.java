package com.minebot.bot.build;

import com.minebot.bot.BotMemory;
import com.minebot.bot.BotPlayer;
import com.minebot.bot.action.Inv;
import com.minebot.bot.world.ProtectedAreas;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Great Build: one big structure from a schematic that all the bots build together, a bit at a
 * time. Every {@link #PERIOD} days, in the morning, they all go to the site and work there for two
 * days, each bringing the 5-10 stacks of materials it was asked for ({@link #orderOf}); in between,
 * each gets its share ready (mined underground, crafted). On the site they level the ground, dig out
 * the cellars, fill in, and build bottom up.
 *
 * What's built isn't saved block by block: the world is the record. Cells are checked against the
 * plan where the chunks are loaded ({@link #tick}), and the bots pick their work from what isn't done.
 */
public final class GreatBuild extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final long DAY = 24000;
    public static final long PERIOD = 15 * DAY;
    public static final long SESSION = 2 * DAY;
    /** A session starts in the morning (the first quarter of the day), or the next morning. */
    private static final long MORNING_END = 6000;
    public static final int MIN_STACKS = 5;
    public static final int MAX_STACKS = 10;
    /** Cells checked against the world per tick while a session is on. */
    private static final int VERIFY_PER_TICK = 20000;
    /** A cell worked on by one bot is left to it this long. */
    private static final long CLAIM_TICKS = 20 * 30;
    /** A cell that couldn't be done (no foothold, nothing to click on) is left this long. */
    private static final long COOLDOWN_TICKS = 20 * 60 * 3;
    /** Blocks that will do for filling in the ground. */
    public static final Predicate<ItemStack> FILL = stack -> stack.is(Items.DIRT) || stack.is(Items.COBBLESTONE)
        || stack.is(Items.COBBLED_DEEPSLATE) || stack.is(Items.STONE) || stack.is(Items.ANDESITE) || stack.is(Items.DIORITE)
        || stack.is(Items.GRANITE) || stack.is(Items.TUFF) || stack.is(Items.NETHERRACK) || stack.is(Items.COARSE_DIRT);

    private static final Codec<Map<String, Integer>> COUNTS = Codec.unboundedMap(Codec.STRING, Codec.INT);
    private static final Codec<GreatBuild> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.optionalFieldOf("file", "").forGetter(b -> b.file),
        BlockPos.CODEC.optionalFieldOf("centre", BlockPos.ZERO).forGetter(b -> b.centre),
        Codec.LONG.optionalFieldOf("next_day", -1L).forGetter(b -> b.nextDay),
        Codec.LONG.optionalFieldOf("session_end", -1L).forGetter(b -> b.sessionEnd),
        Codec.INT.optionalFieldOf("sessions", 0).forGetter(b -> b.sessions),
        Codec.unboundedMap(UUIDUtil.STRING_CODEC, COUNTS).optionalFieldOf("orders", Map.of()).forGetter(b -> b.orders),
        COUNTS.optionalFieldOf("failures", Map.of()).forGetter(b -> b.failures),
        Codec.LONG.optionalFieldOf("placed", 0L).forGetter(b -> b.placed),
        Codec.LONG.optionalFieldOf("dug", 0L).forGetter(b -> b.dug),
        Codec.INT.listOf().optionalFieldOf("chunk_done", List.of()).forGetter(b -> b.chunkDone),
        Codec.unboundedMap(UUIDUtil.STRING_CODEC, BlockPos.CODEC.listOf()).optionalFieldOf("camp", Map.of()).forGetter(b -> b.camp),
        UUIDUtil.STRING_CODEC.listOf().optionalFieldOf("sent_early", List.of()).forGetter(b -> List.copyOf(b.sentEarly)),
        Codec.unboundedMap(UUIDUtil.STRING_CODEC, BlockPos.CODEC).optionalFieldOf("camp_chest", Map.of()).forGetter(b -> b.campChest),
        Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING.listOf()).optionalFieldOf("cant_get", Map.of()).forGetter(b -> b.cantGet),
        Codec.INT.optionalFieldOf("floor_layer", 0).forGetter(b -> b.floorLayer),
        Codec.INT.optionalFieldOf("site_dug_session", -1).forGetter(b -> b.siteDugSession)
    ).apply(i, GreatBuild::new));
    private static final SavedDataType<GreatBuild> TYPE =
        new SavedDataType<>("minebot_great_build", GreatBuild::new, CODEC, null);

    // ---- saved ----------------------------------------------------------------------------------
    /** The schematic's file name (in minebot/schematics), or "" when there's no build. */
    private String file;
    /** Where the schematic's paste point goes (like WorldEdit's //paste). */
    private BlockPos centre;
    /** The day the next session starts (on its morning). */
    private long nextDay;
    /** While a session is on: the day time it ends. */
    private long sessionEnd;
    private int sessions;
    /** What each bot is to bring next time: item id to count. */
    private final Map<UUID, Map<String, Integer>> orders;
    /** Items the bots couldn't get: item id to how many times (not ordered again past a few). */
    private final Map<String, Integer> failures;
    private long placed;
    private long dug;
    /** Per chunk column of the site: cells done as of when it was last looked at in full. */
    private final List<Integer> chunkDone;
    /** Each bot's camp by the site: its camp fire first, then its furnaces round it. */
    private final Map<UUID, List<BlockPos>> camp;
    /** Bots sent off to the site ahead of time (an admin's "go"): they go now and wait there for the start. */
    private final Set<UUID> sentEarly = new HashSet<>();
    /** Each bot's chest at its camp (anyone at the site may use it). */
    private final Map<UUID, BlockPos> campChest;
    /** What each bot couldn't get getting ready (no flowers, no sheep round its home...): ordered from others. */
    private final Map<UUID, List<String>> cantGet;
    /**
     * Ladders, pillars and bridges the bots put up at the site to get about in it: left standing for an hour (they stand
     * in cells the plan has empty, so the diggers took them down as soon as they went up, the builders put them up
     * again to climb out of the pit, and so on - most of a session). The way up is used by all of them meanwhile.
     */
    private static final java.util.Map<Long, Long> CLIMB = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CLIMB_KEEP_TICKS = 20L * 60 * 60;

    public static void climbBlockPlaced(BlockPos pos, long now) {
        if (nearSite(pos)) {
            if (CLIMB.size() > 5000) {
                CLIMB.values().removeIf(until -> until < now);
            }
            CLIMB.put(pos.asLong(), now + CLIMB_KEEP_TICKS);
        }
    }

    private static boolean isClimbBlock(BlockPos pos, long now) {
        Long until = CLIMB.get(pos.asLong());
        return until != null && until > now;
    }

    /** Layers below this are written off for good: the bots in numbers couldn't get at them (see noteGiveUp). */
    private int floorLayer;
    /** The session count when the site was dug out (99%, the diggers down to the lowest layer); -1: not yet. */
    private int siteDugSession = -1;
    /** Sessions after that before the builders may write off layers they can't get at. */
    private static final int SESSIONS_BEFORE_WRITE_OFF = 3;

    // ---- not saved ------------------------------------------------------------------------------
    private @Nullable Schematic plan;
    /** The site with some room around it (min x, min z, max x, max z): part of every bot's zone. */
    private static volatile int @Nullable [] site;
    private static final int SITE_MARGIN = 160;
    private @Nullable String planError;
    private BlockPos origin = BlockPos.ZERO;
    private final BitSet pending = new BitSet();
    private final BitSet verified = new BitSet();
    private int cursor;
    private int chunksX;
    private int chunksZ;
    private int[] relevantInChunk = new int[0];
    private int[] verifiedInChunk = new int[0];
    private int[] doneInChunk = new int[0];
    private int[] relevantTotal = new int[2];
    private final Map<Integer, Long> claims = new HashMap<>();
    private final Map<Integer, Long> cooldowns = new HashMap<>();
    /** Bots at the site getting more materials from below it right now. */
    private final Set<UUID> restocking = new HashSet<>();
    private final Map<Item, Integer> materialTotals = new LinkedHashMap<>();

    public GreatBuild() {
        this("", BlockPos.ZERO, -1, -1, 0, Map.of(), Map.of(), 0, 0, List.of(), Map.of(), List.of(), Map.of(), Map.of(), 0, -1);
    }

    private GreatBuild(String file, BlockPos centre, long nextDay, long sessionEnd, int sessions,
                       Map<UUID, Map<String, Integer>> orders, Map<String, Integer> failures, long placed, long dug,
                       List<Integer> chunkDone, Map<UUID, List<BlockPos>> camp, List<UUID> sentEarly, Map<UUID, BlockPos> campChest, Map<UUID, List<String>> cantGet, int floorLayer, int siteDugSession) {
        this.floorLayer = floorLayer;
        this.siteDugSession = siteDugSession;
        this.file = file;
        this.centre = centre;
        this.nextDay = nextDay;
        this.sessionEnd = sessionEnd;
        this.sessions = sessions;
        this.orders = new HashMap<>();
        orders.forEach((uuid, order) -> this.orders.put(uuid, new LinkedHashMap<>(order)));
        this.failures = new HashMap<>(failures);
        this.placed = placed;
        this.dug = dug;
        this.chunkDone = new ArrayList<>(chunkDone);
        this.camp = new HashMap<>();
        camp.forEach((uuid, list) -> this.camp.put(uuid, new ArrayList<>(list)));
        this.sentEarly.addAll(sentEarly);
        this.campChest = new HashMap<>(campChest);
        this.cantGet = new HashMap<>();
        cantGet.forEach((uuid, items) -> this.cantGet.put(uuid, new ArrayList<>(items)));
    }

    public static GreatBuild get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static Path schematicsDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("minebot").resolve("schematics");
    }

    // ---- the project ----------------------------------------------------------------------------

    public boolean exists() {
        return !file.isEmpty();
    }

    /** The plan, loaded on first use (null if there's no build or the file is missing or bad). */
    public @Nullable Schematic plan(MinecraftServer server) {
        if (plan == null && exists() && planError == null) {
            try {
                setPlan(Schematic.load(schematicsDir(server).resolve(file)));
            } catch (IOException | RuntimeException e) {
                planError = e.getMessage();
                LOGGER.warn("Great build: can't read {}", file, e);
            }
        }
        return plan;
    }

    private void setPlan(Schematic schematic) {
        plan = schematic;
        roofs = null;
        entrances = null;
        origin = centre.subtract(schematic.pastePoint());
        site = new int[] {origin.getX() - SITE_MARGIN, origin.getZ() - SITE_MARGIN,
            origin.getX() + schematic.width() + SITE_MARGIN, origin.getZ() + schematic.length() + SITE_MARGIN};
        pending.clear();
        verified.clear();
        cursor = 0;
        claims.clear();
        cooldowns.clear();
        chunksX = ((origin.getX() + schematic.width() - 1) >> 4) - (origin.getX() >> 4) + 1;
        chunksZ = ((origin.getZ() + schematic.length() - 1) >> 4) - (origin.getZ() >> 4) + 1;
        relevantInChunk = new int[2 * chunksX * chunksZ];
        verifiedInChunk = new int[2 * chunksX * chunksZ];
        doneInChunk = new int[2 * chunksX * chunksZ];
        relevantTotal = new int[2];
        materialTotals.clear();
        for (int index = 0; index < schematic.size(); index++) {
            LegacyBlocks.Spec spec = schematic.at(index);
            if (relevant(spec)) {
                relevantInChunk[slot(index)]++;
                relevantTotal[category(spec)]++;
                pending.set(index); // (until seen to be done)
            }
            if (spec.kind() == LegacyBlocks.Kind.EXACT) {
                materialTotals.merge(itemFor(spec), 1, Integer::sum);
            }
        }
        while (chunkDone.size() < 2 * chunksX * chunksZ) {
            chunkDone.add(0);
        }
    }

    private static boolean relevant(LegacyBlocks.Spec spec) {
        return spec.kind() != LegacyBlocks.Kind.SKIP;
    }

    /** On or around the site (in the overworld): every bot may go, hunt and mine there, whatever its zone. */
    public static boolean nearSite(BlockPos pos) {
        int[] box = site;
        return box != null && pos.getX() >= box[0] && pos.getZ() >= box[1] && pos.getX() <= box[2] && pos.getZ() <= box[3];
    }

    public @Nullable String planError() {
        return planError;
    }

    /** Starts a new build (replacing any old one); the first session is a period from now. */
    public String start(MinecraftServer server, String fileName, BlockPos at) throws IOException {
        Path path = schematicsDir(server).resolve(fileName);
        if (!Files.isRegularFile(path)) {
            throw new IOException("no file " + path);
        }
        Schematic schematic = Schematic.load(path);
        file = fileName;
        centre = at;
        planError = null;
        chunkDone.clear();
        orders.clear();
        camp.clear(); // (a new site: new camps)
        campChest.clear();
        failures.clear();
        cantGet.clear();
        placed = 0;
        dug = 0;
        sessions = 0;
        sessionEnd = -1;
        setPlan(schematic);
        long today = server.overworld().getDayTime() / DAY;
        nextDay = today + PERIOD / DAY;
        makeOrders(server);
        setDirty();
        broadcast(server, "Объявлена Великая стройка! Место: " + at.getX() + " " + at.getY() + " " + at.getZ()
            + ". Боты готовят материалы, первый сбор через " + (PERIOD / DAY) + " дней.");
        return schematic.width() + "x" + schematic.height() + "x" + schematic.length() + ", "
            + relevantTotal[0] + " blocks to build, " + relevantTotal[1] + " cells to dig or fill" + (schematic.unknown().isEmpty() ? "" : ", unknown ids " + schematic.unknown());
    }

    public void stop(MinecraftServer server) {
        if (exists()) {
            broadcast(server, "Великая стройка остановлена.");
        }
        file = "";
        plan = null;
        site = null;
        planError = null;
        orders.clear();
        sessionEnd = -1;
        restocking.clear();
        setDirty();
    }

    public BlockPos centre() {
        return centre;
    }

    public BlockPos origin() {
        return origin;
    }

    public ServerLevel level(MinecraftServer server) {
        return server.overworld();
    }

    // ---- sessions -------------------------------------------------------------------------------

    public boolean inSession(MinecraftServer server) {
        return exists() && sessionEnd >= 0 && server.overworld().getDayTime() < sessionEnd;
    }

    public long sessionEnd() {
        return sessionEnd;
    }

    public long nextDay() {
        return nextDay;
    }

    public int sessions() {
        return sessions;
    }

    /** How fast a bot gets along over a long way (blocks a second: round obstacles, over water by boat). */
    private static final double TRAVEL_SPEED = 3.0;

    /** Day time this bot should set off at to be at the site when the next session starts. */
    public long departure(BotPlayer bot) {
        return nextDay * DAY - Math.min(travelTicks(bot), DAY);
    }

    /** About how long the way to the site takes this bot from where it is (with a bit to spare). */
    public long travelTicks(BotPlayer bot) {
        double dx = bot.getX() - centre.getX();
        double dz = bot.getZ() - centre.getZ();
        return (long) (Math.sqrt(dx * dx + dz * dz) / TRAVEL_SPEED * 20 * 1.25) + 600;
    }

    /** A session going on that this bot couldn't get to before it's over (far off: after dying, say). */
    public boolean tooLateFor(BotPlayer bot) {
        return inSession(bot.level().getServer()) && !inSite(bot.blockPosition(), 16)
            && sessionEnd - bot.level().getDayTime() < travelTicks(bot);
    }

    /** Close to the start of the next session (from a day before it to a day after): who set off early waits for it. */
    public boolean awaitingSession(long now) {
        return exists() && sessionEnd < 0 && now >= nextDay * DAY - DAY && now < nextDay * DAY + DAY;
    }

    /** Before the session: time this bot set off, to get there for the start (a long way off, the evening before). */
    public boolean departureDue(BotPlayer bot) {
        if (!exists() || sessionEnd >= 0 || bot.level() != bot.level().getServer().overworld()) {
            return false;
        }
        long now = bot.level().getDayTime();
        return sentEarly.contains(bot.getUUID()) || now >= departure(bot) && now < nextDay * DAY + DAY;
    }


    /** Sends these bots to the site now, ahead of the next session; how many were sent. */
    public int sendEarly(java.util.Collection<BotPlayer> bots) {
        int sent = 0;
        for (BotPlayer bot : bots) {
            if (bot.memory().autonomous() && sentEarly.add(bot.getUUID())) {
                sent++;
            }
        }
        if (sent > 0) {
            setDirty(); // (kept over a restart: they'd all go home otherwise)
        }
        return sent;
    }

    public boolean sentEarly(BotPlayer bot) {
        return sentEarly.contains(bot.getUUID());
    }

    /** A message to everyone on the server. */
    public static void announce(MinecraftServer server, String text) {
        broadcast(server, text);
    }

    /** Starts a session right away (an admin's "now"). */
    public void startSession(MinecraftServer server) {
        noteSiteDug();
        long now = server.overworld().getDayTime();
        sessionEnd = now + SESSION;
        nextDay = (now / DAY) + PERIOD / DAY;
        sessions++;
        restocking.clear();
        setDirty();
        broadcast(server, "Великая стройка: боты отправились на стройку! Координаты: "
            + centre.getX() + " " + centre.getY() + " " + centre.getZ() + " (на 2 игровых дня). Готово " + progressText() + ".");
    }

    public void endSession(MinecraftServer server) {
        noteSiteDug();
        sessionEnd = -1;
        restocking.clear();
        sentEarly.clear();
        makeOrders(server);
        setDirty();
        broadcast(server, "Великая стройка: на сегодня всё, боты возвращаются домой. Готово " + progressText()
            + ". Следующий сбор через " + Math.max(0, nextDay - server.overworld().getDayTime() / DAY) + " дн.");
    }

    public void tick(MinecraftServer server) {
        if (!exists() || plan(server) == null) {
            return;
        }
        ServerLevel level = level(server);
        long now = level.getDayTime();
        if (server.getTickCount() % 20 == 0) {
            if (sessionEnd >= 0 && now >= sessionEnd) {
                endSession(server);
            } else if (sessionEnd < 0 && now / DAY >= nextDay && now % DAY < MORNING_END) {
                startSession(server);
            }
        }
        if (sessionEnd >= 0 || !sentEarly.isEmpty()) { // (or bots sent early at work there)
            verify(level, VERIFY_PER_TICK);
        } else if (orders.isEmpty() && server.getTickCount() % 200 == 0 && !com.minebot.bot.BotManager.all().isEmpty()) {
            makeOrders(server); // (none were on when they were handed out)
        }
        if (server.getTickCount() % 1200 == 0) {
            long time = level.getGameTime();
            claims.values().removeIf(until -> until < time);
            cooldowns.values().removeIf(until -> until < time);
        }
    }

    private static void broadcast(MinecraftServer server, String text) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("[Великая стройка] " + text).withStyle(ChatFormatting.GOLD), false);
        LOGGER.info("Great build: {}", text);
    }

    // ---- what's done ----------------------------------------------------------------------------

    public BlockPos worldPos(int index) {
        Schematic schematic = plan;
        int w = schematic.width();
        int l = schematic.length();
        int x = index % w;
        int z = (index / w) % l;
        int y = index / (w * l);
        return origin.offset(x, y, z);
    }

    public int indexOf(BlockPos pos) {
        Schematic schematic = plan;
        if (schematic == null) {
            return -1;
        }
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        if (x < 0 || y < 0 || z < 0 || x >= schematic.width() || y >= schematic.height() || z >= schematic.length()) {
            return -1;
        }
        return schematic.index(x, y, z);
    }

    private int chunkOf(int index) {
        Schematic schematic = plan;
        int x = index % schematic.width();
        int z = (index / schematic.width()) % schematic.length();
        int cx = ((origin.getX() + x) >> 4) - (origin.getX() >> 4);
        int cz = ((origin.getZ() + z) >> 4) - (origin.getZ() >> 4);
        return cz * chunksX + cx;
    }

    /** Checks the next cells in loaded chunks (unloaded ones are skipped and stay as they were). */
    private void verify(ServerLevel level, int budget) {
        Schematic schematic = plan;
        int size = schematic.size();
        for (int n = 0; n < budget; n++) {
            int index = cursor;
            cursor = (cursor + 1) % size;
            if (!relevant(schematic.at(index))) {
                continue;
            }
            BlockPos pos = worldPos(index);
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null) {
                update(index, isDone(schematic.at(index), chunk.getBlockState(pos)));
            }
        }
    }

    /** Looks at one cell again (after a bot changed it). */
    public void recheck(ServerLevel level, BlockPos pos) {
        int index = indexOf(pos);
        if (index >= 0 && relevant(plan.at(index))) {
            update(index, isDone(plan.at(index), level.getBlockState(pos)));
        }
    }

    private void update(int index, boolean done) {
        int chunk = slot(index);
        if (!verified.get(index)) {
            verified.set(index);
            verifiedInChunk[chunk]++;
            if (done) {
                doneInChunk[chunk]++;
            }
        } else if (done == pending.get(index)) {
            doneInChunk[chunk] += done ? 1 : -1;
        }
        pending.set(index, !done);
        if (verifiedInChunk[chunk] == relevantInChunk[chunk] && chunkDone.get(chunk) != doneInChunk[chunk]) {
            chunkDone.set(chunk, doneInChunk[chunk]);
            setDirty();
        }
    }

    public static boolean isDone(LegacyBlocks.Spec spec, BlockState state) {
        return switch (spec.kind()) {
            case SKIP -> true;
            case AIR -> state.getFluidState().isEmpty() && state.canBeReplaced();
            case GROUND -> !state.canBeReplaced() && state.getFluidState().isEmpty();
            case SOIL -> state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM) || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH);
            case COMPANION -> state.is(spec.state().getBlock());
            case EXACT -> sameBuild(spec.state(), state);
        };
    }

    /** The right block, turned the right way (connections of fences and the like don't matter). */
    private static boolean sameBuild(BlockState wanted, BlockState state) {
        if (!state.is(wanted.getBlock())) {
            return false;
        }
        for (Property<?> property : ORIENTATION) {
            if (wanted.hasProperty(property) && !wanted.getValue(property).equals(state.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    /** What decides which way a block is turned: set by hand after placing, if placing got it wrong. */
    public static final List<Property<?>> ORIENTATION = List.of(BlockStateProperties.HORIZONTAL_FACING,
        BlockStateProperties.HALF, BlockStateProperties.SLAB_TYPE, BlockStateProperties.AXIS);

    /** 0: blocks of the building; 1: the site's ground (dug out, filled in). Counted apart. */
    private static int category(LegacyBlocks.Spec spec) {
        return spec.kind() == LegacyBlocks.Kind.EXACT || spec.kind() == LegacyBlocks.Kind.COMPANION ? 0 : 1;
    }

    private int slot(int index) {
        return category(plan.at(index)) * chunksX * chunksZ + chunkOf(index);
    }

    /** 0-100: how much of a category is done, as far as it has been seen. */
    public double progress(int category) {
        if (relevantTotal[category] == 0) {
            return 100;
        }
        long done = 0;
        int chunks = chunksX * chunksZ;
        for (int slot = category * chunks; slot < (category + 1) * chunks; slot++) {
            done += verifiedInChunk[slot] == relevantInChunk[slot] ? doneInChunk[slot] : chunkDone.get(slot);
        }
        return 100.0 * done / relevantTotal[category];
    }

    /**
     * Why a bot carrying material isn't putting it in: for each plan block it has on it, the lowest layer that still wants
     * it, against the layers the building is on now (the lowest unfinished one and {@link #UP_LAYERS} above).
     */
    public String explain(BotPlayer bot) {
        Schematic schematic = plan;
        if (schematic == null) {
            return "no plan";
        }
        seenLevel = bot.level();
        int size = schematic.width() * schematic.length();
        int lowest = computeLowestPendingLayer();
        Map<Item, int[]> wanted = new java.util.HashMap<>(); // item -> {lowest layer, cells pending}
        for (int index = pending.nextSetBit(0); index >= 0; index = pending.nextSetBit(index + 1)) {
            LegacyBlocks.Spec spec = schematic.at(index);
            if (spec.kind() != LegacyBlocks.Kind.EXACT) {
                continue;
            }
            int[] entry = wanted.computeIfAbsent(itemFor(spec), i -> new int[] {Integer.MAX_VALUE, 0});
            entry[0] = Math.min(entry[0], index / size);
            entry[1]++;
        }
        StringBuilder text = new StringBuilder(bot.getPlainTextName() + ": building layers " + lowest + ".." + windowTop(lowest)
            + " (the lowest unfinished one is " + lowest + ", height " + (origin.getY() + lowest) + ")");
        boolean any = false;
        for (Map.Entry<Item, int[]> entry : new java.util.TreeMap<Item, int[]>(java.util.Comparator.comparing(GreatBuild::key)) {{
            putAll(wanted);
        }}.entrySet()) {
            Item item = entry.getKey();
            int carried = com.minebot.bot.action.Inv.count(bot, stack -> stack.is(item));
            if (carried < 8) {
                continue;
            }
            any = true;
            int layer = entry.getValue()[0];
            text.append("\n ").append(key(item).replace("minecraft:", "")).append(" x").append(carried).append(": wanted from layer ")
                .append(layer).append(" up (").append(entry.getValue()[1]).append(" cells) - ")
                .append(layer <= windowTop(lowest) ? "can be put in now" : "too high for now (" + (layer - windowTop(lowest)) + " layers above the window)");
        }
        if (!any) {
            text.append("\n (carries no 8+ of anything the plan still wants)");
        }
        // The lowest layer's free cells: why they can or can't be worked on now
        int[] why = new int[5]; // workable, nothing to put it against, cooling down/claimed, not loaded, done meanwhile
        ServerLevel level = bot.level();
        long time = level.getGameTime();
        for (int index = pending.nextSetBit(lowest * size); index >= 0 && index < (lowest + 1) * size; index = pending.nextSetBit(index + 1)) {
            if (schematic.at(index).kind() != LegacyBlocks.Kind.EXACT || coversPending(index)) {
                continue;
            }
            BlockPos pos = worldPos(index);
            if (!level.isLoaded(pos)) {
                why[3]++;
            } else if (isDone(schematic.at(index), level.getBlockState(pos))) {
                why[4]++;
            } else if (claims.getOrDefault(index, 0L) > time || cooldowns.getOrDefault(index, 0L) > time) {
                why[2]++;
            } else if (workable(level, pos, level.getBlockState(pos), JobType.PLACE)) {
                why[0]++;
            } else {
                why[1]++;
            }
        }
        text.append("\n layer ").append(lowest).append(" free cells: ").append(why[0]).append(" workable, ").append(why[1])
            .append(" nothing to put them against, ").append(why[2]).append(" claimed or cooling down, ").append(why[3])
            .append(" not loaded, ").append(why[4]).append(" done already");
        // What the layers it builds in still want (cells to put in; held back: with something still to go under them)
        for (int layer = lowest; layer <= Math.min(windowTop(lowest), lowest + 16) && layer < schematic.height(); layer++) {
            Map<Item, int[]> left = new java.util.HashMap<>();
            for (int index = pending.nextSetBit(layer * size); index >= 0 && index < (layer + 1) * size; index = pending.nextSetBit(index + 1)) {
                LegacyBlocks.Spec spec = schematic.at(index);
                if (spec.kind() != LegacyBlocks.Kind.EXACT) {
                    continue;
                }
                int[] entry = left.computeIfAbsent(itemFor(spec), i -> new int[2]);
                entry[coversPending(index) ? 1 : 0]++;
            }
            text.append("\n layer ").append(layer).append(":");
            left.entrySet().stream().sorted((a, b) -> b.getValue()[0] + b.getValue()[1] - a.getValue()[0] - a.getValue()[1]).limit(6)
                .forEach(e -> text.append(" ").append(key(e.getKey()).replace("minecraft:", "")).append(" ")
                    .append(e.getValue()[0]).append("+").append(e.getValue()[1]).append("held"));
        }
        return text.toString();
    }

    /** Can a bot pass through this cell of the plan (once built): empty, or a door, stairs, a slab, a ladder... */
    private boolean passableInPlan(int index) {
        LegacyBlocks.Spec spec = plan.at(index);
        if (spec.kind() == LegacyBlocks.Kind.AIR || spec.kind() == LegacyBlocks.Kind.SKIP) {
            return true;
        }
        BlockState state = spec.state();
        if (state == null) {
            return true;
        }
        return state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
            || state.is(net.minecraft.tags.BlockTags.DOORS) || state.is(net.minecraft.tags.BlockTags.FENCE_GATES)
            || state.is(net.minecraft.tags.BlockTags.TRAPDOORS) || state.is(Blocks.LADDER);
    }

    // ---- the basement: under the ground, under the plan's roof ---------------------------------------

    /** Per column (x + z * width): the highest layer of the plan with a block (-1: none); worked out once. */
    private int @Nullable [] roofs;
    /** Ways into the basement from the open pit or the ground floor, a few blocks apart; worked out once. */
    private @Nullable List<BlockPos> entrances;

    private int groundLayer() {
        return Math.max(0, Math.min(plan.height() - 1, centre.getY() - origin.getY()));
    }

    private int[] roofs() {
        if (roofs == null) {
            Schematic s = plan;
            int[] r = new int[s.width() * s.length()];
            for (int z = 0; z < s.length(); z++) {
                for (int x = 0; x < s.width(); x++) {
                    r[z * s.width() + x] = -1;
                    for (int y = s.height() - 1; y >= 0; y--) {
                        if (!passableInPlan(s.index(x, y, z))) {
                            r[z * s.width() + x] = y;
                            break;
                        }
                    }
                }
            }
            roofs = r;
        }
        return roofs;
    }

    /** A cell of the plan under the ground with the plan's own roof over it. */
    private boolean inBasement(int index) {
        int area = plan.width() * plan.length();
        return index / area < groundLayer() && index / area < roofs()[index % area];
    }

    /** Is this spot in the basement? */
    public boolean inBasement(BlockPos pos) {
        int x = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int z = pos.getZ() - origin.getZ();
        return plan != null && x >= 0 && z >= 0 && y >= 0 && x < plan.width() && z < plan.length() && y < plan.height()
            && inBasement(plan.index(x, y, z));
    }

    private List<BlockPos> entrances() {
        if (entrances == null) {
            Schematic s = plan;
            int w = s.width();
            int l = s.length();
            List<BlockPos> found = new ArrayList<>();
            for (int index = 0; index < groundLayer() * w * l; index++) {
                if (!inBasement(index) || !passableInPlan(index)) {
                    continue;
                }
                int y = index / (w * l);
                int z = (index / w) % l;
                int x = index % w;
                int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
                for (int[] d : steps) {
                    int nx = x + d[0];
                    int ny = y + d[1];
                    int nz = z + d[2];
                    if (nx < 0 || nz < 0 || nx >= w || nz >= l || ny >= s.height()) {
                        continue;
                    }
                    int next = s.index(nx, ny, nz);
                    if (passableInPlan(next) && !inBasement(next)) {
                        BlockPos at = worldPos(index);
                        if (found.stream().noneMatch(e -> e.closerThan(at, 8))) {
                            found.add(at);
                        }
                        break;
                    }
                }
            }
            entrances = List.copyOf(found);
        }
        return entrances;
    }

    /** The way into the basement nearest this spot in it (null: none in the plan). */
    public @Nullable BlockPos entranceNear(BlockPos pos) {
        return entrances().stream().min(java.util.Comparator.comparingDouble(e -> e.distSqr(pos))).orElse(null);
    }

    /**
     * Work in the basement for this bot (the "basement crew"): a block it carries to put in, the nearest one, down there under
     * the plan's roof - written-off layers or not, below the layers the rest build in. Null if none.
     */
    public @Nullable Job basementJob(BotPlayer bot, long time) {
        Schematic s = plan;
        if (s == null) {
            return null;
        }
        ServerLevel level = bot.level();
        seenLevel = level;
        boolean[] carried = new boolean[s.palette().size()];
        boolean any = false;
        for (int i = 0; i < carried.length; i++) {
            LegacyBlocks.Spec spec = s.palette().get(i);
            if (spec.kind() == LegacyBlocks.Kind.EXACT) {
                Item item = itemFor(spec);
                carried[i] = Inv.count(bot, stack -> stack.is(item)) > 0;
                any |= carried[i];
            }
        }
        if (!any) {
            return null;
        }
        int end = groundLayer() * s.width() * s.length();
        Job best = null;
        double bestDistance = Double.MAX_VALUE;
        int checked = 0;
        for (int index = pending.nextSetBit(0); index >= 0 && index < end && checked < 40000; index = pending.nextSetBit(index + 1), checked++) {
            LegacyBlocks.Spec spec = s.at(index);
            if (spec.kind() != LegacyBlocks.Kind.EXACT || !carried[paletteIndex(index)]) {
                continue;
            }
            if (claims.getOrDefault(index, 0L) > time && !bot.getUUID().equals(claimedBy.get(index)) || cooldowns.getOrDefault(index, 0L) > time) {
                continue;
            }
            // In the basement: the cell, or one beside it (its walls and floors are the plan's blocks round its rooms)
            BlockPos pos = worldPos(index);
            if (!inBasement(index) && !inBasement(pos.above()) && !inBasement(pos.below()) && !inBasement(pos.north())
                && !inBasement(pos.south()) && !inBasement(pos.east()) && !inBasement(pos.west())) {
                continue;
            }
            Boolean there = seen(index);
            if (there == null || there) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!workable(level, pos, state, JobType.PLACE) || ProtectedAreas.isProtected(level, pos)) {
                continue;
            }
            double distance = pos.distSqr(bot.blockPosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new Job(JobType.PLACE, pos, index, spec);
            }
        }
        return best;
    }

    /** What can be put in now (up top in the layers built in; or in the basement), worked out every few seconds. */
    private final Map<Boolean, Set<Item>> wantedNow = new java.util.HashMap<>();
    private final Map<Boolean, Long> wantedNowAt = new java.util.HashMap<>();

    /**
     * The blocks there is a place for now: up top, the free cells of the layers being built in; for the basement crew, the
     * basement's. The exchange through the site's chests goes by it: what a bot can't place, it leaves there; what's
     * wanted, the others take out.
     */
    public Set<Item> wantedNow(ServerLevel level, boolean basement) {
        long time = level.getGameTime();
        Long at = wantedNowAt.get(basement);
        if (at != null && time - at < 100 && time >= at) {
            return wantedNow.get(basement);
        }
        wantedNowAt.put(basement, time);
        Set<Item> items = new HashSet<>();
        Schematic s = plan;
        if (s != null) {
            seenLevel = level;
            int area = s.width() * s.length();
            int from;
            int to;
            if (basement) {
                from = 0;
                to = groundLayer() * area;
            } else {
                int lowest = lowestPendingLayer(time);
                from = lowest * area;
                to = Math.min(s.height(), windowTop(lowest) + 1) * area;
            }
            int checked = 0;
            for (int index = pending.nextSetBit(from); index >= 0 && index < to && checked < 60000; index = pending.nextSetBit(index + 1), checked++) {
                LegacyBlocks.Spec spec = s.at(index);
                if (spec.kind() != LegacyBlocks.Kind.EXACT || !basement && coversPending(index)) {
                    continue;
                }
                Item item = itemFor(spec);
                if (!items.contains(item) && !Boolean.TRUE.equals(seen(index))) {
                    items.add(item);
                }
            }
        }
        wantedNow.put(basement, Set.copyOf(items));
        return wantedNow.get(basement);
    }

    /** The plan's rooms under the ground: each with its size, what's still to build in and round it, and its ways up. */
    public String basement() {
        Schematic s = plan;
        if (s == null) {
            return "no plan";
        }
        int ground = Math.max(0, Math.min(s.height() - 1, centre.getY() - origin.getY()));
        int w = s.width();
        int l = s.length();
        // Under a roof: a block of the plan somewhere over it in its column (the open pit round the building is no room)
        int[] roof = new int[w * l];
        for (int z = 0; z < l; z++) {
            for (int x = 0; x < w; x++) {
                roof[z * w + x] = -1;
                for (int y = s.height() - 1; y >= 0; y--) {
                    if (!passableInPlan(s.index(x, y, z))) {
                        roof[z * w + x] = y;
                        break;
                    }
                }
            }
        }
        java.util.BitSet seen = new java.util.BitSet(s.size());
        StringBuilder text = new StringBuilder("Basement (layers 0.." + (ground - 1) + ", heights " + origin.getY() + ".." + (origin.getY() + ground - 1) + "):");
        int rooms = 0;
        for (int start = 0; start < ground * w * l; start++) {
            if (seen.get(start) || !passableInPlan(start) || start / (w * l) >= roof[start % (w * l)]) {
                continue;
            }
            // One room: the passable cells joined to this one, under the ground
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            seen.set(start);
            int cells = 0;
            int left = 0;
            List<BlockPos> exits = new ArrayList<>();
            int minY = Integer.MAX_VALUE;
            int maxY = 0;
            while (!queue.isEmpty()) {
                int index = queue.poll();
                cells++;
                int y = index / (w * l);
                int z = (index / w) % l;
                int x = index % w;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
                for (int[] d : steps) {
                    int nx = x + d[0];
                    int ny = y + d[1];
                    int nz = z + d[2];
                    if (nx < 0 || nz < 0 || nx >= w || nz >= l || ny < 0) {
                        continue;
                    }
                    int next = s.index(nx, ny, nz);
                    boolean covered = ny < roof[nz * w + nx];
                    if (ny >= ground || !covered) {
                        if (passableInPlan(next) && exits.size() < 4) {
                            exits.add(worldPos(next)); // (open into the ground floor, or the open pit: a way in)
                        }
                        continue;
                    }
                    if (!passableInPlan(next)) {
                        if (pending.get(next)) {
                            left++; // (a wall, floor or ceiling of it still to build)
                        }
                        continue;
                    }
                    if (!seen.get(next)) {
                        seen.set(next);
                        queue.add(next);
                    }
                }
            }
            if (cells < 4) {
                continue;
            }
            rooms++;
            BlockPos at = worldPos(start);
            text.append("\n room ").append(rooms).append(" at ").append(at.toShortString()).append(": ").append(cells).append(" cells, heights ")
                .append(origin.getY() + minY).append("..").append(origin.getY() + maxY).append(", ").append(left).append(" blocks round it to build, ")
                .append(exits.isEmpty() ? "NO way up" : "ways up at " + exits.stream().map(BlockPos::toShortString).toList());
            if (rooms >= 25) {
                text.append("\n ...");
                break;
            }
        }
        return text.toString();
    }

    /** Every block a layer still wants (as far as seen in loaded chunks: what's in already is marked done on the way). */
    public String leftIn(ServerLevel level, int layer) {
        Schematic schematic = plan;
        if (schematic == null || layer < 0 || layer >= schematic.height()) {
            return "no such layer";
        }
        seenLevel = level;
        int size = schematic.width() * schematic.length();
        Map<Item, Integer> left = new java.util.TreeMap<>(java.util.Comparator.comparing(GreatBuild::key));
        int unloaded = 0;
        for (int index = pending.nextSetBit(layer * size); index >= 0 && index < (layer + 1) * size; index = pending.nextSetBit(index + 1)) {
            LegacyBlocks.Spec spec = schematic.at(index);
            if (spec.kind() != LegacyBlocks.Kind.EXACT) {
                continue;
            }
            Boolean there = seen(index);
            if (there == null) {
                unloaded++;
            } else if (!there) {
                left.merge(itemFor(spec), 1, Integer::sum);
            }
        }
        StringBuilder text = new StringBuilder("layer " + layer + " (height " + (origin.getY() + layer) + ") still wants:");
        left.forEach((item, count) -> text.append(" ").append(key(item).replace("minecraft:", "")).append(" ").append(count)
            .append(failures.getOrDefault(key(item), 0) >= 3 ? " (nobody can get it)" : "").append(","));
        if (unloaded > 0) {
            text.append(" + ").append(unloaded).append(" cells in chunks not loaded");
        }
        return text.toString();
    }

    /** For the status: which layers are written off (below which height), and when that may start. */
    public String writeOffText() {
        if (floorLayer > 0) {
            return "written-off layers: 0.." + (floorLayer - 1) + " (heights " + origin.getY() + ".." + (origin.getY() + floorLayer - 1)
                + "); building from layer " + floorLayer + " (height " + (origin.getY() + floorLayer) + ")";
        }
        if (mayWriteOff()) {
            return "no layers written off yet (the rule is on: three builders giving up on the lowest layer within five minutes)";
        }
        return siteDugSession < 0 ? "layer write-off: waits for the site to be dug out (99%)"
            : "layer write-off: starts in " + (siteDugSession + SESSIONS_BEFORE_WRITE_OFF - sessions) + " sessions";
    }

    public String progressText() {
        return String.format(java.util.Locale.ROOT, "%.1f%% постройки (площадка готова на %.0f%%)", progress(0), progress(1));
    }

    // ---- work -----------------------------------------------------------------------------------

    public enum JobType { PLACE, FILL, DIG }

    /** One thing to do: put {@code spec} (or fill) at {@code pos}, or dig it out. */
    public record Job(JobType type, BlockPos pos, int index, LegacyBlocks.Spec spec) {
    }

    public static Item itemFor(LegacyBlocks.Spec spec) {
        return spec.state().getBlock().asItem();
    }

    /**
     * The next job for this bot near it (or anywhere on the site): placing what it carries,
     * lowest first; then filling in the ground; then digging out, highest first.
     */
    public @Nullable Job nextJob(BotPlayer bot, boolean digging, boolean digFirst) {
        // What it can reach standing where it is, without a jump or a pillar, first (up to five blocks over its feet and
        // three under them); only when there is none, anything on the site
        reachOnly = true;
        try {
            Job job = nextJobIn(bot, digging, digFirst);
            if (job != null) {
                return job;
            }
        } finally {
            reachOnly = false;
        }
        return nextJobIn(bot, digging, digFirst);
    }

    /** A job within reach of where the bot stands (no walking), or null: for one on its way to some far job past work. */
    public @Nullable Job jobAtHand(BotPlayer bot, boolean digging, boolean digFirst) {
        reachOnly = true;
        try {
            return nextJobIn(bot, digging, digFirst);
        } finally {
            reachOnly = false;
        }
    }

    /** Only cells within reach up and down of the bot (see nextJob). */
    private boolean reachOnly;
    private static final int REACH_UP = 5;
    private static final int REACH_DOWN = 3;

    private @Nullable Job nextJobIn(BotPlayer bot, boolean digging, boolean digFirst) {
        Schematic schematic = plan;
        if (schematic == null) {
            return null;
        }
        ServerLevel level = bot.level();
        seenLevel = level;
        long time = level.getGameTime();
        // Plan entries it has the block for
        boolean[] carried = new boolean[schematic.palette().size()];
        boolean any = false;
        for (int i = 0; i < carried.length; i++) {
            LegacyBlocks.Spec spec = schematic.palette().get(i);
            if (spec.kind() == LegacyBlocks.Kind.EXACT) {
                Item item = itemFor(spec);
                carried[i] = Inv.count(bot, stack -> stack.is(item)) > 0;
                any |= carried[i];
            }
        }
        boolean fill = Inv.count(bot, FILL) > 0;
        boolean dirt = Inv.count(bot, stack -> stack.is(Items.DIRT)) > 0;
        for (int radius : new int[] {8, 20, 200}) { // (close by first: from where it stands, without walking)
            if (digging && digFirst && Inv.freeSlots(bot) >= 3) {
                // A digger: clearing the site first (with a full bag, it fills holes with what it dug, then builds)
                Job job = scan(bot, radius, false, index -> schematic.at(index).kind() == LegacyBlocks.Kind.AIR ? JobType.DIG : null, time);
                if (job != null) {
                    return job;
                }
            }
            if (any) {
                Job job = scan(bot, radius, true, index -> {
                    LegacyBlocks.Spec spec = schematic.at(index);
                    return spec.kind() == LegacyBlocks.Kind.EXACT && carried[paletteIndex(index)] ? JobType.PLACE : null;
                }, time);
                if (job != null) {
                    return job;
                }
            }
            if (fill) {
                Job job = scan(bot, radius, true, index -> {
                    LegacyBlocks.Kind kind = schematic.at(index).kind();
                    return kind == LegacyBlocks.Kind.GROUND || kind == LegacyBlocks.Kind.SOIL && dirt
                        || kind == LegacyBlocks.Kind.AIR && !level.getFluidState(worldPos(index)).isEmpty() ? JobType.FILL : null;
                }, time);
                if (job != null) {
                    return job;
                }
            }
            if (digging) {
                Job job = scan(bot, radius, false, index -> schematic.at(index).kind() == LegacyBlocks.Kind.AIR ? JobType.DIG : null, time);
                if (job != null) {
                    return job;
                }
            }
        }
        return null;
    }

    private int paletteIndex(int index) {
        return plan.paletteIndex(index);
    }

    private interface JobKind {
        @Nullable JobType of(int index);
    }

    /** Pending cells within {@code radius} (across) of the bot, by layer (bottom up or top down), nearest first in a layer. */
    private @Nullable Job scan(BotPlayer bot, int radius, boolean bottomUp, JobKind kind, long time) {
        Schematic schematic = plan;
        ServerLevel level = bot.level();
        int bx = bot.getBlockX() - origin.getX();
        int bz = bot.getBlockZ() - origin.getZ();
        int x0 = Math.max(0, bx - radius);
        int x1 = Math.min(schematic.width() - 1, bx + radius);
        int z0 = Math.max(0, bz - radius);
        int z1 = Math.min(schematic.length() - 1, bz + radius);
        if (x0 > x1 || z0 > z1) {
            return null;
        }
        // The lowest (or, digging, highest) layer with work, and a few above it: a cell close by a layer
        // up is better than walking across the site for the layer below (it walked more than it built)
        // Building: the lowest layer with anything still to put in, anywhere on the site, and the one above it - not the
        // lowest near the bot (up on the finished part it went for the top, climbing pillars to it to put them up and
        // take them down again)
        int lowest = bottomUp ? lowestPendingLayer(time) : 0;
        int windowBase = bottomUp ? Math.max(lowest, lowestBusyLayer) : 0; // (see computeLowestPendingLayer)
        Job best = null;
        double bestScore = Double.MAX_VALUE;
        int firstStep = -1;
        for (int step = 0; step < schematic.height(); step++) {
            if (bottomUp && step < lowest) {
                continue;
            }
            if (bottomUp && step > windowTop(lowest)) {
                break;
            }
            if (firstStep >= 0 && step > firstStep + LAYER_WINDOW) {
                break;
            }
            int y = bottomUp ? step : schematic.height() - 1 - step;
            for (int z = z0; z <= z1; z++) {
                int from = schematic.index(x0, y, z);
                int to = schematic.index(x1, y, z);
                for (int index = pending.nextSetBit(from); index >= 0 && index <= to; index = pending.nextSetBit(index + 1)) {
                    if (claims.getOrDefault(index, 0L) > time && !bot.getUUID().equals(claimedBy.get(index))
                        || cooldowns.getOrDefault(index, 0L) > time) {
                        continue;
                    }
                    JobType type = kind.of(index);
                    if (type == null || bottomUp && coversPending(index)) {
                        continue;
                    }
                    BlockPos pos = worldPos(index);
                    if (type == JobType.DIG && isClimbBlock(pos, time)) {
                        continue; // (a bot's way up or across: left)
                    }
                    if (reachOnly && bottomUp && (pos.getY() > bot.getBlockY() + REACH_UP || pos.getY() < bot.getBlockY() - REACH_DOWN)) {
                        continue;
                    }
                    LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                    if (chunk == null) {
                        continue;
                    }
                    BlockState state = chunk.getBlockState(pos);
                    LegacyBlocks.Spec spec = schematic.at(index);
                    if (isDone(spec, state)) {
                        update(index, true);
                        continue;
                    }
                    if (!workable(level, pos, state, type) || ProtectedAreas.isProtected(level, pos)) {
                        continue;
                    }
                    if (firstStep < 0) {
                        firstStep = step;
                    }
                    double score = Math.sqrt(bot.blockPosition().distSqr(pos)) + LAYER_COST * (step - firstStep);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Job(type, pos, index, spec);
                    }
                }
            }
        }
        return best;
    }

    /** The lowest layer with a block still to put in (what can't be had, or isn't asked for, doesn't hold the rest back). */
    private int lowestLayer;
    private long lowestLayerAt = Long.MIN_VALUE / 4; // (not MIN_VALUE itself: "time - it" overflowed, and the layer worked out once was kept for ever)

    /** (Worked out once in a couple of seconds for everyone: every bot asking on every tick hung the server.) */
    private int lowestPendingLayer(long time) {
        if (time - lowestLayerAt < 40 && time >= lowestLayerAt) {
            return lowestLayer;
        }
        lowestLayerAt = time;
        lowestLayer = computeLowestPendingLayer();
        return lowestLayer;
    }

    /**
     * The first layer from the bottom with real work left in it (BUSY_CELLS blocks or more): the layers the bots build in reach
     * that far up from it. A few odd blocks nobody has (red beds, pressure plates, note blocks) held the lowest layer, and
     * so the whole build, down at a layer long done: no work for the bots up the walls above. (Those few stay in reach.)
     */
    private int lowestBusyLayer;
    private static final int BUSY_CELLS = 200;
    /**
     * The top of the layers built in: up from the busy one till there's this much to put in (five layers at least). The
     * layers over the busy one held a few fences, torches and some wool, and the stone bricks every bot had brought - two
     * thousand a layer - were wanted from two layers above the top: the bots dug cobblestone for more all session, 16 blocks in.
     */
    private int windowTop;
    private static final int WINDOW_CELLS = 3000;

    /** The highest layer built in now. */
    private int windowTop(int lowest) {
        return Math.max(windowTop, Math.max(lowest, lowestBusyLayer) + UP_LAYERS);
    }

    private int computeLowestPendingLayer() {
        Schematic schematic = plan;
        int layer = schematic.width() * schematic.length();
        int checked = 0;
        int lowestFound = -1;
        int countLayer = -1;
        int count = 0;
        lowestBusyLayer = floorLayer;
        windowTop = 0;
        int busyCells = -1;
        for (int index = pending.nextSetBit(floorLayer * layer); index >= 0 && checked < 20000; index = pending.nextSetBit(index + 1), checked++) {
            LegacyBlocks.Spec spec = schematic.at(index);
            LegacyBlocks.Kind kind = spec.kind();
            if (kind == LegacyBlocks.Kind.AIR || kind == LegacyBlocks.Kind.SKIP) {
                continue;
            }
            if (kind == LegacyBlocks.Kind.EXACT || kind == LegacyBlocks.Kind.COMPANION) {
                Item item = itemFor(spec);
                if (failures.getOrDefault(key(item), 0) >= 3) {
                    continue;
                }
            }
            Boolean there = seen(index);
            if (there == null) {
                continue; // (not loaded, nobody near: unknown, it doesn't hold the rest back)
            }
            if (there) {
                continue; // (in already: marked so now)
            }
            int at = index / layer;
            if (lowestFound < 0) {
                lowestFound = at;
            }
            if (at != countLayer) {
                countLayer = at;
                count = 0;
            }
            if (busyCells >= 0) {
                if (kind == LegacyBlocks.Kind.EXACT && ++busyCells >= WINDOW_CELLS) {
                    windowTop = at;
                    return lowestFound;
                }
                continue;
            }
            if (kind == LegacyBlocks.Kind.EXACT && ++count >= BUSY_CELLS) { // (blocks to put in: not ground to fill, not the other half of a bed)
                lowestBusyLayer = at;
                busyCells = count; // (on up from here to the top of the window)
                continue;
            }
        }
        if (busyCells >= 0) {
            windowTop = checked >= 20000 ? countLayer : schematic.height(); // (not that much left above it: all of it - as far as looked)
            return lowestFound;
        }
        lowestBusyLayer = lowestFound >= 0 ? lowestFound : floorLayer;
        return lowestFound >= 0 ? lowestFound : floorLayer;
    }

    /**
     * Something still to be put in lower down this cell's column (right under it, or under the air of a room or
     * a passage): a block here would cover it up, out of reach. Each column goes up from the bottom.
     */
    private boolean coversPending(int index) {
        Schematic schematic = plan;
        int layer = schematic.width() * schematic.length();
        for (int below = index - layer; below >= floorLayer * layer; below -= layer) { // (written-off layers below no longer count)
            LegacyBlocks.Kind kind = schematic.at(below).kind();
            if (kind == LegacyBlocks.Kind.AIR || kind == LegacyBlocks.Kind.SKIP) {
                continue; // (open space between: what's under it counts)
            }
            if (!pending.get(below)) {
                return false;
            }
            Boolean there = seen(below);
            return there != null && !there; // (in already, or out of sight: it doesn't hold this one back)
        }
        return false;
    }

    /** The level the site was last looked at in (to see what's in already). */
    private @Nullable ServerLevel seenLevel;

    /**
     * Is this cell's block in already, as the world has it now (marked so if it is)? Null: its chunk isn't loaded.
     * (Thousands of cells of the lowest layer were in long since, still marked to do - only a bot carrying that very block
     * ever looked at them - and held the whole of the layers above back: 46 cells of work for 15 bots.)
     */
    private @Nullable Boolean seen(int index) {
        ServerLevel level = seenLevel;
        if (level == null) {
            return false;
        }
        BlockPos pos = worldPos(index);
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return null;
        }
        if (isDone(plan.at(index), chunk.getBlockState(pos))) {
            update(index, true);
            return true;
        }
        return false;
    }

    /** Layers above the lowest one with work that are looked at too, and what a layer up counts as (blocks of walking). */
    private static final int LAYER_WINDOW = 3;
    /** The layers above the lowest unfinished one it may build in when it has no blocks for that one (five: no further up). */
    private static final int UP_LAYERS = 5;
    private static final double LAYER_COST = 6;

    /** Can this job be started on this block as it is? */
    private static boolean workable(ServerLevel level, BlockPos pos, BlockState state, JobType type) {
        if (state.is(Blocks.BEDROCK) || state.getDestroySpeed(level, pos) < 0) {
            return false;
        }
        if (type == JobType.DIG) {
            return !state.canBeReplaced();
        }
        // Placing: into an empty (or plant, or water) cell with something next to it to click on; a wrong
        // solid block is dug out first
        if (!state.canBeReplaced()) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            BlockState neighbour = level.getBlockState(pos.relative(direction));
            if (!neighbour.canBeReplaced() && !neighbour.getCollisionShape(level, pos.relative(direction)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private final Map<Integer, UUID> claimedBy = new HashMap<>();

    public void claim(BotPlayer bot, Job job) {
        long until = bot.level().getGameTime() + CLAIM_TICKS;
        claims.put(job.index(), until);
        claimedBy.put(job.index(), bot.getUUID());
    }

    /** Couldn't be done for now: left alone a while. */
    /** @param unreachable no way to the cell (or it's shut behind what mustn't go): only that counts towards writing its layer off */
    public void giveUp(BotPlayer bot, Job job, boolean unreachable) {
        long now = bot.level().getGameTime();
        cooldowns.put(job.index(), now + COOLDOWN_TICKS);
        claims.remove(job.index());
        if (unreachable) {
            noteGiveUp(bot, job, now);
        }
    }

    private final Map<Integer, Map<UUID, Long>> layerGiveUps = new java.util.HashMap<>();
    private static final int GIVE_UP_BOTS = 3;
    /** Not one layer after another in a rush: ten minutes at least between two (eight went in eleven minutes, two in fifteen seconds). */
    private static final long WRITE_OFF_GAP = 20L * 60 * 10;
    private long lastWriteOff;
    private static final long GIVE_UP_WINDOW = 20L * 60 * 5;
    /** The share of the site dug out (percent) that counts as done. */
    private static final double SITE_DUG = 99.0;

    /** Noted at the start and end of a session: the site dug out, from when the sessions are counted. */
    private void noteSiteDug() {
        if (siteDugSession < 0 && plan != null && progress(1) >= SITE_DUG) {
            siteDugSession = sessions;
            setDirty();
            LOGGER.info("Great Build: the site is dug out (session {}); layers may be written off in {} sessions", sessions, SESSIONS_BEFORE_WRITE_OFF);
        }
    }

    /** An admin's: the site counts as dug out long ago - layers may be written off from now on. */
    /** An admin's: the building starts again from this layer (the ones below it stay written off; 0: none). */
    public void setFloorLayer(int layer) {
        floorLayer = Math.max(0, layer);
        layerGiveUps.clear();
        lowestLayerAt = Long.MIN_VALUE / 4;
        setDirty();
    }

    public void allowWriteOffNow() {
        siteDugSession = 0;
        setDirty();
    }

    private boolean mayWriteOff() {
        return siteDugSession >= 0 && sessions >= siteDugSession + SESSIONS_BEFORE_WRITE_OFF;
    }

    /**
     * The lowest layer to build in is too deep to get at (a pit under the finished part): three bots or more giving
     * up on its cells within five minutes write it off - the building goes on from the layer above it, and if that
     * can't be got at either, from the next, and so on - for good.
     */
    private void noteGiveUp(BotPlayer bot, Job job, long now) {
        if (job.type() == JobType.DIG || plan == null || !mayWriteOff()) {
            return; // (not while the site is still being dug out: the diggers are at work, the layers below are still opening up)
        }
        int size = plan.width() * plan.length();
        int layer = job.index() / size;
        if (layer != lowestPendingLayer(now)) {
            return;
        }
        Map<UUID, Long> bots = layerGiveUps.computeIfAbsent(layer, l -> new java.util.HashMap<>());
        bots.put(bot.getUUID(), now);
        bots.values().removeIf(at -> now - at > GIVE_UP_WINDOW);
        if (bots.size() >= GIVE_UP_BOTS && (lastWriteOff == 0 || now - lastWriteOff >= WRITE_OFF_GAP)) {
            lastWriteOff = now;
            floorLayer = layer + 1;
            layerGiveUps.clear();
            lowestLayerAt = Long.MIN_VALUE / 4;
            setDirty();
            LOGGER.info("Great Build: layer {} is out of the bots' reach; building on from layer {}", layer, floorLayer);
        }
    }

    public void done(ServerLevel level, Job job) {
        claims.remove(job.index());
        if (job.type() == JobType.DIG) {
            dug++;
        } else {
            placed++;
        }
        recheck(level, job.pos());
        setDirty();
    }

    public boolean restocking(BotPlayer bot, boolean on) {
        if (!on) {
            restocking.remove(bot.getUUID());
            return false;
        }
        return restocking.add(bot.getUUID());
    }

    public int restockers() {
        return restocking.size();
    }

    // ---- the bots' camps ------------------------------------------------------------------------

    /** Camps stand this far out from the site's edge, and about this far apart along it. */
    private static final int CAMP_OFFSET = 7;
    private static final int CAMP_SPACING = 16;
    /** A camp's furnaces round its camp fire (a block's gap between): left, right, then behind. */
    private static final int[][] CAMP_FURNACES = {{-2, 0}, {2, 0}, {0, -2}, {-2, -2}, {2, -2}, {-2, 2}, {2, 2}};

    /** This bot's camp fire by the site (a spot of its own round the site, kept from then on), or null. */
    public @Nullable BlockPos campCentre(BotPlayer bot) {
        List<BlockPos> mine = camp.get(bot.getUUID());
        if (mine != null && !mine.isEmpty()) {
            return mine.get(0);
        }
        Schematic schematic = plan;
        if (schematic == null) {
            return null;
        }
        ServerLevel level = bot.level();
        // Spots all round the site, outside it; the bots spread evenly over them, in name order
        List<int[]> ring = new ArrayList<>();
        int x0 = origin.getX() - CAMP_OFFSET;
        int z0 = origin.getZ() - CAMP_OFFSET;
        int x1 = origin.getX() + schematic.width() + CAMP_OFFSET;
        int z1 = origin.getZ() + schematic.length() + CAMP_OFFSET;
        for (int x = x0; x < x1; x += CAMP_SPACING) {
            ring.add(new int[] {x, z0});
        }
        for (int z = z0; z < z1; z += CAMP_SPACING) {
            ring.add(new int[] {x1, z});
        }
        for (int x = x1; x > x0; x -= CAMP_SPACING) {
            ring.add(new int[] {x, z1});
        }
        for (int z = z1; z > z0; z -= CAMP_SPACING) {
            ring.add(new int[] {x0, z});
        }
        List<String> names = new ArrayList<>();
        for (BotMemory memory : com.minebot.bot.BotRegistry.get(level.getServer()).all()) {
            names.add(memory.name());
        }
        names.sort(null);
        int rank = Math.max(0, names.indexOf(bot.memory().name()));
        int start = rank * ring.size() / Math.max(1, names.size());
        Set<Long> taken = new HashSet<>();
        for (List<BlockPos> other : camp.values()) {
            if (!other.isEmpty()) {
                taken.add(BlockPos.asLong(other.get(0).getX(), 0, other.get(0).getZ()));
            }
        }
        for (int i = 0; i < ring.size(); i++) {
            int[] spot = ring.get((start + i) % ring.size());
            if (taken.contains(BlockPos.asLong(spot[0], 0, spot[1]))
                || level.getChunkSource().getChunkNow(spot[0] >> 4, spot[1] >> 4) == null) {
                continue;
            }
            BlockPos centre = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(spot[0], 0, spot[1]));
            if (!level.getFluidState(centre.below()).isEmpty() || ProtectedAreas.isProtected(level, centre)) {
                continue; // (not out on the water, nor by someone's house)
            }
            List<BlockPos> list = new ArrayList<>();
            list.add(centre);
            camp.put(bot.getUUID(), list);
            setDirty();
            return centre;
        }
        return null;
    }

    /** Where this bot's {@code k}-th camp furnace goes (on the ground round its camp fire), or null. */
    public @Nullable BlockPos campFurnaceSpot(BotPlayer bot, int k) {
        BlockPos centre = campCentre(bot);
        if (centre == null || k >= CAMP_FURNACES.length) {
            return null;
        }
        return bot.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            centre.offset(CAMP_FURNACES[k][0], 0, CAMP_FURNACES[k][1]));
    }

    /** Where this bot's camp chest goes: in front of its fire, across from the furnace behind it; or null. */
    public @Nullable BlockPos campChestSpot(BotPlayer bot) {
        BlockPos centre = campCentre(bot);
        return centre == null ? null
            : bot.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centre.offset(0, 0, 2));
    }

    /** This bot's camp chest, if it's still there. */
    public @Nullable BlockPos campChest(BotPlayer bot) {
        BlockPos pos = campChest.get(bot.getUUID());
        return pos != null && (!bot.level().isLoaded(pos) || bot.level().getBlockState(pos).is(Blocks.CHEST)) ? pos : null;
    }

    public void setCampChest(BotPlayer bot, BlockPos pos) {
        campChest.put(bot.getUUID(), pos.immutable());
        setDirty();
    }

    /**
     * Where a camp's chests go, in front of its fire: the first, then one beside it (a double chest), then
     * the next row - three double chests at most.
     */
    private static final int[][] CAMP_CHESTS = {{0, 2}, {1, 2}, {0, 3}, {1, 3}, {0, 4}, {1, 4}};

    /** The chest spots of the camp round this fire (on the ground there). */
    private static List<BlockPos> chestSpots(ServerLevel level, BlockPos fire) {
        List<BlockPos> spots = new ArrayList<>();
        for (int[] offset : CAMP_CHESTS) {
            spots.add(level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                fire.offset(offset[0], 0, offset[1])));
        }
        return spots;
    }

    /** The chests standing at this bot's camp now. */
    public List<BlockPos> campChests(BotPlayer bot) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos fire = campCentre(bot);
        if (fire == null || !bot.level().isLoaded(fire)) {
            return result;
        }
        for (BlockPos spot : chestSpots(bot.level(), fire)) {
            // (the ground's top is the chest itself where one stands)
            BlockPos chest = spot.below();
            if (bot.level().getBlockState(chest).is(Blocks.CHEST)) {
                result.add(chest);
            }
        }
        return result;
    }

    /** Where this bot's next camp chest goes (beside its last, for a double chest), or null if the camp has all of them. */
    public @Nullable BlockPos nextCampChestSpot(BotPlayer bot) {
        BlockPos fire = campCentre(bot);
        if (fire == null || !bot.level().isLoaded(fire)) {
            return null;
        }
        for (BlockPos spot : chestSpots(bot.level(), fire)) {
            if (!bot.level().getBlockState(spot.below()).is(Blocks.CHEST)) {
                return spot;
            }
        }
        return null;
    }

    /** Every camp's chests: anyone at the site may put in or take out (it's all for the build). */
    public List<BlockPos> allCampChests(ServerLevel level) {
        java.util.Set<BlockPos> result = new java.util.LinkedHashSet<>(campChest.values());
        for (List<BlockPos> one : camp.values()) {
            if (one.isEmpty() || !level.isLoaded(one.get(0))) {
                continue;
            }
            for (BlockPos spot : chestSpots(level, one.get(0))) {
                if (level.getBlockState(spot.below()).is(Blocks.CHEST)) {
                    result.add(spot.below());
                }
            }
        }
        result.removeIf(pos -> level.isLoaded(pos) && !level.getBlockState(pos).is(Blocks.CHEST));
        return new ArrayList<>(result);
    }

    /**
     * Every chest to take materials from: the camp's, and any other standing on the site (a player's, say) in the
     * chunks that are loaded.
     */
    public List<BlockPos> siteChests(ServerLevel level) {
        java.util.Set<BlockPos> result = new java.util.LinkedHashSet<>(allCampChests(level));
        int[] box = site;
        if (box != null) {
            for (int cx = box[0] >> 4; cx <= box[2] >> 4; cx++) {
                for (int cz = box[1] >> 4; cz <= box[3] >> 4; cz++) {
                    var chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) {
                        continue;
                    }
                    for (var entry : chunk.getBlockEntities().entrySet()) {
                        BlockPos pos = entry.getKey();
                        if (entry.getValue() instanceof net.minecraft.world.level.block.entity.ChestBlockEntity && nearSite(pos)) {
                            result.add(pos.immutable());
                        }
                    }
                }
            }
        }
        return new ArrayList<>(result);
    }

    /** This bot's furnaces at its camp (those still standing). */
    public List<BlockPos> campFurnaces(BotPlayer bot) {
        List<BlockPos> mine = camp.getOrDefault(bot.getUUID(), List.of());
        List<BlockPos> result = new ArrayList<>();
        for (int i = 1; i < mine.size(); i++) {
            BlockPos pos = mine.get(i);
            if (!bot.level().isLoaded(pos) || bot.level().getBlockState(pos).is(Blocks.FURNACE)) {
                result.add(pos);
            }
        }
        return result;
    }

    /** Every bot's camp furnaces: what's done in them anyone at the site may take out (it's all for the build). */
    public List<BlockPos> allCampFurnaces() {
        List<BlockPos> result = new ArrayList<>();
        for (List<BlockPos> one : camp.values()) {
            result.addAll(one.subList(Math.min(1, one.size()), one.size()));
        }
        return result;
    }

    public void addCampFurnace(BotPlayer bot, BlockPos pos) {
        List<BlockPos> mine = camp.get(bot.getUUID());
        if (mine != null && !mine.contains(pos)) {
            mine.add(pos.immutable());
            setDirty();
        }
    }

    /** How many furnaces this bot keeps at its camp: 2 or 3 (its own way). */
    public static int campFurnaceCount(BotPlayer bot) {
        // (two or three were too few: 320 stone for stone bricks took most of an hour on them - the bots stood by their fires)
        return 5 + (bot.getUUID().hashCode() & 1);
    }

    // ---- materials ------------------------------------------------------------------------------

    /** Is this a block the plan uses (so never rubbish)? */
    public boolean isMaterial(Item item) {
        return plan != null && materialTotals.containsKey(item);
    }

    /**
     * Blocks most needed for what can be built next (pending cells, bottom up), up to
     * {@code limit} kinds, with how many: what a bot out of materials at the site goes to get.
     */
    public List<Map.Entry<Item, Integer>> neededNext(int cells, Set<Item> excluded) {
        Map<Item, Integer> bill = bill(cells, excluded);
        List<Map.Entry<Item, Integer>> list = new ArrayList<>(bill.entrySet());
        list.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        return list;
    }

    /** Blocks for the next {@code cells} pending exact cells, bottom up. */
    private Map<Item, Integer> bill(int cells, Set<Item> excluded) {
        Map<Item, Integer> bill = new LinkedHashMap<>();
        Schematic schematic = plan;
        if (schematic == null) {
            return bill;
        }
        int counted = 0;
        for (int index = pending.nextSetBit(0); index >= 0 && counted < cells; index = pending.nextSetBit(index + 1)) {
            LegacyBlocks.Spec spec = schematic.at(index);
            if (spec.kind() != LegacyBlocks.Kind.EXACT) {
                continue;
            }
            Item item = itemFor(spec);
            if (excluded.contains(item) || failures.getOrDefault(key(item), 0) >= 3) {
                continue;
            }
            if (Boolean.TRUE.equals(seen(index))) {
                continue; // (in already, only still marked to do: the orders asked for thousands of blocks long built)
            }
            bill.merge(item, 1, Integer::sum);
            counted++;
        }
        return bill;
    }

    public static String key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    public static @Nullable Item item(String key) {
        Identifier id = Identifier.tryParse(key);
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    /** What the next {@code cells} blocks to put in are (as orders would have them), checked against the world. */
    public String needs(ServerLevel level, int cells) {
        seenLevel = level;
        StringBuilder text = new StringBuilder("The next " + cells + " blocks to put in, bottom up:");
        neededNext(cells, Set.of()).stream().limit(25).forEach(e -> text.append(" ").append(key(e.getKey()).replace("minecraft:", ""))
            .append(" ").append(e.getValue()).append(","));
        return text.toString();
    }

    /** What every bot is to bring next time: the next blocks of the plan, 5-10 stacks each. */
    private void makeOrders(MinecraftServer server) {
        orders.clear();
        seenLevel = server.overworld();
        failures.clear(); // (each round gets a fresh go: what failed may have been a passing trouble)
        if (plan == null) {
            return;
        }
        // Only the bots on the server now (an empty server: handed out once they're back, see tick)
        List<BotMemory> bots = new ArrayList<>();
        for (BotPlayer online : com.minebot.bot.BotManager.all()) {
            if (online.memory().autonomous()) {
                bots.add(online.memory());
            }
        }
        if (bots.isEmpty()) {
            return;
        }
        bots.sort(Comparator.comparing(BotMemory::name));
        var random = server.overworld().getRandom();
        int[] capacity = new int[bots.size()];
        int totalStacks = 0;
        for (int i = 0; i < capacity.length; i++) {
            capacity[i] = MIN_STACKS + random.nextInt(MAX_STACKS - MIN_STACKS + 1);
            totalStacks += capacity[i];
        }
        // The bill for that many stacks' worth of blocks, bottom up
        Map<Item, Integer> bill = bill(totalStacks * 64, Set.of());
        List<Map.Entry<Item, Integer>> items = new ArrayList<>(bill.entrySet());
        items.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        int bot = 0;
        for (Map.Entry<Item, Integer> entry : items) {
            int left = entry.getValue();
            int stackSize = new ItemStack(entry.getKey()).getMaxStackSize();
            String itemKey = key(entry.getKey());
            for (int tries = 0; left > 0 && tries < bots.size() * 2; tries++) {
                int i = bot % bots.size();
                // (not from a bot that couldn't get it before: the next one gets it)
                boolean cant = cantGet.getOrDefault(bots.get(i).uuid(), List.of()).contains(itemKey);
                if (capacity[i] > 0 && !cant) {
                    int take = Math.min(left, capacity[i] * stackSize);
                    orders.computeIfAbsent(bots.get(i).uuid(), u -> new LinkedHashMap<>()).merge(key(entry.getKey()), take, Integer::sum);
                    capacity[i] -= (take + stackSize - 1) / stackSize;
                    left -= take;
                }
                if (left > 0 || capacity[i] <= 0 || cant) {
                    bot++;
                }
            }
        }
        setDirty();
    }

    /** What this bot is to bring to the next session. */
    public Map<Item, Integer> orderOf(UUID bot) {
        Map<Item, Integer> result = new LinkedHashMap<>();
        orders.getOrDefault(bot, Map.of()).forEach((key, count) -> {
            Item item = item(key);
            if (item != null && item != Items.AIR) {
                result.put(item, count);
            }
        });
        return result;
    }

    public Map<UUID, Map<String, Integer>> orders() {
        return orders;
    }

    /** A bot couldn't get this: after a few such, it isn't ordered any more (an admin brings it). */
    public void failed(Item item) {
        failures.merge(key(item), 1, Integer::sum);
        setDirty();
    }

    /**
     * This bot couldn't get {@code item} for its order (no flowers or sheep round its home, say): what it
     * still lacks of it goes to another bot on the server that hasn't failed at it (the one with the least
     * to bring), and it isn't ordered this from now on. With nobody to take it, it's dropped (an admin brings it).
     */
    public void failedBy(BotPlayer bot, Item item, int missing) {
        failed(item);
        String key = key(item);
        List<String> cant = cantGet.computeIfAbsent(bot.getUUID(), u -> new ArrayList<>());
        if (!cant.contains(key)) {
            cant.add(key);
        }
        Map<String, Integer> own = orders.get(bot.getUUID());
        if (own == null || !own.containsKey(key) || missing <= 0) {
            setDirty();
            return;
        }
        int kept = own.get(key) - missing; // (what it got ready of it stays its to bring)
        if (kept > 0) {
            own.put(key, kept);
        } else {
            own.remove(key);
        }
        BotPlayer taker = null;
        int least = Integer.MAX_VALUE;
        for (BotPlayer other : com.minebot.bot.BotManager.all()) {
            if (other == bot || !other.memory().autonomous() || cantGet.getOrDefault(other.getUUID(), List.of()).contains(key)) {
                continue;
            }
            int load = orders.getOrDefault(other.getUUID(), Map.of()).values().stream().mapToInt(Integer::intValue).sum();
            if (load < least) {
                least = load;
                taker = other;
            }
        }
        if (taker != null) {
            // A swap: the taker hands this one as much of something of its own order this one can get (stone
            // bricks, say), so neither ends up with more or less to bring than before
            Map<String, Integer> theirs = orders.computeIfAbsent(taker.getUUID(), u -> new LinkedHashMap<>());
            // (best something it makes already for its own order; never wool again to one that couldn't get wool)
            String give = null;
            int most = 0;
            int bestRank = -1;
            for (Map.Entry<String, Integer> entry : theirs.entrySet()) {
                String candidate = entry.getKey();
                if (candidate.equals(key) || cant.contains(candidate) || isWool(candidate) && cant.stream().anyMatch(GreatBuild::isWool)) {
                    continue;
                }
                int rank = own.containsKey(candidate) ? 1 : 0;
                if (rank > bestRank || rank == bestRank && entry.getValue() > most) {
                    give = candidate;
                    most = entry.getValue();
                    bestRank = rank;
                }
            }
            if (give != null) {
                int swap = Math.min(missing, most);
                if (most - swap > 0) {
                    theirs.put(give, most - swap);
                } else {
                    theirs.remove(give);
                }
                own.merge(give, swap, Integer::sum);
                LOGGER.info("Great build: {} takes {} {} from {}'s order in exchange", bot.getPlainTextName(), swap, give,
                    taker.getPlainTextName());
            }
            theirs.merge(key, missing, Integer::sum);
            LOGGER.info("Great build: {} can't get {}; {} of it ordered from {} instead", bot.getPlainTextName(), key, missing,
                taker.getPlainTextName());
        } else {
            LOGGER.info("Great build: {} can't get {}, and nobody else is left to ask", bot.getPlainTextName(), key);
        }
        setDirty();
    }

    private static boolean isWool(String key) {
        Item item = item(key);
        return item != null && new ItemStack(item).is(net.minecraft.tags.ItemTags.WOOL);
    }

    public void clearFailures() {
        failures.clear();
        cantGet.clear(); // (each bot may try again)
        setDirty();
    }

    public Map<String, Integer> failures() {
        return failures;
    }

    public long placed() {
        return placed;
    }

    public long dug() {
        return dug;
    }

    public Map<Item, Integer> materialTotals() {
        return materialTotals;
    }

    public String file() {
        return file;
    }

    /** Re-reads the bots' list: a bot that's new gets an order next time. */
    public void reorder(MinecraftServer server) {
        makeOrders(server);
    }

    /** Is this inside the site (the plan's box, a few blocks around it)? */
    public boolean inSite(BlockPos pos, int margin) {
        Schematic schematic = plan;
        return schematic != null && pos.getX() >= origin.getX() - margin && pos.getZ() >= origin.getZ() - margin
            && pos.getX() < origin.getX() + schematic.width() + margin && pos.getZ() < origin.getZ() + schematic.length() + margin;
    }

    /** No mining under the site higher than this: the cellars' floor stays solid. */
    public int mineCeiling() {
        return origin.getY() - 6;
    }

    public boolean onlyNatural(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.DIRT);
    }
}
