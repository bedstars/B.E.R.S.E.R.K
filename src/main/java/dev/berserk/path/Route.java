package dev.berserk.path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A drawn route. Points are ABSOLUTE world coordinates (x, z).
 * The first point is the START, the last is the END. If {@code closed} is set the END is
 * connected back to the START, so start and end are the same point.
 */
public final class Route {
    /** The grid reaches this many blocks in every direction from the origin (1000 x 1000 in total). */
    public static final double HALF = 500.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Route cur = new Route();

    public int version = 1;
    public boolean hasOrigin;
    public double originX, originZ;
    public boolean closed;
    public boolean sprint = true;
    public boolean loop = true;
    public List<Pt> pts = new ArrayList<>();

    public static final class Pt {
        public double x, z;
        public String cmd = "";
        /** Camera to ease to once this point is reached. null = leave that axis alone. */
        public Double yaw, pitch;

        public Pt() {}

        public Pt(double x, double z) {
            this.x = x;
            this.z = z;
        }
    }

    /** Wraps an angle into [-180, 180). */
    public static double wrapYaw(double a) {
        a %= 360.0;
        if (a >= 180.0) a -= 360.0;
        if (a < -180.0) a += 360.0;
        return a;
    }

    public int n() {
        return pts.size();
    }

    /** Number of segments (a closed route has one more: END -> START). */
    public int segs() {
        int n = pts.size();
        if (n < 2) return 0;
        return closed ? n : n - 1;
    }

    public Pt a(int seg) {
        return pts.get(seg);
    }

    public Pt b(int seg) {
        return pts.get((seg + 1) % pts.size());
    }

    public String role(int i) {
        int n = pts.size();
        if (closed && i == 0) return "START / END";
        if (i == 0) return "START";
        if (i == n - 1 && !closed) return "END";
        return "POINT";
    }

    public double length() {
        double s = 0;
        for (int i = 0; i < segs(); i++) s += Math.hypot(b(i).x - a(i).x, b(i).z - a(i).z);
        return s;
    }

    public double clampX(double x) {
        return Math.max(originX - HALF, Math.min(originX + HALF, x));
    }

    public double clampZ(double z) {
        return Math.max(originZ - HALF, Math.min(originZ + HALF, z));
    }

    // ------------------------------------------------------------------ storage

    private static Path file(int slot) {
        return Cfg.dir().resolve("route_" + slot + ".json");
    }

    public static void load(int slot) {
        Route r = null;
        try {
            Path p = file(slot);
            if (Files.exists(p)) r = GSON.fromJson(Files.readString(p), Route.class);
        } catch (Throwable t) {
            PathMod.LOG.warn("Could not read route slot " + slot, t);
        }
        if (r == null) r = new Route();
        if (r.pts == null) r.pts = new ArrayList<>();
        for (Pt p : r.pts) if (p.cmd == null) p.cmd = "";
        if (r.pts.size() < 3) r.closed = false;
        cur = r;
    }

    public static void save() {
        try {
            Files.writeString(file(Cfg.I.slot), GSON.toJson(cur));
        } catch (Throwable t) {
            PathMod.LOG.warn("Could not save route", t);
        }
    }
}
