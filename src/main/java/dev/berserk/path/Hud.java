package dev.berserk.path;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small status pill while a path is running. */
final class Hud {
    private Hud() {}

    static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !Walker.running) return;
        String t = Walker.status();
        int w = Ui.tw(t) + 24;
        int y = dev.berserk.Input.freeMouse ? 22 : 4;
        Ui.rr(g, 4, y, w, 14, 0xC00F0A12);
        g.fill(4, y, 6, y + 14, Ui.CYAN);
        if ((System.currentTimeMillis() / 500) % 2 == 0) g.fill(11, y + 4, 17, y + 10, Ui.CYAN);
        Ui.text(g, t, 22, y + 3, Ui.TEXT);
    }
}
