package com.minebot.bot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Every bot that ever existed on this world, with its memory. Saved with the world. */
public class BotRegistry extends SavedData {
    private static final Codec<BotRegistry> CODEC = RecordCodecBuilder.create(i -> i.group(
        BotMemory.CODEC.listOf().fieldOf("bots").forGetter(r -> List.copyOf(r.bots.values()))
    ).apply(i, BotRegistry::new));

    // Forge allows a null data fixer for mod data
    private static final SavedDataType<BotRegistry> TYPE =
        new SavedDataType<>("minebot_bots", BotRegistry::new, CODEC, null);

    private final Map<String, BotMemory> bots = new LinkedHashMap<>();

    public BotRegistry() {
        this(List.of());
    }

    private BotRegistry(List<BotMemory> memories) {
        for (BotMemory memory : memories) {
            memory.setOnChange(this::setDirty);
            bots.put(key(memory.name()), memory);
        }
    }

    public static BotRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public @Nullable BotMemory get(String name) {
        return bots.get(key(name));
    }

    public BotMemory getOrCreate(String name, UUID uuid, GlobalPos anchor) {
        BotMemory memory = bots.get(key(name));
        if (memory == null) {
            memory = new BotMemory(uuid, name, anchor);
            memory.setOnChange(this::setDirty);
            bots.put(key(name), memory);
            setDirty();
        }
        return memory;
    }

    public Collection<BotMemory> all() {
        return bots.values();
    }

    public boolean contains(UUID uuid) {
        for (BotMemory memory : bots.values()) {
            if (memory.uuid().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
