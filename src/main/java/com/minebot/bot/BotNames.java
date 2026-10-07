package com.minebot.bot;

import net.minecraft.core.UUIDUtil;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Names for bots spawned without one: first the nine default Minecraft
 * characters, then names of Minecraft materials. A character bot also gets
 * that character's default skin.
 */
public final class BotNames {
    /**
     * Default characters and their slot in the client's default skin table
     * (DefaultPlayerSkin: slim models 0-8, wide 9-17, alphabetical).
     */
    private static final Map<String, Integer> CHARACTERS = Map.of(
        "Steve", 15, // wide
        "Alex", 0,   // slim
        "Ari", 10,
        "Efe", 2,
        "Kai", 12,
        "Makena", 4,
        "Noor", 5,
        "Sunny", 16,
        "Zuri", 17);
    private static final int DEFAULT_SKINS = 18;

    private static final List<String> MATERIALS = List.of(
        "Redstone", "Obsidian", "Netherite", "Emerald", "Glowstone", "Amethyst", "Prismarine",
        "Lapis", "Quartz", "Basalt", "Calcite", "Deepslate", "Copper", "Tuff", "Bedrock", "Herobrine");

    private BotNames() {
    }

    /** A random free character name, or a material name once all characters are taken. */
    public static String pick(Predicate<String> isFree, RandomSource random) {
        for (List<String> pool : List.of(new ArrayList<>(CHARACTERS.keySet()), MATERIALS)) {
            List<String> free = pool.stream().filter(isFree).toList();
            if (!free.isEmpty()) {
                return free.get(random.nextInt(free.size()));
            }
        }
        for (int i = 1; ; i++) {
            if (isFree.test("Bot" + i)) {
                return "Bot" + i;
            }
        }
    }

    /**
     * The bot's UUID: the usual offline-mode UUID for the name, nudged for
     * character names so the client shows that character's default skin
     * (clients pick a default skin from the UUID's hash).
     */
    public static UUID uuidFor(String name) {
        UUID uuid = UUIDUtil.createOfflinePlayerUUID(name);
        Integer skin = CHARACTERS.entrySet().stream()
            .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)))
            .map(Map.Entry::getValue)
            .findFirst().orElse(null);
        if (skin == null) {
            return uuid;
        }
        long least = uuid.getLeastSignificantBits();
        for (long nudge = 0; ; nudge++) {
            UUID candidate = new UUID(uuid.getMostSignificantBits(), least + nudge);
            if (Math.floorMod(candidate.hashCode(), DEFAULT_SKINS) == skin) {
                return candidate;
            }
        }
    }
}
