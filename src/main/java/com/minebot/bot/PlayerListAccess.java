package com.minebot.bot;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Reaches PlayerList's private player collections, which respawning a bot has
 * to update the same way {@link PlayerList#respawn} does. Fields are found by
 * what they hold rather than by name, so this works with any mappings.
 */
final class PlayerListAccess {
    private static Field playersField;
    private static Field playersByUuidField;

    private PlayerListAccess() {
    }

    @SuppressWarnings("unchecked")
    static List<ServerPlayer> players(PlayerList list, ServerPlayer known) {
        if (playersField == null) {
            // Both the backing list and Forge's unmodifiable view contain the player; we need the ArrayList
            playersField = find(list, value -> value instanceof ArrayList<?> l && l.contains(known));
        }
        return (List<ServerPlayer>) get(playersField, list);
    }

    @SuppressWarnings("unchecked")
    static Map<UUID, ServerPlayer> playersByUuid(PlayerList list, ServerPlayer known) {
        if (playersByUuidField == null) {
            playersByUuidField = find(list, value -> value instanceof Map<?, ?> m && m.get(known.getUUID()) == known);
        }
        return (Map<UUID, ServerPlayer>) get(playersByUuidField, list);
    }

    private static Field find(PlayerList list, Predicate<Object> matches) {
        for (Field field : PlayerList.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            if (matches.test(get(field, list))) {
                return field;
            }
        }
        throw new IllegalStateException("Could not find player collection in PlayerList");
    }

    private static Object get(Field field, PlayerList list) {
        try {
            return field.get(list);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
