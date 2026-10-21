package dev.berserk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Persistent settings (config/berserk/config.json). */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Config I = new Config();

    // ---- settings ----
    public String failCommand = "";
    public int maxRetries = 3;
    public int loops = 0;            // 0 = infinite
    public int loopDelay = 20;       // ticks between iterations
    public int screenTimeout = 40;   // ticks to wait for an expected screen
    public double maxDrift = 8.0;    // blocks away from the recorded path before it counts as desync
    public double setbackDist = 3.0; // sudden position error in one tick = setback
    public double startTolerance = 4.0;
    public double damageThreshold = 4.0;
    public boolean absolutePos = true;
    public double alignTolerance = 0.1; // blocks; how close the auto-align gets you to the recorded start
    public int alignTimeout = 80;       // ticks before auto-align gives up and just runs
    public static final int SLOTS = 5;
    public String[] slots = {"", "", "", "", ""};     // macro file name stored in each slot
    public boolean[] randSel = new boolean[SLOTS];    // slots included in the randomizer
    public boolean randomize = false;                 // pick a random ticked macro each time one finishes
    public int activeSlot = 0;                        // slot that REC records into / PLAY runs (randomize off)
    public boolean slotsMigrated = false;

    // ---- modules ----
    public boolean mFailsafes = true;
    public boolean mSetback = true;
    public boolean mDamage = false;
    public boolean mScreenSync = true;
    public boolean mBlockInput = true;
    public boolean mBackground = true;
    public boolean mHud = true;
    public boolean mCoords = false;
    public boolean mSprint = false;
    public boolean mChatButtons = true;
    public boolean mLoop = true;
    public boolean mStartCheck = true;
    public boolean mAlign = true;

    public static Path dir() {
        try {
            Path d = FabricLoader.getInstance().getConfigDir().resolve("berserk");
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
                Config c = GSON.fromJson(Files.readString(p), Config.class);
                if (c != null) I = c;
            }
        } catch (Throwable t) {
            Berserk.LOG.warn("Could not read config, using defaults", t);
            I = new Config();
        }
        if (I.failCommand == null) I.failCommand = "";
        fixSlots();
    }

    /** Makes sure the slot arrays are the right size, and fills slots from old macro files once. */
    private static void fixSlots() {
        String[] s = new String[SLOTS];
        boolean[] r = new boolean[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            s[i] = (I.slots != null && i < I.slots.length && I.slots[i] != null) ? I.slots[i] : "";
            r[i] = I.randSel != null && i < I.randSel.length && I.randSel[i];
        }
        I.slots = s;
        I.randSel = r;
        I.activeSlot = Math.max(0, Math.min(SLOTS - 1, I.activeSlot));
        if (!I.slotsMigrated) {
            I.slotsMigrated = true;
            boolean any = false;
            for (String n : I.slots) any |= !n.isEmpty();
            if (!any) {
                int i = 0;
                for (String n : MacroStore.list()) {
                    if (i >= SLOTS) break;
                    I.slots[i++] = n;
                }
            }
            save();
        }
    }

    public static String selectedName() {
        return I.slots[I.activeSlot];
    }

    public static boolean nameUsed(String name, int exceptSlot) {
        for (int i = 0; i < SLOTS; i++) if (i != exceptSlot && I.slots[i].equals(name)) return true;
        return false;
    }

    public static void save() {
        try {
            Files.writeString(dir().resolve("config.json"), GSON.toJson(I));
        } catch (Throwable t) {
            Berserk.LOG.warn("Could not save config", t);
        }
    }
}
