package dev.berserk;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Tiny immediate-mode drawing helpers built only on fill() and text(). */
final class Ui {
    private Ui() {}

    static final int PANEL = 0xF2140F19, CARD = 0xFF1F1826, CARD_HI = 0xFF2B2234, CARD_ALT = 0xFF1A1420,
            LINE = 0xFF392C43, ACCENT = 0xFFE5243B, ACCENT2 = 0xFFFF8A1F, TEXT = 0xFFF0EAF5,
            DIM = 0xFF9A8FA6, GREEN = 0xFF34D17A, OFF = 0xFF4B4054, WARN = 0xFFFFC857, BLACK = 0xFF0B080D;

    // 7x7 pixel glyphs
    static final String[] REC = {"..###..", ".#####.", "#######", "#######", "#######", ".#####.", "..###.."};
    static final String[] PLAY = {"##.....", "####...", "#####..", "######.", "#####..", "####...", "##....."};
    static final String[] STOP = {".#####.", "#######", "#######", "#######", "#######", "#######", ".#####."};
    static final String[] SHIELD = {"#######", "#.....#", "#.....#", "#.....#", ".#...#.", "..#.#..", "...#..."};
    static final String[] BOLT = {"...###.", "..###..", ".#####.", "...##..", "..##...", ".##....", "##....."};
    static final String[] HEART = {".##.##.", "#######", "#######", "#######", ".#####.", "..###..", "...#..."};
    static final String[] SCREEN = {"#######", "#.....#", "#.###.#", "#.###.#", "#.....#", "#######", "..###.."};
    static final String[] LOCK = {"..###..", ".#...#.", ".#...#.", "#######", "##.#.##", "#######", "#######"};
    static final String[] MOON = {"..###..", ".##....", "##.....", "##.....", "##.....", ".##..#.", "..###.."};
    static final String[] EYE = {".......", "..###..", ".#...#.", "#..#..#", ".#...#.", "..###..", "......."};
    static final String[] PIN = {"..###..", ".#####.", ".##.##.", ".#####.", "..###..", "...#...", "...#..."};
    static final String[] RUN = {"...#...", "....#..", "#######", "....#..", "...#...", ".......", "......."};
    static final String[] CHAT = {"#######", "#.....#", "#.###.#", "#.....#", "###.###", "..#.#..", "...#..."};
    static final String[] LOOP = {".#####.", "#.....#", "#.....#", "#....##", "#...###", "#.....#", ".#####."};
    static final String[] TARGET = {"..###..", ".#...#.", "#..#..#", "#.###.#", "#..#..#", ".#...#.", "..###.."};
    static final String[] MENU = {".......", "#######", ".......", "#######", ".......", "#######", "......."};

    static int tw(String s) {
        return Minecraft.getInstance().font.width(s);
    }

    static void text(GuiGraphicsExtractor g, String s, int x, int y, int c) {
        g.text(Minecraft.getInstance().font, s, x, y, c, true);
    }

    static void ctext(GuiGraphicsExtractor g, String s, int cx, int y, int c) {
        text(g, s, cx - tw(s) / 2, y, c);
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

    /** Small pill-shaped button used by the HUD and the menu. */
    static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, String[] icon, int color, boolean hover) {
        int bg = hover ? lerp(color, 0xFFFFFFFF, 0.18f) : color;
        rr(g, x, y, w, h, alpha(bg, 0xE0));
        border(g, x, y, w, h, alpha(0xFFFFFF, hover ? 90 : 40));
        int tx = x + (w - tw(label)) / 2;
        if (icon != null) {
            glyph(g, icon, x + 5, y + (h - 7) / 2, 1, TEXT);
            tx += 4;
        }
        text(g, label, tx, y + (h - 8) / 2, TEXT);
    }
}
