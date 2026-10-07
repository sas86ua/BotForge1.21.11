package com.minebot.bot.world;

import com.minebot.bot.BotManager;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Areas around real players' respawn points (their beds, anchors, /spawnpoint).
 * Bots don't break blocks, hunt, build or take beds there, so player homes
 * stay untouched.
 */
public final class ProtectedAreas {
    public static final int RADIUS = 32;
    /** Offline players' respawn points are re-read from disk this often. */
    private static final int REFRESH_TICKS = 5 * 60 * 20;
    private static final Logger LOGGER = LogUtils.getLogger();

    private static List<GlobalPos> offlineRespawns = List.of();
    private static List<GlobalPos> onlineRespawns = List.of();
    private static int lastRefresh = Integer.MIN_VALUE;

    private ProtectedAreas() {
    }

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() - lastRefresh >= REFRESH_TICKS) {
            lastRefresh = server.getTickCount();
            offlineRespawns = readSavedRespawns(server);
        }
        // Online players can move their bed any time; cheap to recompute every second
        if (server.getTickCount() % 20 == 0) {
            List<GlobalPos> online = new ArrayList<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                GlobalPos respawn = respawnOf(player);
                if (respawn != null && !BotManager.isBot(player.getUUID())) {
                    online.add(respawn);
                }
            }
            onlineRespawns = online;
        }
    }

    public static boolean isProtected(ServerLevel level, BlockPos pos) {
        return near(onlineRespawns, level, pos, RADIUS) || near(offlineRespawns, level, pos, RADIUS)
            || NoGoAreas.contains(level, pos);
    }

    /** True if this bed/anchor is some real player's respawn point. */
    public static boolean isPlayerRespawnBlock(ServerLevel level, BlockPos pos) {
        // A bed is two blocks; the respawn point is stored on either half
        return near(onlineRespawns, level, pos, 1) || near(offlineRespawns, level, pos, 1);
    }

    private static boolean near(List<GlobalPos> points, ServerLevel level, BlockPos pos, int radius) {
        for (GlobalPos point : points) {
            if (point.dimension() == level.dimension()) {
                BlockPos p = point.pos();
                int dx = p.getX() - pos.getX();
                int dz = p.getZ() - pos.getZ();
                if (dx * dx + dz * dz <= radius * radius && Math.abs(p.getY() - pos.getY()) <= radius) {
                    return true;
                }
            }
        }
        return false;
    }

    private static GlobalPos respawnOf(ServerPlayer player) {
        ServerPlayer.RespawnConfig config = player.getRespawnConfig();
        return config == null ? null : config.respawnData().globalPos();
    }

    private static List<GlobalPos> readSavedRespawns(MinecraftServer server) {
        List<GlobalPos> result = new ArrayList<>();
        File folder = server.getPlayerList().getPlayerIo().getPlayerDataFolder();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".dat"));
        if (files == null) {
            return result;
        }
        for (File file : files) {
            UUID uuid;
            try {
                uuid = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (BotManager.isBot(uuid) || server.getPlayerList().getPlayer(uuid) != null) {
                continue; // online players are covered live
            }
            try {
                CompoundTag tag = NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap());
                tag.read("respawn", ServerPlayer.RespawnConfig.CODEC)
                    .ifPresent(config -> result.add(config.respawnData().globalPos()));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not read respawn point from {}", file, e);
            }
        }
        return result;
    }
}
