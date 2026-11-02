package kitchenrush;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/** An original, bouncy note-block loop (C major, 8 bars of eighth notes). */
final class Music {
    // Note-block pitch steps: 6 = C, 8 = D, 10 = E, 11 = F, 13 = G, 15 = A, 17 = B, 18 = high C ... -1 = rest.
    private static final int[] MELODY = {
            10, 13, 18, 13, 15, 13, 10, -1,
            11, 15, 20, 15, 13, 11, 10, 8,
            10, 13, 18, 22, 20, 18, 15, 13,
            11, 10, 8, 10, 6, -1, -1, -1,
            15, 15, 13, 15, 18, -1, 15, 13,
            11, 11, 10, 11, 15, -1, 13, 11,
            10, 13, 18, 20, 22, 20, 18, 15,
            13, -1, 20, -1, 18, -1, -1, -1,
    };
    // Root and fifth for each bar: C, F, C, G, Am, F, C, G->C
    private static final int[][] BASS = {{6, 13}, {11, 18}, {6, 13}, {13, 20}, {15, 22}, {11, 18}, {6, 13}, {13, 6}};

    private boolean playing;
    private int step, wait;

    void start() {
        playing = true;
        step = 0;
        wait = 0;
    }

    void stop() {
        playing = false;
    }

    void tick(java.util.List<Player> listeners, boolean hurry) {
        if (!playing || listeners.isEmpty()) return;
        var p = listeners;
        if (++wait < (hurry ? 3 : 4)) return;
        wait = 0;
        int bar = step / 8, beat = step % 8;
        int note = MELODY[step];
        if (note >= 0) {
            play(p, Sound.BLOCK_NOTE_BLOCK_FLUTE, 0.55f, note);
            if (beat % 2 == 0) play(p, Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 0.18f, note);
        }
        if (beat == 0) play(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, BASS[bar][0]);
        if (beat == 4) play(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, bar == 7 ? BASS[bar][1] : BASS[bar][0]);
        if (beat == 2 || beat == 6) play(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, bar == 7 ? BASS[bar][0] : BASS[bar][1]);
        if (beat == 0 || beat == 4) play(p, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.45f, 12);
        if (beat == 2 || beat == 6) play(p, Sound.BLOCK_NOTE_BLOCK_SNARE, 0.35f, 12);
        if (beat % 2 == 1) play(p, Sound.BLOCK_NOTE_BLOCK_HAT, 0.22f, 16);
        step = (step + 1) % MELODY.length;
    }

    private static void play(java.util.List<Player> listeners, Sound s, float vol, int note) {
        float pitch = (float) Math.pow(2, (note - 12) / 12.0);
        for (Player p : listeners) p.playSound(p, s, SoundCategory.RECORDS, vol, pitch);
    }
}
