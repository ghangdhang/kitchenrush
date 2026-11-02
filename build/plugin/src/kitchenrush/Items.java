package kitchenrush;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Kitchen items: ingredients, plates and recipes. */
final class Items {
    enum Recipe {
        SALAD("양상추 샐러드", "plate_salad", Set.of("lettuce_chopped"), 1, 60,
                new String[]{"lettuce_chopped"}),
        BURGER("버거", "plate_burger", Set.of("bread", "patty_cooked"), 3, 80,
                new String[]{"bread", "+", "patty_cooked"}),
        TOMATO_SALAD("토마토 양상추 샐러드", "plate_mixsalad", Set.of("lettuce_chopped", "tomato_chopped"), 2, 75,
                new String[]{"lettuce_chopped", "+", "tomato_chopped"}),
        SPECIAL("스페셜 버거", "plate_special", Set.of("bread", "patty_cooked", "lettuce_chopped", "tomato_chopped"), 4, 110,
                new String[]{"bread", "+", "patty_cooked", "+", "lettuce_chopped", "+", "tomato_chopped"}),
        FISH_SET("생선튀김 정식", "plate_fishset", Set.of("fish_fried", "fries", "lettuce_chopped", "tomato_chopped"), 5, 140,
                new String[]{"fish_fried", "+", "fries", "+", "lettuce_chopped", "+", "tomato_chopped"});

        final String name, icon;
        final Set<String> contents;
        final int points, seconds;
        final String[] hint;

        Recipe(String name, String icon, Set<String> contents, int points, int seconds, String[] hint) {
            this.name = name;
            this.icon = icon;
            this.contents = contents;
            this.points = points;
            this.seconds = seconds;
            this.hint = hint;
        }
    }

    static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("lettuce", "양상추"), Map.entry("lettuce_chopped", "썬 양상추"),
            Map.entry("tomato", "토마토"), Map.entry("tomato_chopped", "썬 토마토"),
            Map.entry("meat", "고기"), Map.entry("patty_raw", "다진 고기"),
            Map.entry("patty_cooked", "구운 다진 고기"), Map.entry("bread", "빵"),
            Map.entry("fish", "생선"), Map.entry("fish_chopped", "다진 생선"), Map.entry("fish_fried", "튀긴 다진 생선"),
            Map.entry("potato", "감자"), Map.entry("potato_chopped", "썬 감자"), Map.entry("fries", "튀긴 썬 감자"),
            Map.entry("burnt", "탄 음식"), Map.entry("plate", "접시"), Map.entry("dirty_plate", "더러운 접시"),
            Map.entry("pan", "프라이팬"));

    static final Map<String, String> CHOPS = Map.of(
            "lettuce", "lettuce_chopped", "tomato", "tomato_chopped", "meat", "patty_raw",
            "fish", "fish_chopped", "potato", "potato_chopped");

    static final Map<String, String> FRIES = Map.of("fish_chopped", "fish_fried", "potato_chopped", "fries");

    /** Plate pictures, checked biggest first: the plate shows the largest picture its contents cover. */
    private static final List<Map.Entry<String, Set<String>>> PLATE_MODELS = List.of(
            Map.entry("plate_fishset", Set.of("fish_fried", "fries", "lettuce_chopped", "tomato_chopped")),
            Map.entry("plate_special", Set.of("bread", "patty_cooked", "lettuce_chopped", "tomato_chopped")),
            Map.entry("plate_fishfries", Set.of("fish_fried", "fries")),
            Map.entry("plate_burger", Set.of("bread", "patty_cooked")),
            Map.entry("plate_mixsalad", Set.of("lettuce_chopped", "tomato_chopped")),
            Map.entry("plate_fish", Set.of("fish_fried")),
            Map.entry("plate_fries", Set.of("fries")),
            Map.entry("plate_bread", Set.of("bread")),
            Map.entry("plate_patty", Set.of("patty_cooked")),
            Map.entry("plate_salad", Set.of("lettuce_chopped")),
            Map.entry("plate_tomato", Set.of("tomato_chopped")));

    private static NamespacedKey typeKey, plateKey, cookKey;

    static void init(Plugin plugin) {
        typeKey = new NamespacedKey(plugin, "type");
        plateKey = new NamespacedKey(plugin, "plate");
        cookKey = new NamespacedKey(plugin, "cook");
    }

    /** Ticks of cooking/frying an ingredient has had so far, kept on the item when it is taken off the heat. */
    static int cook(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer v = item.getItemMeta().getPersistentDataContainer().get(cookKey, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    static ItemStack withCook(ItemStack item, int ticks) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (ticks > 0) meta.getPersistentDataContainer().set(cookKey, PersistentDataType.INTEGER, ticks);
        else meta.getPersistentDataContainer().remove(cookKey);
        copy.setItemMeta(meta);
        return copy;
    }

    static ItemStack of(String type) {
        return make(type, type, NAMES.getOrDefault(type, type));
    }

    static ItemStack plate(Set<String> contents) {
        String label = "접시";
        for (Recipe r : Recipe.values()) {
            if (r.contents.equals(contents)) label = r.name;
        }
        if (label.equals("접시") && !contents.isEmpty()) {
            label = "접시 (" + String.join(", ", contents.stream().map(Items::name).toList()) + ")";
        }
        ItemStack item = make("plate", plateModel(contents), label);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(plateKey, PersistentDataType.STRING, String.join(",", contents));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack make(String type, String model, String label) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("kitchen", model));
        meta.displayName(Component.text(label, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    static String type(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    static boolean isPlate(ItemStack item) {
        return "plate".equals(type(item));
    }

    static Set<String> contents(ItemStack plate) {
        String raw = plate.getItemMeta().getPersistentDataContainer().get(plateKey, PersistentDataType.STRING);
        Set<String> set = new TreeSet<>();
        if (raw != null && !raw.isEmpty()) set.addAll(Arrays.asList(raw.split(",")));
        return set;
    }

    /** A plate only accepts ingredients that keep it on the way to some recipe. */
    static boolean canAdd(Set<String> contents, String type) {
        if (type == null || contents.contains(type)) return false;
        Set<String> next = new TreeSet<>(contents);
        next.add(type);
        for (Recipe r : Recipe.values()) {
            if (r.contents.containsAll(next)) return true;
        }
        return false;
    }

    static ItemStack addToPlate(ItemStack plate, String type) {
        Set<String> next = new TreeSet<>(contents(plate));
        next.add(type);
        return plate(next);
    }

    static String plateModel(Set<String> contents) {
        for (var e : PLATE_MODELS) {
            if (contents.containsAll(e.getValue())) return e.getKey();
        }
        return "plate";
    }

    static String name(String type) {
        return NAMES.getOrDefault(type, type);
    }

    /** Hover text for a plate: what's on it and which dishes it can still become. */
    static List<String> describePlate(Set<String> contents) {
        List<String> lines = new java.util.ArrayList<>();
        if (contents.isEmpty()) {
            lines.add("빈 접시");
            return lines;
        }
        lines.add("접시: " + String.join(" + ", contents.stream().map(Items::name).toList()));
        for (Recipe r : Recipe.values()) {
            if (r.contents.equals(contents)) {
                lines.add("✔ " + r.name + " 완성! 서빙대로");
                return lines;
            }
        }
        for (Recipe r : Recipe.values()) {
            if (r.contents.containsAll(contents)) {
                List<String> missing = r.contents.stream().filter(x -> !contents.contains(x)).map(Items::name).sorted().toList();
                lines.add("→ " + r.name + ": " + String.join(", ", missing) + " 필요");
            }
        }
        return lines;
    }

    private Items() {
    }
}
