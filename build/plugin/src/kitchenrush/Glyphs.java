package kitchenrush;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Resource pack fonts: HUD icons, negative/positive spaces and text width estimation. */
final class Glyphs {
    static final Key ICONS = Key.key("kitchen", "icons");
    static final Key SPACE = Key.key("kitchen", "space");

    record Icon(char ch, int advance) {
    }

    private static final Map<String, Icon> ICON_MAP = new HashMap<>();

    static void load(Plugin plugin) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                plugin.getResource("glyphs.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] p = line.trim().split(" ");
                if (p.length == 3) ICON_MAP.put(p[0], new Icon((char) Integer.parseInt(p[1], 16), Integer.parseInt(p[2])));
            }
        }
    }

    static Icon icon(String name) {
        return ICON_MAP.get(name);
    }

    /** Shift the text cursor by n pixels (negative = left). */
    static Component shift(int n) {
        if (n == 0) return Component.empty();
        int base = n < 0 ? 0xF001 : 0xF011;
        int abs = Math.abs(n);
        StringBuilder sb = new StringBuilder();
        for (int bit = 10; bit >= 0; bit--) {
            if ((abs & (1 << bit)) != 0) sb.append((char) (base + bit));
        }
        return Component.text(sb.toString()).font(SPACE);
    }

    /** Approximate advance of the default Minecraft font, in GUI pixels. */
    static float charWidth(char c) {
        if (c == ' ') return 4;
        if (c >= 0xAC00 && c <= 0xD7A3) return 8.5f;
        return switch (c) {
            case '!', '.', ',', ':', ';', '|', '\'', 'i' -> 2;
            case 'l', '`' -> 3;
            case 'I', 't', '[', ']', '"', '*' -> 4;
            case 'f', 'k', '<', '>', '(', ')', '{', '}' -> 5;
            case '@', '~' -> 7;
            default -> c < 128 ? 6 : 9;
        };
    }

    /** Builds one left-aligned boss bar line while tracking its width. */
    static final class Line {
        private final TextComponent.Builder b = Component.text();
        private float width;

        Line text(String s, TextColor color) {
            b.append(Component.text(s, color));
            for (char c : s.toCharArray()) width += charWidth(c);
            return this;
        }

        Line icon(String name) {
            return icon(name, NamedTextColor.WHITE);
        }

        Line icon(String name, TextColor color) {
            Icon ic = ICON_MAP.get(name);
            if (ic == null) return text(name, color);
            b.append(Component.text(String.valueOf(ic.ch())).font(ICONS).color(color));
            width += ic.advance();
            return this;
        }

        Line gap(int px) {
            b.append(shift(px));
            width += px;
            return this;
        }

        /** Big pixel digits (and ':') from the pack, for the score and timer panels. */
        Line digits(String s, TextColor color) {
            for (char c : s.toCharArray()) {
                if (c == ':') icon("digit_colon", color);
                else if (Character.isDigit(c)) icon("digit_" + c, color);
            }
            return this;
        }

        /** A panel background made of left cap, repeated middle tiles and right cap, at least `inner` px wide. */
        static Line panel(String prefix, float inner) {
            Line l = new Line();
            Icon mid = ICON_MAP.get(prefix + "_m");
            int n = Math.max(1, (int) Math.ceil(inner / (mid.advance() - 1)));
            l.icon(prefix + "_l").gap(-1);
            for (int i = 0; i < n; i++) l.icon(prefix + "_m").gap(-1);
            l.icon(prefix + "_r");
            return l;
        }

        float width() {
            return width;
        }

        Component component() {
            return b.build();
        }

        /**
         * Boss bar titles are drawn centred. Shifting left by (screen - 2*margin) and then cancelling the
         * text's own width makes the text start at the same left margin whatever its length.
         */
        Component leftAligned(int guiWidth) {
            int s = guiWidth - 12;
            return Component.text()
                    .append(shift(-s))
                    .append(b.build())
                    .append(shift(-Math.round(width)))
                    .build();
        }
    }

    /**
     * Places pieces at absolute x positions measured from the screen centre. Boss bar titles and the
     * action bar are drawn centred on their total width; ending with a shift back to 0 makes that width 0,
     * so every piece lands exactly where it was placed.
     */
    static final class Overlay {
        private final TextComponent.Builder b = Component.text();
        private int cursor;

        Overlay at(float x, Line line) {
            int target = Math.round(x);
            b.append(shift(target - cursor));
            b.append(line.component());
            cursor = target + Math.round(line.width());
            return this;
        }

        Component build() {
            b.append(shift(-cursor));
            return b.build();
        }
    }

    private Glyphs() {
    }
}
