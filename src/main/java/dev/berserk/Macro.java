package dev.berserk;

import java.util.ArrayList;
import java.util.List;

/**
 * A recording. frames[i] is the world state sampled at the START of client tick i.
 * events[].t is the tick bucket the event happened in (between sample t and sample t+1).
 * Camera rotation is stored as absolute per-tick samples, so replay never depends on
 * raw mouse deltas (this is what makes camera movement replay correctly).
 */
public final class Macro {
    public static final int KEY = 0, CHAR = 1, MOUSE = 2, SCROLL = 3, CURSOR = 4;

    public int version = 1;
    public String name = "";
    public int ticks;
    public List<Frame> frames = new ArrayList<>();
    public List<Ev> events = new ArrayList<>();

    public static final class Frame {
        public double x, y, z;
        public float yaw, pitch, hp;
        public String screen = "";
    }

    /**
     * KEY:    a=key b=scancode c=action d=mods
     * CHAR:   a=codepoint
     * MOUSE:  a=button c=action d=mods, fx/fy = cursor as fraction of the window
     * SCROLL: fx=dx fy=dy
     * CURSOR: fx/fy = cursor as fraction of the window (only recorded while a screen is open)
     */
    public static final class Ev {
        public int t, type, a, b, c, d;
        public double fx, fy;
    }
}
