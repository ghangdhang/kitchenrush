package kitchenrush;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** One round: timer, orders, score, station logic, dish washing and the top-left HUD. */
final class Game {
    enum Phase { IDLE, COUNTDOWN, RUNNING }

    enum Mode {
        SOLO("1인", new int[]{80, 180, 280}, 18),
        DUO("2인", new int[]{150, 320, 480}, 13);

        final String label;
        final int[] stars;
        final int orderEvery;

        Mode(String label, int[] stars, int orderEvery) {
            this.label = label;
            this.stars = stars;
            this.orderEvery = orderEvery;
        }
    }

    static final int MAX_ORDERS = 4, CHOPS = 6, WASHES = 6, PLATES = 3, DIRTY_DELAY = 7 * 20;
    static final int PAN_COOK = 120, PAN_BURN = 200, FRY_COOK = 140, FRY_BURN = 220;
    static final TextColor GOLD = TextColor.color(0xFFD84A);

    record Order(Items.Recipe recipe, int total, int[] left) {
    }

    private final Music music = new Music();
    private final Random rnd = new Random();
    private final List<Order> orders = new ArrayList<>();
    private final List<BossBar> bars = new ArrayList<>();
    private final List<int[]> dirtyComing = new ArrayList<>();
    private final Map<UUID, Integer> lastHit = new HashMap<>();
    private final List<Player> players = new ArrayList<>();
    private final Map<UUID, Station> hovering = new HashMap<>();
    private final org.bukkit.plugin.Plugin plugin;

    private Phase phase = Phase.IDLE;
    private Mode mode = Mode.SOLO;
    private Kitchen kitchen;
    private int ticks, timeLeft, score, served, missed, nextOrder;
    int roundSeconds = 180, guiWidth = 480;

    Game(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
    }

    boolean running() {
        return phase == Phase.RUNNING;
    }

    boolean playing(Player p) {
        return phase != Phase.IDLE && players.contains(p);
    }

    Kitchen kitchen() {
        return kitchen;
    }

    static final double DEFAULT_JUMP = 0.41999998688697815;

    /** Jumping is off during a round so nobody hops over counters; the kitchens have walk-through gaps instead. */
    static void setJump(Player p, boolean allowed) {
        var jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) jump.setBaseValue(allowed ? DEFAULT_JUMP : 0);
    }

    // ---------------------------------------------------------------- round lifecycle

    void start(Mode mode, Kitchen kitchen, List<Player> who) {
        stop();
        this.mode = mode;
        this.kitchen = kitchen;
        kitchen.build();
        players.addAll(who);
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            p.teleport(kitchen.spawns.get(Math.min(i, kitchen.spawns.size() - 1)));
            p.setGameMode(GameMode.ADVENTURE);
            p.getInventory().clear();
            p.getInventory().setHeldItemSlot(0);
            p.setFoodLevel(20);
            var hp = p.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) p.setHealth(hp.getValue());
            p.setWalkSpeed(0.24f);
            setJump(p, false);
        }
        for (Station s : kitchen.stations) {
            if (s.kind == Station.Kind.PLATES) {
                s.count = s == kitchen.plates ? PLATES : 0;
                s.showStack("plate", 0.0);
            }
        }
        hovering.clear();
        kitchen.returns.count = 0;
        kitchen.returns.showStack("dirty_plate", 0.0);
        orders.clear();
        dirtyComing.clear();
        score = served = missed = ticks = 0;
        timeLeft = roundSeconds * 20;
        phase = Phase.COUNTDOWN;
        flashUntil = 0;
        for (int i = 0; i < MAX_ORDERS; i++) {
            BossBar bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
            bars.add(bar);
            players.forEach(p -> p.showBossBar(bar));
        }
        updateHud();
    }

    void stop() {
        for (Player p : players) {
            for (BossBar b : bars) p.hideBossBar(b);
            p.sendActionBar(Component.empty());
            if (p.isOnline()) {
                p.getInventory().clear();
                p.setWalkSpeed(0.2f);
                setJump(p, true);
            }
        }
        bars.clear();
        for (Player p : players) {
            Station h = hovering.get(p.getUniqueId());
            if (h != null && h.hoverIfPresent() != null) p.hideEntity(plugin, h.hoverIfPresent());
        }
        hovering.clear();
        music.stop(players);
        players.clear();
        orders.clear();
        dirtyComing.clear();
        phase = Phase.IDLE;
    }

    private void finish() {
        int stars = 0;
        for (int t : mode.stars) if (score >= t) stars++;
        String starText = "★".repeat(stars) + "☆".repeat(3 - stars);
        for (Player p : players) {
            p.showTitle(Title.title(
                    Component.text(starText, GOLD),
                    Component.text("영업 종료!  점수 " + score, NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofSeconds(1))));
            p.sendMessage(Component.text("── 오늘의 영업 결과 (" + mode.label + ") ──", NamedTextColor.GOLD));
            p.sendMessage(Component.text(" 점수: " + score + "   서빙: " + served + "   놓친 주문: " + missed, NamedTextColor.WHITE));
            p.sendMessage(Component.text(" 별 기준: ★" + mode.stars[0] + " / ★★" + mode.stars[1] + " / ★★★" + mode.stars[2], NamedTextColor.GRAY));
        }
        music.stop(players);
        Jingle.play(players, stars > 0 ? Jingle.CLEAR : Jingle.FAIL);
        stop();
    }

    // ---------------------------------------------------------------- ticking

    void tick(org.bukkit.World world) {
        if (Bukkit.getCurrentTick() % 200 == 0) world.setTime(6000);
        if (phase == Phase.IDLE) return;
        players.removeIf(p -> !p.isOnline());
        if (players.isEmpty()) {
            stop();
            return;
        }
        ticks++;
        if (phase == Phase.COUNTDOWN) {
            int left = 3 - (ticks - 1) / 20;
            if ((ticks - 1) % 20 == 0 && left > 0) {
                title(Component.text(String.valueOf(left), GOLD), Component.text("준비!", NamedTextColor.WHITE));
                playAll(Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1f);
            }
            if (ticks > 60) {
                title(Component.text("영업 시작!", GOLD), Component.empty());
                Jingle.play(players, Jingle.START);
                phase = Phase.RUNNING;
                spawnOrder();
                spawnOrder();
                nextOrder = mode.orderEvery * 20;
                music.start();
            }
            return;
        }
        timeLeft--;
        for (Station s : kitchen.stations) tickStation(s);
        tickOrders();
        tickDishes();
        if (ticks % 2 == 0) tickHover();
        music.tick(players, timeLeft <= 30 * 20);
        if (timeLeft == 30 * 20) {
            flash("30초 남았어요! 서둘러요!", NamedTextColor.RED);
            playAll(Sound.BLOCK_BELL_USE, 1f, 1.2f);
        }
        if (timeLeft <= 10 * 20 && timeLeft % 20 == 0) playAll(Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 2f);
        if (timeLeft % 5 == 0) updateHud();
        if (timeLeft <= 0) finish();
    }

    private void tickOrders() {
        Iterator<Order> it = orders.iterator();
        while (it.hasNext()) {
            Order o = it.next();
            if (--o.left()[0] <= 0) {
                it.remove();
                score = Math.max(0, score - 10);
                missed++;
                flash(o.recipe().name + " 주문을 놓쳤어요… -10", NamedTextColor.RED);
                playAll(Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
            }
        }
        if ((--nextOrder <= 0 || orders.isEmpty()) && orders.size() < MAX_ORDERS) {
            spawnOrder();
            nextOrder = mode.orderEvery * 20;
        }
    }

    /** Served plates come back dirty to the return counter a few seconds later. */
    private void tickDishes() {
        Iterator<int[]> it = dirtyComing.iterator();
        while (it.hasNext()) {
            int[] t = it.next();
            if (--t[0] <= 0) {
                it.remove();
                kitchen.returns.count++;
                kitchen.returns.showStack("dirty_plate", 0.0);
                sound(kitchen.returns.top(), Sound.BLOCK_DECORATED_POT_INSERT, 0.8f, 0.8f);
            }
        }
    }

    /** Shows each cook an info label over the station under their crosshair. */
    private void tickHover() {
        for (Player p : players) {
            org.bukkit.block.Block b = p.getTargetBlockExact(5);
            Station s = b == null ? null : kitchen.at(b);
            List<String> info = s == null ? null : describe(s);
            if (info == null) s = null;
            Station old = hovering.get(p.getUniqueId());
            if (old != s) {
                if (old != null && old.hoverIfPresent() != null) p.hideEntity(plugin, old.hoverIfPresent());
                if (s != null) {
                    s.setHoverText(info);
                    p.showEntity(plugin, s.hoverLabel());
                    hovering.put(p.getUniqueId(), s);
                } else {
                    hovering.remove(p.getUniqueId());
                }
            } else if (s != null) {
                s.setHoverText(info);
            }
        }
    }

    private List<String> describe(Station s) {
        switch (s.kind) {
            case PLATES -> {
                return List.of((s == kitchen.plates ? "접시대" : "건조대") + ": 깨끗한 접시 " + s.count + "개");
            }
            case RETURN -> {
                return List.of("반납대: 더러운 접시 " + s.count + "개", "싱크대로 가져가 설거지하세요");
            }
            case SINK -> {
                return s.count == 0 ? List.of("싱크대", "더러운 접시를 넣고 꾹 눌러 설거지")
                        : List.of("싱크대: 설거지할 접시 " + s.count + "개", "빈손으로 꾹 눌러 설거지");
            }
            case CRATE -> {
                return List.of(Items.name(s.crate) + " 상자");
            }
            case COUNTER, BOARD, PAN, FRYER -> {
                if (s.item == null) return null;
                if (Items.isPlate(s.item)) return Items.describePlate(Items.contents(s.item));
                String t = Items.type(s.item);
                List<String> lines = new ArrayList<>();
                lines.add(Items.name(t));
                if (Items.CHOPS.containsKey(t)) lines.add("→ 도마에서 썰면: " + Items.name(Items.CHOPS.get(t)));
                else if ("patty_raw".equals(t)) lines.add("→ 프라이팬에 구우면: " + Items.name("patty_cooked"));
                else if (Items.FRIES.containsKey(t)) lines.add("→ 튀김기에 넣으면: " + Items.name(Items.FRIES.get(t)));
                else if ("burnt".equals(t)) lines.add("쓰레기통에 버리세요");
                else if ("dirty_plate".equals(t)) lines.add("싱크대에서 설거지하세요");
                return lines;
            }
            default -> {
                return null;
            }
        }
    }

    private void spawnOrder() {
        double r = rnd.nextDouble();
        Items.Recipe recipe = r < 0.15 ? Items.Recipe.SALAD : r < 0.35 ? Items.Recipe.TOMATO_SALAD
                : r < 0.58 ? Items.Recipe.BURGER : r < 0.8 ? Items.Recipe.SPECIAL : Items.Recipe.FISH_SET;
        orders.add(new Order(recipe, recipe.seconds * 20, new int[]{recipe.seconds * 20}));
        playAll(Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.4f);
    }

    private void tickStation(Station s) {
        Location fx = s.top().add(0, 0.3, 0);
        if ((s.kind == Station.Kind.PAN || s.kind == Station.Kind.FRYER) && s.item != null) {
            boolean pan = s.kind == Station.Kind.PAN;
            String t = Items.type(s.item);
            String done = pan ? ("patty_raw".equals(t) ? "patty_cooked" : null) : Items.FRIES.get(t);
            int cookTime = pan ? PAN_COOK : FRY_COOK, burnTime = pan ? PAN_BURN : FRY_BURN;
            if (done != null) {
                s.cook++;
                s.showBar(s.cook / (float) cookTime, NamedTextColor.GREEN, "");
                if (s.cook % 25 == 0) sound(fx, pan ? Sound.BLOCK_FIRE_AMBIENT : Sound.BLOCK_LAVA_POP, 0.8f, 1.6f);
                if (s.cook >= cookTime) {
                    s.setItem(Items.of(done));
                    s.cook = 0;
                    s.hideBar();
                    sound(fx, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
                }
            } else if (!"burnt".equals(t)) {
                s.cook++;
                warnBurn(s, fx, s.cook, burnTime);
                if (s.cook >= burnTime) {
                    s.setItem(Items.of("burnt"));
                    s.hideBar();
                    sound(fx, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 0.8f, 1f);
                }
            } else if (ticks % 6 == 0) {
                fx.getWorld().spawnParticle(Particle.LARGE_SMOKE, fx, 2, 0.15, 0.1, 0.15, 0.01);
            }
        }
    }

    private void warnBurn(Station s, Location fx, int cook, int burnAt) {
        if (cook < burnAt / 2) return;
        float left = 1f - cook / (float) burnAt;
        boolean blink = (cook / 6) % 2 == 0;
        s.showBar(left, NamedTextColor.RED, blink ? " !" : "  ");
        if (cook % 10 == 0) sound(fx, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.8f);
        if (cook % 8 == 0) fx.getWorld().spawnParticle(Particle.SMOKE, fx, 3, 0.15, 0.05, 0.15, 0.01);
    }

    // ---------------------------------------------------------------- interaction

    void interact(Player p, Station s) {
        if (!running() || !players.contains(p) || kitchen.at(s.block) != s) return;
        ItemStack hand = hand(p);
        String ht = Items.type(hand);
        switch (s.kind) {
            case CRATE -> {
                if (hand == null) {
                    setHand(p, Items.of(s.crate));
                    good(p, Sound.ENTITY_ITEM_PICKUP);
                } else if (Items.isPlate(hand) && Items.canAdd(Items.contents(hand), s.crate)) {
                    setHand(p, Items.addToPlate(hand, s.crate));
                    good(p, Sound.ENTITY_ITEM_PICKUP);
                } else bad(p);
            }
            case PLATES -> {
                if (hand == null && s.count > 0) {
                    s.count--;
                    s.showStack("plate", 0.0);
                    setHand(p, Items.plate(Set.of()));
                    good(p, Sound.BLOCK_DECORATED_POT_INSERT);
                } else if (hand == null) {
                    flash("깨끗한 접시가 없어요! 설거지를 해 주세요", NamedTextColor.RED);
                    bad(p);
                } else if (Items.isPlate(hand) && Items.contents(hand).isEmpty()) {
                    s.count++;
                    s.showStack("plate", 0.0);
                    setHand(p, null);
                    good(p, Sound.BLOCK_DECORATED_POT_INSERT);
                } else bad(p);
            }
            case RETURN -> {
                if (hand == null && s.count > 0) {
                    s.count--;
                    s.showStack("dirty_plate", 0.0);
                    setHand(p, Items.of("dirty_plate"));
                    good(p, Sound.BLOCK_DECORATED_POT_INSERT);
                } else if ("dirty_plate".equals(ht)) {
                    s.count++;
                    s.showStack("dirty_plate", 0.0);
                    setHand(p, null);
                    good(p, Sound.BLOCK_DECORATED_POT_INSERT);
                } else bad(p);
            }
            case SINK -> sink(p, s, hand, ht);
            case TRASH -> {
                if (hand == null || "dirty_plate".equals(ht)) bad(p);
                else if (Items.isPlate(hand)) {
                    if (Items.contents(hand).isEmpty()) bad(p);
                    else {
                        setHand(p, Items.plate(Set.of()));
                        good(p, Sound.BLOCK_COMPOSTER_FILL);
                    }
                } else {
                    setHand(p, null);
                    good(p, Sound.BLOCK_COMPOSTER_FILL);
                }
            }
            case SERVE -> {
                if (Items.isPlate(hand) && !Items.contents(hand).isEmpty()) serve(p, s, Items.contents(hand));
                else bad(p);
            }
            case COUNTER, BOARD -> counter(p, s, hand);
            case PAN, FRYER -> cooker(p, s, hand, ht);
        }
    }

    private void counter(Player p, Station s, ItemStack hand) {
        ItemStack held = s.item;
        if (hand == null) {
            if (held == null) {
                bad(p);
                return;
            }
            String chopped = Items.CHOPS.get(Items.type(held));
            if (s.kind == Station.Kind.BOARD && chopped != null) {
                chop(p, s, chopped);
                return;
            }
            setHand(p, held);
            s.setItem(null);
            s.chop = 0;
            s.hideBar();
            good(p, Sound.ENTITY_ITEM_PICKUP);
            return;
        }
        if (held == null) {
            s.setItem(hand);
            s.chop = 0;
            s.hideBar();
            setHand(p, null);
            good(p, Sound.BLOCK_WOOD_PLACE);
            return;
        }
        if (Items.isPlate(hand) && !Items.isPlate(held) && Items.canAdd(Items.contents(hand), Items.type(held))) {
            setHand(p, Items.addToPlate(hand, Items.type(held)));
            s.setItem(null);
            s.chop = 0;
            s.hideBar();
            good(p, Sound.ENTITY_ITEM_PICKUP);
            return;
        }
        if (!Items.isPlate(hand) && Items.isPlate(held) && Items.canAdd(Items.contents(held), Items.type(hand))) {
            s.setItem(Items.addToPlate(held, Items.type(hand)));
            setHand(p, null);
            good(p, Sound.BLOCK_WOOD_PLACE);
            return;
        }
        bad(p);
    }

    /**
     * Holding right-click repeats the use packet every few ticks; this keeps one hit per 3 server ticks.
     * Uses the server tick, not the per-round counter: that one restarts at 0 every round and blocked chopping.
     */
    private boolean hitReady(Player p) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastHit.get(p.getUniqueId());
        if (last != null && now - last >= 0 && now - last < 3) return false;
        lastHit.put(p.getUniqueId(), now);
        return true;
    }

    private void chop(Player p, Station s, String chopped) {
        if (!hitReady(p)) return;
        s.chop++;
        Location fx = s.top().add(0, 0.15, 0);
        sound(fx, Sound.BLOCK_WOOD_HIT, 1f, 1.4f + rnd.nextFloat() * 0.3f);
        fx.getWorld().spawnParticle(Particle.CRIT, fx, 4, 0.15, 0.05, 0.15, 0.05);
        p.swingMainHand();
        if (s.chop >= CHOPS) {
            s.setItem(Items.of(chopped));
            s.chop = 0;
            s.hideBar();
            sound(fx, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.5f);
        } else {
            s.showBar(s.chop / (float) CHOPS, NamedTextColor.AQUA, "");
        }
    }

    private void sink(Player p, Station s, ItemStack hand, String ht) {
        if ("dirty_plate".equals(ht)) {
            s.count++;
            s.showStack("dirty_plate", -0.1);
            setHand(p, null);
            good(p, Sound.ENTITY_GENERIC_SPLASH);
            return;
        }
        if (hand != null || s.count == 0) {
            bad(p);
            return;
        }
        if (!hitReady(p)) return;
        s.chop++;
        Location fx = s.top().add(0, 0.1, 0);
        sound(fx, Sound.ENTITY_GENERIC_SPLASH, 0.5f, 1.4f + rnd.nextFloat() * 0.4f);
        fx.getWorld().spawnParticle(Particle.SPLASH, fx, 8, 0.2, 0.05, 0.2, 0.1);
        p.swingMainHand();
        if (s.chop >= WASHES) {
            s.chop = 0;
            s.count--;
            s.showStack("dirty_plate", -0.1);
            s.hideBar();
            Station rack = kitchen.rackFor(s);
            rack.count++;
            rack.showStack("plate", 0.0);
            sound(fx, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f);
            Location r = rack.top().add(0, 0.3, 0);
            r.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, r, 6, 0.2, 0.1, 0.2, 0);
        } else {
            s.showBar(s.chop / (float) WASHES, NamedTextColor.AQUA, "");
        }
    }

    private void cooker(Player p, Station s, ItemStack hand, String ht) {
        boolean pan = s.kind == Station.Kind.PAN;
        if (hand == null) {
            if (s.item == null) {
                bad(p);
                return;
            }
            setHand(p, s.item);
            s.setItem(null);
            s.cook = 0;
            s.hideBar();
            good(p, Sound.ENTITY_ITEM_PICKUP);
            return;
        }
        if (s.item == null) {
            boolean ok = pan ? "patty_raw".equals(ht) : Items.FRIES.containsKey(ht);
            if (ok) {
                s.setItem(hand);
                s.cook = 0;
                setHand(p, null);
                good(p, pan ? Sound.BLOCK_FIRE_AMBIENT : Sound.BLOCK_LAVA_POP);
            } else bad(p);
            return;
        }
        if (Items.isPlate(hand) && Items.canAdd(Items.contents(hand), Items.type(s.item))) {
            setHand(p, Items.addToPlate(hand, Items.type(s.item)));
            s.setItem(null);
            s.cook = 0;
            s.hideBar();
            good(p, Sound.ENTITY_ITEM_PICKUP);
            return;
        }
        bad(p);
    }

    private void serve(Player p, Station s, Set<String> contents) {
        for (Order o : orders) {
            if (o.recipe().contents.equals(contents)) {
                int tip = Math.round(8f * o.left()[0] / o.total());
                score += o.recipe().points + tip;
                served++;
                orders.remove(o);
                setHand(p, null);
                dirtyComing.add(new int[]{DIRTY_DELAY});
                Location fx = s.top().add(0, 0.5, 0);
                fx.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, fx, 12, 0.3, 0.3, 0.3, 0);
                playAll(Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                playAll(Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
                flash(o.recipe().name + " 완료! +" + o.recipe().points + "  팁 +" + tip, TextColor.color(0x7CFF6A));
                return;
            }
        }
        flash("주문에 없는 요리예요", NamedTextColor.RED);
        bad(p);
    }

    // ---------------------------------------------------------------- HUD

    static final TextColor INK = TextColor.color(0x4A3426), INK_SOFT = TextColor.color(0x8A6A50);
    static final TextColor PIP_OFF = TextColor.color(0xD9C9A8);

    private String flashText = "";
    private TextColor flashColor = NamedTextColor.WHITE;
    private int flashUntil;

    /** Short message shown in the middle of the bottom HUD for a couple of seconds. */
    private void flash(String text, TextColor color) {
        flashText = text;
        flashColor = color;
        flashUntil = ticks + 50;
        updateHud();
    }

    private void updateHud() {
        if (bars.isEmpty()) return;
        float left = -guiWidth / 2f + 6, right = guiWidth / 2f - 6;

        // Order tickets, top-left: cream cards with the dish, its ingredients and a patience bar.
        for (int i = 0; i < MAX_ORDERS; i++) {
            BossBar bar = bars.get(i);
            if (i >= orders.size()) {
                bar.name(Component.empty());
                continue;
            }
            Order o = orders.get(i);
            float frac = o.left()[0] / (float) o.total();
            TextColor c = frac > 0.5f ? TextColor.color(0x3FA535) : frac > 0.25f ? TextColor.color(0xE0A000) : TextColor.color(0xD8342C);
            Glyphs.Line line = new Glyphs.Line().icon(o.recipe().icon).gap(3).text(o.recipe().name, INK).gap(5);
            for (String h : o.recipe().hint) {
                if (h.equals("+")) line.text("+", INK_SOFT);
                else if (h.startsWith("x")) line.text(h, INK_SOFT);
                else line.icon(h);
            }
            line.gap(6);
            int pips = 8, full = (int) Math.ceil(frac * pips);
            for (int k = 0; k < pips; k++) line.icon("pip", k < full ? c : PIP_OFF);
            Glyphs.Line card = Glyphs.Line.panel("card", line.width() + 8);
            bar.name(new Glyphs.Overlay().at(left, card).at(left + 6, line).build());
        }

        // Bottom bar: score and clean plates on the left, big timer on the right, messages in the middle.
        int secs = Math.max(0, (timeLeft + 19) / 20);
        int clean = 0;
        for (Station s : kitchen.stations) if (s.kind == Station.Kind.PLATES) clean += s.count;
        Glyphs.Line scoreLine = new Glyphs.Line().icon("big_coin").gap(4).digits(String.valueOf(score), GOLD)
                .gap(10).icon("big_plate").gap(4).digits(String.valueOf(clean), clean == 0 ? TextColor.color(0xFF6A5A) : NamedTextColor.WHITE);
        Glyphs.Line scorePanel = Glyphs.Line.panel("nav", scoreLine.width() + 10);
        TextColor timeColor = secs <= 30 && secs % 2 == 0 ? TextColor.color(0xFF6A5A) : NamedTextColor.WHITE;
        Glyphs.Line timeLine = new Glyphs.Line().icon("big_clock").gap(4).digits(String.format("%d:%02d", secs / 60, secs % 60), timeColor);
        Glyphs.Line timePanel = Glyphs.Line.panel("nav", timeLine.width() + 10);
        Glyphs.Overlay bottom = new Glyphs.Overlay()
                .at(left, scorePanel).at(left + 7, scoreLine)
                .at(right - timePanel.width(), timePanel).at(right - timePanel.width() + 7, timeLine);
        if (ticks < flashUntil) {
            Glyphs.Line msg = new Glyphs.Line().text(flashText, flashColor);
            bottom.at(-msg.width() / 2, msg);
        }
        Component bar = bottom.build();
        players.forEach(p -> p.sendActionBar(bar));
    }

    // ---------------------------------------------------------------- helpers

    static ItemStack hand(Player p) {
        ItemStack i = p.getInventory().getItemInMainHand();
        return i.getType().isAir() ? null : i;
    }

    static void setHand(Player p, ItemStack i) {
        p.getInventory().setItemInMainHand(i == null ? null : i.clone());
    }

    private void title(Component main, Component sub) {
        Title t = Title.title(main, sub, Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200)));
        players.forEach(p -> p.showTitle(t));
    }

    private void playAll(Sound s, float vol, float pitch) {
        players.forEach(p -> p.playSound(p, s, SoundCategory.MASTER, vol, pitch));
    }

    private static void good(Player p, Sound s) {
        p.playSound(p, s, SoundCategory.MASTER, 0.7f, 1.2f);
    }

    private static void bad(Player p) {
        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.MASTER, 0.6f, 0.6f);
    }

    private static void sound(Location l, Sound s, float vol, float pitch) {
        l.getWorld().playSound(l, s, SoundCategory.BLOCKS, vol, pitch);
    }
}
