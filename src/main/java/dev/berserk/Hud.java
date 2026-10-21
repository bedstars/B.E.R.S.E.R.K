package dev.berserk;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;

import java.util.Locale;

/** In-game overlay: status pill, coords, and the MENU / REC / PLAY buttons shown while chat is open. */
final class Hud {
    private Hud() {}

    static final int BW = 46, BH = 14;

    private static int bx(int gw, int idx) {
        return gw - (3 - idx) * (BW + 4) - 2;
    }

    private static int by(int gh) {
        return gh - 34;
    }

    /** 0 = menu, 1 = record, 2 = play, -1 = nothing */
    static int hit(double gx, double gy) {
        Minecraft mc = Minecraft.getInstance();
        int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
        for (int i = 0; i < 3; i++) {
            int x = bx(gw, i), y = by(gh);
            if (gx >= x && gx < x + BW && gy >= y && gy < y + BH) return i;
        }
        return -1;
    }

    static void act(int idx, Minecraft mc) {
        if (idx == 0) Engine.toggleMenu(mc);
        else if (idx == 1) Engine.toggleRecord(mc);
        else if (idx == 2) Engine.togglePlay(mc);
    }

    static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();

        int y = 4;
        if (Config.I.mHud && Engine.mode != Engine.Mode.IDLE) {
            boolean rec = Engine.mode == Engine.Mode.RECORDING;
            int c = rec ? Ui.ACCENT : Ui.GREEN;
            String t = Engine.hudLine();
            int w = Ui.tw(t) + 24;
            Ui.rr(g, 4, y, w, 14, 0xC00F0A12);
            g.fill(4, y, 6, y + 14, c);
            if (!rec || (System.currentTimeMillis() / 500) % 2 == 0) g.fill(11, y + 4, 17, y + 10, c);
            Ui.text(g, t, 22, y + 3, Ui.TEXT);
            y += 18;
        }
        if (Input.freeMouse) {
            String t = "FREE MOUSE";
            Ui.rr(g, 4, y, Ui.tw(t) + 14, 14, 0xC00F0A12);
            g.fill(4, y, 6, y + 14, Ui.ACCENT2);
            Ui.text(g, t, 11, y + 3, Ui.ACCENT2);
            y += 18;
        }
        if (Config.I.mCoords) {
            String t = String.format(Locale.ROOT, "XYZ  %.1f  %.1f  %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
            Ui.rr(g, 4, y, Ui.tw(t) + 14, 14, 0xC00F0A12);
            g.fill(4, y, 6, y + 14, Ui.DIM);
            Ui.text(g, t, 11, y + 3, Ui.TEXT);
        }

        if (mc.screen instanceof ChatScreen && Config.I.mChatButtons) {
            double[] m = Input.gui();
            int h = hit(m[0], m[1]);
            boolean rec = Engine.mode == Engine.Mode.RECORDING;
            boolean play = Engine.mode == Engine.Mode.PLAYING;
            Ui.pill(g, bx(gw, 0), by(gh), BW, BH, "MENU", Ui.MENU, Ui.CARD_HI, h == 0);
            Ui.pill(g, bx(gw, 1), by(gh), BW, BH, rec ? "STOP" : "REC", rec ? Ui.STOP : Ui.REC, 0xFFB01B2E, h == 1);
            Ui.pill(g, bx(gw, 2), by(gh), BW, BH, play ? "STOP" : "PLAY", play ? Ui.STOP : Ui.PLAY, 0xFF1F8F52, h == 2);
        }
    }
}
