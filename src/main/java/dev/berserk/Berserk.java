package dev.berserk;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** B.E.R.S.E.R.K - client-side macro recorder / player. */
public final class Berserk implements ClientModInitializer {
    public static final String ID = "berserk";
    public static final Logger LOG = LoggerFactory.getLogger("B.E.R.S.E.R.K");

    public static KeyMapping K_MENU, K_REC, K_PLAY, K_FREE;

    @Override
    public void onInitializeClient() {
        Config.load();

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
        K_MENU = reg("menu", GLFW.GLFW_KEY_RIGHT_SHIFT, cat);
        K_REC = reg("record", GLFW.GLFW_KEY_F7, cat);
        K_PLAY = reg("play", GLFW.GLFW_KEY_F8, cat);
        K_FREE = reg("free", GLFW.GLFW_KEY_LEFT_ALT, cat);

        ClientTickEvents.START_CLIENT_TICK.register(Engine::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(Engine::onEndTick);

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ID, "overlay"), (graphics, tickCounter) -> Hud.draw(graphics));

        // registered AFTER our own tick hook on purpose: the Path editor's GLFW hook is installed last,
        // so it sees raw keys first (its F8 Ortho never collides with the macro Play key).
        dev.berserk.path.PathMod.init();

        LOG.info("B.E.R.S.E.R.K armed (macros + path).");
    }

    private static KeyMapping reg(String name, int key, KeyMapping.Category cat) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + ID + "." + name, InputConstants.Type.KEYSYM, key, cat));
    }
}
