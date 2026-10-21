package dev.berserk.path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Persistent settings (config/berserk-path/config.json). */
public final class Cfg {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Cfg I = new Cfg();

    public int slot = 1;               // active route slot (1..SLOTS)
    public boolean snap = true;        // snap clicks to block centres
    public int cmdDelay = 20;          // ticks to wait after running a point command
    public double maxStartDist = 60.0; // refuse to start if the first point is further away than this

    public static final int SLOTS = 5;

    public static Path dir() {
        try {
            Path d = FabricLoader.getInstance().getConfigDir().resolve("berserk-path");
            Files.createDirectories(d);
            return d;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void load() {
        try {
            Path p = dir().resolve("config.json");
            if (Files.exists(p)) {
                Cfg c = GSON.fromJson(Files.readString(p), Cfg.class);
                if (c != null) I = c;
            }
        } catch (Throwable t) {
            PathMod.LOG.warn("Could not read config, using defaults", t);
            I = new Cfg();
        }
        if (I.slot < 1 || I.slot > SLOTS) I.slot = 1;
        if (I.cmdDelay < 0) I.cmdDelay = 0;
    }

    public static void save() {
        try {
            Files.writeString(dir().resolve("config.json"), GSON.toJson(I));
        } catch (Throwable t) {
            PathMod.LOG.warn("Could not save config", t);
        }
    }
}
