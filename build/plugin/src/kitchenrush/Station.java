package kitchenrush;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** One interactive spot in the kitchen and its floating visuals. */
final class Station {
    enum Kind { COUNTER, BOARD, CRATE, PAN, FRYER, POT, PLATES, RETURN, SINK, SERVE, TRASH }

    static final String TAG = "kitchenrush";

    final Kind kind;
    final Block block;
    final String crate;
    ItemStack item;
    int chop, cook, tomatoes, count;
    boolean soupDone, burnt;

    private ItemDisplay itemDisplay;
    private TextDisplay bar, hover;
    private String hoverText = "";
    private final List<Entity> extras = new ArrayList<>();
    private final List<ItemDisplay> stack = new ArrayList<>();

    Station(Kind kind, Block block, String crate) {
        this.kind = kind;
        this.block = block;
        this.crate = crate;
    }

    /** Centre of the top face of the station block. */
    Location top() {
        return block.getLocation().add(0.5, 1.0, 0.5);
    }

    private float itemHeight() {
        return switch (kind) {
            case BOARD -> 0.09f;
            case PAN -> 0.08f;
            case POT, FRYER -> -0.12f;
            default -> 0.03f;
        };
    }

    void setItem(ItemStack stack) {
        item = stack == null ? null : stack.clone();
        refresh();
    }

    void refresh() {
        ItemStack shown = item;
        if (kind == Kind.POT) {
            shown = burnt ? Items.of("burnt") : soupDone ? Items.of("soup") : null;
            if (tomatoes == 0 && !soupDone && !burnt) {
                block.setType(Material.CAULDRON, false);
            } else {
                block.setBlockData(Bukkit.createBlockData("minecraft:water_cauldron[level=" + Math.max(1, Math.min(3, tomatoes)) + "]"), false);
            }
        }
        if (shown == null) {
            if (itemDisplay != null) itemDisplay.remove();
            itemDisplay = null;
            return;
        }
        if (itemDisplay == null || !itemDisplay.isValid()) {
            itemDisplay = flatItem(top().add(0, itemHeight(), 0), shown, kind == Kind.POT ? 0.8f : 0.6f);
        } else {
            itemDisplay.setItemStack(shown);
        }
    }

    /** Shows `count` stacked copies of an item (plate piles, dirty dishes in the sink). */
    void showStack(String type, double base) {
        while (stack.size() > count) stack.remove(stack.size() - 1).remove();
        while (stack.size() < count) {
            ItemStack it = type.equals("plate") ? Items.plate(java.util.Set.of()) : Items.of(type);
            stack.add(flatItem(top().add(0, base + stack.size() * 0.05, 0), it, 0.6f));
        }
    }

    ItemDisplay flatItem(Location loc, ItemStack stack, float scale) {
        return loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(new Vector3f(),
                    new AxisAngle4f((float) (-Math.PI / 2), 1, 0, 0),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
    }

    void addExtra(Entity e) {
        e.addScoreboardTag(TAG);
        e.setPersistent(false);
        extras.add(e);
    }

    TextDisplay label(String text, double y, float scale) {
        TextDisplay t = block.getWorld().spawn(top().add(0, y, 0), TextDisplay.class, d -> {
            d.text(Component.text(text, NamedTextColor.WHITE));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(110, 20, 20, 30));
            d.setShadowed(true);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
        addExtra(t);
        return t;
    }

    /** Shows a segmented progress bar above the station. */
    void showBar(float progress, TextColor color, String suffix) {
        if (bar == null || !bar.isValid()) {
            bar = block.getWorld().spawn(top().add(0, 0.75, 0), TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
            });
        }
        int segs = 8;
        int full = Math.max(0, Math.min(segs, Math.round(progress * segs)));
        bar.text(Component.text()
                .append(Component.text("■".repeat(full), color))
                .append(Component.text("■".repeat(segs - full), NamedTextColor.DARK_GRAY))
                .append(Component.text(suffix, NamedTextColor.RED))
                .build());
    }

    /** Info label shown only to players looking at this station (hidden by default). */
    TextDisplay hoverLabel() {
        if (hover == null || !hover.isValid()) {
            hover = block.getWorld().spawn(top().add(0, 1.35, 0), TextDisplay.class, d -> {
                d.setVisibleByDefault(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setBackgroundColor(Color.fromARGB(190, 15, 15, 25));
                d.setShadowed(true);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setLineWidth(260);
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(0.55f, 0.55f, 0.55f), new AxisAngle4f()));
            });
            hoverText = "";
        }
        return hover;
    }

    TextDisplay hoverIfPresent() {
        return hover != null && hover.isValid() ? hover : null;
    }

    void setHoverText(java.util.List<String> lines) {
        String joined = String.join("\n", lines);
        if (joined.equals(hoverText)) return;
        hoverText = joined;
        var b = Component.text();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) b.append(Component.newline());
            b.append(Component.text(lines.get(i), i == 0 ? NamedTextColor.YELLOW : NamedTextColor.WHITE));
        }
        hoverLabel().text(b.build());
    }

    void hideBar() {
        if (bar != null) bar.remove();
        bar = null;
    }

    void resetPot() {
        tomatoes = 0;
        cook = 0;
        soupDone = false;
        burnt = false;
        hideBar();
        refresh();
    }

    void removeVisuals() {
        if (itemDisplay != null) itemDisplay.remove();
        if (hover != null) hover.remove();
        hover = null;
        hideBar();
        extras.forEach(Entity::remove);
        extras.clear();
        stack.forEach(Entity::remove);
        stack.clear();
        itemDisplay = null;
    }
}
