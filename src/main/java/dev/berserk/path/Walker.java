package dev.berserk.path;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import dev.berserk.Engine;

import java.util.Locale;

/**
 * Walks the route using ONLY the movement keys (W/A/S/D + sprint). The camera is never touched.
 *
 * Every tick the walker looks at where the player is relative to the drawn line, picks a point a
 * little further along that line, and presses whichever of the 8 key combinations moves the
 * player closest to that direction, given the camera yaw the player currently has. Because it
 * re-checks every tick it stays glued to the line whatever the camera yaw is.
 */
public final class Walker {
    private Walker() {}

    public static boolean running;

    private static int target;       // index of the point we are walking to
    private static int dir = 1;      // +1 forwards, -1 backwards (open route, looping = ping-pong)
    private static int lap;
    private static boolean started;  // reached the START at least once
    private static int wait;         // ticks to idle (after running a command)
    private static double ax, az;    // where the current leg began
    private static boolean pressed;  // we are holding keys
    private static boolean paused;
    private static double ex, ez;    // direction error carried to the next tick (error diffusion)

    // ------------------------------------------------------------------ control

    public static void toggle(Minecraft mc) {
        if (running) stop(mc, "Path stopped");
        else start(mc);
    }

    public static void start(Minecraft mc) {
        Route r = Route.cur;
        if (mc.player == null || mc.level == null) return;
        if (Engine.mode != Engine.Mode.IDLE) {
            Msg.overlay(mc, Msg.S + "cStop the macro first");
            return;
        }
        if (r.n() < 2) {
            Msg.overlay(mc, Msg.S + "cDraw a route with at least 2 points first (K)");
            return;
        }
        Route.Pt p0 = r.pts.get(0);
        double d = Math.hypot(p0.x - mc.player.getX(), p0.z - mc.player.getZ());
        if (d > Cfg.I.maxStartDist) {
            Msg.overlay(mc, Msg.S + String.format(Locale.ROOT, "cStart point is %.0f blocks away - stand closer (max %.0f)", d, Cfg.I.maxStartDist));
            return;
        }
        target = 0;
        dir = 1;
        lap = 0;
        started = false;
        wait = 0;
        paused = false;
        ex = 0;
        ez = 0;
        ax = mc.player.getX();
        az = mc.player.getZ();
        running = true;
        Msg.overlay(mc, Msg.S + "aPath started " + Msg.S + "7- press the run key again to stop");
    }

    public static void stop(Minecraft mc, String why) {
        boolean was = running;
        running = false;
        release(mc);
        if (was && mc != null && why != null) Msg.overlay(mc, Msg.S + "e" + why);
    }

    public static String status() {
        Route r = Route.cur;
        String s = "PATH  P" + (target + 1) + "/" + r.n();
        if (r.loop) s += "  lap " + (lap + 1);
        if (paused) s += "  (paused)";
        return s;
    }

    // ------------------------------------------------------------------ keys

    private static void release(Minecraft mc) {
        if (!pressed || mc == null || mc.options == null) {
            pressed = false;
            return;
        }
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        pressed = false;
    }

    private static void press(Minecraft mc, int forward, int strafe, boolean sprint) {
        mc.options.keyUp.setDown(forward == 1);
        mc.options.keyDown.setDown(forward == -1);
        mc.options.keyRight.setDown(strafe == 1);
        mc.options.keyLeft.setDown(strafe == -1);
        mc.options.keySprint.setDown(sprint && forward == 1);
        pressed = true;
    }

    /**
     * Pick the key combination that moves the player closest to the world direction (dx, dz).
     * Only 8 directions exist, so the part of the wanted direction a tick could not deliver is
     * carried over to the next tick (error diffusion). Averaged over a few ticks the player then
     * moves in exactly the wanted direction, even when the camera yaw is not a multiple of 45.
     */
    private static void steer(Minecraft mc, double dx, double dz, boolean sprint) {
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            release(mc);
            return;
        }
        dx /= len;
        dz /= len;

        double tx = dx + ex, tz = dz + ez;

        double yaw = Math.toRadians(mc.player.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // forward
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);  // right hand side

        int bf = 0, bs = 0;
        double bcx = 0, bcz = 0;
        double best = -1e9;
        for (int f = -1; f <= 1; f++) {
            for (int s = -1; s <= 1; s++) {
                if (f == 0 && s == 0) continue;
                double cx = f * fx + s * rx, cz = f * fz + s * rz;
                double cl = Math.hypot(cx, cz);
                cx /= cl;
                cz /= cl;
                double score = cx * tx + cz * tz;
                if (f == 1) score += 0.02; // tie-break towards forward so sprinting stays possible
                if (score > best) {
                    best = score;
                    bf = f;
                    bs = s;
                    bcx = cx;
                    bcz = cz;
                }
            }
        }
        ex = Math.max(-1.5, Math.min(1.5, tx - bcx));
        ez = Math.max(-1.5, Math.min(1.5, tz - bcz));
        press(mc, bf, bs, sprint);
    }

    // ------------------------------------------------------------------ tick

    public static void tick(Minecraft mc) {
        if (!running) return;
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) {
            stop(mc, "Path stopped (world lost)");
            return;
        }
        Route r = Route.cur;
        if (r.n() < 2 || target >= r.n()) {
            stop(mc, "Path stopped (route changed)");
            return;
        }

        // any screen (chat, inventory, the editor...) pauses the walk and lets go of the keys
        if (mc.screen != null) {
            paused = true;
            release(mc);
            return;
        }
        paused = false;

        if (wait > 0) {
            wait--;
            release(mc);
            return;
        }

        Route.Pt t = r.pts.get(target);
        boolean precise = !t.cmd.isBlank();

        double px = mc.player.getX(), pz = mc.player.getZ();
        double dist = Math.hypot(t.x - px, t.z - pz);
        Vec3 v = mc.player.getDeltaMovement();
        double speed = v.horizontalDistance();

        double stopDist = precise ? 0.30 : 0.40;
        if (dist <= stopDist) {
            if (!precise || speed < 0.03) {
                arrive(mc);
                return;
            }
            release(mc); // let the player come to rest on the exact spot before running the command
            return;
        }

        // ---- follow the drawn line from (ax, az) to the target point
        double sx = t.x - ax, sz = t.z - az;
        double len = Math.hypot(sx, sz);
        double dx, dz;
        if (len < 1e-6) {
            dx = t.x - px;
            dz = t.z - pz;
        } else {
            double ux = sx / len, uz = sz / len;
            double rx = px - ax, rz = pz - az;
            double proj = rx * ux + rz * uz;
            double cross = Math.abs(rx * uz - rz * ux);
            double look = Math.max(0.5, (r.sprint ? 2.0 : 1.2) - cross * 0.5);
            double q = Math.max(0, Math.min(len, proj + look));
            dx = ax + ux * q - px;
            dz = az + uz * q - pz;
        }

        boolean slow = (precise && dist < 3.0) || (dist < 1.8 && sharpTurn(r, target));
        steer(mc, dx, dz, r.sprint && !slow);
    }

    /** True when the route turns sharply after point {@code i} (so we should not sprint into the corner). */
    private static boolean sharpTurn(Route r, int i) {
        int n = r.n();
        int next;
        if (r.closed) next = (i + 1) % n;
        else if (dir == 1) next = i + 1;
        else next = i - 1;
        if (next < 0 || next >= n) return true; // the route ends / reverses here
        Route.Pt a = r.pts.get(i), b = r.pts.get(next);
        double ox = a.x - ax, oz = a.z - az;
        double nx = b.x - a.x, nz = b.z - a.z;
        double lo = Math.hypot(ox, oz), ln = Math.hypot(nx, nz);
        if (lo < 1e-6 || ln < 1e-6) return false;
        return (ox * nx + oz * nz) / (lo * ln) < 0.8; // turns by more than ~37 degrees
    }

    // ------------------------------------------------------------------ arrival

    private static void arrive(Minecraft mc) {
        Route r = Route.cur;
        int n = r.n();
        int i = target;
        Route.Pt t = r.pts.get(i);

        // the next leg starts from the exact drawn point, so the line stays the line
        ax = t.x;
        az = t.z;

        boolean finish = false;
        if (r.closed) {
            if (i == 0 && started) {
                lap++;
                if (!r.loop) finish = true;
            }
            started = true;
            target = (i + 1) % n;
        } else if (dir == 1) {
            if (i == n - 1) {
                if (!r.loop) finish = true;
                else {
                    dir = -1;
                    target = n - 2;
                }
            } else {
                target = i + 1;
            }
            started = true;
        } else {
            if (i == 0) {
                lap++;
                dir = 1;
                target = 1;
            } else {
                target = i - 1;
            }
        }

        ex = 0;
        ez = 0;
        release(mc);
        if (!t.cmd.isBlank()) {
            Msg.send(mc, t.cmd);
            wait = Math.max(1, Cfg.I.cmdDelay);
        }
        if (finish) stop(mc, "Path finished");
    }
}
