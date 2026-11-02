package kitchenrush;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Background music from the resource pack (original tracks made by build/gen_audio.py).
 * Minecraft sounds don't loop, so each track is replayed when it ends; the loops are whole ticks long.
 */
final class Music {
    static final String BGM = "kitchen:music.bgm", HURRY = "kitchen:music.bgm_hurry";

    private static final Map<String, Integer> LENGTHS = new HashMap<>();

    private boolean playing, hurry;
    private int t;

    static void load(Plugin plugin) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                plugin.getResource("audio.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.trim().split(" ");
                if (p.length == 2) LENGTHS.put(p[0], Integer.parseInt(p[1]));
            }
        }
    }

    void start() {
        playing = true;
        hurry = false;
        t = 0;
    }

    void stop(List<Player> listeners) {
        playing = false;
        for (Player p : listeners) {
            p.stopSound(BGM, SoundCategory.RECORDS);
            p.stopSound(HURRY, SoundCategory.RECORDS);
        }
    }

    void tick(List<Player> listeners, boolean hurryNow) {
        if (!playing || listeners.isEmpty()) return;
        if (hurryNow && !hurry) {
            for (Player p : listeners) p.stopSound(BGM, SoundCategory.RECORDS);
            hurry = true;
            t = 0;
        }
        if (t == 0) {
            String track = hurry ? HURRY : BGM;
            for (Player p : listeners) p.playSound(p, track, SoundCategory.RECORDS, 0.7f, 1f);
        }
        int length = LENGTHS.getOrDefault(hurry ? "bgm_hurry" : "bgm", 576);
        if (++t >= length) t = 0;
    }
}
