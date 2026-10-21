package dev.berserk;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import dev.berserk.path.Cfg;
import dev.berserk.path.PathMod;
import dev.berserk.path.PathScreen;
import dev.berserk.path.Route;
import dev.berserk.path.Walker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * The B.E.R.S.E.R.K menu. All input arrives through {@link Input} (our GLFW hook), so this screen
 * does not depend on any Screen input method signatures, which change between Minecraft versions.
 * Drawing is done with fill() / text() only.
 */
public final class BerserkScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable r) {
        boolean in(double a, double b) {
            return a >= x && a < x + w && b >= y && b < y + h;
        }
    }

    private record Mod(String name, String desc, String[] glyph, BooleanSupplier get, Consumer<Boolean> set) {}

    private interface Row {}

    private record Num(String label, DoubleSupplier get, DoubleConsumer set, double min, double max, double step, boolean isInt) implements Row {}

    private record Tog(String label, BooleanSupplier get, Consumer<Boolean> set) implements Row {}

    private record Txt(String label) implements Row {}

    private static final String[] TABS = {"MODS", "MACROS", "PATH", "SETTINGS", "KEYBINDS"};

    private final List<Hit> hits = new ArrayList<>();
    private final List<Mod> mods = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Integer> tickMap = new HashMap<>();
    private final int[] scroll = new int[TABS.length];

    private int tab = 0;
    private int capture = 0; // 0 none, 1 failure command, 2 rename
    private StringBuilder buf = new StringBuilder();
    private String renameFrom = "";
    private String confirmDel = "";
    private String hoverDesc = "";
    private int mx, my, vy0, vy1;

    public BerserkScreen() {
        super(Component.literal("B.E.R.S.E.R.K"));
        buildModules();
        buildRows();
        refresh();
    }

    public boolean isPauseScreen() {
        return false;
    }

    boolean capturing() {
        return capture != 0;
    }

    // ------------------------------------------------------------------ model

    private void buildModules() {
        mods.add(new Mod("Failsafes", "Press ESC out of menus and retry. Halts + runs your command after too many failures.", Ui.SHIELD, () -> Config.I.mFailsafes, v -> Config.I.mFailsafes = v));
        mods.add(new Mod("Setback Guard", "Detects rubber-banding and drifting off the recorded path.", Ui.BOLT, () -> Config.I.mSetback, v -> Config.I.mSetback = v));
        mods.add(new Mod("Damage Guard", "Counts a big health drop during playback as a failure.", Ui.HEART, () -> Config.I.mDamage, v -> Config.I.mDamage = v));
        mods.add(new Mod("Screen Sync", "Waits for the same menus that were open while recording (or times out).", Ui.SCREEN, () -> Config.I.mScreenSync, v -> Config.I.mScreenSync = v));
        mods.add(new Mod("Block Input", "Ignore your real keyboard and mouse while a macro plays.", Ui.LOCK, () -> Config.I.mBlockInput, v -> Config.I.mBlockInput = v));
        mods.add(new Mod("Background Run", "Do not pause the game when the window loses focus during playback.", Ui.MOON, () -> Config.I.mBackground, v -> Config.I.mBackground = v));
        mods.add(new Mod("Status HUD", "Small REC / PLAY status pill in the corner.", Ui.EYE, () -> Config.I.mHud, v -> Config.I.mHud = v));
        mods.add(new Mod("Coords HUD", "Show your XYZ in the corner.", Ui.PIN, () -> Config.I.mCoords, v -> Config.I.mCoords = v));
        mods.add(new Mod("Auto Sprint", "Hold sprint whenever you walk forward.", Ui.RUN, () -> Config.I.mSprint, v -> Config.I.mSprint = v));
        mods.add(new Mod("Chat Buttons", "MENU / REC / PLAY buttons while the chat box is open.", Ui.CHAT, () -> Config.I.mChatButtons, v -> Config.I.mChatButtons = v));
        mods.add(new Mod("Loop Macro", "Repeat the macro again and again (set the count in SETTINGS).", Ui.LOOP, () -> Config.I.mLoop, v -> Config.I.mLoop = v));
        mods.add(new Mod("Start Check", "Verify you are at the recorded start spot before each run.", Ui.TARGET, () -> Config.I.mStartCheck, v -> Config.I.mStartCheck = v));
    }

    private void buildRows() {
        rows.add(new Txt("Failure command"));
        rows.add(new Num("Max retries", () -> Config.I.maxRetries, v -> Config.I.maxRetries = (int) Math.round(v), 0, 10, 1, true));
        rows.add(new Num("Loops (0 = endless)", () -> Config.I.loops, v -> Config.I.loops = (int) Math.round(v), 0, 999, 1, true));
        rows.add(new Num("Loop delay (ticks)", () -> Config.I.loopDelay, v -> Config.I.loopDelay = (int) Math.round(v), 0, 600, 5, true));
        rows.add(new Num("Screen timeout (ticks)", () -> Config.I.screenTimeout, v -> Config.I.screenTimeout = (int) Math.round(v), 10, 400, 10, true));
        rows.add(new Num("Max drift (blocks)", () -> Config.I.maxDrift, v -> Config.I.maxDrift = v, 1, 50, 0.5, false));
        rows.add(new Num("Setback distance", () -> Config.I.setbackDist, v -> Config.I.setbackDist = v, 0.5, 20, 0.5, false));
        rows.add(new Num("Start tolerance", () -> Config.I.startTolerance, v -> Config.I.startTolerance = v, 0.5, 50, 0.5, false));
        rows.add(new Num("Damage threshold", () -> Config.I.damageThreshold, v -> Config.I.damageThreshold = v, 0.5, 20, 0.5, false));
        rows.add(new Tog("Absolute positions", () -> Config.I.absolutePos, v -> Config.I.absolutePos = v));
    }

    private void refresh() {
        tickMap.clear();
        for (String n : Config.I.slots) if (!n.isEmpty()) tickMap.put(n, MacroStore.ticksOf(n));
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        this.mx = mouseX;
        this.my = mouseY;
        hits.clear();
        hoverDesc = "";
        int gw = this.width, gh = this.height;
        long now = System.currentTimeMillis();

        g.fill(0, 0, gw, gh, 0xA0050308);

        int pw = Math.min(388, gw - 12), ph = Math.min(244, gh - 12);
        int px = (gw - pw) / 2, py = (gh - ph) / 2;

        int pulse = (int) (10 + 8 * Math.sin(now / 420.0));
        for (int i = 5; i >= 1; i--) Ui.rr(g, px - i, py - i, pw + 2 * i, ph + 2 * i, Ui.alpha(Ui.ACCENT, Math.max(3, pulse * (6 - i) / 3)));
        Ui.rr(g, px, py, pw, ph, Ui.PANEL);
        Ui.border(g, px, py, pw, ph, Ui.LINE);
        for (int i = 0; i < pw; i += 2) {
            float wave = (float) (0.5 + 0.5 * Math.sin(now / 450.0 - i * 0.035));
            g.fill(px + i, py, Math.min(px + i + 2, px + pw), py + 2, Ui.lerp(Ui.ACCENT, Ui.ACCENT2, wave));
        }

        // ---- header
        vy0 = 0;
        vy1 = gh;
        Ui.glyph(g, Ui.TARGET, px + 8, py + 7, 2, Ui.ACCENT);
        String title = "B.E.R.S.E.R.K";
        int tx = px + 28;
        for (int i = 0; i < title.length(); i++) {
            String ch = String.valueOf(title.charAt(i));
            Ui.text(g, ch, tx, py + 8, Ui.lerp(Ui.ACCENT, Ui.ACCENT2, (float) i / (title.length() - 1)));
            tx += Ui.tw(ch) + 2;
        }
        Ui.text(g, "v1.2", tx + 4, py + 9, Ui.DIM);

        int closeX = px + pw - 22, playX = closeX - 54, recX = playX - 54;
        boolean recOn = Engine.mode == Engine.Mode.RECORDING, playOn = Engine.mode == Engine.Mode.PLAYING;
        button(g, recX, py + 7, 50, 14, recOn ? "STOP" : "REC", recOn ? Ui.STOP : Ui.REC, 0xFFB01B2E, () -> Engine.toggleRecord(Minecraft.getInstance()));
        button(g, playX, py + 7, 50, 14, playOn ? "STOP" : "PLAY", playOn ? Ui.STOP : Ui.PLAY, 0xFF1F8F52, () -> Engine.togglePlay(Minecraft.getInstance()));
        button(g, closeX, py + 7, 14, 14, "x", null, Ui.CARD_HI, this::closeMenu);

        // ---- tabs
        int tabW = 70;
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            int x = px + 8 + i * (tabW + 2), y = py + 28;
            boolean sel = tab == i;
            boolean hov = in(mx, my, x, y, tabW, 14);
            Ui.rr(g, x, y, tabW, 14, sel ? Ui.CARD_HI : (hov ? Ui.CARD : Ui.CARD_ALT));
            Ui.ctext(g, TABS[i], x + tabW / 2, y + 3, sel ? Ui.TEXT : Ui.DIM);
            if (sel) g.fill(x + 2, y + 13, x + tabW - 2, y + 14, Ui.ACCENT);
            hits.add(new Hit(x, y, tabW, 14, () -> {
                commit();
                tab = idx;
            }));
        }
        g.fill(px + 8, py + 44, px + pw - 8, py + 45, Ui.LINE);

        // ---- content
        int cx = px + 8, cy = py + 48, cw = pw - 16, ch = ph - 48 - 20;
        vy0 = cy;
        vy1 = cy + ch;
        g.enableScissor(cx, cy, cx + cw, cy + ch);
        int contentH = switch (tab) {
            case 0 -> drawMods(g, cx, cy, cw);
            case 1 -> drawMacros(g, cx, cy, cw);
            case 2 -> drawPath(g, cx, cy, cw);
            case 3 -> drawSettings(g, cx, cy, cw);
            default -> drawKeys(g, cx, cy, cw);
        };
        g.disableScissor();
        int maxS = Math.max(0, contentH - ch);
        scroll[tab] = Math.max(0, Math.min(scroll[tab], maxS));
        if (maxS > 0) {
            int barH = Math.max(14, ch * ch / contentH);
            int barY = cy + (int) ((long) (ch - barH) * scroll[tab] / maxS);
            g.fill(cx + cw + 2, cy, cx + cw + 4, cy + ch, Ui.CARD);
            g.fill(cx + cw + 2, barY, cx + cw + 4, barY + barH, Ui.ACCENT);
        }

        // ---- footer
        vy0 = 0;
        vy1 = gh;
        int fy = py + ph - 15;
        g.fill(px + 8, fy - 3, px + pw - 8, fy - 2, Ui.LINE);
        String foot = hoverDesc.isEmpty() ? Engine.statusLine() : hoverDesc;
        int maxW = pw - 100;
        while (foot.length() > 3 && Ui.tw(foot) > maxW) foot = foot.substring(0, foot.length() - 2);
        Ui.text(g, foot, px + 10, fy, hoverDesc.isEmpty() ? Ui.DIM : Ui.TEXT);
        if (Input.freeMouse) Ui.text(g, "FREE MOUSE", px + pw - 10 - Ui.tw("FREE MOUSE"), fy, Ui.ACCENT2);
    }

    private int drawMods(GuiGraphicsExtractor g, int cx, int cy, int cw) {
        int cols = 3, gap = 6;
        int cardW = (cw - (cols - 1) * gap) / cols, cardH = 56;
        int sc = scroll[0];
        for (int i = 0; i < mods.size(); i++) {
            Mod m = mods.get(i);
            int x = cx + (i % cols) * (cardW + gap);
            int y = cy + (i / cols) * (cardH + gap) - sc;
            boolean on = m.get().getAsBoolean();
            boolean hov = in(mx, my, x, y, cardW, cardH) && my >= vy0 && my < vy1;
            Ui.rr(g, x, y, cardW, cardH, hov ? Ui.CARD_HI : Ui.CARD);
            Ui.border(g, x, y, cardW, cardH, on ? Ui.alpha(Ui.ACCENT, 210) : Ui.LINE);
            Ui.glyph(g, m.glyph(), x + cardW / 2 - 7, y + 7, 2, on ? Ui.ACCENT : Ui.DIM);
            Ui.ctext(g, m.name(), x + cardW / 2, y + 25, on ? Ui.TEXT : Ui.DIM);
            int bx = x + 6, by = y + cardH - 17, bw = cardW - 12;
            Ui.rr(g, bx, by, bw, 12, on ? Ui.GREEN : Ui.OFF);
            Ui.ctext(g, on ? "ENABLED" : "DISABLED", x + cardW / 2, by + 2, on ? Ui.BLACK : Ui.TEXT);
            visible(x, y, cardW, cardH, () -> {
                m.set().accept(!on);
                Config.save();
            });
            if (hov) hoverDesc = m.desc();
        }
        int rowsN = (mods.size() + cols - 1) / cols;
        return rowsN * (cardH + gap);
    }

    private int drawMacros(GuiGraphicsExtractor g, int cx, int cy, int cw) {
        int y = cy - scroll[1];

        // ---- randomize master switch
        boolean rnd = Config.I.randomize;
        boolean rh = in(mx, my, cx, y, cw, 20) && my >= vy0 && my < vy1;
        Ui.rr(g, cx, y, cw, 20, rh ? Ui.CARD_HI : Ui.CARD);
        Ui.border(g, cx, y, cw, 20, rnd ? Ui.alpha(Ui.ACCENT, 210) : Ui.LINE);
        Ui.glyph(g, Ui.LOOP, cx + 7, y + 6, 1, rnd ? Ui.ACCENT : Ui.DIM);
        Ui.text(g, "Randomize macros", cx + 20, y + 6, rnd ? Ui.TEXT : Ui.DIM);
        int tbx = cx + cw - 54;
        Ui.rr(g, tbx, y + 4, 48, 12, rnd ? Ui.GREEN : Ui.OFF);
        Ui.ctext(g, rnd ? "ON" : "OFF", tbx + 24, y + 6, rnd ? Ui.BLACK : Ui.TEXT);
        visible(cx, y, cw, 20, () -> {
            commit();
            Config.I.randomize = !Config.I.randomize;
            Config.save();
        });
        if (rh) hoverDesc = "ON: when a macro finishes, a random one of the ticked (RND) slots plays next. OFF: only the selected slot runs.";
        y += 24;

        Ui.text(g, rnd ? "Tick RND on every slot you want in the shuffle." : "Click a slot to select it. REC records into a slot.", cx + 2, y, Ui.DIM);
        y += 14;

        // ---- the 5 slots
        for (int i = 0; i < Config.SLOTS; i++) {
            final int idx = i;
            String n = Config.I.slots[i];
            boolean has = !n.isEmpty();
            boolean active = Config.I.activeSlot == i;
            boolean hov = in(mx, my, cx, y, cw, 22) && my >= vy0 && my < vy1;
            boolean recHere = Engine.mode == Engine.Mode.RECORDING && Engine.recSlot == i;
            boolean playHere = Engine.mode == Engine.Mode.PLAYING && Engine.curSlot == i;
            boolean ticked = Config.I.randSel[i] && has;

            Ui.rr(g, cx, y, cw, 22, hov ? Ui.CARD_HI : Ui.CARD);
            int edge = (rnd ? ticked : active) ? Ui.alpha(Ui.ACCENT, 220) : Ui.LINE;
            Ui.border(g, cx, y, cw, 22, playHere ? Ui.GREEN : edge);
            if (rnd ? ticked : active) g.fill(cx + 2, y + 3, cx + 4, y + 19, Ui.ACCENT);

            // slot number badge
            Ui.rr(g, cx + 8, y + 4, 14, 14, (rnd ? ticked : active) ? Ui.ACCENT : Ui.CARD_HI);
            Ui.ctext(g, Integer.toString(i + 1), cx + 15, y + 7, Ui.TEXT);

            int delX = cx + cw - 32, renX = delX - 32, recX = renX - 34, rndX = recX - 32;
            boolean editing = capture == 2 && renameFrom.equals(n) && has;
            String shown = has ? n : "Empty slot";
            int nameMax = rndX - (cx + 28) - 62;
            if (editing) {
                Ui.text(g, buf + (((System.currentTimeMillis() / 400) % 2 == 0) ? "|" : ""), cx + 28, y + 7, Ui.WARN);
            } else {
                while (shown.length() > 3 && Ui.tw(shown) > nameMax) shown = shown.substring(0, shown.length() - 2);
                Ui.text(g, shown, cx + 28, y + 7, !has ? Ui.OFF : (active ? Ui.TEXT : Ui.DIM));
            }
            if (has) {
                int t = tickMap.getOrDefault(n, 0);
                String info = String.format(Locale.ROOT, "%.1fs", t / 20.0);
                Ui.text(g, info, rndX - Ui.tw(info) - 6, y + 7, Ui.DIM);
            }

            // select this slot (whole row)
            visible(cx, y, cw, 22, () -> {
                commit();
                Config.I.activeSlot = idx;
                Engine.macro = null;
                Config.save();
            });

            // RND checkbox
            button(g, rndX, y + 4, 28, 14, "RND", null, ticked ? 0xFF1F8F52 : Ui.OFF, () -> {
                commit();
                if (Config.I.slots[idx].isEmpty()) return;
                Config.I.randSel[idx] = !Config.I.randSel[idx];
                Config.save();
            });
            if (in(mx, my, rndX, y + 4, 28, 14)) hoverDesc = "Include this slot in the randomizer.";

            // REC into this slot
            button(g, recX, y + 4, 30, 14, recHere ? "STOP" : "REC", null, 0xFFB01B2E, () -> {
                commit();
                Minecraft mc = Minecraft.getInstance();
                if (Engine.mode == Engine.Mode.RECORDING) {
                    Engine.toggleRecord(mc);
                    refresh();
                } else if (Engine.mode == Engine.Mode.IDLE) {
                    Config.I.activeSlot = idx;
                    Config.save();
                    Engine.macro = null;
                    Engine.toggleRecord(mc);
                    Scr.set(mc, null);
                } else {
                    Engine.toggleRecord(mc); // prints "stop the macro first"
                }
            });
            if (in(mx, my, recX, y + 4, 30, 14)) hoverDesc = has ? "Record into this slot (overwrites the macro in it)." : "Record a macro into this slot.";

            if (has) {
                button(g, renX, y + 4, 28, 14, "Ren", null, Ui.CARD_HI, () -> {
                    commit();
                    capture = 2;
                    renameFrom = n;
                    buf = new StringBuilder(n);
                });
                boolean sure = confirmDel.equals(n);
                button(g, delX, y + 4, 28, 14, sure ? "Sure?" : "Del", null, sure ? 0xFFB01B2E : Ui.CARD_HI, () -> {
                    if (confirmDel.equals(n)) {
                        MacroStore.delete(n);
                        Config.I.slots[idx] = "";
                        Config.I.randSel[idx] = false;
                        Engine.macro = null;
                        Config.save();
                        confirmDel = "";
                        refresh();
                    } else {
                        confirmDel = n;
                    }
                });
            }
            y += 26;
        }
        refreshTicksIfNeeded();
        return y + scroll[1] - cy;
    }

    /** Picks up a slot's length right after it was (re)recorded. */
    private void refreshTicksIfNeeded() {
        for (String n : Config.I.slots) {
            if (!n.isEmpty() && !tickMap.containsKey(n)) tickMap.put(n, MacroStore.ticksOf(n));
        }
    }

    private int drawPath(GuiGraphicsExtractor g, int cx, int cy, int cw) {
        int y = cy - scroll[2];
        Minecraft mc = Minecraft.getInstance();
        boolean run = Walker.running;
        Route r = Route.cur;

        Ui.rr(g, cx, y, cw, 34, Ui.CARD);
        Ui.border(g, cx, y, cw, 34, run ? Ui.GREEN : Ui.LINE);
        Ui.text(g, "Route slot " + Cfg.I.slot + " of " + Cfg.SLOTS, cx + 8, y + 6, Ui.TEXT);
        String info = r.n() + " point" + (r.n() == 1 ? "" : "s") + (r.closed ? "  |  closed loop" : "") + (r.loop ? "  |  repeat" : "  |  once");
        Ui.text(g, info, cx + 8, y + 19, Ui.DIM);
        if (run) {
            String st = Walker.status();
            Ui.text(g, st, cx + cw - 8 - Ui.tw(st), y + 6, Ui.GREEN);
        }
        y += 40;

        int bw = (cw - 6) / 2;
        button(g, cx, y, bw, 18, "OPEN EDITOR", Ui.TARGET, Ui.CARD_HI, () -> {
            commit();
            Minecraft m = Minecraft.getInstance();
            if (m.player != null && m.level != null) Scr.set(m, new PathScreen());
        });
        button(g, cx + bw + 6, y, bw, 18, run ? "STOP PATH" : "RUN PATH", run ? Ui.STOP : Ui.PLAY, run ? 0xFFB01B2E : 0xFF1F8F52, () -> {
            commit();
            Minecraft m = Minecraft.getInstance();
            Walker.toggle(m);
            if (Walker.running) Scr.set(m, null);
        });
        if (in(mx, my, cx, y, bw, 18)) hoverDesc = "Draw a route on a 1000 x 1000 block grid and pick one of 5 route slots.";
        if (in(mx, my, cx + bw + 6, y, bw, 18)) hoverDesc = "Walk the current route with W A S D only. Cannot run together with a macro.";
        y += 26;

        Ui.text(g, "Keys: " + keyName(PathMod.K_EDIT) + " = editor, " + keyName(PathMod.K_RUN) + " = start / stop", cx + 2, y, Ui.DIM);
        y += 14;
        Ui.text(g, "The path walker never touches the camera.", cx + 2, y, Ui.DIM);
        y += 14;
        return y + scroll[2] - cy;
    }

    private int drawSettings(GuiGraphicsExtractor g, int cx, int cy, int cw) {
        int y = cy - scroll[3];
        int i = 0;
        for (Row r : rows) {
            Ui.rr(g, cx, y, cw, 18, i++ % 2 == 0 ? Ui.CARD : Ui.CARD_ALT);
            if (r instanceof Txt t) {
                Ui.text(g, t.label(), cx + 6, y + 5, Ui.TEXT);
                int fw = Math.min(210, cw - 140), fx = cx + cw - fw - 4;
                boolean ed = capture == 1;
                Ui.rr(g, fx, y + 2, fw, 14, 0xFF0F0B13);
                Ui.border(g, fx, y + 2, fw, 14, ed ? Ui.ACCENT : Ui.LINE);
                String v = ed ? buf.toString() : Config.I.failCommand;
                if (!ed && v.isEmpty()) {
                    Ui.text(g, "click to set, e.g. /home", fx + 4, y + 5, Ui.OFF);
                } else {
                    while (v.length() > 1 && Ui.tw(v) > fw - 14) v = v.substring(1);
                    Ui.text(g, v + (ed && (System.currentTimeMillis() / 400) % 2 == 0 ? "|" : ""), fx + 4, y + 5, ed ? Ui.WARN : Ui.TEXT);
                }
                visible(fx, y + 2, fw, 14, () -> {
                    commit();
                    capture = 1;
                    buf = new StringBuilder(Config.I.failCommand);
                });
                if (in(mx, my, cx, y, cw, 18)) hoverDesc = "Runs once when the macro gives up. /commands are sent as commands, anything else as chat.";
            } else if (r instanceof Num n) {
                Ui.text(g, n.label(), cx + 6, y + 5, Ui.TEXT);
                int plusX = cx + cw - 20, valX = plusX - 52, minusX = valX - 18;
                button(g, minusX, y + 2, 14, 14, "-", null, Ui.CARD_HI, () -> bump(n, -n.step()));
                Ui.ctext(g, fmt(n), valX + 26, y + 5, Ui.WARN);
                button(g, plusX, y + 2, 14, 14, "+", null, Ui.CARD_HI, () -> bump(n, n.step()));
            } else if (r instanceof Tog t) {
                Ui.text(g, t.label(), cx + 6, y + 5, Ui.TEXT);
                boolean on = t.get().getAsBoolean();
                int bx = cx + cw - 54;
                Ui.rr(g, bx, y + 3, 48, 12, on ? Ui.GREEN : Ui.OFF);
                Ui.ctext(g, on ? "ON" : "OFF", bx + 24, y + 5, on ? Ui.BLACK : Ui.TEXT);
                visible(bx, y + 3, 48, 12, () -> {
                    t.set().accept(!on);
                    Config.save();
                });
                if (in(mx, my, cx, y, cw, 18)) hoverDesc = "On: positions are compared with the recorded coordinates. Off: relative to where each run starts.";
            }
            y += 20;
        }
        return y + scroll[3] - cy;
    }

    private int drawKeys(GuiGraphicsExtractor g, int cx, int cy, int cw) {
        int y = cy - scroll[4];
        String[][] list = {
                {"Open / close this menu", keyName(Berserk.K_MENU)},
                {"Record / stop recording", keyName(Berserk.K_REC)},
                {"Play / stop macro", keyName(Berserk.K_PLAY)},
                {"Free mouse (toggle)", keyName(Berserk.K_FREE)},
                {"Open path editor", keyName(PathMod.K_EDIT)},
                {"Start / stop path", keyName(PathMod.K_RUN)},
        };
        int i = 0;
        for (String[] e : list) {
            Ui.rr(g, cx, y, cw, 18, i++ % 2 == 0 ? Ui.CARD : Ui.CARD_ALT);
            Ui.text(g, e[0], cx + 6, y + 5, Ui.TEXT);
            int w = Ui.tw(e[1]) + 14;
            Ui.rr(g, cx + cw - w - 6, y + 2, w, 14, Ui.CARD_HI);
            Ui.border(g, cx + cw - w - 6, y + 2, w, 14, Ui.ACCENT);
            Ui.ctext(g, e[1], cx + cw - w / 2 - 6, y + 5, Ui.WARN);
            y += 20;
        }
        y += 6;
        Ui.text(g, "Rebind these in Options > Controls > Key Binds > B.E.R.S.E.R.K (and B.E.R.S.E.R.K Path)", cx + 6, y, Ui.DIM);
        y += 14;
        Ui.text(g, "Printable keys do not fire while you type in chat.", cx + 6, y, Ui.DIM);
        y += 14;
        return y + scroll[4] - cy;
    }

    private static String keyName(net.minecraft.client.KeyMapping m) {
        try {
            return m.getTranslatedKeyMessage().getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ widgets

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, String[] icon, int color, Runnable r) {
        boolean hov = in(mx, my, x, y, w, h) && my >= vy0 && my < vy1;
        Ui.pill(g, x, y, w, h, label, icon, color, hov);
        visible(x, y, w, h, r);
    }

    private void visible(int x, int y, int w, int h, Runnable r) {
        if (y + h > vy0 && y < vy1) hits.add(new Hit(x, y, w, h, r));
    }

    private static boolean in(double a, double b, int x, int y, int w, int h) {
        return a >= x && a < x + w && b >= y && b < y + h;
    }

    private static String fmt(Num n) {
        double v = n.get().getAsDouble();
        return n.isInt() ? Integer.toString((int) Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static void bump(Num n, double d) {
        double v = Math.round((n.get().getAsDouble() + d) * 100.0) / 100.0;
        n.set().accept(Math.max(n.min(), Math.min(n.max(), v)));
        Config.save();
    }

    private void closeMenu() {
        commit();
        Scr.set(Minecraft.getInstance(), null);
    }

    // ------------------------------------------------------------------ input (called from Input)

    void onClick(double gx, double gy, int button) {
        if (button != 0) return;
        if (capture != 0) commit(); // clicking anywhere applies the field being edited
        String before = confirmDel;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.in(gx, gy)) {
                h.r().run();
                break;
            }
        }
        // any click that did not (re)arm the delete confirmation cancels it
        if (!before.isEmpty() && confirmDel.equals(before)) confirmDel = "";
    }

    void onScroll(double dy) {
        scroll[tab] -= (int) Math.round(dy * 18);
    }

    void onKey(int key, int sc, int action, int mods) {
        if (capture != 0) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                capture = 0;
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                commit();
            } else if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (buf.length() > 0) buf.setLength(buf.length() - 1);
            } else if (key == GLFW.GLFW_KEY_V && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
                String clip = GLFW.glfwGetClipboardString(Input.window);
                if (clip != null) {
                    for (char c : clip.toCharArray()) if (c >= 32 && buf.length() < 120) buf.append(c);
                }
            }
            return;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) closeMenu();
    }

    void onChar(int cp) {
        if (capture == 0 || cp < 32 || buf.length() >= 120) return;
        buf.appendCodePoint(cp);
    }

    /** Applies whatever text field is currently being edited. */
    private void commit() {
        if (capture == 1) {
            Config.I.failCommand = buf.toString().trim();
            Config.save();
        } else if (capture == 2) {
            String to = MacroStore.clean(buf.toString());
            if (!to.equals(renameFrom) && MacroStore.rename(renameFrom, to)) {
                for (int i = 0; i < Config.SLOTS; i++) {
                    if (Config.I.slots[i].equals(renameFrom)) Config.I.slots[i] = to;
                }
                Engine.macro = null;
                Config.save();
                refresh();
            }
        }
        capture = 0;
        buf = new StringBuilder();
    }
}
