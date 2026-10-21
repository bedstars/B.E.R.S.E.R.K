package dev.berserk;

import com.google.gson.Gson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Saves / loads macros as JSON in config/berserk/macros. */
public final class MacroStore {
    private static final Gson GSON = new Gson();

    private MacroStore() {}

    static Path dir() {
        try {
            Path d = Config.dir().resolve("macros");
            Files.createDirectories(d);
            return d;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String clean(String n) {
        n = n == null ? "" : n.replaceAll("[^A-Za-z0-9 _.\\-]", "_").trim();
        return n.isEmpty() ? "macro" : n;
    }

    public static List<String> list() {
        try (Stream<Path> s = Files.list(dir())) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    public static Macro load(String name) {
        try {
            Path p = dir().resolve(clean(name) + ".json");
            if (!Files.exists(p)) return null;
            Macro m = GSON.fromJson(Files.readString(p), Macro.class);
            if (m != null) {
                m.name = clean(name);
                m.ticks = Math.max(0, m.frames.size() - 1);
            }
            return m;
        } catch (Throwable t) {
            Berserk.LOG.warn("Could not load macro {}", name, t);
            return null;
        }
    }

    public static void save(Macro m) {
        try {
            m.name = clean(m.name);
            Files.writeString(dir().resolve(m.name + ".json"), GSON.toJson(m));
        } catch (Throwable t) {
            Berserk.LOG.warn("Could not save macro", t);
        }
    }

    public static void delete(String name) {
        try {
            Files.deleteIfExists(dir().resolve(clean(name) + ".json"));
        } catch (Throwable ignored) {
        }
    }

    public static boolean rename(String from, String to) {
        try {
            Path a = dir().resolve(clean(from) + ".json");
            Path b = dir().resolve(clean(to) + ".json");
            if (!Files.exists(a) || Files.exists(b)) return false;
            Files.move(a, b);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int ticksOf(String name) {
        Macro m = load(name);
        return m == null ? 0 : m.ticks;
    }
}
