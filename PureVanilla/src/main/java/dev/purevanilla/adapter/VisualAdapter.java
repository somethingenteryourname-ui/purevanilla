package dev.purevanilla.adapter;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * One source of visual settings (vanilla options, Sodium Extra, ...).
 *
 * Contract:
 *  - {@link #capture()} records the user's CURRENT values (the Performance profile).
 *  - {@link #applyVanilla(Snapshot)} switches to vanilla-style visuals.
 *  - {@link #restore(Snapshot)} puts back exactly what capture() recorded.
 *  - Everything runs on the render thread, in memory only. Nothing here touches disk.
 */
public interface VisualAdapter {

    /** Stable id used in the saved state file. */
    String id();

    /** Human-readable name for logs and /purevanilla status. */
    String displayName();

    /** False if the backing mod is missing or its internals could not be found. */
    boolean isAvailable();

    /** Settings this adapter controls, for status output and documentation. */
    List<String> describeSettings();

    Snapshot capture();

    /** @return true if a terrain (chunk mesh) refresh is needed for the change to show. */
    boolean applyVanilla(Snapshot performance) throws Exception;

    /** @return true if a terrain (chunk mesh) refresh is needed for the change to show. */
    boolean restore(Snapshot performance) throws Exception;

    /** Persist the snapshot so it survives a crash. */
    JsonObject toJson(Snapshot snapshot);

    /** Rebuild a snapshot from the crash-recovery file. Null if unreadable. */
    Snapshot fromJson(JsonObject json);

    /** Write this adapter's current in-memory values to its own config file (clean shutdown / recovery only). */
    void flushToDisk();

    /** Marker for an adapter-specific captured state. */
    interface Snapshot {
    }
}
