package com.minebot.stats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minebot.bot.BotManager;
import com.minebot.bot.BotMemory;
import com.minebot.bot.BotRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Once an hour (real time, while someone is on), a summary in the chat of the statistics Minecraft
 * keeps for every player since the world began: blocks mined, things crafted, mobs killed, deaths,
 * distance travelled; the top 5 players by blocks mined, and the bots' total apart.
 */
public final class ServerStats {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long INTERVAL_MS = 60L * 60 * 1000;
    private static final int TOP = 5;

    private static long nextAt;
    private static boolean running;

    private ServerStats() {
    }

    public static void onServerStarted() {
        nextAt = System.currentTimeMillis() + INTERVAL_MS;
    }

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 200 != 0 || running || System.currentTimeMillis() < nextAt) {
            return;
        }
        boolean someone = server.getPlayerList().getPlayers().stream().anyMatch(player -> !BotManager.isBot(player.getUUID()));
        if (!someone) {
            return; // (nobody to read it: when someone is on)
        }
        nextAt = System.currentTimeMillis() + INTERVAL_MS;
        post(server);
    }

    /** Works it out (off the server thread) and posts it. */
    public static void post(MinecraftServer server) {
        running = true;
        // The players on now: their figures written out first, so the files are up to date
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.getStats().save();
        }
        Path dir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        Path cache = server.getServerDirectory().resolve("usercache.json");
        Set<String> bots = new HashSet<>();
        for (BotMemory memory : BotRegistry.get(server).all()) {
            bots.add(memory.uuid().toString());
        }
        CompletableFuture.supplyAsync(() -> summary(dir, cache, bots)).whenComplete((lines, error) -> server.execute(() -> {
            running = false;
            if (error != null) {
                LOGGER.warn("Server statistics failed", error);
                return;
            }
            for (String line : lines) {
                server.getPlayerList().broadcastSystemMessage(Component.literal(line).withStyle(ChatFormatting.AQUA), false);
            }
        }));
    }

    private record Player(String name, long mined, long km, long crafted, long killed) {
    }

    private static List<String> summary(Path dir, Path cache, Set<String> bots) {
        Map<String, String> names = names(cache);
        long mined = 0;
        long crafted = 0;
        long killed = 0;
        long deaths = 0;
        long cm = 0;
        long botsMined = 0;
        List<Player> players = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String uuid = file.getFileName().toString().replace(".json", "");
                JsonObject stats;
                try (Reader reader = Files.newBufferedReader(file)) {
                    stats = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("stats");
                } catch (IOException | RuntimeException e) {
                    continue;
                }
                if (stats == null) {
                    continue;
                }
                long playerMined = sum(stats, "minecraft:mined");
                long playerCrafted = sum(stats, "minecraft:crafted");
                long playerKilled = sum(stats, "minecraft:killed");
                long playerCm = 0;
                JsonObject custom = stats.getAsJsonObject("minecraft:custom");
                if (custom != null) {
                    deaths += value(custom, "minecraft:deaths");
                    playerCm = value(custom, "minecraft:walk_one_cm") + value(custom, "minecraft:sprint_one_cm")
                        + value(custom, "minecraft:boat_one_cm") + value(custom, "minecraft:swim_one_cm")
                        + value(custom, "minecraft:horse_one_cm") + value(custom, "minecraft:fly_one_cm");
                }
                mined += playerMined;
                crafted += playerCrafted;
                killed += playerKilled;
                cm += playerCm;
                if (bots.contains(uuid)) {
                    botsMined += playerMined;
                } else {
                    players.add(new Player(names.getOrDefault(uuid, uuid.substring(0, 8)), playerMined, playerCm / 100_000,
                        playerCrafted, playerKilled));
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("[Статистика сервера] За всё время: добыто " + number(mined) + " блоков, скрафчено " + number(crafted)
            + ", убито мобов " + number(killed) + ", смертей " + number(deaths) + ", пройдено ~" + number(cm / 100_000) + " км.");
        lines.add(top(players, "добыче", Player::mined, ""));
        lines.add(top(players, "пройденному пути", Player::km, " км"));
        lines.add(top(players, "крафту", Player::crafted, ""));
        lines.add(top(players, "убитым мобам", Player::killed, ""));
        if (botsMined > 0) {
            lines.add("Боты вместе добыли " + number(botsMined) + " блоков.");
        }
        return lines;
    }

    /** "Топ-5 по добыче: 1. Igruha — 228 393, 2. ..." */
    private static String top(List<Player> players, String by, java.util.function.ToLongFunction<Player> figure, String unit) {
        List<Player> sorted = new ArrayList<>(players);
        sorted.removeIf(player -> figure.applyAsLong(player) <= 0);
        sorted.sort((a, b) -> Long.compare(figure.applyAsLong(b), figure.applyAsLong(a)));
        StringBuilder line = new StringBuilder("Топ-" + TOP + " по " + by + ":");
        for (int i = 0; i < Math.min(TOP, sorted.size()); i++) {
            line.append(i == 0 ? " " : ", ").append(i + 1).append(". ").append(sorted.get(i).name()).append(" — ")
                .append(number(figure.applyAsLong(sorted.get(i)))).append(unit);
        }
        return line.toString();
    }

    private static long sum(JsonObject stats, String key) {
        JsonObject group = stats.getAsJsonObject(key);
        long total = 0;
        if (group != null) {
            for (Map.Entry<String, JsonElement> entry : group.entrySet()) {
                total += entry.getValue().getAsLong();
            }
        }
        return total;
    }

    private static long value(JsonObject group, String key) {
        JsonElement element = group.get(key);
        return element == null ? 0 : element.getAsLong();
    }

    /** Player names by UUID, from the server's user cache. */
    private static Map<String, String> names(Path cache) {
        Map<String, String> names = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(cache)) {
            for (JsonElement element : JsonParser.parseReader(reader).getAsJsonArray()) {
                JsonObject entry = element.getAsJsonObject();
                names.put(entry.get("uuid").getAsString(), entry.get("name").getAsString());
            }
        } catch (IOException | RuntimeException e) {
            // (no cache: short ids then)
        }
        return names;
    }

    /** 540809 as "540 809". */
    private static String number(long value) {
        String digits = Long.toString(value);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(' ');
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }
}
