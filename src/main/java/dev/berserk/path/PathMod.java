package dev.berserk.path;

import dev.berserk.Scr;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** B.E.R.S.E.R.K Path - draw a route on a grid, the player walks it. Client side only, keys only. */
public final class PathMod {
    public static final String ID = "berserk_path";
    public static final Logger LOG = LoggerFactory.getLogger("B.E.R.S.E.R.K Path");

    public static KeyMapping K_EDIT, K_RUN;

    /** Called once by {@link dev.berserk.Berserk} - the Path module is part of the same mod now. */
    public static void init() {
        Cfg.load();
        Route.load(Cfg.I.slot);

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
        K_EDIT = reg("edit", GLFW.GLFW_KEY_K, cat);
        K_RUN = reg("run", GLFW.GLFW_KEY_J, cat);

        ClientTickEvents.START_CLIENT_TICK.register(PathMod::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(PathMod::onEndTick);

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ID, "overlay"), (graphics, tickCounter) -> Hud.draw(graphics));

        LOG.info("B.E.R.S.E.R.K Path ready.");
    }

    private static KeyMapping reg(String name, int key, KeyMapping.Category cat) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key." + ID + "." + name, InputConstants.Type.KEYSYM, key, cat));
    }

    private static void onStartTick(Minecraft mc) {
        PathInput.ensure();
        Walker.tick(mc);
    }

    private static void onEndTick(Minecraft mc) {
        while (K_EDIT.consumeClick()) {
            if (mc.player != null && mc.level != null && Scr.get(mc) == null) Scr.set(mc, new PathScreen());
        }
        while (K_RUN.consumeClick()) {
            if (mc.player != null && mc.level != null) Walker.toggle(mc);
        }
    }
}
