package dev.berserk.path;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Message helpers that go through reflection with fallbacks, so a renamed vanilla method
 * on a newer Minecraft version degrades gracefully instead of crashing.
 */
final class Msg {
    private Msg() {}

    static final String S = "\u00a7";

    static void overlay(Minecraft mc, String text) {
        Component c = Component.literal(text);
        Object gui = field(mc, "gui");
        if (gui != null && call(gui, "setOverlayMessage", c, Boolean.FALSE)) return;
        if (mc.player != null) call(mc.player, "displayClientMessage", c, Boolean.TRUE);
    }

    static void chat(Minecraft mc, String text) {
        if (mc.player == null) return;
        Component c = Component.literal(text);
        if (call(mc.player, "sendSystemMessage", c)) return;
        call(mc.player, "displayClientMessage", c, Boolean.FALSE);
    }

    /** "/cmd" is sent as a command, anything else as a chat line. */
    static void send(Minecraft mc, String text) {
        if (mc.player == null || text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;
        Object conn = field(mc.player, "connection");
        if (conn == null) return;
        if (text.startsWith("/")) call(conn, "sendCommand", text.substring(1));
        else call(conn, "sendChat", text);
    }

    static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static boolean call(Object target, String name, Object... args) {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            try {
                m.invoke(target, args);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
