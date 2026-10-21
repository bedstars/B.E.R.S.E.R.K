package dev.berserk;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import dev.berserk.path.Walker;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Recording + playback.
 *
 * Timing model (this is what keeps camera + inputs in sync):
 *   START of tick k : sample world state  -> frames[k]      (position, camera yaw/pitch, screen)
 *   between ticks   : real input events   -> events with t = k
 *   playback        : START of tick p sets the camera to frames[p]; END of tick p fires events t<=p.
 * The camera is replayed from absolute samples and the game interpolates between them, so mouse
 * movement never has to be "simulated".
 */
public final class Engine {
    public enum Mode { IDLE, RECORDING, PLAYING }

    private enum Phase { BEGIN, ALIGN, RUN, RECOVER, DELAY }

    public static Mode mode = Mode.IDLE;
    public static Macro macro;
    public static Macro rec;
    public static int recTick;
    public static int recSlot = 0;   // slot the current recording goes into
    public static int curSlot = 0;   // slot of the macro that is playing
    private static final Random RNG = new Random();
    private static double lastFx = -1, lastFy = -1;

    private static Phase phase = Phase.BEGIN;
    private static int p, evIdx, stall, iteration, failures, phaseTicks, settle, escTries;
    private static double sx, sy, sz, firstX, firstY, firstZ, prevX, prevY, prevZ;
    private static double tx, tz;
    private static int alignTicks, alignStable;
    private static boolean haveFirst, havePrev, wasNoScreen = true;
    private static float lastHealth;
    private static final Set<Long> held = new LinkedHashSet<>();
    private static String status = "Idle";

    private static Field pauseField;
    private static boolean pauseLooked, pauseSaved, pauseOrig;

    private Engine() {}

    // ================================================================== tick hooks

    public static void onStartTick(Minecraft mc) {
        Input.ensure();
        if (!Input.installed) return;
        Input.tickFree();
        refreshBackground(mc);

        if (mc.player == null || mc.level == null) {
            if (mode == Mode.RECORDING) stopRecord(mc);
            if (mode == Mode.PLAYING) stopPlayback(mc, "World lost", false);
            return;
        }

        if (Config.I.mSprint && Scr.get(mc) == null && mc.options.keyUp.isDown()) {
            mc.options.keySprint.setDown(true);
        }

        if (mode == Mode.RECORDING) recordTick(mc);
        else if (mode == Mode.PLAYING) playTick(mc);
    }

    public static void onEndTick(Minecraft mc) {
        if (mode != Mode.PLAYING || phase != Phase.RUN || macro == null) return;
        if (Scr.get(mc) instanceof BerserkScreen) return;
        List<Macro.Ev> ev = macro.events;
        while (evIdx < ev.size() && ev.get(evIdx).t <= p) fire(mc, ev.get(evIdx++));
    }

    // ================================================================== UI actions

    public static void toggleMenu(Minecraft mc) {
        if (Scr.get(mc) instanceof BerserkScreen) Scr.set(mc, null);
        else if (Scr.get(mc) == null || Scr.get(mc) instanceof ChatScreen) Scr.set(mc, new BerserkScreen());
    }

    public static void toggleRecord(Minecraft mc) {
        if (mode == Mode.RECORDING) stopRecord(mc);
        else if (mode == Mode.IDLE) startRecord(mc);
        else Msg.overlay(mc, Msg.S + "cStop the macro first");
    }

    public static void togglePlay(Minecraft mc) {
        if (mode == Mode.PLAYING) stopPlayback(mc, "Stopped", false);
        else if (mode == Mode.IDLE) startPlayback(mc);
        else Msg.overlay(mc, Msg.S + "cStop recording first");
    }

    public static String statusLine() {
        switch (mode) {
            case RECORDING:
                return "Recording... " + recTick + " ticks, " + (rec == null ? 0 : rec.events.size()) + " events";
            case PLAYING:
                return (macro == null ? "" : macro.name) + (Config.I.randomize ? " (random)" : "") + " | run " + (iteration + 1) + " | fails " + failures + " | " + status;
            default:
                if (Config.I.randomize) return "Randomize ON - " + randPool().size() + " macro(s) ticked";
                return Config.selectedName().isEmpty() ? "Slot " + (Config.I.activeSlot + 1) + " is empty - press REC" : "Slot " + (Config.I.activeSlot + 1) + ": " + Config.selectedName();
        }
    }

    public static String hudLine() {
        if (mode == Mode.RECORDING) return "REC  " + String.format(Locale.ROOT, "%.1fs", recTick / 20.0) + "  " + (rec == null ? 0 : rec.events.size()) + " ev";
        if (mode == Mode.PLAYING) return "PLAY  S" + (curSlot + 1) + " " + (macro == null ? "" : macro.name) + "  #" + (iteration + 1) + "  F" + failures + "  " + status;
        return "";
    }

    // ================================================================== recording

    private static Macro.Frame sample(Minecraft mc) {
        LocalPlayer pl = mc.player;
        Macro.Frame f = new Macro.Frame();
        f.x = pl.getX();
        f.y = pl.getY();
        f.z = pl.getZ();
        f.yaw = pl.getYRot();
        f.pitch = pl.getXRot();
        f.hp = pl.getHealth();
        f.screen = screenName(mc);
        return f;
    }

    static String screenName(Minecraft mc) {
        Screen s = Scr.get(mc);
        if (s == null || s instanceof BerserkScreen) return "";
        return s.getClass().getSimpleName();
    }

    private static void startRecord(Minecraft mc) {
        if (mc.player == null) return;
        if (Walker.running) {
            Msg.overlay(mc, Msg.S + "cStop the path first");
            return;
        }
        recSlot = Config.I.activeSlot;
        rec = new Macro();
        rec.frames.add(sample(mc));
        recTick = 0;
        lastFx = lastFy = -1;
        mode = Mode.RECORDING;
        Msg.overlay(mc, Msg.S + "c\u25cf Recording slot " + (recSlot + 1) + Msg.S + "7 - press the record key again to stop");
    }

    private static void recordTick(Minecraft mc) {
        recTick++;
        rec.frames.add(sample(mc));
        if (Scr.get(mc) != null && !(Scr.get(mc) instanceof BerserkScreen)) {
            double[] c = Input.cursor();
            int[] w = Input.winSize();
            double fx = c[0] / w[0], fy = c[1] / w[1];
            if (fx != lastFx || fy != lastFy) {
                Macro.Ev e = ev(Macro.CURSOR);
                e.fx = fx;
                e.fy = fy;
                rec.events.add(e);
                lastFx = fx;
                lastFy = fy;
            }
        }
    }

    private static Macro.Ev ev(int type) {
        Macro.Ev e = new Macro.Ev();
        e.t = recTick;
        e.type = type;
        return e;
    }

    static void recKey(int key, int sc, int action, int mods) {
        if (rec == null) return;
        Macro.Ev e = ev(Macro.KEY);
        e.a = key;
        e.b = sc;
        e.c = action;
        e.d = mods;
        rec.events.add(e);
    }

    static void recChar(int cp) {
        if (rec == null) return;
        Macro.Ev e = ev(Macro.CHAR);
        e.a = cp;
        rec.events.add(e);
    }

    static void recMouse(int button, int action, int mods, double fx, double fy) {
        if (rec == null) return;
        Macro.Ev e = ev(Macro.MOUSE);
        e.a = button;
        e.c = action;
        e.d = mods;
        e.fx = fx;
        e.fy = fy;
        rec.events.add(e);
    }

    static void recScroll(double dx, double dy) {
        if (rec == null) return;
        Macro.Ev e = ev(Macro.SCROLL);
        e.fx = dx;
        e.fy = dy;
        rec.events.add(e);
    }

    private static void stopRecord(Minecraft mc) {
        if (mode != Mode.RECORDING) return;
        mode = Mode.IDLE;
        Macro m = rec;
        rec = null;
        if (m == null) return;
        trim(m);
        if (m.frames.size() < 3 && m.events.isEmpty()) {
            Msg.overlay(mc, Msg.S + "cRecording too short - discarded");
            return;
        }
        m.ticks = m.frames.size() - 1;
        String nm = Config.I.slots[recSlot];
        if (nm.isEmpty()) nm = "Macro " + (recSlot + 1);
        while (Config.nameUsed(nm, recSlot)) nm = nm + "-" + new SimpleDateFormat("HHmmss").format(new Date());
        m.name = MacroStore.clean(nm);
        MacroStore.save(m);          // re-recording a slot overwrites its file
        macro = m;
        curSlot = recSlot;
        Config.I.slots[recSlot] = m.name;
        Config.save();
        Msg.overlay(mc, Msg.S + "a\u25a0 Saved to slot " + (recSlot + 1) + " " + Msg.S + "f" + m.name + Msg.S + "7 (" + m.ticks + " ticks, " + m.events.size() + " events)");
    }

    /** Cuts the chat/menu you used to press REC / STOP out of the recording, plus dead time at both ends. */
    private static void trim(Macro m) {
        List<Macro.Frame> f = m.frames;
        if (f.isEmpty()) return;

        if (f.get(0).screen.equals("ChatScreen")) {
            int i = 0;
            while (i < f.size() && f.get(i).screen.equals("ChatScreen")) i++;
            cutHead(m, i);
        }
        if (m.frames.isEmpty()) return;
        f = m.frames;
        if (f.get(f.size() - 1).screen.equals("ChatScreen")) {
            int j = f.size() - 1;
            while (j >= 0 && f.get(j).screen.equals("ChatScreen")) j--;
            keepUpTo(m, j);
        }
        if (m.frames.size() < 2) return;
        f = m.frames;

        int first = Integer.MAX_VALUE, last = -1;
        for (Macro.Ev e : m.events) {
            first = Math.min(first, e.t);
            last = Math.max(last, e.t);
        }
        for (int i = 1; i < f.size(); i++) {
            Macro.Frame a = f.get(i - 1), b = f.get(i);
            boolean moved = Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.z - b.z) > 1e-4
                    || Math.abs(a.yaw - b.yaw) > 1e-3 || Math.abs(a.pitch - b.pitch) > 1e-3;
            if (moved) {
                first = Math.min(first, i - 1);
                last = Math.max(last, i);
            }
        }
        if (last < 0) return;
        keepUpTo(m, Math.min(f.size() - 1, last + 1));
        cutHead(m, Math.max(0, first - 1));
    }

    private static void cutHead(Macro m, int n) {
        if (n <= 0) return;
        n = Math.min(n, m.frames.size());
        m.frames.subList(0, n).clear();
        final int cut = n;
        m.events.removeIf(e -> e.t < cut);
        for (Macro.Ev e : m.events) e.t -= cut;
    }

    private static void keepUpTo(Macro m, int j) {
        if (j < 0) {
            m.frames.clear();
            m.events.clear();
            return;
        }
        if (j + 1 < m.frames.size()) m.frames.subList(j + 1, m.frames.size()).clear();
        m.events.removeIf(e -> e.t > j);
    }

    // ================================================================== playback

    private static void startPlayback(Minecraft mc) {
        if (mc.player == null) return;
        if (Walker.running) {
            Msg.overlay(mc, Msg.S + "cStop the path first");
            return;
        }
        Macro m;
        if (Config.I.randomize) {
            m = pickRandom();
            if (m == null) {
                Msg.overlay(mc, Msg.S + "cRandomize is on - tick at least one recorded macro in the MACROS tab");
                return;
            }
        } else {
            String n = Config.selectedName();
            m = (macro != null && macro.name.equals(n)) ? macro : (n.isEmpty() ? null : MacroStore.load(n));
            curSlot = Config.I.activeSlot;
            if (m == null || m.frames.size() < 2) {
                Msg.overlay(mc, Msg.S + "cSlot " + (Config.I.activeSlot + 1) + " is empty - record a macro into it first");
                return;
            }
        }
        macro = m;
        mode = Mode.PLAYING;
        phase = Phase.BEGIN;
        iteration = 0;
        failures = 0;
        haveFirst = false;
        phaseTicks = 0;
        held.clear();
        status = "starting";
        Msg.overlay(mc, Msg.S + "a\u25b6 Playing " + Msg.S + "f" + m.name);
        if (Scr.get(mc) instanceof BerserkScreen) Scr.set(mc, null);
    }

    static void stopPlayback(Minecraft mc, String why, boolean runCommand) {
        if (mode != Mode.PLAYING) return;
        mode = Mode.IDLE;
        releaseHeld();
        KeyMapping.releaseAll();
        Input.resync();
        status = why;
        Msg.overlay(mc, Msg.S + (runCommand ? "c" : "7") + "\u25a0 " + why);
        if (runCommand) {
            Msg.chat(mc, Msg.S + "c[B.E.R.S.E.R.K] " + Msg.S + "7" + why);
            String cmd = Config.I.failCommand;
            if (cmd != null && !cmd.isBlank()) Msg.send(mc, cmd);
        }
    }

    private static void playTick(Minecraft mc) {
        if (Scr.get(mc) instanceof BerserkScreen) {
            status = "paused (menu open)";
            return;
        }
        switch (phase) {
            case BEGIN -> startIteration(mc);
            case ALIGN -> alignTick(mc);
            case RECOVER -> recoverTick(mc);
            case DELAY -> {
                status = "next loop in " + Math.max(0, Config.I.loopDelay - phaseTicks) + "t";
                if (++phaseTicks >= Config.I.loopDelay) startIteration(mc);
            }
            case RUN -> runTick(mc);
        }
    }

    private static void startIteration(Minecraft mc) {
        if (Scr.get(mc) != null) { // a menu is still open: press ESC until it is gone (not counted as a failure)
            phase = Phase.RECOVER;
            phaseTicks = 0;
            settle = 0;
            escTries = 0;
            status = "closing menu";
            return;
        }
        LocalPlayer pl = mc.player;
        sx = pl.getX();
        sy = pl.getY();
        sz = pl.getZ();
        Macro.Frame f0 = macro.frames.get(0);

        if (Config.I.mStartCheck) {
            double d = 0;
            if (Config.I.absolutePos) d = dist(sx, sy, sz, f0.x, f0.y, f0.z);
            else if (haveFirst) d = dist(sx, sy, sz, firstX, firstY, firstZ);
            if (d > Config.I.startTolerance) {
                fail(mc, String.format(Locale.ROOT, "start position is %.1f blocks off", d));
                return;
            }
        }
        final boolean haveFirstBefore = haveFirst;
        if (!haveFirst) {
            firstX = sx;
            firstY = sy;
            firstZ = sz;
            haveFirst = true;
        }

        // line the player up with the recorded start (coords + camera) before the run begins
        if (Config.I.mAlign) {
            boolean haveTarget = true;
            if (Config.I.absolutePos) {
                tx = f0.x;
                tz = f0.z;
            } else if (haveFirstBefore) {
                tx = firstX;
                tz = firstZ;
            } else {
                haveTarget = false;
            }
            applyRot(pl, f0, true);
            double off = Math.hypot(tx - pl.getX(), tz - pl.getZ());
            if (haveTarget && off > Config.I.alignTolerance && off <= Math.max(Config.I.startTolerance, 4.0)) {
                alignTicks = 0;
                alignStable = 0;
                phase = Phase.ALIGN;
                status = "aligning to start";
                return;
            }
        }
        beginRun(mc);
    }

    /** Walks (W/A/S/D only, camera stays on the recorded start angle) back onto the recorded start spot. */
    private static void alignTick(Minecraft mc) {
        LocalPlayer pl = mc.player;
        if (Scr.get(mc) != null) { // a menu popped up: let the normal start logic close it
            releaseMove(mc);
            phase = Phase.BEGIN;
            return;
        }
        Macro.Frame f0 = macro.frames.get(0);
        applyRot(pl, f0, true); // camera back to exactly where the recording started

        double tol = Config.I.alignTolerance;
        net.minecraft.world.phys.Vec3 v = pl.getDeltaMovement();
        // where the player will coast to if no key is pressed (ground friction ~1.2x velocity)
        double dx = tx - (pl.getX() + v.x * 1.2);
        double dz = tz - (pl.getZ() + v.z * 1.2);
        double rx = tx - pl.getX(), rz = tz - pl.getZ();
        double dist = Math.hypot(rx, rz);
        double speed = Math.hypot(v.x, v.z);

        if (dist <= tol && speed < 0.02) {
            if (++alignStable >= 2) {
                releaseMove(mc);
                beginRun(mc);
                return;
            }
        } else {
            alignStable = 0;
        }
        if (++alignTicks > Config.I.alignTimeout) { // could not get there (blocked?) - run anyway, Setback Guard decides
            releaseMove(mc);
            beginRun(mc);
            return;
        }

        double yaw = Math.toRadians(pl.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // forward
        double rgx = -Math.cos(yaw), rgz = -Math.sin(yaw); // player's right
        double fwd = dx * fx + dz * fz;
        double rgt = dx * rgx + dz * rgz;
        double thr = Math.max(0.02, tol * 0.7);

        mc.options.keyUp.setDown(fwd > thr);
        mc.options.keyDown.setDown(fwd < -thr);
        mc.options.keyRight.setDown(rgt > thr);
        mc.options.keyLeft.setDown(rgt < -thr);
        mc.options.keyShift.setDown(dist < 0.7); // sneak for the last bit = fine control
        mc.options.keySprint.setDown(false);
        status = String.format(Locale.ROOT, "aligning (%.2f off)", dist);
    }

    private static void releaseMove(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyShift.setDown(false);
    }

    private static void beginRun(Minecraft mc) {
        LocalPlayer pl = mc.player;
        Macro.Frame f0 = macro.frames.get(0);
        sx = pl.getX();
        sy = pl.getY();
        sz = pl.getZ();
        p = 0;
        evIdx = 0;
        stall = 0;
        havePrev = false;
        wasNoScreen = true;
        lastHealth = pl.getHealth();
        held.clear();
        KeyMapping.releaseAll();
        applyRot(pl, f0, true);
        phase = Phase.RUN;
        status = "running";
    }

    private static void runTick(Minecraft mc) {
        LocalPlayer pl = mc.player;
        boolean noScreen = Scr.get(mc) == null;
        if (noScreen && !wasNoScreen) reapplyHeld(); // vanilla re-polls keys when a screen closes
        wasNoScreen = noScreen;

        int next = p + 1;
        if (next >= macro.frames.size()) {
            completeIteration(mc);
            return;
        }
        Macro.Frame f = macro.frames.get(next);

        if (Config.I.mScreenSync && !f.screen.equals(screenName(mc))) {
            havePrev = false;
            status = "waiting for " + (f.screen.isEmpty() ? "menu to close" : f.screen);
            if (++stall > Config.I.screenTimeout) {
                fail(mc, "expected " + (f.screen.isEmpty() ? "no screen" : f.screen) + " but got " + (Scr.get(mc) == null ? "none" : screenName(mc)));
            }
            return;
        }
        stall = 0;
        p = next;
        if (noScreen) applyRot(pl, f, false);

        double ax = pl.getX(), ay = pl.getY(), az = pl.getZ();

        if (Config.I.mSetback) {
            Macro.Frame f0 = macro.frames.get(0);
            double ex, ey, ez;
            if (Config.I.absolutePos) {
                ex = f.x;
                ey = f.y;
                ez = f.z;
            } else {
                ex = sx + (f.x - f0.x);
                ey = sy + (f.y - f0.y);
                ez = sz + (f.z - f0.z);
            }
            if (havePrev) {
                Macro.Frame pf = macro.frames.get(p - 1);
                double err = dist(ax - prevX, ay - prevY, az - prevZ, f.x - pf.x, f.y - pf.y, f.z - pf.z);
                if (err > Config.I.setbackDist) {
                    fail(mc, "setback detected");
                    return;
                }
            }
            double drift = dist(ax, ay, az, ex, ey, ez);
            if (drift > Config.I.maxDrift) {
                fail(mc, String.format(Locale.ROOT, "drifted %.1f blocks off the recorded path", drift));
                return;
            }
        }

        float hp = pl.getHealth();
        if (Config.I.mDamage && lastHealth - hp >= Config.I.damageThreshold) {
            lastHealth = hp;
            fail(mc, "took damage");
            return;
        }
        lastHealth = hp;

        prevX = ax;
        prevY = ay;
        prevZ = az;
        havePrev = true;
        status = "tick " + p + "/" + (macro.frames.size() - 1);
    }

    private static void completeIteration(Minecraft mc) {
        releaseHeld();
        iteration++;
        failures = 0;
        if (!Config.I.mLoop || (Config.I.loops > 0 && iteration >= Config.I.loops)) {
            stopPlayback(mc, "Finished " + iteration + " run" + (iteration == 1 ? "" : "s"), false);
            return;
        }
        if (Config.I.randomize) { // only here, when a macro has fully finished, do we roll the next one
            Macro next = pickRandom();
            if (next != null) macro = next;
        }
        phase = Phase.DELAY;
        phaseTicks = 0;
    }

    /** Slots that are ticked for the randomizer and actually hold a macro. */
    static List<Integer> randPool() {
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < Config.SLOTS; i++) if (Config.I.randSel[i] && !Config.I.slots[i].isEmpty()) pool.add(i);
        return pool;
    }

    private static Macro pickRandom() {
        List<Integer> pool = randPool();
        while (!pool.isEmpty()) {
            int k = pool.remove(RNG.nextInt(pool.size()));
            Macro m = MacroStore.load(Config.I.slots[k]);
            if (m != null && m.frames.size() >= 2) {
                curSlot = k;
                return m;
            }
        }
        return null;
    }

    private static void fail(Minecraft mc, String reason) {
        releaseHeld();
        KeyMapping.releaseAll();
        failures++;
        Msg.chat(mc, Msg.S + "c[B.E.R.S.E.R.K] " + Msg.S + "7failure " + failures + "/" + (Config.I.maxRetries + 1) + ": " + reason);
        if (!Config.I.mFailsafes || failures > Config.I.maxRetries) {
            stopPlayback(mc, "Stopped after repeated failures (" + reason + ")", true);
            return;
        }
        phase = Phase.RECOVER;
        phaseTicks = 0;
        settle = 0;
        escTries = 0;
        status = "recovering";
    }

    private static void recoverTick(Minecraft mc) {
        phaseTicks++;
        if (Scr.get(mc) != null) {
            settle = 0;
            if (phaseTicks % 3 == 1) {
                Input.tapKey(256); // ESC
                if (++escTries > 12) stopPlayback(mc, "Could not close menu", true);
            }
            return;
        }
        if (++settle >= 8) startIteration(mc);
    }

    // ================================================================== replay primitives

    private static void fire(Minecraft mc, Macro.Ev e) {
        switch (e.type) {
            case Macro.KEY -> {
                Input.synKey(e.a, e.b, e.c, e.d);
                long k = (long) e.a;
                if (e.c == 1) held.add(k);
                else if (e.c == 0) held.remove(k);
            }
            case Macro.CHAR -> Input.synChar(e.a);
            case Macro.MOUSE -> {
                if (Scr.get(mc) != null) Input.synCursorFrac(e.fx, e.fy);
                Input.synMouse(e.a, e.c, e.d);
                long k = (1L << 32) | (e.a & 0xFFFFFFFFL);
                if (e.c == 1) held.add(k);
                else if (e.c == 0) held.remove(k);
            }
            case Macro.SCROLL -> Input.synScroll(e.fx, e.fy);
            case Macro.CURSOR -> {
                if (Scr.get(mc) != null) Input.synCursorFrac(e.fx, e.fy);
            }
            default -> {
            }
        }
    }

    private static void releaseHeld() {
        for (long k : held.toArray(new Long[0])) {
            int type = (int) (k >>> 32);
            int code = (int) k;
            if (type == 0) Input.synKey(code, 0, 0, 0);
            else Input.synMouse(code, 0, 0);
        }
        held.clear();
    }

    private static void reapplyHeld() {
        for (long k : held.toArray(new Long[0])) {
            int type = (int) (k >>> 32);
            int code = (int) k;
            if (type == 0) Input.synKey(code, 0, 1, 0);
            else Input.synMouse(code, 1, 0);
        }
    }

    private static void applyRot(LocalPlayer pl, Macro.Frame f, boolean snap) {
        float cy = pl.getYRot();
        float ny = cy + wrap(f.yaw - cy); // shortest way round, never a 360 degree spin
        float np = Math.max(-90f, Math.min(90f, f.pitch));
        float cp = pl.getXRot();
        pl.yRotO = snap ? ny : cy;
        pl.xRotO = snap ? np : cp;
        pl.setYRot(ny);
        pl.setXRot(np);
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ================================================================== background running

    /** While a macro plays (or the mouse is free) the game must not pause when the window loses focus. */
    private static void refreshBackground(Minecraft mc) {
        if (!Config.I.mBackground) return;
        boolean on = mode == Mode.PLAYING || Walker.running || Input.freeMouse;
        try {
            if (!pauseLooked) {
                pauseLooked = true;
                pauseField = mc.options.getClass().getField("pauseOnLostFocus");
            }
            if (pauseField == null) return;
            if (on) {
                if (!pauseSaved) {
                    pauseOrig = pauseField.getBoolean(mc.options);
                    pauseSaved = true;
                }
                pauseField.setBoolean(mc.options, false);
            } else if (pauseSaved) {
                pauseField.setBoolean(mc.options, pauseOrig);
                pauseSaved = false;
            }
        } catch (Throwable ignored) {
            pauseField = null;
        }
    }
}
