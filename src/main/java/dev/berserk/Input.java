package dev.berserk;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * Hooks GLFW directly (wrapping Minecraft's own callbacks).
 *  - records real input while recording
 *  - swallows real input while a macro plays / the mouse is "free"
 *  - replays recorded events by invoking Minecraft's ORIGINAL callbacks, so every screen,
 *    chat box and inventory behaves exactly as if you had done it yourself.
 * Nothing here touches the network.
 */
public final class Input {
    static long window;
    static boolean installed;
    public static boolean freeMouse;

    // strong refs so the native callbacks are never garbage collected
    private static GLFWKeyCallback myKey;
    private static GLFWCharModsCallback myCharMods;
    private static GLFWCharCallback myChar;
    private static GLFWMouseButtonCallback myMouse;
    private static GLFWScrollCallback mySroll;
    private static GLFWCursorPosCallback myCursor;

    private static GLFWKeyCallbackI prevKey;
    private static GLFWCharModsCallbackI prevCharMods;
    private static GLFWCharCallbackI prevChar;
    private static GLFWMouseButtonCallbackI prevMouse;
    private static GLFWScrollCallbackI prevScroll;
    private static GLFWCursorPosCallbackI prevCursor;

    private static double lastX, lastY;
    private static final Set<Integer> consumedKeys = new HashSet<>();
    private static final Set<Integer> consumedButtons = new HashSet<>();
    private static Field keyField;

    private Input() {}

    // ------------------------------------------------------------------ install

    static void ensure() {
        if (installed) return;
        long w = GLFW.glfwGetCurrentContext();
        if (w == 0L) return;
        window = w;

        double[] cx = new double[1], cy = new double[1];
        GLFW.glfwGetCursorPos(w, cx, cy);
        lastX = cx[0];
        lastY = cy[0];

        myKey = GLFWKeyCallback.create(Input::onKey);
        GLFWKeyCallback pk = GLFW.glfwSetKeyCallback(w, myKey);
        prevKey = pk != null ? pk : (a, b, c, d, e) -> {};

        myCharMods = GLFWCharModsCallback.create(Input::onCharMods);
        GLFWCharModsCallback pcm = GLFW.glfwSetCharModsCallback(w, myCharMods);
        if (pcm != null) {
            prevCharMods = pcm;
        } else {
            // this Minecraft version uses the plain char callback instead
            GLFW.glfwSetCharModsCallback(w, null);
            myChar = GLFWCharCallback.create(Input::onChar);
            GLFWCharCallback pc = GLFW.glfwSetCharCallback(w, myChar);
            prevChar = pc != null ? pc : (a, b) -> {};
        }

        myMouse = GLFWMouseButtonCallback.create(Input::onMouse);
        GLFWMouseButtonCallback pm = GLFW.glfwSetMouseButtonCallback(w, myMouse);
        prevMouse = pm != null ? pm : (a, b, c, d) -> {};

        mySroll = GLFWScrollCallback.create(Input::onScroll);
        GLFWScrollCallback ps = GLFW.glfwSetScrollCallback(w, mySroll);
        prevScroll = ps != null ? ps : (a, b, c) -> {};

        myCursor = GLFWCursorPosCallback.create(Input::onCursor);
        GLFWCursorPosCallback pc2 = GLFW.glfwSetCursorPosCallback(w, myCursor);
        prevCursor = pc2 != null ? pc2 : (a, b, c) -> {};

        installed = true;
        Berserk.LOG.info("Input hooks installed.");
    }

    // ------------------------------------------------------------------ helpers

    static boolean playing() {
        return Engine.mode == Engine.Mode.PLAYING;
    }

    static boolean blockKeys() {
        return playing() && Config.I.mBlockInput;
    }

    static boolean blockMouse() {
        return blockKeys() || freeMouse;
    }

    static double[] cursor() {
        double[] x = new double[1], y = new double[1];
        GLFW.glfwGetCursorPos(window, x, y);
        return new double[]{x[0], y[0]};
    }

    static int[] winSize() {
        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetWindowSize(window, w, h);
        return new int[]{Math.max(1, w[0]), Math.max(1, h[0])};
    }

    /** cursor in GUI-scaled coordinates */
    static double[] gui() {
        Minecraft mc = Minecraft.getInstance();
        double[] c = cursor();
        int[] ws = winSize();
        return new double[]{
                c[0] * mc.getWindow().getGuiScaledWidth() / ws[0],
                c[1] * mc.getWindow().getGuiScaledHeight() / ws[1]};
    }

    /** Key currently bound to a mapping. Returns -2 if it cannot be read. */
    private static int keyOf(KeyMapping m) {
        try {
            if (keyField == null) {
                for (Field f : KeyMapping.class.getDeclaredFields()) {
                    if (f.getType() == InputConstants.Key.class
                            && !Modifier.isStatic(f.getModifiers())
                            && !Modifier.isFinal(f.getModifiers())) {
                        f.setAccessible(true);
                        keyField = f;
                        break;
                    }
                }
            }
            if (keyField == null) return -2;
            InputConstants.Key k = (InputConstants.Key) keyField.get(m);
            if (k.getType() != InputConstants.Type.KEYSYM) return -1;
            return k.getValue();
        } catch (Throwable t) {
            return -2;
        }
    }

    /** True when {@code key} is the free-mouse key (Left Alt by default). Used by the path editor's hook. */
    public static boolean isFreeKey(int key) {
        return match(Berserk.K_FREE, key, GLFW.GLFW_KEY_LEFT_ALT);
    }

    private static boolean match(KeyMapping m, int key, int def) {
        if (m == null) return false;
        int c = keyOf(m);
        if (c == -2) c = def;
        return c > 0 && c == key;
    }

    // ------------------------------------------------------------------ synthetic input

    static void synKey(int key, int sc, int action, int mods) {
        if (installed) prevKey.invoke(window, key, sc, action, mods);
    }

    static void tapKey(int key) {
        int sc = GLFW.glfwGetKeyScancode(key);
        synKey(key, sc, GLFW.GLFW_PRESS, 0);
        synKey(key, sc, GLFW.GLFW_RELEASE, 0);
    }

    static void synChar(int cp) {
        if (!installed) return;
        if (prevCharMods != null) prevCharMods.invoke(window, cp, 0);
        else if (prevChar != null) prevChar.invoke(window, cp);
    }

    static void synMouse(int button, int action, int mods) {
        if (installed) prevMouse.invoke(window, button, action, mods);
    }

    static void synScroll(double dx, double dy) {
        if (installed) prevScroll.invoke(window, dx, dy);
    }

    static void synCursorFrac(double fx, double fy) {
        if (!installed) return;
        int[] ws = winSize();
        double x = fx * ws[0], y = fy * ws[1];
        prevCursor.invoke(window, x, y);
        lastX = x;
        lastY = y;
    }

    /**
     * After real cursor events were swallowed, put the (virtual) cursor back to where
     * Minecraft last saw it, so the next real movement is not turned into a giant camera jump.
     */
    static void resync() {
        if (!installed) return;
        Minecraft mc = Minecraft.getInstance();
        if (Scr.get(mc) == null) GLFW.glfwSetCursorPos(window, lastX, lastY);
    }

    // ------------------------------------------------------------------ free mouse

    static void toggleFree(Minecraft mc) {
        freeMouse = !freeMouse;
        if (freeMouse) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            Msg.overlay(mc, Msg.S + "bMouse freed " + Msg.S + "7- real mouse input is ignored by the game");
        } else {
            if (Scr.get(mc) == null && mc.mouseHandler.isMouseGrabbed()) {
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
                resync();
            }
            Msg.overlay(mc, Msg.S + "7Mouse captured again");
        }
    }

    static void tickFree() {
        if (freeMouse && GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_NORMAL) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
    }

    // ------------------------------------------------------------------ callbacks

    private static void onKey(long w, int key, int sc, int action, int mods) {
        try {
            if (handleKey(key, sc, action, mods)) return;
        } catch (Throwable t) {
            Berserk.LOG.warn("key hook", t);
        }
        prevKey.invoke(w, key, sc, action, mods);
    }

    private static boolean handleKey(int key, int sc, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.player != null && mc.level != null;
        Screen s = Scr.get(mc);
        BerserkScreen bs = s instanceof BerserkScreen b ? b : null;
        boolean capturing = bs != null && bs.capturing();

        if (action == GLFW.GLFW_RELEASE && consumedKeys.remove(key)) return true;
        if (action == GLFW.GLFW_REPEAT && consumedKeys.contains(key)) return true;

        // hotkeys. Printable keys never fire while typing into some other screen (chat, anvil...)
        if (action == GLFW.GLFW_PRESS && !capturing && inWorld && (s == null || bs != null || key >= 256)) {
            if (match(Berserk.K_MENU, key, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
                consumedKeys.add(key);
                Engine.toggleMenu(mc);
                return true;
            }
            if (match(Berserk.K_REC, key, GLFW.GLFW_KEY_F7)) {
                consumedKeys.add(key);
                Engine.toggleRecord(mc);
                return true;
            }
            if (match(Berserk.K_PLAY, key, GLFW.GLFW_KEY_F8)) {
                consumedKeys.add(key);
                Engine.togglePlay(mc);
                return true;
            }
            if (match(Berserk.K_FREE, key, GLFW.GLFW_KEY_LEFT_ALT)) {
                consumedKeys.add(key);
                toggleFree(mc);
                return true;
            }
        }

        if (bs != null) {
            if (action != GLFW.GLFW_RELEASE) {
                bs.onKey(key, sc, action, mods);
                return true;
            }
            return false; // let releases through so nothing sticks
        }

        if (blockKeys()) return true;

        if (Engine.mode == Engine.Mode.RECORDING && inWorld) Engine.recKey(key, sc, action, mods);
        return false;
    }

    private static void onCharMods(long w, int cp, int mods) {
        try {
            if (handleChar(cp)) return;
        } catch (Throwable t) {
            Berserk.LOG.warn("char hook", t);
        }
        if (prevCharMods != null) prevCharMods.invoke(w, cp, mods);
    }

    private static void onChar(long w, int cp) {
        try {
            if (handleChar(cp)) return;
        } catch (Throwable t) {
            Berserk.LOG.warn("char hook", t);
        }
        if (prevChar != null) prevChar.invoke(w, cp);
    }

    private static boolean handleChar(int cp) {
        Minecraft mc = Minecraft.getInstance();
        if (Scr.get(mc) instanceof BerserkScreen bs) {
            bs.onChar(cp);
            return true;
        }
        if (blockKeys()) return true;
        if (Engine.mode == Engine.Mode.RECORDING && mc.player != null) Engine.recChar(cp);
        return false;
    }

    private static void onMouse(long w, int button, int action, int mods) {
        try {
            if (handleMouse(button, action, mods)) return;
        } catch (Throwable t) {
            Berserk.LOG.warn("mouse hook", t);
        }
        prevMouse.invoke(w, button, action, mods);
    }

    private static boolean handleMouse(int button, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        if (action == GLFW.GLFW_RELEASE && consumedButtons.remove(button)) return true;

        double[] g = gui();
        if (action == GLFW.GLFW_PRESS && button == 0 && Scr.get(mc) instanceof ChatScreen
                && Config.I.mChatButtons && mc.player != null) {
            int hit = Hud.hit(g[0], g[1]);
            if (hit >= 0) {
                consumedButtons.add(button);
                Hud.act(hit, mc);
                return true;
            }
        }

        if (Scr.get(mc) instanceof BerserkScreen bs) {
            if (action == GLFW.GLFW_PRESS) bs.onClick(g[0], g[1], button);
            return true;
        }

        if (blockMouse()) return true;

        if (Engine.mode == Engine.Mode.RECORDING && mc.player != null) {
            double[] c = cursor();
            int[] ws = winSize();
            Engine.recMouse(button, action, mods, c[0] / ws[0], c[1] / ws[1]);
        }
        return false;
    }

    private static void onScroll(long w, double dx, double dy) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (Scr.get(mc) instanceof BerserkScreen bs) {
                bs.onScroll(dy);
                return;
            }
            if (blockMouse()) return;
            if (Engine.mode == Engine.Mode.RECORDING && mc.player != null) Engine.recScroll(dx, dy);
        } catch (Throwable t) {
            Berserk.LOG.warn("scroll hook", t);
        }
        prevScroll.invoke(w, dx, dy);
    }

    private static void onCursor(long w, double x, double y) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!(Scr.get(mc) instanceof BerserkScreen) && blockMouse()) return;
        } catch (Throwable t) {
            Berserk.LOG.warn("cursor hook", t);
        }
        lastX = x;
        lastY = y;
        prevCursor.invoke(w, x, y);
    }
}
