package dev.purevanilla.adapter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.purevanilla.PureVanillaConfig;
import dev.purevanilla.mixin.SimpleOptionAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.particle.ParticlesMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Minecraft's own video options that PureVanilla switches.
 *
 * Deliberately NOT touched (preserved exactly): resolution, GUI scale, render distance,
 * simulation distance, FPS limit, graphics preset, Improved Transparency (shader reload),
 * Cutout Leaves (needs a renderer reload), weather radius, chunk fade, cloud distance,
 * biome blend, FOV and every other non-visual setting.
 */
public final class VanillaOptionsAdapter implements VisualAdapter {

    private record Entry(String key, String label, Function<GameOptions, SimpleOption<?>> getter,
                         Object vanillaValue, boolean affectsTerrain) {
    }

    private final PureVanillaConfig config;
    private final List<Entry> entries = new ArrayList<>();

    public VanillaOptionsAdapter(PureVanillaConfig config) {
        this.config = config;
        entries.add(new Entry("clouds", "Clouds (Fancy)", GameOptions::getCloudRenderMode, CloudRenderMode.FANCY, false));
        entries.add(new Entry("smoothLighting", "Smooth Lighting (On)", GameOptions::getAo, Boolean.TRUE, true));
        entries.add(new Entry("entityShadows", "Entity Shadows (On)", GameOptions::getEntityShadows, Boolean.TRUE, false));
        entries.add(new Entry("vignette", "Vignette (On)", GameOptions::getVignette, Boolean.TRUE, false));
        entries.add(new Entry("particles", "Particles (All)", GameOptions::getParticles, ParticlesMode.ALL, false));
    }

    @Override public String id() { return "minecraft"; }
    @Override public String displayName() { return "Minecraft video options"; }
    @Override public boolean isAvailable() { return MinecraftClient.getInstance().options != null; }

    @Override
    public List<String> describeSettings() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            if (isEnabled(e)) out.add(e.label());
        }
        return out;
    }

    private boolean isEnabled(Entry e) {
        return !e.key().equals("smoothLighting") || config.switchSmoothLighting;
    }

    private static GameOptions options() {
        return MinecraftClient.getInstance().options;
    }

    private static Object getRaw(SimpleOption<?> option) {
        return ((SimpleOptionAccessor) (Object) option).purevanilla$getRawValue();
    }

    private static void setRaw(SimpleOption<?> option, Object value) {
        ((SimpleOptionAccessor) (Object) option).purevanilla$setRawValue(value);
    }

    // ------------------------------------------------------------------ snapshot

    public static final class Values implements Snapshot {
        final Map<String, Object> values = new LinkedHashMap<>();
    }

    @Override
    public Snapshot capture() {
        Values snap = new Values();
        GameOptions opts = options();
        for (Entry e : entries) {
            snap.values.put(e.key(), getRaw(e.getter().apply(opts)));
        }
        return snap;
    }

    @Override
    public boolean applyVanilla(Snapshot performance) {
        GameOptions opts = options();
        boolean terrain = false;
        for (Entry e : entries) {
            if (!isEnabled(e)) continue;
            SimpleOption<?> option = e.getter().apply(opts);
            if (!Objects.equals(getRaw(option), e.vanillaValue())) {
                setRaw(option, e.vanillaValue());
                terrain |= e.affectsTerrain();
            }
        }
        return terrain;
    }

    @Override
    public boolean restore(Snapshot performance) {
        if (!(performance instanceof Values snap)) return false;
        GameOptions opts = options();
        boolean terrain = false;
        for (Entry e : entries) {
            if (!snap.values.containsKey(e.key())) continue;
            Object wanted = snap.values.get(e.key());
            if (wanted == null) continue;
            SimpleOption<?> option = e.getter().apply(opts);
            if (!Objects.equals(getRaw(option), wanted)) {
                setRaw(option, wanted);
                terrain |= e.affectsTerrain();
            }
        }
        return terrain;
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public JsonObject toJson(Snapshot snapshot) {
        JsonObject json = new JsonObject();
        if (!(snapshot instanceof Values snap)) return json;
        snap.values.forEach((key, value) -> {
            if (value instanceof Boolean b) json.addProperty(key, b);
            else if (value instanceof Enum<?> en) json.addProperty(key, en.name());
            else if (value instanceof Number n) json.addProperty(key, n);
        });
        return json;
    }

    @Override
    public Snapshot fromJson(JsonObject json) {
        if (json == null) return null;
        Values snap = new Values();
        GameOptions opts = options();
        for (Entry e : entries) {
            JsonElement el = json.get(e.key());
            if (el == null || !el.isJsonPrimitive()) continue;
            Object current = getRaw(e.getter().apply(opts));
            Object parsed = parseLike(current, el.getAsJsonPrimitive());
            if (parsed != null) snap.values.put(e.key(), parsed);
        }
        return snap;
    }

    private static Object parseLike(Object template, JsonPrimitive p) {
        if (template instanceof Boolean && p.isBoolean()) return p.getAsBoolean();
        if (template instanceof Enum<?> en && p.isString()) {
            for (Object c : en.getDeclaringClass().getEnumConstants()) {
                if (((Enum<?>) c).name().equals(p.getAsString())) return c;
            }
            return null;
        }
        if (template instanceof Integer && p.isNumber()) return p.getAsInt();
        if (template instanceof Double && p.isNumber()) return p.getAsDouble();
        return null;
    }

    @Override
    public void flushToDisk() {
        options().write();
    }
}
