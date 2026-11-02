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
    /** Counters use sandstone, retextured by the pack as a wooden worktop over cabinet doors. */
    static final Material COUNTER = Material.SANDSTONE;

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
        for (int cx = (ox - HALL - 2) >> 4; cx <= (ox + w + HALL + 2) >> 4; cx++) {
            for (int cz = (oz - HALL - 2) >> 4; cz <= (oz + d + HALL + 2) >> 4; cz++) world.getChunkAt(cx, cz).load(true);
        }
        clear();
        plates = returns = null;
        for (int x = -HALL - 1; x <= w + HALL; x++) {
            for (int z = -HALL - 1; z <= d + HALL; z++) {
                for (int y = -3; y <= 7; y++) set(x, y, z, Material.AIR);
            }
        }
        buildHall(w, d);
        // Kitchen floor: plain cream tiles (retextured white concrete).
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                set(x, 0, z, Material.WHITE_CONCRETE);
                set(x, -1, z, Material.SPRUCE_PLANKS);
                buildCell(layout[z].charAt(x), x, z);
            }
        }
        // Brick half-wall around the kitchen, with invisible barriers above so nobody climbs out.
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= d; z++) {
                if (x == -1 || z == -1 || x == w || z == d) {
                    set(x, 0, z, Material.BRICKS);
                    set(x, 1, z, Material.BRICKS);
                    set(x, 2, z, Material.BRICKS);
                    for (int y = 3; y <= 5; y++) set(x, y, z, Material.BARRIER);
                }
            }
        }
        for (int[] p : new int[][]{{-1, -1}, {w, -1}, {-1, d}, {w, d}}) {
            set(p[0], 3, p[1], Material.SPRUCE_FENCE);
            set(p[0], 4, p[1], Material.LANTERN);
        }
    }

    static final int HALL = 7;

    /** A cosy dining hall around the kitchen: wooden floor, tables, chairs, plants and lanterns. */
    private void buildHall(int w, int d) {
        for (int x = -HALL; x < w + HALL; x++) {
            for (int z = -HALL; z < d + HALL; z++) {
                set(x, 0, z, Material.SPRUCE_PLANKS);
                set(x, -1, z, Material.SPRUCE_PLANKS);
                boolean edge = x == -HALL || z == -HALL || x == w + HALL - 1 || z == d + HALL - 1;
                if (edge) {
                    for (int y = 1; y <= 4; y++) set(x, y, z, Material.BRICKS);
                    if ((x + z) % 4 == 0) {
                        set(x, 2, z, Material.GLASS_PANE);
                        set(x, 3, z, Material.GLASS_PANE);
                    }
                }
            }
        }
        Random rnd = new Random(7);
        List<int[]> spots = new ArrayList<>();
        for (int x = -HALL + 3; x < w + HALL - 3; x += 5) {
            spots.add(new int[]{x, -HALL + 3});
            spots.add(new int[]{x, d + HALL - 4});
        }
        for (int z = 3; z < d - 2; z += 5) {
            spots.add(new int[]{-HALL + 3, z});
            spots.add(new int[]{w + HALL - 4, z});
        }
        for (int[] t : spots) {
            int x = t[0], z = t[1];
            set(x, 1, z, Material.DARK_OAK_FENCE);
            set(x, 2, z, rnd.nextBoolean() ? Material.WHITE_CARPET : Material.RED_CARPET);
            setData(x - 1, 1, z, "minecraft:oak_stairs[facing=east]");
            setData(x + 1, 1, z, "minecraft:oak_stairs[facing=west]");
        }
        for (int[] c : new int[][]{{-HALL + 1, -HALL + 1}, {w + HALL - 2, -HALL + 1}, {-HALL + 1, d + HALL - 2}, {w + HALL - 2, d + HALL - 2}}) {
            set(c[0], 1, c[1], Material.POTTED_AZALEA_BUSH);
            set(c[0], 3, c[1], Material.LANTERN);
            set(c[0], 2, c[1], Material.SPRUCE_FENCE);
        }
    }

    private void setData(int x, int y, int z, String data) {
        world.getBlockAt(ox + x, oy + y, oz + z).setBlockData(Bukkit.createBlockData(data), false);
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
                s.addExtra(s.restItem(s.top(), Items.of(type), 0.55f));
                s.label(Items.name(type), 0.7, 0.5f);
            }
            case 'P' -> {
                b.setBlockData(Bukkit.createBlockData("minecraft:smoker[facing=south,lit=true]"), false);
                s = add(new Station(Station.Kind.PAN, b, null));
                s.addExtra(s.restItem(s.top(), Items.of("pan"), 0.85f));
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
