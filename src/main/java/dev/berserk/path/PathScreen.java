package dev.berserk.path;

import dev.berserk.Scr;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The grid editor. A top-down view of the world (X to the right, Z down = south), with a grid that
 * covers 1000 x 1000 blocks. Click to place points, select a point to edit its exact coordinates
 * and the command it runs. All input arrives through {@link PathInput}, so this screen does not
 * depend on any Screen input method signatures. Drawing uses fill() / text() only.
 */
public final class PathScreen extends Screen {
    private enum Tool { DRAW, SELECT }

    private enum Field { NONE, X, Z, YAW, PITCH, CMD }

    private record Hit(int x, int y, int w, int h, Runnable r) {
        boolean in(double a, double b) {
            return a >= x && a < x + w && b >= y && b < y + h;
        }
    }

    private record TB(Hit hit, String label, String[] icon, int color) {}

    private static final int SB = 14;   // status bar height
    private static final int PW = 172;  // properties panel width
    private static final int BH = 14;   // toolbar button height

    private final List<Hit> hits = new ArrayList<>();
    private final List<TB> toolbar = new ArrayList<>();

    // view
    private double vx, vz, scale = 6;
    private int vpx0, vpy0, vpx1, vpy1, tbH = 22;
    private double cx, cy;
    private boolean needFit;

    // state
    private Tool tool;
    private boolean ortho, props;
    private int sel = -1, selSeg = -1;
    private int hoverPt = -1, hoverSeg = -1;
    private double mx, my;

    // text editing
    private Field editing = Field.NONE;
    private final StringBuilder buf = new StringBuilder();

    // dragging
    private int dragMode;         // 0 none, 1 pan, 2 move point
    private int dragButton = -1;
    private double lastGx, lastGy;
    private boolean moved;

    // misc
    private String flash = "";
    private long flashUntil;
    private long clearArmed;
    private int panelX, panelY, panelH;

    public PathScreen() {
        super(Component.literal("B.E.R.S.E.R.K Path"));
        initRoute();
    }

    public boolean isPauseScreen() {
        return false;
    }

    // ================================================================== setup

    private void initRoute() {
        Minecraft mc = Minecraft.getInstance();
        Route r = Route.cur;
        if (mc.player != null) {
            vx = mc.player.getX();
            vz = mc.player.getZ();
            if (r.n() == 0 || !r.hasOrigin) {
                r.originX = r.n() > 0 ? Math.floor(r.pts.get(0).x) + 0.5 : Math.floor(vx) + 0.5;
                r.originZ = r.n() > 0 ? Math.floor(r.pts.get(0).z) + 0.5 : Math.floor(vz) + 0.5;
                r.hasOrigin = true;
                Route.save();
            }
        }
        sel = -1;
        selSeg = -1;
        tool = r.n() == 0 ? Tool.DRAW : Tool.SELECT;
        needFit = r.n() > 0;
        scale = 6;
    }

    private Route.Pt selPt() {
        Route r = Route.cur;
        return sel >= 0 && sel < r.n() ? r.pts.get(sel) : null;
    }

    private void flash(String s) {
        flash = s;
        flashUntil = System.currentTimeMillis() + 2800;
    }

    // ================================================================== geometry

    private double sx(double wx) {
        return cx + (wx - vx) * scale;
    }

    private double sy(double wz) {
        return cy + (wz - vz) * scale;
    }

    private double wx(double px) {
        return vx + (px - cx) / scale;
    }

    private double wz(double py) {
        return vz + (py - cy) / scale;
    }

    private double minScale() {
        return Math.max(0.05, Math.min(vpx1 - vpx0, vpy1 - vpy0) / (2 * Route.HALF));
    }

    private void layout() {
        vpx0 = 0;
        vpy0 = tbH;
        vpx1 = width;
        vpy1 = height - SB;
        cx = (vpx0 + vpx1) / 2.0;
        cy = (vpy0 + vpy1) / 2.0;
        clampView();
        panelX = width - PW - 6;
        panelY = tbH + 6;
        panelH = selPt() != null ? 200 : 56;
    }

    private void clampView() {
        Route r = Route.cur;
        scale = Math.max(minScale(), Math.min(48, scale));
        vx = Math.max(r.originX - Route.HALF, Math.min(r.originX + Route.HALF, vx));
        vz = Math.max(r.originZ - Route.HALF, Math.min(r.originZ + Route.HALF, vz));
    }

    private boolean overUi(double gx, double gy) {
        if (gy < tbH || gy >= vpy1) return true;
        return props && gx >= panelX && gx < panelX + PW && gy >= panelY && gy < panelY + panelH;
    }

    private double snapV(double v) {
        return Cfg.I.snap ? Math.floor(v) + 0.5 : Math.round(v * 10.0) / 10.0;
    }

    /** Where a click would place a point right now (snap, ortho and grid limits applied). */
    private double[] cand() {
        Route r = Route.cur;
        double x = wx(mx), z = wz(my);
        if (ortho && tool == Tool.DRAW && r.n() > 0 && !r.closed) {
            Route.Pt l = r.pts.get(r.n() - 1);
            if (Math.abs(x - l.x) >= Math.abs(z - l.z)) {
                x = snapV(x);
                z = l.z;
            } else {
                z = snapV(z);
                x = l.x;
            }
        } else {
            x = snapV(x);
            z = snapV(z);
        }
        return new double[]{r.clampX(x), r.clampZ(z)};
    }

    private static double distSeg(double px, double py, double x0, double y0, double x1, double y1) {
        double dx = x1 - x0, dy = y1 - y0;
        double l2 = dx * dx + dy * dy;
        double t = l2 < 1e-9 ? 0 : ((px - x0) * dx + (py - y0) * dy) / l2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (x0 + t * dx), py - (y0 + t * dy));
    }

    private int ptAt(double gx, double gy, double rad) {
        Route r = Route.cur;
        int best = -1;
        double bd = rad;
        for (int i = 0; i < r.n(); i++) {
            Route.Pt p = r.pts.get(i);
            double d = Math.hypot(gx - sx(p.x), gy - sy(p.z));
            if (d <= bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private int segAt(double gx, double gy, double rad) {
        Route r = Route.cur;
        int best = -1;
        double bd = rad;
        for (int s = 0; s < r.segs(); s++) {
            Route.Pt a = r.a(s), b = r.b(s);
            double d = distSeg(gx, gy, sx(a.x), sy(a.z), sx(b.x), sy(b.z));
            if (d <= bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    // ================================================================== formatting

    private static String num(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "");
            s = s.replaceAll("\\.$", "");
        }
        return s.equals("-0") ? "0" : s;
    }

    private static String coords(Route.Pt p) {
        return num(p.x) + ", " + num(p.z);
    }

    // ================================================================== actions

    private void setTool(Tool t) {
        if (t == Tool.DRAW && Route.cur.closed) {
            flash("Path is closed - press CLOSED to open it before adding points");
            return;
        }
        tool = t;
        if (t == Tool.DRAW) {
            sel = -1;
            selSeg = -1;
        }
    }

    private void finishDrawing() {
        if (tool != Tool.DRAW) return;
        Route r = Route.cur;
        tool = Tool.SELECT;
        if (r.n() >= 2) {
            sel = r.n() - 1;
            flash("END set at P" + r.n() + "  (" + coords(r.pts.get(r.n() - 1)) + ")");
        } else {
            flash("A route needs at least 2 points");
        }
    }

    private void drawClick(double gx, double gy) {
        Route r = Route.cur;
        if (r.closed) {
            flash("Path is closed");
            return;
        }
        int near = ptAt(gx, gy, 7);
        if (near == 0 && r.n() >= 3) {
            r.closed = true;
            tool = Tool.SELECT;
            sel = 0;
            Route.save();
            flash("Loop closed - START and END are the same point");
            return;
        }
        if (near >= 0) {
            sel = near;
            selSeg = -1;
            return;
        }
        double[] p = cand();
        r.pts.add(new Route.Pt(p[0], p[1]));
        sel = r.n() - 1;
        selSeg = -1;
        Route.save();
    }

    private void selectClick(double gx, double gy, int mods) {
        int pi = ptAt(gx, gy, 7);
        if (pi >= 0) {
            sel = pi;
            selSeg = -1;
            dragMode = 2;
            dragButton = 0;
            moved = false;
            lastGx = gx;
            lastGy = gy;
            return;
        }
        int si = segAt(gx, gy, 5);
        if (si >= 0) {
            if ((mods & GLFW.GLFW_MOD_SHIFT) != 0) {
                Route r = Route.cur;
                int idx = si + 1;
                r.pts.add(idx, new Route.Pt(r.clampX(snapV(wx(gx))), r.clampZ(snapV(wz(gy)))));
                sel = idx;
                selSeg = -1;
                Route.save();
                flash("Point inserted");
            } else {
                selSeg = si;
                sel = -1;
            }
            return;
        }
        sel = -1;
        selSeg = -1;
        startPan(0, gx, gy);
    }

    private void startPan(int button, double gx, double gy) {
        dragMode = 1;
        dragButton = button;
        lastGx = gx;
        lastGy = gy;
    }

    private void deleteSelected() {
        Route r = Route.cur;
        if (sel < 0 || sel >= r.n()) return;
        r.pts.remove(sel);
        if (r.n() < 3) r.closed = false;
        sel = -1;
        selSeg = -1;
        editing = Field.NONE;
        Route.save();
        flash("Point deleted");
    }

    private void toggleClosed() {
        Route r = Route.cur;
        if (r.n() < 3) {
            flash("Need at least 3 points to close a loop");
            return;
        }
        r.closed = !r.closed;
        if (r.closed && tool == Tool.DRAW) tool = Tool.SELECT;
        Route.save();
        flash(r.closed ? "Loop closed - START and END are the same point" : "Loop opened");
    }

    private void cycleSlot() {
        Route.save();
        Cfg.I.slot = Cfg.I.slot % Cfg.SLOTS + 1;
        Cfg.save();
        Route.load(Cfg.I.slot);
        editing = Field.NONE;
        initRoute();
        flash("Slot " + Cfg.I.slot + (Route.cur.n() == 0 ? " (empty)" : " - " + Route.cur.n() + " points"));
    }

    private void clearRoute() {
        long now = System.currentTimeMillis();
        if (now - clearArmed > 3000) {
            clearArmed = now;
            flash("Click CLEAR again to erase this slot");
            return;
        }
        clearArmed = 0;
        Route r = Route.cur;
        r.pts.clear();
        r.closed = false;
        r.hasOrigin = false;
        editing = Field.NONE;
        Route.save();
        initRoute();
        flash("Slot cleared");
    }

    private void centerOnPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        vx = mc.player.getX();
        vz = mc.player.getZ();
    }

    private void fit() {
        Route r = Route.cur;
        if (r.n() == 0) {
            vx = r.originX;
            vz = r.originZ;
            scale = minScale();
            return;
        }
        double x0 = 1e18, x1 = -1e18, z0 = 1e18, z1 = -1e18;
        for (Route.Pt p : r.pts) {
            x0 = Math.min(x0, p.x);
            x1 = Math.max(x1, p.x);
            z0 = Math.min(z0, p.z);
            z1 = Math.max(z1, p.z);
        }
        double w = Math.max(x1 - x0, 10) + 10, h = Math.max(z1 - z0, 10) + 10;
        scale = Math.min((vpx1 - vpx0 - 60) / w, (vpy1 - vpy0 - 60) / h);
        vx = (x0 + x1) / 2;
        vz = (z0 + z1) / 2;
    }

    private void run() {
        Minecraft mc = Minecraft.getInstance();
        Route.save();
        Scr.set(mc, null);
        Walker.start(mc);
    }

    // ================================================================== text editing

    private String valueOf(Field f, Route.Pt p) {
        return switch (f) {
            case X -> num(p.x);
            case Z -> num(p.z);
            case YAW -> p.yaw == null ? "" : num(p.yaw);
            case PITCH -> p.pitch == null ? "" : num(p.pitch);
            case CMD -> p.cmd;
            default -> "";
        };
    }

    private void startEdit(Field f) {
        if (editing != Field.NONE) commit();
        Route.Pt p = selPt();
        if (p == null) return;
        editing = f;
        buf.setLength(0);
        buf.append(valueOf(f, p));
    }

    private void commit() {
        Field f = editing;
        editing = Field.NONE;
        Route.Pt p = selPt();
        if (p == null || f == Field.NONE) return;
        String s = buf.toString().trim();
        Route r = Route.cur;
        try {
            switch (f) {
                case X -> p.x = r.clampX(Double.parseDouble(s));
                case Z -> p.z = r.clampZ(Double.parseDouble(s));
                case YAW -> p.yaw = s.isEmpty() ? null : Double.valueOf(Route.wrapYaw(Double.parseDouble(s)));
                case PITCH -> p.pitch = s.isEmpty() ? null : Double.valueOf(Math.max(-90.0, Math.min(90.0, Double.parseDouble(s))));
                case CMD -> p.cmd = s;
                default -> {
                }
            }
            Route.save();
        } catch (NumberFormatException e) {
            flash("Not a valid number: " + s);
        }
    }

    private void acceptChar(char c) {
        if (editing == Field.CMD) {
            if (c >= 32 && c != 127 && buf.length() < 200) buf.append(c);
        } else if (editing != Field.NONE) {
            if (c == ',') c = '.';
            if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                if (buf.length() < 12) buf.append(c);
            }
        }
    }

    // ================================================================== input (called from PathInput)

    void onKey(int key, int action, int mods) {
        boolean rep = action == GLFW.GLFW_REPEAT;
        boolean ctrl = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;

        if (editing != Field.NONE) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                editing = Field.NONE;
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                commit();
            } else if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (buf.length() > 0) buf.setLength(buf.length() - 1);
            } else if (ctrl && key == GLFW.GLFW_KEY_V) {
                for (char c : PathInput.clipboard().toCharArray()) acceptChar(c);
            }
            return;
        }

        double pan = 40.0 / scale;
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (rep) return;
                if (tool == Tool.DRAW && Route.cur.n() > 0) tool = Tool.SELECT;
                else if (sel >= 0 || selSeg >= 0) {
                    sel = -1;
                    selSeg = -1;
                } else if (props) props = false;
                else Scr.set(Minecraft.getInstance(), null);
            }
            case GLFW.GLFW_KEY_F8, GLFW.GLFW_KEY_O -> {
                if (rep) return;
                ortho = !ortho;
                flash(ortho ? "ORTHO on - lines snap to the X or Z axis" : "ORTHO off");
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (!rep) finishDrawing();
            }
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> deleteSelected();
            case GLFW.GLFW_KEY_LEFT -> vx -= pan;
            case GLFW.GLFW_KEY_RIGHT -> vx += pan;
            case GLFW.GLFW_KEY_UP -> vz -= pan;
            case GLFW.GLFW_KEY_DOWN -> vz += pan;
            case GLFW.GLFW_KEY_D -> {
                if (!rep) setTool(Tool.DRAW);
            }
            case GLFW.GLFW_KEY_S -> {
                if (!rep) setTool(Tool.SELECT);
            }
            case GLFW.GLFW_KEY_P -> {
                if (!rep) props = !props;
            }
            case GLFW.GLFW_KEY_C -> {
                if (!rep) centerOnPlayer();
            }
            case GLFW.GLFW_KEY_F -> {
                if (!rep) fit();
            }
            default -> {
            }
        }
    }

    void onChar(int cp) {
        if (editing != Field.NONE && cp > 0 && cp < 0x10000) acceptChar((char) cp);
    }

    void onMouse(int button, int action, int mods) {
        double[] g = PathInput.gui();
        mx = g[0];
        my = g[1];
        if (action == GLFW.GLFW_PRESS) press(button, g[0], g[1], mods);
        else if (action == GLFW.GLFW_RELEASE) release(button);
    }

    private void press(int button, double gx, double gy, int mods) {
        if (editing != Field.NONE) commit();

        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.in(gx, gy)) {
                if (button == 0) h.r().run();
                return;
            }
        }
        if (overUi(gx, gy)) return;

        if (button == 2) {
            startPan(2, gx, gy);
        } else if (button == 1) {
            if (tool == Tool.DRAW) finishDrawing();
            else startPan(1, gx, gy);
        } else if (button == 0) {
            if (tool == Tool.DRAW) drawClick(gx, gy);
            else selectClick(gx, gy, mods);
        }
    }

    private void release(int button) {
        if (button == dragButton) {
            if (dragMode == 2) Route.save();
            dragMode = 0;
            dragButton = -1;
        }
    }

    void onScroll(double dy) {
        if (dy == 0) return;
        double[] g = PathInput.gui();
        mx = g[0];
        my = g[1];
        double wxb = wx(mx), wzb = wz(my);
        scale *= dy > 0 ? 1.25 : 1 / 1.25;
        scale = Math.max(minScale(), Math.min(48, scale));
        vx = wxb - (mx - cx) / scale;
        vz = wzb - (my - cy) / scale;
    }

    private void updateDrag() {
        if (dragMode == 0) return;
        if (PathInput.installed && GLFW.glfwGetMouseButton(PathInput.window, dragButton) != GLFW.GLFW_PRESS) {
            release(dragButton);
            return;
        }
        if (dragMode == 1) {
            vx -= (mx - lastGx) / scale;
            vz -= (my - lastGy) / scale;
        } else if (dragMode == 2) {
            if (!moved && Math.hypot(mx - lastGx, my - lastGy) < 3) return; // a plain click must not nudge the point
            moved = true;
            Route.Pt p = selPt();
            if (p != null) {
                Route r = Route.cur;
                p.x = r.clampX(snapV(wx(mx)));
                p.z = r.clampZ(snapV(wz(my)));
            }
        }
        lastGx = mx;
        lastGy = my;
    }

    // ================================================================== render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (PathInput.installed) {
            double[] gm = PathInput.gui();
            mx = gm[0];
            my = gm[1];
        } else {
            mx = mouseX;
            my = mouseY;
        }

        hits.clear();
        buildToolbar();
        layout();
        if (needFit) {
            fit();
            needFit = false;
            layout();
        }
        updateDrag();
        clampView();

        boolean inView = !overUi(mx, my);
        hoverPt = inView && dragMode == 0 ? ptAt(mx, my, 7) : -1;
        hoverSeg = inView && dragMode == 0 && hoverPt < 0 ? segAt(mx, my, 5) : -1;

        g.fill(0, 0, width, height, 0xFF0A0D12);
        drawGrid(g);
        drawRoute(g);
        if (inView) drawCursor(g);
        drawToolbar(g);
        if (props) drawPanel(g);
        drawStatus(g, inView);

        if (System.currentTimeMillis() < flashUntil) {
            int w = Ui.tw(flash) + 16;
            int x = (width - w) / 2, y = tbH + 6;
            Ui.rr(g, x, y, w, 15, 0xF0151B26);
            Ui.border(g, x, y, w, 15, Ui.alpha(Ui.WARN, 140));
            Ui.ctext(g, flash, width / 2, y + 4, Ui.WARN);
        }
    }

    // ------------------------------------------------------------------ grid

    private int pickMajor() {
        int[] c = {10, 20, 50, 100, 200, 500, 1000};
        for (int m : c) if (m * scale >= 50) return m;
        return 1000;
    }

    private int pickMinor(int major) {
        int[] c = {1, 2, 5, 10, 20, 50, 100, 200, 500};
        for (int m : c) if (m < major && major % m == 0 && m * scale >= 6) return m;
        return major;
    }

    private void drawGrid(GuiGraphicsExtractor g) {
        Route r = Route.cur;
        g.fill(vpx0, vpy0, vpx1, vpy1, 0xFF090B0F);

        int bx0 = (int) Math.round(sx(r.originX - Route.HALF)), bx1 = (int) Math.round(sx(r.originX + Route.HALF));
        int by0 = (int) Math.round(sy(r.originZ - Route.HALF)), by1 = (int) Math.round(sy(r.originZ + Route.HALF));
        int ix0 = Math.max(vpx0, bx0), ix1 = Math.min(vpx1, bx1);
        int iy0 = Math.max(vpy0, by0), iy1 = Math.min(vpy1, by1);
        if (ix1 <= ix0 || iy1 <= iy0) return;

        g.fill(ix0, iy0, ix1, iy1, 0xFF111720);

        int major = pickMajor(), minor = pickMinor(major);
        double minX = wx(ix0), maxX = wx(ix1), minZ = wz(iy0), maxZ = wz(iy1);

        if (minor != major) {
            for (long k = (long) Math.ceil(minX / minor); k <= (long) Math.floor(maxX / minor); k++) {
                if ((k * minor) % major == 0) continue;
                int x = (int) Math.round(sx(k * minor));
                g.fill(x, iy0, x + 1, iy1, 0xFF192030);
            }
            for (long k = (long) Math.ceil(minZ / minor); k <= (long) Math.floor(maxZ / minor); k++) {
                if ((k * minor) % major == 0) continue;
                int y = (int) Math.round(sy(k * minor));
                g.fill(ix0, y, ix1, y + 1, 0xFF192030);
            }
        }
        for (long k = (long) Math.ceil(minX / major); k <= (long) Math.floor(maxX / major); k++) {
            int x = (int) Math.round(sx(k * major));
            g.fill(x, iy0, x + 1, iy1, 0xFF2A364B);
        }
        for (long k = (long) Math.ceil(minZ / major); k <= (long) Math.floor(maxZ / major); k++) {
            int y = (int) Math.round(sy(k * major));
            g.fill(ix0, y, ix1, y + 1, 0xFF2A364B);
        }
        // axis labels on the major lines
        for (long k = (long) Math.ceil(minX / major); k <= (long) Math.floor(maxX / major); k++) {
            int x = (int) Math.round(sx(k * major));
            Ui.text(g, Long.toString(k * major), x + 3, iy0 + 2, 0xFF5E6E8C);
        }
        for (long k = (long) Math.ceil(minZ / major); k <= (long) Math.floor(maxZ / major); k++) {
            int y = (int) Math.round(sy(k * major));
            Ui.text(g, Long.toString(k * major), ix0 + 3, y + 2, 0xFF5E6E8C);
        }

        // edge of the 1000 x 1000 grid
        int c = Ui.alpha(Ui.ACCENT, 170);
        if (bx0 >= vpx0 && bx0 < vpx1) g.fill(bx0, iy0, bx0 + 1, iy1, c);
        if (bx1 - 1 >= vpx0 && bx1 - 1 < vpx1) g.fill(bx1 - 1, iy0, bx1, iy1, c);
        if (by0 >= vpy0 && by0 < vpy1) g.fill(ix0, by0, ix1, by0 + 1, c);
        if (by1 - 1 >= vpy0 && by1 - 1 < vpy1) g.fill(ix0, by1 - 1, ix1, by1, c);
    }

    // ------------------------------------------------------------------ route

    private void tag(GuiGraphicsExtractor g, String s, int x, int y, int fg) {
        int w = Ui.tw(s) + 6;
        x = Math.max(2, Math.min(x, width - w - 2));
        y = Math.max(vpy0 + 2, Math.min(y, vpy1 - 13));
        Ui.rr(g, x, y, w, 11, 0xE6151B26);
        Ui.border(g, x, y, w, 11, Ui.alpha(fg, 110));
        Ui.text(g, s, x + 3, y + 2, fg);
    }

    private void tagC(GuiGraphicsExtractor g, String s, int xc, int y, int fg) {
        tag(g, s, xc - (Ui.tw(s) + 6) / 2, y, fg);
    }

    private void arrow(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int c) {
        double dx = x1 - x0, dy = y1 - y0, l = Math.hypot(dx, dy);
        if (l < 30) return;
        dx /= l;
        dy /= l;
        double mxp = (x0 + x1) / 2, myp = (y0 + y1) / 2;
        double hx = mxp - dx * 5, hy = myp - dy * 5;
        Ui.line(g, mxp, myp, hx - dy * 3, hy + dx * 3, c, 1, vpx0, vpy0, vpx1, vpy1);
        Ui.line(g, mxp, myp, hx + dy * 3, hy - dx * 3, c, 1, vpx0, vpy0, vpx1, vpy1);
    }

    private void drawRoute(GuiGraphicsExtractor g) {
        Route r = Route.cur;
        int n = r.n();

        // segments
        for (int s = 0; s < r.segs(); s++) {
            Route.Pt a = r.a(s), b = r.b(s);
            double x0 = sx(a.x), y0 = sy(a.z), x1 = sx(b.x), y1 = sy(b.z);
            boolean on = s == selSeg || s == hoverSeg;
            int col = s == selSeg ? Ui.ORANGE : (s == hoverSeg ? 0xFF9BE8FF : Ui.CYAN);
            Ui.line(g, x0, y0, x1, y1, col, on ? 2 : 1, vpx0, vpy0, vpx1, vpy1);
            arrow(g, x0, y0, x1, y1, col);
        }
        // segment tags (length, and full coordinates for the selected / hovered line)
        for (int s = 0; s < r.segs(); s++) {
            Route.Pt a = r.a(s), b = r.b(s);
            double x0 = sx(a.x), y0 = sy(a.z), x1 = sx(b.x), y1 = sy(b.z);
            double len = Math.hypot(b.x - a.x, b.z - a.z);
            boolean on = s == selSeg || s == hoverSeg;
            double sl = Math.hypot(x1 - x0, y1 - y0);
            if (!on && sl < 54) continue;
            double mxp = (x0 + x1) / 2, myp = (y0 + y1) / 2;
            if (mxp < vpx0 - 40 || mxp > vpx1 + 40 || myp < vpy0 - 20 || myp > vpy1 + 20) continue;
            String t = on
                    ? "(" + coords(a) + ") > (" + coords(b) + ")   L " + num(Math.round(len * 100) / 100.0)
                    : num(Math.round(len * 10) / 10.0);
            tagC(g, t, (int) mxp, (int) myp - (on ? 18 : 6), on ? Ui.ORANGE : Ui.BLUE);
            if (on) tagC(g, "\u0394x " + num(b.x - a.x) + "   \u0394z " + num(b.z - a.z), (int) mxp, (int) myp - 6, Ui.TEXT);
        }

        // rubber band while drawing
        boolean inView = !overUi(mx, my);
        if (tool == Tool.DRAW && !r.closed && inView) {
            double[] c = cand();
            if (n > 0) {
                Route.Pt l = r.pts.get(n - 1);
                Ui.line(g, sx(l.x), sy(l.z), sx(c[0]), sy(c[1]), Ui.alpha(Ui.ORANGE, 230), 1, vpx0, vpy0, vpx1, vpy1);
            }
            int gx = (int) Math.round(sx(c[0])), gy = (int) Math.round(sy(c[1]));
            Ui.border(g, gx - 3, gy - 3, 7, 7, Ui.alpha(Ui.ORANGE, 230));
        }

        // points
        for (int i = 0; i < n; i++) {
            Route.Pt p = r.pts.get(i);
            int px = (int) Math.round(sx(p.x)), py = (int) Math.round(sy(p.z));
            if (px < vpx0 - 20 || px > vpx1 + 20 || py < vpy0 - 20 || py > vpy1 + 20) continue;

            boolean isSel = i == sel, isHov = i == hoverPt;
            int col;
            if (i == 0) col = Ui.GREEN;
            else if (i == n - 1 && !r.closed) col = Ui.RED;
            else col = Ui.CYAN;
            if (isSel) col = Ui.ORANGE;

            int h = isSel || isHov ? 4 : 3;
            g.fill(px - h - 1, py - h - 1, px + h + 2, py + h + 2, 0xFF05070A);
            g.fill(px - h, py - h, px + h + 1, py + h + 1, col);
            if (r.closed && i == 0 && !isSel) g.fill(px - 1, py - 1, px + 2, py + 2, Ui.RED);
            if (isSel) Ui.border(g, px - h - 2, py - h - 2, 2 * h + 5, 2 * h + 5, Ui.alpha(0xFFFFFF, 220));

            if (scale >= 1.2 || isSel || isHov) {
                String role = r.role(i);
                String l1 = (role.equals("POINT") ? "P" + (i + 1) : role + "  P" + (i + 1)) + (p.cmd.isBlank() ? "" : "   /CMD") + (p.yaw != null || p.pitch != null ? "   CAM" : "");
                int tx = px + 8, ty = py - 24;
                tag(g, l1, tx, ty, p.cmd.isBlank() && p.yaw == null && p.pitch == null ? col : Ui.ORANGE);
                tag(g, coords(p), tx, ty + 11, Ui.TEXT);
            }
        }

        // closing cue
        if (tool == Tool.DRAW && !r.closed && n >= 3 && hoverPt == 0) {
            Route.Pt p = r.pts.get(0);
            tagC(g, "CLICK TO CLOSE THE LOOP", (int) Math.round(sx(p.x)), (int) Math.round(sy(p.z)) + 10, Ui.GREEN);
        }

        // the player
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            int px = (int) Math.round(sx(mc.player.getX())), py = (int) Math.round(sy(mc.player.getZ()));
            if (px >= vpx0 && px < vpx1 && py >= vpy0 && py < vpy1) {
                g.fill(px - 3, py - 1, px + 4, py + 2, Ui.WARN);
                g.fill(px - 1, py - 3, px + 2, py + 4, Ui.WARN);
                Ui.text(g, "YOU", px + 6, py - 4, Ui.WARN);
            }
        }
    }

    private void drawCursor(GuiGraphicsExtractor g) {
        int x = (int) Math.round(mx), y = (int) Math.round(my);
        g.fill(vpx0, y, vpx1, y + 1, 0x30FFFFFF);
        g.fill(x, vpy0, x + 1, vpy1, 0x30FFFFFF);
        Ui.border(g, x - 4, y - 4, 9, 9, 0xB0FFFFFF);

        Route r = Route.cur;
        String s;
        if (tool == Tool.DRAW && !r.closed) {
            double[] c = cand();
            s = "X " + num(c[0]) + "   Z " + num(c[1]);
            if (r.n() > 0) {
                Route.Pt l = r.pts.get(r.n() - 1);
                s += "   L " + num(Math.round(Math.hypot(c[0] - l.x, c[1] - l.z) * 10) / 10.0);
            }
        } else {
            s = "X " + num(Math.round(wx(mx) * 10) / 10.0) + "   Z " + num(Math.round(wz(my) * 10) / 10.0);
        }
        tag(g, s, x + 12, y + 12, Ui.TEXT);
    }

    // ------------------------------------------------------------------ toolbar

    private void addBtn(String label, String[] icon, int color, int[] pos, Runnable r) {
        int w = Ui.tw(label) + (icon != null ? 18 : 12);
        if (pos[0] + w > width - 4) {
            pos[0] = 4;
            pos[1] += BH + 3;
        }
        Hit h = new Hit(pos[0], pos[1], w, BH, r);
        toolbar.add(new TB(h, label, icon, color));
        pos[0] += w + 3;
    }

    private void buildToolbar() {
        toolbar.clear();
        Route r = Route.cur;
        int[] pos = {4, 3};
        boolean armed = System.currentTimeMillis() - clearArmed < 3000;

        addBtn("DRAW", Ui.PEN, tool == Tool.DRAW ? Ui.BLUE : Ui.CARD_HI, pos, () -> setTool(Tool.DRAW));
        addBtn("SELECT", Ui.ARROW, tool == Tool.SELECT ? Ui.BLUE : Ui.CARD_HI, pos, () -> setTool(Tool.SELECT));
        addBtn("PROPERTIES", null, props ? Ui.BLUE : Ui.CARD_HI, pos, () -> props = !props);
        addBtn("ORTHO F8 " + (ortho ? "ON" : "OFF"), null, ortho ? 0xFFB5651D : Ui.CARD_HI, pos, () -> ortho = !ortho);
        addBtn("SNAP " + (Cfg.I.snap ? "ON" : "OFF"), null, Cfg.I.snap ? 0xFF2A6F8F : Ui.CARD_HI, pos, () -> {
            Cfg.I.snap = !Cfg.I.snap;
            Cfg.save();
        });
        addBtn(r.closed ? "LOOP CLOSED" : "CLOSE LOOP", null, r.closed ? 0xFF1F8F52 : Ui.CARD_HI, pos, this::toggleClosed);
        addBtn("SPRINT " + (r.sprint ? "ON" : "OFF"), null, r.sprint ? 0xFF1F8F52 : Ui.CARD_HI, pos, () -> {
            r.sprint = !r.sprint;
            Route.save();
        });
        addBtn("REPEAT " + (r.loop ? "ON" : "OFF"), null, r.loop ? 0xFF1F8F52 : Ui.CARD_HI, pos, () -> {
            r.loop = !r.loop;
            Route.save();
        });
        addBtn("SLOT " + Cfg.I.slot, null, Ui.CARD_HI, pos, this::cycleSlot);
        addBtn("FIT", null, Ui.CARD_HI, pos, this::fit);
        addBtn("CENTER", null, Ui.CARD_HI, pos, this::centerOnPlayer);
        addBtn(armed ? "SURE?" : "CLEAR", null, armed ? Ui.ACCENT : Ui.CARD_HI, pos, this::clearRoute);
        addBtn("RUN", Ui.PLAY, 0xFF1F8F52, pos, this::run);

        tbH = pos[1] + BH + 4;
        for (TB t : toolbar) hits.add(t.hit());
    }

    private void drawToolbar(GuiGraphicsExtractor g) {
        g.fill(0, 0, width, tbH, 0xFF0F141C);
        g.fill(0, tbH - 1, width, tbH, Ui.LINE);
        for (TB t : toolbar) {
            Hit h = t.hit();
            Ui.pill(g, h.x(), h.y(), h.w(), h.h(), t.label(), t.icon(), t.color(), h.in(mx, my));
        }
    }

    // ------------------------------------------------------------------ properties panel

    private void fieldRow(GuiGraphicsExtractor g, String label, Field f, String value, int y) {
        fieldRow(g, label, f, value, y, null);
    }

    private void fieldRow(GuiGraphicsExtractor g, String label, Field f, String value, int y, String hint) {
        int bx = panelX + 32, bw = PW - 40;
        Ui.text(g, label, panelX + 8, y + 3, Ui.DIM);
        boolean on = editing == f;
        Ui.rr(g, bx, y, bw, 14, on ? 0xFF0E1420 : Ui.CARD);
        Ui.border(g, bx, y, bw, 14, on ? Ui.CYAN : Ui.LINE);
        String shown = on ? buf + ((System.currentTimeMillis() / 450) % 2 == 0 ? "_" : " ") : value;
        if (!on && value.isEmpty() && hint != null) Ui.text(g, hint, bx + 4, y + 3, Ui.DIM);
        else Ui.text(g, Ui.fit(shown, bw - 8), bx + 4, y + 3, Ui.TEXT);
        hits.add(new Hit(bx, y, bw, 14, () -> startEdit(f)));
    }

    private void panelBtn(GuiGraphicsExtractor g, String label, int x, int y, int w, int color, Runnable r) {
        Hit h = new Hit(x, y, w, BH, r);
        Ui.pill(g, x, y, w, BH, label, null, color, h.in(mx, my));
        hits.add(h);
    }

    private void drawPanel(GuiGraphicsExtractor g) {
        Route r = Route.cur;
        Route.Pt p = selPt();
        Ui.rr(g, panelX, panelY, PW, panelH, Ui.PANEL);
        Ui.border(g, panelX, panelY, PW, panelH, Ui.LINE);
        g.fill(panelX + 1, panelY, panelX + PW - 1, panelY + 2, Ui.CYAN);
        Ui.text(g, "PROPERTIES", panelX + 8, panelY + 6, Ui.DIM);

        // close box
        Hit close = new Hit(panelX + PW - 16, panelY + 4, 12, 12, () -> props = false);
        Ui.text(g, "x", panelX + PW - 12, panelY + 6, close.in(mx, my) ? Ui.RED : Ui.DIM);
        hits.add(close);

        if (p == null) {
            Ui.text(g, "Select a point with the", panelX + 8, panelY + 24, Ui.DIM);
            Ui.text(g, "SELECT tool (S) first.", panelX + 8, panelY + 35, Ui.DIM);
            return;
        }

        String role = r.role(sel);
        int rc = role.equals("POINT") ? Ui.CYAN : (role.equals("END") ? Ui.RED : Ui.GREEN);
        Ui.text(g, "P" + (sel + 1) + " / " + r.n() + "   " + role, panelX + 8, panelY + 22, rc);

        int y = panelY + 36;
        fieldRow(g, "X", Field.X, num(p.x), y);
        y += 18;
        fieldRow(g, "Z", Field.Z, num(p.z), y);
        y += 18;
        fieldRow(g, "YAW", Field.YAW, p.yaw == null ? "" : num(p.yaw), y, "keep current");
        y += 18;
        fieldRow(g, "PIT", Field.PITCH, p.pitch == null ? "" : num(p.pitch), y, "keep current");
        y += 18;
        fieldRow(g, "CMD", Field.CMD, p.cmd, y);
        y += 18;
        Ui.text(g, "Camera eases to YAW / PIT on", panelX + 8, y, Ui.DIM);
        Ui.text(g, "arrival. Empty = unchanged.", panelX + 8, y + 10, Ui.DIM);
        Ui.text(g, "CMD runs once on the point.", panelX + 8, y + 20, Ui.DIM);
        y += 34;
        panelBtn(g, "DELETE", panelX + 8, y, 74, 0xFFB01B2E, this::deleteSelected);
        panelBtn(g, "CLEAR CMD", panelX + 86, y, 78, Ui.CARD_HI, () -> {
            p.cmd = "";
            editing = Field.NONE;
            Route.save();
        });
        y += 18;
        panelBtn(g, "USE MY VIEW", panelX + 8, y, 82, Ui.CARD_HI, () -> {
            Minecraft m = Minecraft.getInstance();
            if (m.player == null) return;
            p.yaw = Math.round(Route.wrapYaw(m.player.getYRot()) * 10.0) / 10.0;
            p.pitch = Math.round(Math.max(-90.0, Math.min(90.0, (double) m.player.getXRot())) * 10.0) / 10.0;
            editing = Field.NONE;
            Route.save();
            flash("Camera set to your current view");
        });
        panelBtn(g, "CLEAR VIEW", panelX + 94, y, 70, Ui.CARD_HI, () -> {
            p.yaw = null;
            p.pitch = null;
            editing = Field.NONE;
            Route.save();
        });
    }

    // ------------------------------------------------------------------ status bar

    private void drawStatus(GuiGraphicsExtractor g, boolean inView) {
        Route r = Route.cur;
        int y = height - SB;
        g.fill(0, y, width, height, 0xFF0F141C);
        g.fill(0, y, width, y + 1, Ui.LINE);

        String left = inView
                ? "X " + num(Math.round(wx(mx) * 10) / 10.0) + "  Z " + num(Math.round(wz(my) * 10) / 10.0)
                : "";
        String right = r.n() + " pts   " + num(Math.round(r.length() * 10) / 10.0) + " blocks   " + num(Math.round(scale * 100) / 100.0) + " px/block";
        Ui.text(g, left, 5, y + 4, Ui.TEXT);
        Ui.text(g, right, width - Ui.tw(right) - 5, y + 4, Ui.DIM);

        String hint = tool == Tool.DRAW
                ? "LMB add point  -  click START to close the loop  -  RMB / Enter = finish (END)  -  F8 ortho"
                : "Click a point or a line  -  drag to move  -  Shift+click a line inserts a point  -  Del removes";
        int free = width - Ui.tw(left) - Ui.tw(right) - 30;
        if (free > 60) Ui.text(g, Ui.fit(hint, free), 5 + Ui.tw(left) + 14, y + 4, Ui.DIM);
    }
}
