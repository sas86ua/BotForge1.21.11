package com.minebot.bot;

import com.minebot.bot.action.Inv;
import com.minebot.bot.ai.BotBrain;
import com.minebot.bot.path.Goal;
import com.minebot.compat.NAuthCompat;
import com.minebot.bot.path.Navigator;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A server-side player driven by its own AI instead of a client.
 */
public class BotPlayer extends ServerPlayer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int STATUS_INTERVAL = 20 * 30;

    private final BotController controller = new BotController(this);
    private final BotCombat combat = new BotCombat(this);
    private final Navigator navigator = new Navigator(this);
    /**
     * This bot's own moment in every cycle (0-2399 ticks, the longest one): its periodic checks don't fall on
     * the same tick as everyone else's, though they all came on the server at once (a restart, someone joining).
     * (Before the brain: that starts its count from it.)
     */
    private final int phase = java.util.concurrent.ThreadLocalRandom.current().nextInt(2400);
    private final BotBrain brain = new BotBrain(this);
    private final com.minebot.bot.world.BotChunks chunks = new com.minebot.bot.world.BotChunks();
    private BotMemory memory;
    private boolean wasFighting;
    /** Crafting tables/furnaces/campfires the bot put down away from home and still has to pick up. */
    private final List<BlockPos> leftBehind = new ArrayList<>();
    /** Pillars it put up (to reach an enemy, the top of a tree) and should take down again. */
    /** When each block in {@code pillars} was put down (ticks); taken down only a while after. */
    private final java.util.Map<BlockPos, Integer> pillarTicks = new java.util.HashMap<>();
    /** Where it recently felled trees: good places to plant saplings. */
    private final Deque<BlockPos> choppedTrees = new ArrayDeque<>();
    /** The tree it's felling (where it stood) and its logs still standing; kept when the task is interrupted. */
    private @Nullable BlockPos fellingTree;
    private final java.util.Set<BlockPos> trunk = new java.util.HashSet<>();
    /** Containers it has looked into on its travels (positions as longs). */
    private final LongLinkedOpenHashSet browsedChests = new LongLinkedOpenHashSet();
    private long nextChestBrowse;
    /** The journey under way, if any (not saved: a restart ends it). */
    private @Nullable com.minebot.bot.ai.JourneyTask.Journey journey;
    private long nextFarmVisit;
    private long nextTidy;
    private long nextArmorCheck;
    /** Throttled checks (chests, nearby blocks): when each may run again, and its last answer. */
    private final java.util.Map<String, Long> nextCheck = new java.util.HashMap<>();
    private final java.util.Map<String, Boolean> lastCheck = new java.util.HashMap<>();
    private @Nullable com.minebot.bot.craft.Target armorUpgrade;
    private int aiPausedUntil;
    private @Nullable BlockPos airTarget;
    /** When it last had to swim up for air (tasks give up on targets that need diving). */
    private int lastGaspTick = -1000;
    /** While set: smelting big batches, it loads the furnaces and goes (see GreatBuildPrepTask). */
    private boolean leaveWhileSmelting;
    /** Set by a smelt that loaded everything and left: ticks until it's all cooked (-1: none). */
    private int leftCooking = -1;
    /** While set: stone and ore only from underground (see MiningRule). */
    private @Nullable com.minebot.bot.ai.MiningRule miningRule;
    /** Swimming for air: how close it got, how long since it got closer, when it gave up (ticks). */
    private double breatheBest = Double.MAX_VALUE;
    private int breatheStill;
    private int breatheGiveUpUntil;
    /** Air it couldn't swim to this time under water (forgotten once it breathes again). */
    private final java.util.Set<BlockPos> badAir = new java.util.HashSet<>();
    /** Where it last filled a bucket for its fields (not kept over a restart). */
    private @Nullable BlockPos farmWater;

    /** When recent navigations failed (ticks), to notice being stuck. */
    private final java.util.ArrayDeque<Integer> navFailures = new java.util.ArrayDeque<>();
    /** Where the bot was at the last status line, to notice when it stands still for long. */
    private @Nullable Vec3 lastStatusPos;
    private int stillStatuses;
    private boolean debug;
    /** Server tick of death, or -1 while alive. */
    private int deathTick = -1;
    private BotConnection botConnection;

    private BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    /**
     * Brings a bot into the world. A bot that played before comes back with
     * its saved inventory and position; a new one appears at {@code fresh}.
     * @return the bot, and whether it was restored from saved data
     */
    static Spawned spawn(MinecraftServer server, BotMemory memory, BotSpawnPoints.Location fresh) {
        GameProfile profile = new GameProfile(memory.uuid(), memory.name());
        Optional<CompoundTag> saved = server.getPlayerList().loadPlayerData(new NameAndId(memory.uuid(), memory.name()));

        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            Optional<ValueInput> input = saved.map(tag -> TagValueInput.create(reporter, server.registryAccess(), tag));
            ServerPlayer.SavedPosition position = input.flatMap(in -> in.read(ServerPlayer.SavedPosition.MAP_CODEC))
                .orElse(ServerPlayer.SavedPosition.EMPTY);

            ServerLevel level = position.dimension().map(server::getLevel).orElse(fresh.level());
            Vec3 pos = position.position().orElse(fresh.pos());
            Vec2 rotation = position.rotation().orElse(new Vec2(fresh.yaw(), 0.0F));

            BotPlayer bot = new BotPlayer(server, level, profile);
            bot.memory = memory;
            input.ifPresent(bot::load);
            if (bot.isDeadOrDying()) {
                // Logged out while dead: come back alive at a spawn point
                bot.setHealth(bot.getMaxHealth());
                level = fresh.level();
                pos = fresh.pos();
                bot.setServerLevel(level);
            }
            bot.snapTo(pos.x, pos.y, pos.z, rotation.x, rotation.y);
            // Make sure the ground exists before the bot starts falling
            level.getChunk(BlockPos.containing(pos));

            bot.botConnection = new BotConnection();
            server.getPlayerList().placeNewPlayer(bot.botConnection, bot, CommonListenerCookie.createInitial(profile, false));
            if (saved.isEmpty()) {
                bot.setGameMode(GameType.SURVIVAL);
                bot.setHealth(bot.getMaxHealth());
            }
            bot.finishSetup();
            return new Spawned(bot, saved.isPresent());
        }
    }

    record Spawned(BotPlayer bot, boolean restored) {
    }

    /**
     * Replaces a dead bot with a fresh one, the way {@link PlayerList#respawn}
     * does for real players (which would create a plain ServerPlayer instead).
     * The bot keeps its entity id and tab-list entry, so there is no leave/join.
     */
    static BotPlayer respawn(BotPlayer old, ServerLevel level, Vec3 pos, float yRot, boolean keepRespawnPoint) {
        MinecraftServer server = level.getServer();
        PlayerList playerList = server.getPlayerList();
        List<ServerPlayer> players = PlayerListAccess.players(playerList, old);
        Map<UUID, ServerPlayer> playersByUuid = PlayerListAccess.playersByUuid(playerList, old);

        players.remove(old);
        old.chunks.release();
        old.level().removePlayerImmediately(old, Entity.RemovalReason.KILLED);

        BotPlayer bot = new BotPlayer(server, level, old.getGameProfile());
        bot.memory = old.memory;
        bot.debug = old.debug;
        bot.botConnection = old.botConnection;
        // A fresh handler (sets bot.connection). Reusing the old one, as vanilla does, would
        // leave it "waiting for respawn", which only a client packet clears and which keeps
        // the player invulnerable.
        old.getTextFilter().leave();
        new ServerGamePacketListenerImpl(server, bot.botConnection, bot, CommonListenerCookie.createInitial(old.getGameProfile(), false));
        bot.restoreFrom(old, false);
        bot.setId(old.getId());
        bot.setMainArm(old.getMainArm());
        if (keepRespawnPoint) {
            bot.copyRespawnPosition(old);
        }
        old.getTags().forEach(bot::addTag);
        bot.snapTo(pos.x, pos.y, pos.z, yRot, 0.0F);
        level.getChunk(BlockPos.containing(pos));

        level.addRespawnedPlayer(bot);
        players.add(bot);
        playersByUuid.put(bot.getUUID(), bot);
        bot.initInventoryMenu();
        bot.setHealth(bot.getHealth());
        bot.finishSetup();
        ForgeEventFactory.firePlayerRespawnEvent(bot, false);
        return bot;
    }

    private void finishSetup() {
        // Show every skin layer (hat, jacket, sleeves...) like a normal client would
        byte allParts = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            allParts |= (byte) part.getMask();
        }
        getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, allParts);
        navigator.setZone(memory.anchor().pos(), BotMemory.ZONE_RADIUS);
    }

    public BotController controller() {
        return controller;
    }

    public BotCombat combat() {
        return combat;
    }

    public Navigator navigator() {
        return navigator;
    }

    /** See {@link #phase}. */
    public int phase() {
        return phase;
    }

    public BotBrain brain() {
        return brain;
    }

    public List<BlockPos> leftBehind() {
        return leftBehind;
    }

    /** A block it put down to stand on, to take down again once it's done with it. */
    public void notePillar(BlockPos pos) {
        List<BlockPos> pillars = pillars();
        pillars.add(pos);
        if (pillars.size() > 256) {
            pillars.remove(0); // (the oldest are long forgotten)
        }
        pillarTicks.put(pos, tickCount);
        if (pillarTicks.size() > 256) {
            pillarTicks.keySet().retainAll(new java.util.HashSet<>(pillars)); // (taken down or forgotten ones)
        }
    }

    /** Ticks since this block of {@code pillars} was put down (long ago if not known). */
    public int pillarAge(BlockPos pos) {
        Integer at = pillarTicks.get(pos);
        return at == null ? Integer.MAX_VALUE : tickCount - at;
    }

    /** Blocks (and ladders) it put up to climb on, to take down again: kept in its memory over sleeps and restarts. */
    public List<BlockPos> pillars() {
        return memory.scaffold();
    }

    public LongLinkedOpenHashSet browsedChests() {
        while (browsedChests.size() > 512) {
            browsedChests.removeFirstLong();
        }
        return browsedChests;
    }

    public @Nullable com.minebot.bot.ai.JourneyTask.Journey journey() {
        return journey;
    }

    public void setJourney(@Nullable com.minebot.bot.ai.JourneyTask.Journey journey) {
        this.journey = journey;
    }

    /** The answer of {@code check}, worked out again at most every {@code interval} ticks. */
    public boolean every(String key, int interval, java.util.function.BooleanSupplier check) {
        long now = level().getGameTime();
        if (interval > 0 && !nextCheck.containsKey(key)) {
            nextCheck.put(key, now + phase % interval); // (the first look at its own moment in the cycle)
        }
        if (now >= nextCheck.getOrDefault(key, 0L)) {
            nextCheck.put(key, now + interval);
            lastCheck.put(key, check.getAsBoolean());
        }
        return lastCheck.getOrDefault(key, false);
    }

    public long nextArmorCheck() {
        return nextArmorCheck;
    }

    public @Nullable com.minebot.bot.craft.Target armorUpgrade() {
        return armorUpgrade;
    }

    public void setArmorCheck(long next, @Nullable com.minebot.bot.craft.Target upgrade) {
        nextArmorCheck = next;
        armorUpgrade = upgrade;
    }

    public long nextTidy() {
        return nextTidy;
    }

    public void setNextTidy(long gameTime) {
        nextTidy = gameTime;
    }

    public long nextFarmVisit() {
        return nextFarmVisit;
    }

    public void setNextFarmVisit(long gameTime) {
        nextFarmVisit = gameTime;
    }

    public long nextChestBrowse() {
        return nextChestBrowse;
    }

    public void setNextChestBrowse(long gameTime) {
        nextChestBrowse = gameTime;
    }

    public @Nullable BlockPos fellingTree() {
        return fellingTree;
    }

    /** Starts (or, with null, ends) felling a tree; ending it forgets the trunk. */
    public void setFellingTree(@Nullable BlockPos base) {
        fellingTree = base;
        if (base == null) {
            trunk.clear();
        }
    }

    public java.util.Set<BlockPos> trunk() {
        return trunk;
    }

    public Deque<BlockPos> choppedTrees() {
        while (choppedTrees.size() > 16) {
            choppedTrees.removeFirst();
        }
        return choppedTrees;
    }

    /** How long it has been going nowhere (in steps of 30 s; 0 if it moved or is resting). */
    public int stillSeconds() {
        return stillStatuses * STATUS_INTERVAL / 20;
    }

    public @Nullable BlockPos farmWater() {
        return farmWater;
    }

    public void setFarmWater(@Nullable BlockPos pos) {
        farmWater = pos;
    }

    public void noteNavFailure() {
        navFailures.addLast(tickCount);
        while (navFailures.size() > 10) {
            navFailures.removeFirst();
        }
    }

    /** Navigations that failed in the last {@code ticks}. */
    public int recentNavFailures(int ticks) {
        int count = 0;
        for (int at : navFailures) {
            if (tickCount - at < ticks) {
                count++;
            }
        }
        return count;
    }

    public void clearNavFailures() {
        navFailures.clear();
    }

    /** Had to swim up for air within the last few seconds. */
    public boolean leaveWhileSmelting() {
        return leaveWhileSmelting;
    }

    public void setLeaveWhileSmelting(boolean leave) {
        this.leaveWhileSmelting = leave;
        this.leftCooking = -1;
    }

    public void setLeftCooking(int ticks) {
        this.leftCooking = ticks;
    }

    /** Ticks until the furnaces it left are done, once (then -1 again). */
    public int takeLeftCooking() {
        int ticks = leftCooking;
        leftCooking = -1;
        return ticks;
    }

    public @Nullable com.minebot.bot.ai.MiningRule miningRule() {
        return miningRule;
    }

    public void setMiningRule(@Nullable com.minebot.bot.ai.MiningRule rule) {
        this.miningRule = rule;
    }

    public boolean gaspedRecently() {
        return tickCount - lastGaspTick < 40;
    }

    public BotMemory memory() {
        return memory;
    }

    /** Close enough to use this block (chest, furnace...) and not through a wall. */
    public boolean canUse(BlockPos pos) {
        if (!isWithinBlockInteractionRange(pos, 0.0)) {
            return false;
        }
        return Goal.canSee(level(), getEyePosition(), pos);
    }

    /** Moves the centre of the area the bot lives in. */
    public void setAnchor(GlobalPos anchor) {
        memory.setAnchor(anchor);
        navigator.setZone(anchor.pos(), BotMemory.ZONE_RADIUS);
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    /** Writes to the bot's log file; also to the server console while /bot debug is on. */
    public void debug(String message, Object... args) {
        BotLog.log(getGameProfile().name(), message, args);
        if (debug) {
            LOGGER.info("[" + getGameProfile().name() + "] " + message, args);
        }
    }

    int deathTick() {
        return deathTick;
    }

    /**
     * Right-clicks the nearest bed within reach, like a player would. That sets
     * the bot's respawn point (and at night the bot goes to sleep).
     * @return false if there is no bed within reach
     */
    public boolean useNearestBed() {
        int radius = Mth.ceil(blockInteractionRange());
        BlockPos origin = blockPosition();
        BlockPos bed = BlockPos.betweenClosedStream(origin.offset(-radius, -radius, -radius), origin.offset(radius, radius, radius))
            .filter(pos -> level().getBlockState(pos).getBlock() instanceof BedBlock && canUse(pos))
            .map(BlockPos::immutable)
            .min(Comparator.comparingDouble(pos -> pos.distToCenterSqr(position())))
            .orElse(null);
        return bed != null && useBed(bed);
    }

    /** Right-clicks a bed: sets the respawn point, and sleeps if it's night. */
    public boolean useBed(BlockPos bed) {
        if (!canUse(bed)) {
            return false;
        }
        Vec3 hit = Vec3.atCenterOf(bed);
        controller.lookAt(hit);
        gameMode.useItemOn(this, level(), getMainHandItem(), InteractionHand.MAIN_HAND,
            new BlockHitResult(hit, Direction.UP, bed, false));
        return true;
    }

    /** Leaving the server: the chunks round it stop ticking on its account. */
    void releaseChunks() {
        chunks.release();
    }

    @Override
    public void tick() {
        // Normally the network handler resyncs position and chunk tracking when
        // move packets arrive; the bot has no client, so do it here.
        if (level().getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            level().getChunkSource().move(this);
            chunks.update(this); // (a smaller patch of world ticking round a bot than round a player)
        }
        if (level().getServer().getTickCount() % 100 == 0) {
            NAuthCompat.checkLoggedIn(this);
        }

        if (!isUnderWater() && !badAir.isEmpty()) {
            badAir.clear(); // (breathing again: next time every bit of air is worth a try)
        }
        if (!isDeadOrDying() && isUnderWater() && getAirSupply() < getMaxAirSupply() / 2 && breathe()) {
            // Reflex: running out of breath, swim for air whatever we were doing
            // (no air anywhere near: a flooded cave; the "escape" need breaks out through the rock)
        } else if (!isDeadOrDying() && tickCount >= aiPausedUntil) {
            try {
                boolean fighting = combat.tick();
                if (fighting && !wasFighting) {
                    brain.pause();
                }
                wasFighting = fighting;
                if (!fighting) {
                    brain.tick();
                }
            } catch (RuntimeException e) {
                // A bug in the AI must not take the server down: log it, drop the task, rest a moment
                debug("ERROR in AI while {}: {}", brain.describe(), e.toString(), e);
                brain.reset();
                navigator.stop();
                controller.releaseInputs();
                aiPausedUntil = tickCount + 100;
            }
        }
        if ((tickCount + phase) % STATUS_INTERVAL == 0 && !isDeadOrDying()) {
            logStatus();
        }
        super.tick();
        // Vanilla runs the player's physics from the network handler's tick,
        // which never runs for a bot connection. Skip it while the ground under
        // the bot isn't loaded, or it would fall through the world.
        if (level().isLoaded(blockPosition())) {
            doTick();
        }
    }

    /** Swims towards the nearest open air (straight up may be blocked by leaves or an overhang). */
    /** Swims for the nearest air (one block over the water is enough for the head). False if there's none near. */
    private boolean breathe() {
        lastGaspTick = tickCount;
        if (tickCount < breatheGiveUpUntil) {
            return false; // (that air couldn't be reached: the "escape" need breaks out through the rock)
        }
        BlockPos feet = blockPosition();
        BlockPos above = airStraightUp(feet);
        BlockPos before = airTarget;
        if (above != null && !badAir.contains(above)) {
            airTarget = above; // straight up is surest
        } else if (airTarget == null || tickCount % 20 == 0) {
            airTarget = BlockPos.betweenClosedStream(feet.offset(-8, -2, -8), feet.offset(8, 8, 8))
                .filter(pos -> level().getBlockState(pos).isAir() && !level().getFluidState(pos.below()).isEmpty()
                    && level().getBlockState(pos.below()).getCollisionShape(level(), pos.below()).isEmpty()) // (open water under it)
                .filter(pos -> !badAir.contains(pos))
                .map(BlockPos::immutable)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(feet)))
                .orElse(null);
        }
        if (airTarget == null) {
            if (!badAir.isEmpty()) {
                // Tried every bit of air about: none to be reached; the "escape" need breaks out through the rock
                debug("no way to the air from here; breaking out instead");
                badAir.clear();
                breatheGiveUpUntil = tickCount + 100;
                brain.reconsider(); // (escaping comes first, right away)
            }
            return false;
        }
        if (!airTarget.equals(before)) {
            breatheBest = Double.MAX_VALUE;
            breatheStill = 0;
        }
        // Swimming straight at it (no path): if it gets no closer (a wall in the way), give up on it
        double distance = position().distanceTo(Vec3.atBottomCenterOf(airTarget));
        if (distance < breatheBest - 0.3) {
            breatheBest = distance;
            breatheStill = 0;
        } else if (++breatheStill > 40) {
            // No closer in two seconds (a wall, a slab in the way): the next nearest air instead
            debug("can't get to the air at {}; trying elsewhere", airTarget.toShortString());
            badAir.add(airTarget);
            airTarget = null;
            return true;
        }
        navigator.interrupt();
        controller.moveTowards(Vec3.atBottomCenterOf(airTarget), false, true);
        return true;
    }

    /** Air right above, up through nothing but water (null if rock is in the way). */
    private @Nullable BlockPos airStraightUp(BlockPos feet) {
        for (int dy = 1; dy <= 10; dy++) {
            BlockPos pos = feet.above(dy);
            BlockState state = level().getBlockState(pos);
            if (!state.getFluidState().isEmpty() && state.getCollisionShape(level(), pos).isEmpty()) {
                continue; // (a waterlogged slab or stairs is in the way, though there's water in it)
            }
            return state.isAir() ? pos : null;
        }
        return null;
    }

    /** A line every 30 s with where the bot is and what it does; flags it if it seems stuck. */
    private void logStatus() {
        String task = brain.describe();
        debug("status: {} at {} {} {} {} | hp {} food {} | holding {} | free slots {} | nav {}",
            task, getBlockX(), getBlockY(), getBlockZ(), level().dimension().identifier().getPath(),
            (int) getHealth(), getFoodData().getFoodLevel(), getMainHandItem().getHoverName().getString(),
            Inv.freeSlots(this), navigator.status());
        // (keeping a player company, standing by them while they stand still, isn't being stuck)
        boolean resting = isSleeping() || task.contains("idling") || task.startsWith("idle")
            || task.startsWith("keeping ") && !task.contains("(")
            || task.contains("smelting ") || task.contains("cooking "); // (waiting by a furnace or camp fire takes minutes)
        if (lastStatusPos != null && lastStatusPos.distanceTo(position()) < 2.0 && !resting) {
            if (++stillStatuses >= 3) {
                debug("STUCK? hasn't moved for {} s while: {} (nav goal {}, nav status {})",
                    stillStatuses * STATUS_INTERVAL / 20, task, navigator.goal(), navigator.status());
            }
            if (stillStatuses >= 6) {
                // Three minutes going nowhere: drop it and do something else for a while
                brain.abandon("stuck for " + stillStatuses * STATUS_INTERVAL / 20 + " s");
                stillStatuses = 0;
            }
        } else {
            stillStatuses = 0;
        }
        lastStatusPos = position();
    }

    /** The server simulates the bot's movement, so it owns ground checks and fall damage. */
    @Override
    public boolean isClientAuthoritative() {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        // getEntity() is the shooter for projectiles, so archers count as attackers too
        if (hurt && source.getEntity() instanceof LivingEntity attacker) {
            combat.onAttacked(attacker);
        }
        return hurt;
    }

    /**
     * Bots lose most of their gear on death, so killing them isn't a free source
     * of iron and diamonds. Anything else they carry drops as usual.
     */
    @Override
    protected void dropEquipment(ServerLevel level) {
        if (!level.getGameRules().get(GameRules.KEEP_INVENTORY)) {
            Inventory inventory = getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (BotLoadout.isGear(stack) && getRandom().nextFloat() >= BotLoadout.GEAR_DROP_CHANCE) {
                    inventory.removeItemNoUpdate(slot);
                }
            }
        }
        super.dropEquipment(level);
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        debug("DIED: {} at {} {} {}", source.getLocalizedDeathMessage(this).getString(), getBlockX(), getBlockY(), getBlockZ());
        controller.releaseInputs();
        navigator.stop();
        combat.reset();
        // BotManager respawns the bot once the death animation has played
        deathTick = level().getServer().getTickCount();
    }
}
