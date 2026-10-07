package com.minebot.compat;

import com.minebot.bot.BotPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/**
 * Compatibility with the NAuth data pack (namespace {@code nnms_auth}), which
 * makes every player register/log in before playing.
 *
 * NAuth treats bots like any player: a bot coming back after a restart (or a
 * /bot remove + spawn) would be sent to the auth dimension in spectator mode
 * and stay there, since it can't type a password. So bots are marked as
 * registered and logged in through NAuth's own authorize function.
 */
public final class NAuthCompat {
    /** Every bot carries this tag, so data packs can tell bots apart with {@code @a[tag=!minebot]}. */
    public static final String BOT_TAG = "minebot";

    private static final Identifier AUTHORIZE = Identifier.parse("nnms_auth:core/authorize");
    private static final String REGISTERED_TAG = "nnms.auth.registered";

    private NAuthCompat() {
    }

    /** Call once a bot is in the world (new, restored or re-spawned by command). */
    public static void onBotJoined(BotPlayer bot) {
        bot.addTag(BOT_TAG);
        MinecraftServer server = bot.level().getServer();
        if (server.getFunctions().get(AUTHORIZE).isEmpty()) {
            return; // NAuth isn't installed
        }
        // Registered: no "please register" prompts, and NAuth's "no registered player online"
        // check won't throw bots out when only bots are on the server.
        bot.addTag(REGISTERED_TAG);
        // A missing score counts as a login attempt with a wrong password; clear it
        run(server, bot, "scoreboard players set @s login 0");
        run(server, bot, "scoreboard players set @s register 0");
        // Log in: clears the "logged out" mark so NAuth leaves the bot where it is. For a bot
        // NAuth hasn't seen yet there's no session, the call does nothing, and NAuth sets the
        // bot up as logged in on its next pass.
        run(server, bot, "function nnms_auth:core/authorize with entity @s");
    }

    /**
     * NAuth may still log a bot out (someone teleported it into the auth lobby, a
     * check ran while it was respawning): it then sits in the auth dimension in
     * spectator mode, immune to everything. Log it back in. Call now and then.
     */
    public static void checkLoggedIn(BotPlayer bot) {
        boolean inLobby = bot.level().dimension().identifier().getNamespace().equals("nnms_auth");
        if (inLobby || bot.isSpectator()) {
            bot.debug("NAuth has me logged out ({}); logging in again", inLobby ? "in its lobby" : "spectator");
            onBotJoined(bot);
        }
    }

    private static void run(MinecraftServer server, BotPlayer bot, String command) {
        CommandSourceStack source = server.createCommandSourceStack()
            .withEntity(bot)
            .withLevel(bot.level())
            .withPosition(bot.position())
            .withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, command);
    }
}
