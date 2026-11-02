package kitchenrush;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.List;

/** Round start/end jingles from the resource pack (original, made by build/gen_audio.py). */
final class Jingle {
    static final String START = "kitchen:sfx.start", CLEAR = "kitchen:sfx.clear", FAIL = "kitchen:sfx.fail";

    static void play(List<Player> listeners, String sound) {
        for (Player p : listeners) {
            if (p.isOnline()) p.playSound(p, sound, SoundCategory.MASTER, 1f, 1f);
        }
    }

    private Jingle() {
    }
}
