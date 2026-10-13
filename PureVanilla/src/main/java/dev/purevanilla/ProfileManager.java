package dev.purevanilla;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.purevanilla.adapter.SodiumExtraAdapter;
import dev.purevanilla.adapter.VanillaOptionsAdapter;
import dev.purevanilla.adapter.VisualAdapter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The heart of PureVanilla.
 *
 * Invariant that makes crash recovery simple: the settings files on disk always hold your
 * PERFORMANCE values. PureVanilla Mode only ever exists in memory. If the game closes normally
 * while PureVanilla Mode is on, your Performance values are put back and saved first, and
 * PureVanilla Mode is re-applied on the next launch. If the game crashes, the next launch
 * restores the Performance profile saved in state.json.
 */
public final class ProfileManager {

    public enum Mode { PERFORMANCE, PUREVANILLA }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path dir = FabricLoader.getInstance().getConfigDir().resolve(PureVanilla.MOD_ID);
    private final Path configFile = dir.resolve("purevanilla.json");
    private final Path stateFile = dir.resolve("state.json");
    private final Path firstRunBackup = dir.resolve("performance-backup-first-run.json");

    private PureVanillaConfig config;
    private final List<VisualAdapter> adapters = new ArrayList<>();
    private SodiumExtraAdapter sodiumExtra;

    private Mode mode = Mode.PERFORMANCE;
    /** The Performance profile captured at the moment PureVanilla Mode was switched on. */
    private Map<VisualAdapter, VisualAdapter.Snapshot> performance = new LinkedHashMap<>();
    private boolean ready;
    private String pendingNotice;

    /** One idle daemon thread so saving state.json never stalls the frame you pressed the key on. */
    private ExecutorService io;

    // ------------------------------------------------------------------ lifecycle

    /** Called once the client (and every other mod) has finished starting. */
    public void onClientStarted() {
        config = PureVanillaConfig.load(configFile);
        adapters.clear();
        adapters.add(new VanillaOptionsAdapter(config));
        sodiumExtra = new SodiumExtraAdapter(config);
        sodiumExtra.init();
        adapters.add(sodiumExtra);
        ready = true;

        JsonObject state = readState();
        Mode saved = parseMode(state);
        boolean clean = state != null && state.has("cleanShutdown") && state.get("cleanShutdown").getAsBoolean();

        if (!Files.exists(firstRunBackup)) {
            writeSync(firstRunBackup, GSON.toJson(serialize(captureAll())));
            PureVanilla.LOGGER.info("[PureVanilla] First run: your current settings were captured as the Performance profile (backup: {}).", firstRunBackup);
        }

        if (saved == Mode.PUREVANILLA && !clean) {
            recoverAfterCrash(state);
        } else if (saved == Mode.PUREVANILLA) {
            // Normal shutdown last time with PureVanilla on: keep the user's chosen mode.
            if (enable(false)) PureVanilla.LOGGER.info("[PureVanilla] Restored PureVanilla Mode from last session.");
        }
    }

    /** Clean shutdown: put Performance values back and save them, so disk always holds Performance. */
    public void onClientStopping() {
        if (!ready) return;
        if (mode == Mode.PUREVANILLA) {
            JsonObject profile = serialize(performance);
            restoreAll(performance);
            for (VisualAdapter a : adapters) if (a.isAvailable()) a.flushToDisk();
            writeSync(stateFile, GSON.toJson(state(Mode.PUREVANILLA, true, profile)));
        }
        if (io != null) {
            io.shutdown();
            try {
                io.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void recoverAfterCrash(JsonObject state) {
        JsonObject profile = state.has("performanceProfile") && state.get("performanceProfile").isJsonObject()
                ? state.getAsJsonObject("performanceProfile") : new JsonObject();
        int restored = 0;
        for (VisualAdapter a : adapters) {
            if (!a.isAvailable() || !profile.has(a.id())) continue;
            try {
                VisualAdapter.Snapshot snap = a.fromJson(profile.getAsJsonObject(a.id()));
                if (snap != null) {
                    a.restore(snap);
                    a.flushToDisk();
                    restored++;
                }
            } catch (Exception e) {
                PureVanilla.LOGGER.error("[PureVanilla] Could not restore {} after crash", a.displayName(), e);
            }
        }
        mode = Mode.PERFORMANCE;
        writeSync(stateFile, GSON.toJson(state(Mode.PERFORMANCE, true, null)));
        PureVanilla.LOGGER.warn("[PureVanilla] Minecraft did not close normally while PureVanilla Mode was on. "
                + "Restored your Performance settings ({} sources).", restored);
        pendingNotice = "PureVanilla: restored your Performance settings after an unexpected shutdown.";
    }

    // ------------------------------------------------------------------ switching

    public boolean isReady() { return ready; }
    public Mode mode() { return mode; }
    public PureVanillaConfig config() { return config; }
    public List<VisualAdapter> adapters() { return adapters; }

    public void toggle() {
        if (!ready) return;
        if (mode == Mode.PUREVANILLA) disable(true);
        else enable(true);
    }

    /** Switch to PureVanilla Mode. Rolls everything back if any part fails. */
    public boolean enable(boolean notify) {
        if (mode == Mode.PUREVANILLA) return true;

        Map<VisualAdapter, VisualAdapter.Snapshot> captured = captureAll();
        List<VisualAdapter> applied = new ArrayList<>();
        boolean terrain = false;
        for (VisualAdapter a : adapters) {
            if (!a.isAvailable()) continue;
            try {
                terrain |= a.applyVanilla(captured.get(a));
                applied.add(a);
            } catch (Throwable t) {
                PureVanilla.LOGGER.error("[PureVanilla] Failed to apply {}; rolling back to Performance Mode.", a.displayName(), t);
                applied.add(a); // it may have changed some values before failing
                for (VisualAdapter done : applied) {
                    try {
                        done.restore(captured.get(done));
                    } catch (Throwable t2) {
                        PureVanilla.LOGGER.error("[PureVanilla] Rollback of {} failed", done.displayName(), t2);
                    }
                }
                // No terrain refresh needed: meshes are only rebuilt after a successful switch.
                if (notify) notifyPlayer(Text.literal("PureVanilla failed to switch, kept Performance Mode (see log)").formatted(Formatting.RED));
                return false;
            }
        }

        performance = captured;
        mode = Mode.PUREVANILLA;
        if (terrain) TerrainRefresher.refreshLoadedTerrain();
        saveStateAsync(state(Mode.PUREVANILLA, false, serialize(captured)));
        if (notify) notifyPlayer(Text.literal("PureVanilla Enabled").formatted(Formatting.GREEN));
        return true;
    }

    /** Switch back to the exact Performance values captured when PureVanilla Mode was enabled. */
    public void disable(boolean notify) {
        if (mode != Mode.PUREVANILLA) return;
        boolean terrain = restoreAll(performance);
        mode = Mode.PERFORMANCE;
        performance = new LinkedHashMap<>();
        if (terrain) TerrainRefresher.refreshLoadedTerrain();
        saveStateAsync(state(Mode.PERFORMANCE, false, null));
        if (notify) notifyPlayer(Text.literal("Performance Mode Enabled").formatted(Formatting.AQUA));
    }

    /** /purevanilla reload: re-read purevanilla.json and re-apply cleanly if PureVanilla Mode is on. */
    public void reloadConfig() {
        boolean wasOn = mode == Mode.PUREVANILLA;
        if (wasOn) disable(false);
        PureVanillaConfig fresh = PureVanillaConfig.load(configFile);
        config.showNotification = fresh.showNotification;
        config.switchSmoothLighting = fresh.switchSmoothLighting;
        config.sodiumExtra = fresh.sodiumExtra;
        if (wasOn) enable(false);
    }

    private boolean restoreAll(Map<VisualAdapter, VisualAdapter.Snapshot> profile) {
        boolean terrain = false;
        for (VisualAdapter a : adapters) {
            VisualAdapter.Snapshot snap = profile.get(a);
            if (snap == null || !a.isAvailable()) continue;
            try {
                terrain |= a.restore(snap);
            } catch (Throwable t) {
                // Keep going: one broken source must never stop the others from being restored.
                PureVanilla.LOGGER.error("[PureVanilla] Could not restore {}", a.displayName(), t);
            }
        }
        return terrain;
    }

    private Map<VisualAdapter, VisualAdapter.Snapshot> captureAll() {
        Map<VisualAdapter, VisualAdapter.Snapshot> map = new LinkedHashMap<>();
        for (VisualAdapter a : adapters) {
            if (a.isAvailable()) map.put(a, a.capture());
        }
        return map;
    }

    // ------------------------------------------------------------------ messages

    public void tick(MinecraftClient client) {
        if (pendingNotice != null && client.player != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal(pendingNotice).formatted(Formatting.YELLOW));
            pendingNotice = null;
        }
    }

    private void notifyPlayer(Text text) {
        if (!config.showNotification) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud != null && client.player != null) {
            client.inGameHud.setOverlayMessage(text, false);
        }
    }

    // ------------------------------------------------------------------ state file

    private JsonObject serialize(Map<VisualAdapter, VisualAdapter.Snapshot> profile) {
        JsonObject json = new JsonObject();
        profile.forEach((a, snap) -> json.add(a.id(), a.toJson(snap)));
        return json;
    }

    private static JsonObject state(Mode mode, boolean cleanShutdown, JsonObject profile) {
        JsonObject json = new JsonObject();
        json.addProperty("mode", mode.name());
        json.addProperty("cleanShutdown", cleanShutdown);
        if (profile != null) json.add("performanceProfile", profile);
        return json;
    }

    private JsonObject readState() {
        if (!Files.exists(stateFile)) return null;
        try {
            JsonElement el = JsonParser.parseString(Files.readString(stateFile));
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            PureVanilla.LOGGER.warn("[PureVanilla] state.json unreadable; starting in Performance Mode", e);
            return null;
        }
    }

    private static Mode parseMode(JsonObject state) {
        if (state == null || !state.has("mode")) return Mode.PERFORMANCE;
        try {
            return Mode.valueOf(state.get("mode").getAsString());
        } catch (Exception e) {
            return Mode.PERFORMANCE;
        }
    }

    private void saveStateAsync(JsonObject state) {
        String content = GSON.toJson(state); // built on the render thread, so it is a consistent copy
        if (io == null) {
            io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PureVanilla-IO");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        io.execute(() -> writeSync(stateFile, content));
    }

    private void writeSync(Path file, String content) {
        try {
            Files.createDirectories(dir);
            AtomicFiles.write(file, content);
        } catch (IOException e) {
            PureVanilla.LOGGER.error("[PureVanilla] Could not write {}", file, e);
        }
    }
}
