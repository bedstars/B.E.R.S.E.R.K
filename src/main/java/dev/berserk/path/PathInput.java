package dev.berserk.path;

import dev.berserk.Scr;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.*;

/**
 * Hooks GLFW (wrapping Minecraft's own callbacks) so the editor screen gets raw keys, characters,
 * mouse buttons and scrolling without depending on Screen input method signatures, which change
 * between Minecraft versions. Everything is passed straight through when the editor is closed.
 */
public final class PathInput {
    static long window;
    static boolean installed;

    // strong refs so the native callbacks are never garbage collected
    private static GLFWKeyCallback myKey;
    private static GLFWCharModsCallback myCharMods;
    private static GLFWCharCallback myChar;
    private static GLFWMouseButtonCallback myMouse;
    private static GLFWScrollCallback myScroll;

    private static GLFWKeyCallbackI prevKey;
    private static GLFWCharModsCallbackI prevCharMods;
    private static GLFWCharCallbackI prevChar;
    private static GLFWMouseButtonCallbackI prevMouse;
    private static GLFWScrollCallbackI prevScroll;

    private PathInput() {}

    static void ensure() {
        if (installed) return;
        long w = GLFW.glfwGetCurrentContext();
        if (w == 0L) return;
        window = w;

        myKey = GLFWKeyCallback.create(PathInput::onKey);
        GLFWKeyCallback pk = GLFW.glfwSetKeyCallback(w, myKey);
        prevKey = pk != null ? pk : (a, b, c, d, e) -> {};

        myCharMods = GLFWCharModsCallback.create(PathInput::onCharMods);
        GLFWCharModsCallback pcm = GLFW.glfwSetCharModsCallback(w, myCharMods);
        if (pcm != null) {
            prevCharMods = pcm;
        } else {
            // this Minecraft version uses the plain char callback instead
            GLFW.glfwSetCharModsCallback(w, null);
            myChar = GLFWCharCallback.create(PathInput::onChar);
            GLFWCharCallback pc = GLFW.glfwSetCharCallback(w, myChar);
            prevChar = pc != null ? pc : (a, b) -> {};
        }

        myMouse = GLFWMouseButtonCallback.create(PathInput::onMouse);
        GLFWMouseButtonCallback pm = GLFW.glfwSetMouseButtonCallback(w, myMouse);
        prevMouse = pm != null ? pm : (a, b, c, d) -> {};

        myScroll = GLFWScrollCallback.create(PathInput::onScroll);
        GLFWScrollCallback ps = GLFW.glfwSetScrollCallback(w, myScroll);
        prevScroll = ps != null ? ps : (a, b, c) -> {};

        installed = true;
        PathMod.LOG.info("Input hooks installed.");
    }

    /** Cursor in GUI-scaled coordinates. */
    static double[] gui() {
        Minecraft mc = Minecraft.getInstance();
        double[] x = new double[1], y = new double[1];
        GLFW.glfwGetCursorPos(window, x, y);
        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetWindowSize(window, w, h);
        int ww = Math.max(1, w[0]), wh = Math.max(1, h[0]);
        return new double[]{
                x[0] * mc.getWindow().getGuiScaledWidth() / ww,
                y[0] * mc.getWindow().getGuiScaledHeight() / wh};
    }

    static String clipboard() {
        try {
            String s = GLFW.glfwGetClipboardString(window);
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static PathScreen screen() {
        return Scr.get(Minecraft.getInstance()) instanceof PathScreen ps ? ps : null;
    }

    // ------------------------------------------------------------------ callbacks

    private static void onKey(long w, int key, int sc, int action, int mods) {
        try {
            PathScreen ps = screen();
            if (ps != null && action != GLFW.GLFW_RELEASE) {
                ps.onKey(key, action, mods);
                return;
            }
        } catch (Throwable t) {
            PathMod.LOG.warn("key hook", t);
        }
        prevKey.invoke(w, key, sc, action, mods);
    }

    private static void onCharMods(long w, int cp, int mods) {
        try {
            PathScreen ps = screen();
            if (ps != null) {
                ps.onChar(cp);
                return;
            }
        } catch (Throwable t) {
            PathMod.LOG.warn("char hook", t);
        }
        if (prevCharMods != null) prevCharMods.invoke(w, cp, mods);
    }

    private static void onChar(long w, int cp) {
        try {
            PathScreen ps = screen();
            if (ps != null) {
                ps.onChar(cp);
                return;
            }
        } catch (Throwable t) {
            PathMod.LOG.warn("char hook", t);
        }
        if (prevChar != null) prevChar.invoke(w, cp);
    }

    private static void onMouse(long w, int button, int action, int mods) {
        try {
            PathScreen ps = screen();
            if (ps != null) {
                ps.onMouse(button, action, mods);
                return;
            }
        } catch (Throwable t) {
            PathMod.LOG.warn("mouse hook", t);
        }
        prevMouse.invoke(w, button, action, mods);
    }

    private static void onScroll(long w, double dx, double dy) {
        try {
            PathScreen ps = screen();
            if (ps != null) {
                ps.onScroll(dy);
                return;
            }
        } catch (Throwable t) {
            PathMod.LOG.warn("scroll hook", t);
        }
        prevScroll.invoke(w, dx, dy);
    }
}
