package dev.purevanilla.adapter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.purevanilla.PureVanilla;
import dev.purevanilla.PureVanillaConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sodium Extra integration.
 *
 * Sodium Extra keeps its settings in one live object (SodiumExtraClientMod.options()) whose
 * fields its mixins read every frame / every particle spawn. Changing those fields in memory
 * takes effect immediately with no reload. Sodium Extra is not a compile dependency, so this
 * class looks the fields up by name at startup: anything missing in your installed version
 * is skipped and logged instead of crashing.
 *
 * Fog settings, toasts, FPS/coords overlay, shader prevention and other non-visual or
 * distance-type options are intentionally left alone.
 */
public final class SodiumExtraAdapter implements VisualAdapter {

    private static final String MOD_ID = "sodium-extra";
    private static final String MOD_CLASS = "me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod";

    /** One boolean field inside one settings group. */
    private record Setting(String group, String field, boolean vanillaValue, boolean affectsTerrain,
                           String category, String label) {
        String path() { return group + "." + field; }
    }

    private static final List<Setting> KNOWN = List.of(
            // Animations (texture animations: water, lava, fire, portals, ...)
            new Setting("animationSettings", "animation", true, false, "animations", "All animations"),
            new Setting("animationSettings", "water", true, false, "animations", "Water animation"),
            new Setting("animationSettings", "lava", true, false, "animations", "Lava animation"),
            new Setting("animationSettings", "fire", true, false, "animations", "Fire animation"),
            new Setting("animationSettings", "portal", true, false, "animations", "Portal animation"),
            new Setting("animationSettings", "blockAnimations", true, false, "animations", "Block animations"),
            new Setting("animationSettings", "sculkSensor", true, false, "animations", "Sculk sensor animation"),
            // Particles (the per-particle list, incl. explosions/crits/totem/potions, is handled separately)
            new Setting("particleSettings", "particles", true, false, "particles", "Particles master switch"),
            new Setting("particleSettings", "rainSplash", true, false, "particles", "Rain splash"),
            new Setting("particleSettings", "blockBreak", true, false, "particles", "Block break particles"),
            new Setting("particleSettings", "blockBreaking", true, false, "particles", "Block breaking particles"),
            // Details (sky and weather)
            new Setting("detailSettings", "sky", true, false, "details", "Sky"),
            new Setting("detailSettings", "sun", true, false, "details", "Sun"),
            new Setting("detailSettings", "moon", true, false, "details", "Moon"),
            new Setting("detailSettings", "stars", true, false, "details", "Stars"),
            new Setting("detailSettings", "rainSnow", true, false, "details", "Rain & snow"),
            new Setting("detailSettings", "skyColors", true, false, "details", "Sky colors"),
            new Setting("detailSettings", "biomeColors", true, true, "details", "Biome colors"),
            // Render (entities and block entities)
            new Setting("renderSettings", "lightUpdates", true, false, "render", "Light updates"),
            new Setting("renderSettings", "itemFrame", true, false, "render", "Item frames"),
            new Setting("renderSettings", "armorStand", true, false, "render", "Armor stands"),
            new Setting("renderSettings", "painting", true, false, "render", "Paintings"),
            new Setting("renderSettings", "piston", true, false, "render", "Piston animation"),
            new Setting("renderSettings", "beaconBeam", true, false, "render", "Beacon beam"),
            new Setting("renderSettings", "limitBeaconBeamHeight", false, false, "render", "Beacon beam height limit (off)"),
            new Setting("renderSettings", "enchantingTableBook", true, false, "render", "Enchanting table book"),
            new Setting("renderSettings", "itemFrameNameTag", true, false, "render", "Item frame name tags"),
            new Setting("renderSettings", "playerNameTag", true, false, "render", "Player name tags"),
            // Extras that change how vanilla looks
            new Setting("extraSettings", "cloudHeightOverride", false, false, "extras", "Cloud height override (off)"),
            new Setting("extraSettings", "instantSneak", false, false, "extras", "Instant sneak (off, vanilla sneak animation)"),
            new Setting("extraSettings", "paniniProjection", false, false, "extras", "Panini projection (off)")
    );

    private static final String OTHER_PARTICLES_PATH = "particleSettings.otherMap";

    private final PureVanillaConfig config;
    private Object options;                          // SodiumExtraGameOptions instance
    private final Map<String, Field> groupFields = new HashMap<>();
    private final Map<String, Field> settingFields = new LinkedHashMap<>();
    private Field otherMapField;
    private boolean available;

    public SodiumExtraAdapter(PureVanillaConfig config) {
        this.config = config;
    }

    /** Resolve Sodium Extra's option object. Called once at client start. */
    public void init() {
        available = false;
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            PureVanilla.LOGGER.info("[PureVanilla] Sodium Extra not installed; skipping its settings.");
            return;
        }
        try {
            Class<?> modClass = Class.forName(MOD_CLASS);
            options = modClass.getMethod("options").invoke(null);
            if (options == null) throw new IllegalStateException("options() returned null");

            List<String> missing = new ArrayList<>();
            for (Setting s : KNOWN) {
                Field group = groupField(s.group());
                if (group == null) { missing.add(s.path()); continue; }
                try {
                    Field f = group.getType().getField(s.field());
                    if (f.getType() != boolean.class || Modifier.isStatic(f.getModifiers())) {
                        missing.add(s.path());
                        continue;
                    }
                    settingFields.put(s.path(), f);
                } catch (NoSuchFieldException e) {
                    missing.add(s.path());
                }
            }
            Field particleGroup = groupField("particleSettings");
            if (particleGroup != null) {
                try {
                    Field f = particleGroup.getType().getField("otherMap");
                    if (Map.class.isAssignableFrom(f.getType())) otherMapField = f;
                } catch (NoSuchFieldException e) {
                    missing.add(OTHER_PARTICLES_PATH);
                }
            }
            available = !settingFields.isEmpty() || otherMapField != null;
            PureVanilla.LOGGER.info("[PureVanilla] Sodium Extra detected: {} settings linked{}.",
                    settingFields.size() + (otherMapField != null ? 1 : 0),
                    missing.isEmpty() ? "" : ", not present in this version: " + missing);
        } catch (Throwable t) {
            PureVanilla.LOGGER.warn("[PureVanilla] Sodium Extra is installed but its settings could not be linked; skipping it.", t);
            available = false;
        }
    }

    private Field groupField(String name) {
        return groupFields.computeIfAbsent(name, n -> {
            try {
                return options.getClass().getField(n);
            } catch (NoSuchFieldException e) {
                return null;
            }
        });
    }

    @Override public String id() { return "sodium-extra"; }
    @Override public String displayName() { return "Sodium Extra"; }
    @Override public boolean isAvailable() { return available; }

    private boolean categoryEnabled(String category) {
        return switch (category) {
            case "animations" -> config.sodiumExtra.animations;
            case "particles" -> config.sodiumExtra.particles;
            case "details" -> config.sodiumExtra.details;
            case "render" -> config.sodiumExtra.render;
            case "extras" -> config.sodiumExtra.extras;
            default -> false;
        };
    }

    @Override
    public List<String> describeSettings() {
        List<String> out = new ArrayList<>();
        for (Setting s : KNOWN) {
            if (settingFields.containsKey(s.path()) && categoryEnabled(s.category())) out.add(s.label());
        }
        if (otherMapField != null && config.sodiumExtra.particles) {
            out.add("Per-particle toggles (explosions, crits, totem, potions, enchant, ...) all on");
        }
        return out;
    }

    // ------------------------------------------------------------------ live access

    private Object group(String name) throws IllegalAccessException {
        Field g = groupField(name);
        return g == null ? null : g.get(options);
    }

    private Boolean readBool(Setting s) throws IllegalAccessException {
        Field f = settingFields.get(s.path());
        Object g = group(s.group());
        return (f == null || g == null) ? null : f.getBoolean(g);
    }

    private void writeBool(Setting s, boolean value) throws IllegalAccessException {
        Field f = settingFields.get(s.path());
        Object g = group(s.group());
        if (f != null && g != null) f.setBoolean(g, value);
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> readOtherMap() throws IllegalAccessException {
        if (otherMapField == null) return null;
        Object g = group("particleSettings");
        return g == null ? null : (Map<Object, Object>) otherMapField.get(g);
    }

    private void writeOtherMap(Map<?, ?> map) throws IllegalAccessException {
        Object g = group("particleSettings");
        if (otherMapField != null && g != null) otherMapField.set(g, map);
    }

    /** Make an empty map of the same type Sodium Extra uses (empty = every particle enabled). */
    @SuppressWarnings("unchecked")
    private Map<Object, Object> newMapLike(Map<?, ?> template) {
        if (template != null) {
            try {
                return (Map<Object, Object>) template.getClass().getDeclaredConstructor().newInstance();
            } catch (Throwable ignored) {
                // fall through
            }
        }
        return new HashMap<>();
    }

    // ------------------------------------------------------------------ snapshot

    public static final class Values implements Snapshot {
        final Map<String, Boolean> booleans = new LinkedHashMap<>();
        Map<Object, Object> otherMap;              // the original live map object, restored by reference
        Map<String, Boolean> otherMapSerialized;    // only used when restored from the crash file
    }

    @Override
    public Snapshot capture() {
        Values snap = new Values();
        if (!available) return snap;
        try {
            for (Setting s : KNOWN) {
                Boolean v = readBool(s);
                if (v != null) snap.booleans.put(s.path(), v);
            }
            snap.otherMap = readOtherMap();
        } catch (IllegalAccessException e) {
            PureVanilla.LOGGER.warn("[PureVanilla] Could not read Sodium Extra settings", e);
        }
        return snap;
    }

    @Override
    public boolean applyVanilla(Snapshot performance) throws Exception {
        if (!available) return false;
        boolean terrain = false;
        for (Setting s : KNOWN) {
            if (!categoryEnabled(s.category())) continue;
            Boolean current = readBool(s);
            if (current != null && current != s.vanillaValue()) {
                writeBool(s, s.vanillaValue());
                terrain |= s.affectsTerrain();
            }
        }
        if (config.sodiumExtra.particles) {
            Map<Object, Object> current = readOtherMap();
            if (current != null && !current.isEmpty()) {
                // A fresh empty map: the user's original map object is kept untouched in the snapshot.
                writeOtherMap(newMapLike(current));
            }
        }
        return terrain;
    }

    @Override
    public boolean restore(Snapshot performance) throws Exception {
        if (!available || !(performance instanceof Values snap)) return false;
        boolean terrain = false;
        for (Setting s : KNOWN) {
            Boolean wanted = snap.booleans.get(s.path());
            if (wanted == null) continue;
            Boolean current = readBool(s);
            if (current != null && !current.equals(wanted)) {
                writeBool(s, wanted);
                terrain |= s.affectsTerrain();
            }
        }
        if (otherMapField != null) {
            if (snap.otherMap != null) {
                writeOtherMap(snap.otherMap);
            } else if (snap.otherMapSerialized != null) {
                Map<Object, Object> rebuilt = newMapLike(readOtherMap());
                snap.otherMapSerialized.forEach((key, value) -> {
                    Identifier id = Identifier.tryParse(key);
                    if (id != null) rebuilt.put(id, value);
                });
                writeOtherMap(rebuilt);
            }
        }
        return terrain;
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public JsonObject toJson(Snapshot snapshot) {
        JsonObject json = new JsonObject();
        if (!(snapshot instanceof Values snap)) return json;
        JsonObject bools = new JsonObject();
        snap.booleans.forEach(bools::addProperty);
        json.add("settings", bools);
        Map<?, ?> other = snap.otherMap;
        if (other != null) {
            JsonObject map = new JsonObject();
            other.forEach((k, v) -> {
                if (k != null && v instanceof Boolean b) map.addProperty(k.toString(), b);
            });
            json.add("particles", map);
        } else if (snap.otherMapSerialized != null) {
            JsonObject map = new JsonObject();
            snap.otherMapSerialized.forEach(map::addProperty);
            json.add("particles", map);
        }
        return json;
    }

    @Override
    public Snapshot fromJson(JsonObject json) {
        if (json == null) return null;
        Values snap = new Values();
        JsonElement bools = json.get("settings");
        if (bools != null && bools.isJsonObject()) {
            bools.getAsJsonObject().entrySet().forEach(e -> {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isBoolean()) {
                    snap.booleans.put(e.getKey(), e.getValue().getAsBoolean());
                }
            });
        }
        JsonElement particles = json.get("particles");
        if (particles != null && particles.isJsonObject()) {
            snap.otherMapSerialized = new LinkedHashMap<>();
            particles.getAsJsonObject().entrySet().forEach(e -> {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isBoolean()) {
                    snap.otherMapSerialized.put(e.getKey(), e.getValue().getAsBoolean());
                }
            });
        }
        return snap;
    }

    @Override
    public void flushToDisk() {
        if (!available) return;
        try {
            options.getClass().getMethod("writeChanges").invoke(options);
        } catch (Throwable t) {
            PureVanilla.LOGGER.warn("[PureVanilla] Could not save Sodium Extra settings", t);
        }
    }
}
