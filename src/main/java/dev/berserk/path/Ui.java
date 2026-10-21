package dev.berserk.path;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Tiny immediate-mode drawing helpers built only on fill() and text(). */
final class Ui {
    private Ui() {}

    static final int PANEL = 0xF2141A24, CARD = 0xFF1B2330, CARD_HI = 0xFF2A3547, LINE = 0xFF33405A,
            ACCENT = 0xFFE5243B, ORANGE = 0xFFFF8A1F, TEXT = 0xFFF0EAF5, DIM = 0xFF8E9AB0,
            GREEN = 0xFF34D17A, RED = 0xFFFF4D5E, CYAN = 0xFF3DD6FF, WARN = 0xFFFFC857, BLUE = 0xFF5B9BFF;

    // 7x7 pixel glyphs
    static final String[] PLAY = {"##.....", "####...", "#####..", "######.", "#####..", "####...", "##....."};
    static final String[] PEN = {"......#", ".....##", "....##.", "...##..", "..##...", ".##....", "##....."};
    static final String[] ARROW = {"##.....", "###....", "####...", "#####..", "####...", "###.#..", "##....."};
    static final String[] PIN = {"..###..", ".#####.", ".##.##.", ".#####.", "..###..", "...#...", "...#..."};

    static int tw(String s) {
        return Minecraft.getInstance().font.width(s);
    }

    static void text(GuiGraphicsExtractor g, String s, int x, int y, int c) {
        g.text(Minecraft.getInstance().font, s, x, y, c, true);
    }

    static void ctext(GuiGraphicsExtractor g, String s, int cx, int y, int c) {
        text(g, s, cx - tw(s) / 2, y, c);
    }

    /** Trim a string from the front so it fits maxW pixels. */
    static String fit(String s, int maxW) {
        if (tw(s) <= maxW) return s;
        while (s.length() > 1 && tw("\u2026" + s) > maxW) s = s.substring(1);
        return "\u2026" + s;
    }

    static void rr(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + w, y + h - 1, c);
    }

    static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + 1, c);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    static void glyph(GuiGraphicsExtractor g, String[] rows, int x, int y, int scale, int color) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            for (int c = 0; c < row.length(); c++) {
                if (row.charAt(c) == '#') g.fill(x + c * scale, y + r * scale, x + (c + 1) * scale, y + (r + 1) * scale, color);
            }
        }
    }

    static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int A = (int) (((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
        int R = (int) (((a >>> 16) & 255) * (1 - t) + ((b >>> 16) & 255) * t);
        int G = (int) (((a >>> 8) & 255) * (1 - t) + ((b >>> 8) & 255) * t);
        int B = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (A << 24) | (R << 16) | (G << 8) | B;
    }

    static int alpha(int c, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF);
    }

    /** Small pill-shaped button. */
    static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, String[] icon, int color, boolean hover) {
        int bg = hover ? lerp(color, 0xFFFFFFFF, 0.18f) : color;
        rr(g, x, y, w, h, alpha(bg, 0xE8));
        border(g, x, y, w, h, alpha(0xFFFFFF, hover ? 100 : 40));
        int tx = x + (w - tw(label)) / 2;
        if (icon != null) {
            glyph(g, icon, x + 5, y + (h - 7) / 2, 1, TEXT);
            tx += 5;
        }
        text(g, label, tx, y + (h - 8) / 2, TEXT);
    }

    /**
     * Straight line between two points (screen space), clipped to a rectangle (Liang-Barsky).
     * Drawn as runs of 1-pixel columns / rows so it stays connected at any angle.
     */
    static void line(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int color, int th,
                     int cx0, int cy0, int cx1, int cy1) {
        double dx = x1 - x0, dy = y1 - y0;
        double t0 = 0, t1 = 1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x0 - cx0, cx1 - x0, y0 - cy0, cy1 - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return;
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) {
                    if (r > t1) return;
                    if (r > t0) t0 = r;
                } else {
                    if (r < t0) return;
                    if (r < t1) t1 = r;
                }
            }
        }
        double ax = x0 + t0 * dx, ay = y0 + t0 * dy, bx = x0 + t1 * dx, by = y0 + t1 * dy;

        if (Math.abs(bx - ax) >= Math.abs(by - ay)) {
            if (bx < ax) {
                double tx = ax; ax = bx; bx = tx;
                double ty = ay; ay = by; by = ty;
            }
            int xa = (int) Math.floor(ax), xb = (int) Math.floor(bx);
            double slope = bx - ax < 1e-9 ? 0 : (by - ay) / (bx - ax);
            if (Math.abs(slope) < 0.02) {
                int y = (int) Math.round((ay + by) / 2);
                g.fill(xa, y, xb + 1, y + th, color);
                return;
            }
            for (int x = xa; x <= xb; x++) {
                double ya = ay + (x - ax) * slope, yb = ay + (x + 1 - ax) * slope;
                int lo = (int) Math.floor(Math.min(ya, yb)), hi = (int) Math.floor(Math.max(ya, yb));
                g.fill(x, lo, x + 1, hi + th, color);
            }
        } else {
            if (by < ay) {
                double tx = ax; ax = bx; bx = tx;
                double ty = ay; ay = by; by = ty;
            }
            int ya = (int) Math.floor(ay), yb = (int) Math.floor(by);
            double slope = by - ay < 1e-9 ? 0 : (bx - ax) / (by - ay);
            if (Math.abs(slope) < 0.02) {
                int x = (int) Math.round((ax + bx) / 2);
                g.fill(x, ya, x + th, yb + 1, color);
                return;
            }
            for (int y = ya; y <= yb; y++) {
                double xa = ax + (y - ay) * slope, xb = ax + (y + 1 - ay) * slope;
                int lo = (int) Math.floor(Math.min(xa, xb)), hi = (int) Math.floor(Math.max(xa, xb));
                g.fill(lo, y, hi + th, y + 1, color);
            }
        }
    }
}
