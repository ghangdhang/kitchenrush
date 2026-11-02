package kitchenrush;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Builds one floating sky kitchen from a text layout and keeps track of its stations. */
final class Kitchen {
    /*
     * Top-down layouts (north at the top). One character per block.
     *   C counter   B chopping board   L/T/M/R/F/O lettuce/tomato/meat/bread/fish/potato crates
     *   P stove with pan   Y fryer   K pot   D clean plates   U dirty plate return   W sink   Q drying rack
     *   S serving hatch   X bin   . floor
     */
    static final String[] SOLO = {
            "LTMRFOCPPKYYC",
            "C...........C",
            "B...........C",
            "B...CCDUC...S",
            "C...CCCCC...S",
            "C...........C",
            "X...........W",
            "C...........Q",
            "CCCCCBCCCCCCC",
    };
    /** Two-player kitchen: the middle counter splits prep (west) from cooking and serving (east). */
    static final String[] DUO = {
            "LTMRFOCCCCPPKKYYCCC",
            "C........C........C",
            "B........C........C",
            "B........C........S",
            "B........C........S",
            "C........C........C",
            "W........C........D",
            "Q........C........U",
            "X........C........X",
            "C........C........C",
            "CCCCCCCCCCCCCCCCCCC",
    };
    static final Material COUNTER = Material.STRIPPED_OAK_WOOD;

    final String name;
    final World world;
    final String[] layout;
    final int ox, oy, oz;
    final List<Location> spawns = new ArrayList<>();
    final List<Station> stations = new ArrayList<>();
    private final Map<Long, Station> byBlock = new HashMap<>();
    Station plates, returns;

    Kitchen(String name, World world, String[] layout, int ox, int oy, int oz, double[][] spawns) {
        this.name = name;
        this.world = world;
        this.layout = layout;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        for (double[] s : spawns) this.spawns.add(new Location(world, ox + s[0], oy + 1, oz + s[1], 180f, 0f));
    }

    int width() {
        return layout[0].length();
    }

    /** World x of the divider column in a split kitchen, or Integer.MIN_VALUE when there is none. */
    int dividerX() {
        return layout == DUO ? ox + 9 : Integer.MIN_VALUE;
    }

    @SuppressWarnings({"deprecation", "removal"})
    static void prepareWorld(World world, Location spawn) {
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
        world.setSpawnLocation(spawn);
    }

    /** The drying rack next to a sink, where its washed plates appear. */
    Station rackFor(Station sink) {
        Block b = sink.block;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Station s = at(b.getRelative(d[0], 0, d[1]));
            if (s != null && s.kind == Station.Kind.PLATES) return s;
        }
        return plates;
    }

    Station at(Block b) {
        return byBlock.get(key(b.getX(), b.getY(), b.getZ()));
    }

    void clear() {
        stations.forEach(Station::removeVisuals);
        stations.clear();
        byBlock.clear();
        int w = width(), d = layout.length;
        for (Entity e : world.getNearbyEntities(new Location(world, ox + w / 2.0, oy, oz + d / 2.0), w, 8, d)) {
            if (e.getScoreboardTags().contains(Station.TAG)) e.remove();
        }
    }

    void build() {
        int w = width(), d = layout.length;
        for (int cx = (ox - 8) >> 4; cx <= (ox + w + 8) >> 4; cx++) {
            for (int cz = (oz - 8) >> 4; cz <= (oz + d + 8) >> 4; cz++) world.getChunkAt(cx, cz).load(true);
        }
        clear();
        plates = returns = null;
        for (int x = ox - 7; x <= ox + w + 6; x++) {
            for (int z = oz - 7; z <= oz + d + 6; z++) {
                for (int y = oy - 3; y <= oy + 6; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
        }
        buildClouds(w, d);
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                set(x, 0, z, (x + z) % 2 == 0 ? Material.WHITE_CONCRETE : Material.LIGHT_BLUE_CONCRETE);
                set(x, -1, z, Material.WHITE_WOOL);
                buildCell(layout[z].charAt(x), x, z);
            }
        }
        // Invisible walls just outside the counters so nobody falls off the cloud.
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= d; z++) {
                if (x == -1 || z == -1 || x == w || z == d) {
                    for (int y = 1; y <= 5; y++) set(x, y, z, Material.BARRIER);
                }
            }
        }
        // Striped awning over the back counters.
        for (int x = 0; x < w; x++) set(x, 4, 0, x % 2 == 0 ? Material.RED_CARPET : Material.WHITE_CARPET);
        for (int[] p : new int[][]{{-1, -1}, {w, -1}, {-1, d}, {w, d}}) set(p[0], 6, p[1], Material.LANTERN);
    }

    private void buildClouds(int w, int d) {
        Random rnd = new Random(42);
        for (int x = -7; x <= w + 6; x++) {
            for (int z = -7; z <= d + 6; z++) {
                int dx = Math.max(Math.max(-x, x - (w - 1)), 0);
                int dz = Math.max(Math.max(-z, z - (d - 1)), 0);
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist == 0) continue;
                double r = rnd.nextDouble();
                if (dist <= 2.2 || (dist <= 5.5 && r < 0.75 - dist * 0.1)) {
                    set(x, 0, z, Material.WHITE_WOOL);
                    if (r < 0.5) set(x, -1, z, Material.WHITE_WOOL);
                    if (dist > 3 && r < 0.12) set(x, 1, z, Material.WHITE_WOOL);
                }
            }
        }
    }

    private void buildCell(char c, int x, int z) {
        Block b = world.getBlockAt(ox + x, oy + 1, oz + z);
        Station s;
        switch (c) {
            case 'C' -> {
                b.setType(COUNTER, false);
                add(new Station(Station.Kind.COUNTER, b, null));
            }
            case 'D' -> {
                b.setType(COUNTER, false);
                s = add(new Station(Station.Kind.PLATES, b, null));
                s.label("접시", 1.0, 0.5f);
                plates = s;
            }
            case 'Q' -> {
                b.setBlockData(Bukkit.createBlockData("minecraft:spruce_slab[type=double]"), false);
                s = add(new Station(Station.Kind.PLATES, b, null));
                s.label("건조대", 1.0, 0.5f);
            }
            case 'U' -> {
                b.setType(Material.SPRUCE_PLANKS, false);
                s = add(new Station(Station.Kind.RETURN, b, null));
                s.label("반납대", 1.0, 0.5f);
                returns = s;
            }
            case 'W' -> {
                b.setBlockData(Bukkit.createBlockData("minecraft:water_cauldron[level=3]"), false);
                s = add(new Station(Station.Kind.SINK, b, null));
                s.label("싱크대", 1.0, 0.5f);
            }
            case 'B' -> {
                b.setType(COUNTER, false);
                Block board = b.getRelative(0, 1, 0);
                board.setType(Material.BIRCH_PRESSURE_PLATE, false);
                s = add(new Station(Station.Kind.BOARD, b, null));
                byBlock.put(key(board.getX(), board.getY(), board.getZ()), s);
                s.label("도마", 1.0, 0.5f);
            }
            case 'L', 'T', 'M', 'R', 'F', 'O' -> {
                b.setBlockData(Bukkit.createBlockData("minecraft:barrel[facing=up]"), false);
                String type = switch (c) {
                    case 'L' -> "lettuce";
                    case 'T' -> "tomato";
                    case 'M' -> "meat";
                    case 'F' -> "fish";
                    case 'O' -> "potato";
                    default -> "bread";
                };
                s = add(new Station(Station.Kind.CRATE, b, type));
                s.addExtra(s.flatItem(s.top().add(0, 0.03, 0), Items.of(type), 0.7f));
                s.label(Items.name(type), 0.7, 0.5f);
            }
            case 'P' -> {
                b.setBlockData(Bukkit.createBlockData("minecraft:smoker[facing=south,lit=true]"), false);
                s = add(new Station(Station.Kind.PAN, b, null));
                s.addExtra(s.flatItem(s.top().add(0, 0.02, 0), Items.of("pan"), 0.9f));
                s.label("프라이팬", 1.0, 0.5f);
            }
            case 'Y' -> {
                b.setType(Material.LAVA_CAULDRON, false);
                s = add(new Station(Station.Kind.FRYER, b, null));
                s.label("튀김기", 1.0, 0.5f);
            }
            case 'K' -> {
                b.setType(Material.CAULDRON, false);
                world.getBlockAt(ox + x, oy, oz + z).setBlockData(
                        Bukkit.createBlockData("minecraft:campfire[lit=true,signal_fire=false]"), false);
                s = add(new Station(Station.Kind.POT, b, null));
                s.label("냄비 (토마토 3개)", 1.0, 0.5f);
            }
            case 'S' -> {
                b.setType(Material.LIME_GLAZED_TERRACOTTA, false);
                s = add(new Station(Station.Kind.SERVE, b, null));
                s.label("서빙", 0.6, 0.6f);
            }
            case 'X' -> {
                b.setType(Material.COMPOSTER, false);
                s = add(new Station(Station.Kind.TRASH, b, null));
                s.label("쓰레기통", 0.6, 0.5f);
            }
            default -> {
            }
        }
    }

    private Station add(Station s) {
        stations.add(s);
        byBlock.put(key(s.block.getX(), s.block.getY(), s.block.getZ()), s);
        return s;
    }

    private void set(int x, int y, int z, Material m) {
        world.getBlockAt(ox + x, oy + y, oz + z).setType(m, false);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
