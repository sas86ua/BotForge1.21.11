package com.minebot.bot;

import com.minebot.bot.world.NoGoAreas;
import com.minebot.compat.NAuthCompat;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Keeps track of the bots that are currently in the world. */
public final class BotManager {
    public static final int MAX_BOTS = 15;
    /** Ticks between death and respawn (the death animation takes 20). */
    private static final int RESPAWN_DELAY = 60;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, BotPlayer> BOTS = new LinkedHashMap<>();
    private static @Nullable MinecraftServer server;
    /** Ticks without a real player online (bots don't count). */
    private static int emptyTicks;
    private static boolean wakeUp;

    private BotManager() {
    }

    /** Server started: bring back the bots that were online when it stopped. */
    public static void onServerStarted(MinecraftServer started) {
        server = started;
        BotLog.start(started);
        restoreOnline(started);
    }

    /** Spawns every bot that was online (when the server stopped, or when they fell asleep). */
    private static void restoreOnline(MinecraftServer started) {
        for (BotMemory memory : List.copyOf(BotRegistry.get(started).all())) {
            if (get(memory.name()) != null) {
                continue;
            }
            if (!memory.online() || BOTS.size() >= MAX_BOTS) {
                continue;
            }
            try {
                spawnKnown(started, memory, null);
                LOGGER.info("Restored bot {}", memory.name());
            } catch (RuntimeException e) {
                LOGGER.error("Failed to restore bot {}", memory.name(), e);
            }
        }
    }

    /** Server stopping: log bots out (saving them) but remember they were online. */
    public static void onServerStopping() {
        for (BotPlayer bot : new ArrayList<>(BOTS.values())) {
            logOut(bot);
        }
        BOTS.clear();
    }

    public static void onServerStopped() {
        BotLog.stop();
        NoGoAreas.forget();
        server = null;
        BOTS.clear();
    }

    /** Is this UUID one of our bots (online or not)? */
    public static boolean isBot(UUID uuid) {
        if (server == null) {
            return false;
        }
        for (BotPlayer bot : BOTS.values()) {
            if (bot.getUUID().equals(uuid)) {
                return true;
            }
        }
        return BotRegistry.get(server).contains(uuid);
    }

    /**
     * Spawns a bot. A bot that existed before comes back with its inventory,
     * position and home; a new one appears at a random spawn point (or at
     * {@code fallback} if there are none) with random gear.
     */
    public static BotPlayer spawn(MinecraftServer server, String name, @Nullable BotSpawnPoints.Location fallback) {
        if (BOTS.size() >= MAX_BOTS) {
            throw new IllegalStateException("Bot limit reached (" + MAX_BOTS + ")");
        }
        if (get(name) != null) {
            throw new IllegalStateException("Bot " + name + " already exists");
        }
        if (server.getPlayerList().getPlayerByName(name) != null) {
            throw new IllegalStateException("A player named " + name + " is already online");
        }
        BotMemory memory = BotRegistry.get(server).get(name);
        if (memory != null) {
            return spawnKnown(server, memory, null);
        }

        BotSpawnPoints.Location location = pickSpawn(server, fallback);
        if (location == null) {
            throw new IllegalStateException("No spawn points set; add one with /bot spawnpoint add");
        }
        UUID uuid = BotNames.uuidFor(name);
        GlobalPos anchor = GlobalPos.of(location.level().dimension(), BlockPos.containing(location.pos()));
        memory = BotRegistry.get(server).getOrCreate(name, uuid, anchor);
        // Appear exactly at the anchor: the centre of the zone it will live in
        return spawnKnown(server, memory, location);
    }

    /**
     * @param exact where a brand-new bot appears; null for a known bot, which comes back
     *              where it logged out (or, without saved data, at a spawn point in its zone)
     */
    private static BotPlayer spawnKnown(MinecraftServer server, BotMemory memory, @Nullable BotSpawnPoints.Location exact) {
        BotSpawnPoints.Location fresh = exact != null ? exact : spawnInZone(server, memory);
        BotPlayer.Spawned spawned = BotPlayer.spawn(server, memory, fresh);
        BotPlayer bot = spawned.bot();
        if (!spawned.restored()) {
            BotLoadout.equip(bot);
        }
        memory.setOnline(true);
        BOTS.put(key(memory.name()), bot);
        NAuthCompat.onBotJoined(bot);
        bot.debug("JOINED{} at {} {} {} ({})", spawned.restored() ? " (restored)" : " (new)",
            bot.getBlockX(), bot.getBlockY(), bot.getBlockZ(), bot.level().dimension().identifier().getPath());
        return bot;
    }

    /** A random spawn point inside the bot's zone, or its anchor if none is. */
    private static BotSpawnPoints.Location spawnInZone(MinecraftServer server, BotMemory memory) {
        BotSpawnPoints.Location point = BotSpawnPoints.get(server).pickRandom(server, server.overworld().getRandom(),
            location -> memory.inZone(location.level().dimension(), BlockPos.containing(location.pos())));
        if (point != null) {
            return point;
        }
        ServerLevel level = server.getLevel(memory.anchor().dimension());
        return new BotSpawnPoints.Location(level != null ? level : server.overworld(),
            Vec3.atBottomCenterOf(memory.anchor().pos()), 0.0F);
    }

    private static @Nullable BotSpawnPoints.Location pickSpawn(MinecraftServer server, @Nullable BotSpawnPoints.Location fallback) {
        BotSpawnPoints.Location location = BotSpawnPoints.get(server).pickRandom(server, server.overworld().getRandom());
        return location != null ? location : fallback;
    }

    /**
     * A name from the Minecraft world for a new bot (see {@link BotNames}), skipping
     * names of existing bots and of anyone who has ever played on the server.
     */
    public static String nextFreeName(MinecraftServer server) {
        BotRegistry registry = BotRegistry.get(server);
        return BotNames.pick(name -> get(name) == null && registry.get(name) == null
                && server.getPlayerList().getPlayerByName(name) == null
                && !hasPlayedHere(server, name),
            server.overworld().getRandom());
    }

    /**
     * Has a (non-bot) player with this name played on this server? Checks the local
     * player data only; never asks Mojang's servers.
     */
    private static boolean hasPlayedHere(MinecraftServer server, String name) {
        File folder = server.getPlayerList().getPlayerIo().getPlayerDataFolder();
        return new File(folder, UUIDUtil.createOfflinePlayerUUID(name) + ".dat").exists();
    }

    public static @Nullable BotPlayer get(String name) {
        return BOTS.get(key(name));
    }

    public static Collection<BotPlayer> all() {
        return Collections.unmodifiableCollection(BOTS.values());
    }

    /** Takes the bot out of the world until it is spawned again. */
    public static void remove(BotPlayer bot) {
        if (BOTS.remove(key(bot.getGameProfile().name())) == null) {
            return;
        }
        bot.memory().setOnline(false);
        logOut(bot);
    }

    /**
     * Leaving in a boat (a restart, the server going to sleep): the boat goes in the bag, not
     * left floating out at sea; back on the server the bot has it to put on the water again.
     */
    private static void stowBoat(BotPlayer bot) {
        if (bot.getVehicle() instanceof net.minecraft.world.entity.vehicle.boat.AbstractBoat boat && com.minebot.bot.action.Inv.freeSlots(bot) > 0
            && !(boat instanceof net.minecraft.world.entity.vehicle.boat.AbstractChestBoat)) {
            net.minecraft.world.item.ItemStack item = boat.getPickResult();
            bot.stopRiding();
            boat.discard();
            if (item != null && !item.isEmpty()) {
                com.minebot.bot.action.Inv.give(bot, item);
            }
        }
    }

    private static void logOut(BotPlayer bot) {
        bot.debug("LEFT the server");
        stowBoat(bot);
        // What it gave up on (unreachable tables and furnaces, pits, room to plant): tried again when back
        java.util.UUID id = bot.getUUID();
        com.minebot.bot.ai.CraftTask.forget(id);
        com.minebot.bot.ai.SmeltTask.forget(id);
        com.minebot.bot.ai.LootTask.forget(id);
        com.minebot.bot.ai.PlantTask.forget(id);
        com.minebot.bot.ai.FillHoleTask.forget(id);
        com.minebot.bot.ai.DumpTask.forget(id);
        com.minebot.bot.ai.ClearTreesTask.forget(id);
        com.minebot.bot.ai.CollectItemsTask.forget(id);
        com.minebot.bot.ai.Home.forget(id);
        bot.brain().setCommand(null);
        bot.navigator().stop();
        // Same steps as a real player disconnecting (ServerGamePacketListenerImpl#removePlayerFromWorld)
        MinecraftServer server = bot.level().getServer();
        server.getPlayerList().broadcastSystemMessage(
            Component.translatable("multiplayer.player.left", bot.getDisplayName()).withStyle(ChatFormatting.YELLOW), false);
        bot.disconnect();
        server.getPlayerList().remove(bot);
        bot.releaseChunks();
    }

    /**
     * No real players for the server's pause-when-empty-seconds: the bots fall
     * asleep (log out, saved, still marked online) and the server pauses right
     * away, as it would without bots. They wake up when someone joins.
     */
    private static void checkEmpty(MinecraftServer server) {
        int pauseSeconds = server instanceof DedicatedServer dedicated ? dedicated.pauseWhenEmptySeconds() : 0;
        if (pauseSeconds <= 0 || BOTS.isEmpty()) {
            emptyTicks = 0;
            return;
        }
        boolean people = server.getPlayerList().getPlayers().stream().anyMatch(player -> !(player instanceof BotPlayer));
        emptyTicks = people ? 0 : emptyTicks + 1;
        if (emptyTicks < pauseSeconds * 20) {
            return;
        }
        LOGGER.info("No players for {} s: bots fall asleep with the server", pauseSeconds);
        for (BotPlayer bot : new ArrayList<>(BOTS.values())) {
            bot.debug("ASLEEP: nobody on the server");
            logOut(bot); // (memory stays "online": they come back with the first player)
        }
        BOTS.clear();
        emptyTicks = 0;
        pauseNow(server, pauseSeconds);
    }

    /** Vanilla only starts counting once the bots are gone; skip the wait, it's been empty long enough. */
    private static void pauseNow(MinecraftServer server, int pauseSeconds) {
        try {
            Field empty = ObfuscationReflectionHelper.findField(MinecraftServer.class, "f_349244_"); // emptyTicks
            empty.setInt(server, pauseSeconds * 20 - 1);
        } catch (RuntimeException | IllegalAccessException e) {
            LOGGER.warn("Couldn't pause the server right away; it pauses in {} s", pauseSeconds, e);
        }
    }

    /** A real player joined: wake the sleeping bots. */
    public static void onPlayerLoggedIn(Player player) {
        if (server != null && !(player instanceof BotPlayer)) {
            wakeUp = true; // next tick, once the player is fully in
        }
    }

    /** Called at the end of every server tick. */
    public static void tick(MinecraftServer server) {
        if (wakeUp) {
            wakeUp = false;
            restoreOnline(server);
        }
        checkEmpty(server);
        for (BotPlayer bot : new ArrayList<>(BOTS.values())) {
            int deathTick = bot.deathTick();
            if (deathTick >= 0 && server.getTickCount() - deathTick >= RESPAWN_DELAY) {
                try {
                    respawn(server, bot);
                } catch (RuntimeException e) {
                    LOGGER.error("Failed to respawn bot {}, removing it", bot.getPlainTextName(), e);
                    remove(bot);
                }
            }
        }
    }

    /** Respawns at the bot's bed/anchor if it still works, otherwise at a random spawn point. */
    private static void respawn(MinecraftServer server, BotPlayer dead) {
        ServerLevel level;
        Vec3 pos;
        float yaw;
        boolean usedBed = false;

        TeleportTransition bed = dead.getRespawnConfig() != null
            ? dead.findRespawnPositionAndUseSpawnBlock(true, TeleportTransition.DO_NOTHING)
            : null;
        if (bed != null && !bed.missingRespawnBlock()) {
            level = bed.newLevel();
            pos = bed.position();
            yaw = bed.yRot();
            usedBed = true;
        } else {
            BotMemory memory = dead.memory();
            BotSpawnPoints.Location point = null;
            if (memory.home() == null) {
                // Homeless: start over at any spawn point, and live around that one from now on
                point = BotSpawnPoints.get(server).pickRandom(server, dead.getRandom());
                if (point != null) {
                    memory.setAnchor(GlobalPos.of(point.level().dimension(), BlockPos.containing(point.pos())));
                }
            }
            if (point == null) {
                // Has a home (bed blocked for now) or no spawn points: stay in its own zone
                point = spawnInZone(server, memory);
            }
            level = point.level();
            pos = point.pos();
            yaw = point.yaw();
        }

        // (respawn() also points the navigator at the bot's possibly new zone)
        BotPlayer bot = BotPlayer.respawn(dead, level, pos, yaw, usedBed);
        bot.debug("RESPAWNED at {} {} {} ({}), zone around {}", bot.getBlockX(), bot.getBlockY(), bot.getBlockZ(),
            usedBed ? "bed" : "spawn point", bot.memory().anchor().pos().toShortString());
        if (bot.getInventory().isEmpty()) {
            BotLoadout.equip(bot); // kept inventory (keepInventory gamerule) stays as it was
        }
        BOTS.put(key(bot.getGameProfile().name()), bot);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
