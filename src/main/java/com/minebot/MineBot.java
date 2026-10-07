package com.minebot;

import com.minebot.bot.BotManager;
import com.minebot.bot.world.ProtectedAreas;
import com.minebot.command.BotCommand;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(MineBot.MODID)
public final class MineBot {
    public static final String MODID = "minebot";

    public MineBot() {
        RegisterCommandsEvent.BUS.addListener(event -> BotCommand.register(event.getDispatcher(), event.getBuildContext()));
        ServerStartedEvent.BUS.addListener(event -> {
            BotManager.onServerStarted(event.getServer());
            com.minebot.stats.ServerStats.onServerStarted();
        });
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> {
            ProtectedAreas.tick(event.server());
            BotManager.tick(event.server());
            com.minebot.bot.build.GreatBuild.get(event.server()).tick(event.server());
            com.minebot.stats.ServerStats.tick(event.server());
        });
        // Log bots out cleanly so their data is saved like a normal player's
        // Bots asleep on an empty server wake up when someone joins
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> BotManager.onPlayerLoggedIn(event.getEntity()));
        ServerStoppingEvent.BUS.addListener(event -> BotManager.onServerStopping());
        ServerStoppedEvent.BUS.addListener(event -> BotManager.onServerStopped());
    }
}
