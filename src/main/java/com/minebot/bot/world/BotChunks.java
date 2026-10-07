package com.minebot.bot.world;

import com.minebot.bot.BotManager;
import com.minebot.bot.BotPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * A bot keeps a smaller patch of the world ticking than a player does. The server gives every
 * player a simulation ticket reaching simulation-distance chunks (mobs spawning, crops growing,
 * furnaces burning all round), so ten bots spread over the map made it tick ten players' worth
 * of world. A bot swaps that ticket for one of {@link #RADIUS} chunks. The chunks out to the view
 * distance stay loaded (it still sees them and plans paths through them), they just don't tick.
 */
public final class BotChunks {
    /** Chunks round a bot where mobs move and spawn, crops grow, furnaces burn. */
    public static final int RADIUS = 5;
    /** A ticket "with radius" r is at level 33 - r; entity ticking reaches level 31, so r - 2 chunks. */
    private static final int TICKET_OFFSET = 2;

    private @Nullable ServerLevel level;
    private @Nullable ChunkPos chunk;

    /** After the server moved the bot's tickets along with it (ChunkMap#move). */
    public void update(BotPlayer bot) {
        ServerLevel here = bot.level();
        int simulation = here.getServer().getPlayerList().getSimulationDistance();
        if (simulation <= RADIUS) {
            release(); // (the server ticks less than that anyway)
            return;
        }
        ChunkPos at = bot.chunkPosition();
        if (here != level || !at.equals(chunk)) {
            release();
            here.getChunkSource().addTicketWithRadius(TicketType.PLAYER_SIMULATION, at, RADIUS + TICKET_OFFSET);
            level = here;
            chunk = at;
        }
        // The player-sized ticket the server put where it stands: not for a bot alone
        if (!personIn(here, at)) {
            here.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_SIMULATION, at, simulation + TICKET_OFFSET);
        }
    }

    /** The bot leaves (logs out, dies): its ticket goes. */
    public void release() {
        if (level != null && chunk != null) {
            level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_SIMULATION, chunk, RADIUS + TICKET_OFFSET);
        }
        level = null;
        chunk = null;
    }

    /** A real player in this chunk: its full-size ticket stays. */
    private static boolean personIn(ServerLevel level, ChunkPos chunk) {
        for (ServerPlayer player : level.players()) {
            if (!BotManager.isBot(player.getUUID()) && player.chunkPosition().equals(chunk)) {
                return true;
            }
        }
        return false;
    }
}
