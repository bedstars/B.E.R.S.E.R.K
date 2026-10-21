package dev.berserk;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Current-screen access that works on every supported Minecraft version.
 *
 *   26.1.x : Minecraft#screen (field) and Minecraft#setScreen(Screen)
 *   26.2+  : both moved onto the new Gui class: Minecraft#gui.screen() / Minecraft#gui.setScreen(Screen)
 *
 * It is resolved once through reflection, so the same source compiles and runs on both.
 */
public final class Scr {
    private Scr() {}

    private static boolean looked, warned;
    private static Field screenField;   // Minecraft#screen            (26.1.x)
    private static Method setOld;       // Minecraft#setScreen(Screen) (26.1.x)
    private static Method setAndShow;   // Minecraft#setScreenAndShow  (fallback)
    private static Field guiField;      // Minecraft#gui               (26.2+)
    private static Method guiScreen;    // Gui#screen()
    private static Method guiSet;       // Gui#setScreen(Screen)

    private static void look() {
        if (looked) return;
        looked = true;
        screenField = field(Minecraft.class, "screen");
        setOld = oneArg(Minecraft.class, "setScreen");
        setAndShow = oneArg(Minecraft.class, "setScreenAndShow");
        guiField = field(Minecraft.class, "gui");
        if (guiField != null) {
            Class<?> gc = guiField.getType();
            guiScreen = noArg(gc, "screen");
            guiSet = oneArg(gc, "setScreen");
        }
    }

    private static boolean useGui() {
        return guiField != null && guiScreen != null && guiSet != null;
    }

    public static Screen get(Minecraft mc) {
        try {
            look();
            if (useGui()) {
                Object gui = guiField.get(mc);
                return gui == null ? null : (Screen) guiScreen.invoke(gui);
            }
            if (screenField != null) return (Screen) screenField.get(mc);
        } catch (Throwable t) {
            warn(t);
        }
        return null;
    }

    public static void set(Minecraft mc, Screen s) {
        try {
            look();
            if (useGui()) {
                Object gui = guiField.get(mc);
                if (gui != null) guiSet.invoke(gui, s);
            } else if (setOld != null) {
                setOld.invoke(mc, s);
            } else if (setAndShow != null) {
                setAndShow.invoke(mc, s);
            }
        } catch (Throwable t) {
            warn(t);
        }
    }

    private static void warn(Throwable t) {
        if (warned) return;
        warned = true;
        Berserk.LOG.warn("Could not access the current screen", t);
    }

    private static Field field(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method oneArg(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(Screen.class)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private static Method noArg(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0 && Screen.class.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }
}
