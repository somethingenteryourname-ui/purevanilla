package dev.purevanilla;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/purevanilla/purevanilla.json — user-editable switches for WHAT PureVanilla changes.
 * Reloaded with /purevanilla reload. This file never contains your graphics values;
 * those live in state.json.
 */
public final class PureVanillaConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Show the small "PureVanilla Enabled" / "Performance Mode Enabled" message. */
    public boolean showNotification = true;

    /**
     * Include Smooth Lighting in the switch. Smooth lighting is baked into terrain meshes, so
     * switching it re-meshes chunks in the background (no loading screen, old terrain stays
     * visible until the new one is ready, takes a moment to sweep across the view).
     * Set to false if you want every switch to be purely instant.
     */
    public boolean switchSmoothLighting = true;

    public SodiumExtraSection sodiumExtra = new SodiumExtraSection();

    public static final class SodiumExtraSection {
        public boolean animations = true;
        public boolean particles = true;
        public boolean details = true;
        public boolean render = true;
        public boolean extras = true;
    }

    public static PureVanillaConfig load(Path file) {
        if (Files.exists(file)) {
            try {
                PureVanillaConfig cfg = GSON.fromJson(Files.readString(file), PureVanillaConfig.class);
                if (cfg != null) {
                    if (cfg.sodiumExtra == null) cfg.sodiumExtra = new SodiumExtraSection();
                    cfg.save(file); // adds any options introduced by newer versions, keeps the user's values
                    return cfg;
                }
            } catch (IOException | JsonParseException e) {
                PureVanilla.LOGGER.warn("[PureVanilla] {} could not be read; using defaults for this session (file left untouched).", file, e);
                return new PureVanillaConfig();
            }
        }
        PureVanillaConfig cfg = new PureVanillaConfig();
        cfg.save(file);
        return cfg;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            AtomicFiles.write(file, GSON.toJson(this));
        } catch (IOException e) {
            PureVanilla.LOGGER.warn("[PureVanilla] Could not save {}", file, e);
        }
    }
}
