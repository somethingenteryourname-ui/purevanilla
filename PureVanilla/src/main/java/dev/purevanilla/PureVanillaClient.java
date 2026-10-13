package dev.purevanilla;

import com.mojang.brigadier.CommandDispatcher;
import dev.purevanilla.adapter.VisualAdapter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class PureVanillaClient implements ClientModInitializer {

    private static final ProfileManager PROFILES = new ProfileManager();

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of(PureVanilla.MOD_ID, "main"));

    /** Default O: vanilla already uses P for Social Interactions. Rebind freely in Controls. */
    private static KeyBinding toggleKey;

    /** True while the key is physically held, so holding it (or key repeat) never re-toggles. */
    private boolean heldLastTick;

    public static ProfileManager profiles() {
        return PROFILES;
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.purevanilla.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY));

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> PROFILES.onClientStarted());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PROFILES.onClientStopping());
        ClientTickEvents.START_CLIENT_TICK.register(this::onTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
    }

    private void onTick(MinecraftClient client) {
        int presses = 0;
        while (toggleKey.wasPressed()) presses++;
        boolean held = toggleKey.isPressed();
        // One toggle per physical press: extra queued presses and held-key repeats are ignored.
        if (presses > 0 && !heldLastTick && PROFILES.isReady()) {
            PROFILES.toggle();
        }
        heldLastTick = held;
        PROFILES.tick(client);
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("purevanilla")
                .executes(ctx -> {
                    if (!PROFILES.isReady()) return 0;
                    PROFILES.toggle();
                    return 1;
                })
                .then(literal("status").executes(ctx -> {
                    sendStatus(ctx.getSource());
                    return 1;
                }))
                .then(literal("reload").executes(ctx -> {
                    if (!PROFILES.isReady()) return 0;
                    PROFILES.reloadConfig();
                    ctx.getSource().sendFeedback(Text.literal("PureVanilla config reloaded.").formatted(Formatting.GRAY));
                    return 1;
                })));
    }

    private static void sendStatus(FabricClientCommandSource source) {
        if (!PROFILES.isReady()) {
            source.sendFeedback(Text.literal("PureVanilla is still starting."));
            return;
        }
        boolean on = PROFILES.mode() == ProfileManager.Mode.PUREVANILLA;
        source.sendFeedback(Text.literal("PureVanilla: ").formatted(Formatting.GRAY)
                .append(Text.literal(on ? "PureVanilla Mode" : "Performance Mode")
                        .formatted(on ? Formatting.GREEN : Formatting.AQUA))
                .append(Text.literal("  (key: ").formatted(Formatting.GRAY))
                .append(toggleKey.getBoundKeyLocalizedText().copy().formatted(Formatting.WHITE))
                .append(Text.literal(")").formatted(Formatting.GRAY)));
        for (VisualAdapter a : PROFILES.adapters()) {
            if (!a.isAvailable()) {
                source.sendFeedback(Text.literal(" - " + a.displayName() + ": not installed").formatted(Formatting.DARK_GRAY));
                continue;
            }
            source.sendFeedback(Text.literal(" - " + a.displayName() + ": " + String.join(", ", a.describeSettings()))
                    .formatted(Formatting.GRAY));
        }
    }
}
