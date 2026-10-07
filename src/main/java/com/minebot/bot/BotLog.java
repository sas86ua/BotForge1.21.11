package com.minebot.bot;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Per-bot log files in {@code logs/minebot/<bot>.log}, always on while the
 * bots are being developed. Writing happens on a background thread so the
 * server tick never waits for the disk.
 */
public final class BotLog {
    private static final long MAX_SIZE = 5L * 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Logger LOGGER = LogUtils.getLogger();

    private static @Nullable Path directory;
    private static @Nullable ExecutorService writer;

    private BotLog() {
    }

    public static void start(MinecraftServer server) {
        directory = server.getServerDirectory().resolve("logs").resolve("minebot");
        writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MineBot log writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static void stop() {
        ExecutorService running = writer;
        writer = null;
        if (running != null) {
            running.shutdown();
            try {
                running.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Appends a line (SLF4J-style {} placeholders) to the bot's log file. */
    public static void log(String bot, String message, Object... args) {
        ExecutorService running = writer;
        if (running == null) {
            return;
        }
        String text = MessageFormatter.arrayFormat(message, args).getMessage();
        Throwable error = MessageFormatter.arrayFormat(message, args).getThrowable();
        StringBuilder line = new StringBuilder(TIME.format(LocalDateTime.now())).append(' ').append(text).append('\n');
        if (error != null) {
            line.append(stackTrace(error));
        }
        Path file = fileFor(bot);
        running.execute(() -> append(file, line.toString()));
    }

    /** The last {@code count} lines of the bot's log (reads at most the last 64 KB). */
    public static List<String> tail(String bot, int count) {
        Path file = fileFor(bot);
        if (!Files.exists(file)) {
            return List.of();
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            int size = (int) Math.min(length, 64 * 1024);
            byte[] bytes = new byte[size];
            raf.seek(length - size);
            raf.readFully(bytes);
            List<String> lines = new ArrayList<>(Arrays.asList(new String(bytes, StandardCharsets.UTF_8).split("\n")));
            return lines.subList(Math.max(0, lines.size() - count), lines.size());
        } catch (IOException e) {
            return List.of("(could not read log: " + e.getMessage() + ")");
        }
    }

    private static Path fileFor(String bot) {
        Path dir = directory != null ? directory : Path.of("logs", "minebot");
        return dir.resolve(bot.toLowerCase(Locale.ROOT) + ".log");
    }

    private static void append(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MAX_SIZE) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("Could not write bot log {}", file, e);
        }
    }

    private static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
