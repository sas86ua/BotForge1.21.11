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
        Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING.listOf()).optionalFieldOf("cant_get", Map.of()).forGetter(b -> b.cantGet)
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
        this("", BlockPos.ZERO, -1, -1, 0, Map.of(), Map.of(), 0, 0, List.of(), Map.of(), List.of(), Map.of(), Map.of());
    }

    private GreatBuild(String file, BlockPos centre, long nextDay, long sessionEnd, int sessions,
                       Map<UUID, Map<String, Integer>> orders, Map<String, Integer> failures, long placed, long dug,
                       List<Integer> chunkDone, Map<UUID, List<BlockPos>> camp, List<UUID> sentEarly, Map<UUID, BlockPos> campChest, Map<UUID, List<String>> cantGet) {
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
        Schematic schematic = plan;
        if (schematic == null) {
            return null;
        }
        ServerLevel level = bot.level();
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
        int lowest = bottomUp ? lowestPendingLayer() : 0;
        Job best = null;
        double bestScore = Double.MAX_VALUE;
        int firstStep = -1;
        for (int step = 0; step < schematic.height(); step++) {
            if (bottomUp && step < lowest) {
                continue;
            }
            if (bottomUp && step > lowest + 1) {
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
    private int lowestPendingLayer() {
        Schematic schematic = plan;
        int layer = schematic.width() * schematic.length();
        int checked = 0;
        for (int index = pending.nextSetBit(0); index >= 0 && checked < 20000; index = pending.nextSetBit(index + 1), checked++) {
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
            return index / layer;
        }
        return 0;
    }

    /**
     * Something still to be put in lower down this cell's column (right under it, or under the air of a room or
     * a passage): a block here would cover it up, out of reach. Each column goes up from the bottom.
     */
    private boolean coversPending(int index) {
        Schematic schematic = plan;
        int layer = schematic.width() * schematic.length();
        for (int below = index - layer; below >= 0; below -= layer) {
            LegacyBlocks.Kind kind = schematic.at(below).kind();
            if (kind == LegacyBlocks.Kind.AIR || kind == LegacyBlocks.Kind.SKIP) {
                continue; // (open space between: what's under it counts)
            }
            return pending.get(below);
        }
        return false;
    }

    /** Layers above the lowest one with work that are looked at too, and what a layer up counts as (blocks of walking). */
    private static final int LAYER_WINDOW = 3;
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
    public void giveUp(BotPlayer bot, Job job) {
        cooldowns.put(job.index(), bot.level().getGameTime() + COOLDOWN_TICKS);
        claims.remove(job.index());
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
    private static final int[][] CAMP_FURNACES = {{-2, 0}, {2, 0}, {0, -2}};

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
        return 2 + (bot.getUUID().hashCode() & 1);
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

    /** What every bot is to bring next time: the next blocks of the plan, 5-10 stacks each. */
    private void makeOrders(MinecraftServer server) {
        orders.clear();
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
