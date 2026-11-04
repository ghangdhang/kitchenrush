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
        SOLO("1인", 18, 3, 0),
        DUO("다인", 13, 3, 0),
        /** One team's arena in a /letscook match: bigger crew, more plates, fixed 5 minutes. */
        TEAM("팀전", 8, 6, 300);

        final String label;
        final int orderEvery, plates, seconds;

        Mode(String label, int orderEvery, int plates, int seconds) {
            this.label = label;
            this.orderEvery = orderEvery;
            this.plates = plates;
            this.seconds = seconds;
        }
    }

    static final int MAX_ORDERS = 4, CHOPS = 6, WASHES = 6, DIRTY_DELAY = 7 * 20;
    static final int PAN_COOK = 120, PAN_BURN = 200, FRY_COOK = 140, FRY_BURN = 220, RICE_COOK = 160, RICE_BURN = 220;
    /** Extinguisher sprays (hold right-click) needed to put a fire out. */
    static final int FIRE_SPRAYS = 5;

    static boolean heated(Station.Kind k) {
        return k == Station.Kind.PAN || k == Station.Kind.FRYER || k == Station.Kind.COOKER;
    }

    /** Raw input -> cooked result for a heated station. */
    static Map<String, String> recipeOf(Station.Kind k) {
        return switch (k) {
            case PAN -> Items.PAN;
            case FRYER -> Items.FRIES;
            default -> Items.COOKER;
        };
    }

    static int cookTime(Station.Kind k) {
        return k == Station.Kind.PAN ? PAN_COOK : k == Station.Kind.FRYER ? FRY_COOK : RICE_COOK;
    }

    static int burnTime(Station.Kind k) {
        return k == Station.Kind.PAN ? PAN_BURN : k == Station.Kind.FRYER ? FRY_BURN : RICE_BURN;
    }

    /** Which heated station cooks this raw item, for hover hints and saved progress. */
    static Station.Kind cookerFor(String raw) {
        if (Items.PAN.containsKey(raw)) return Station.Kind.PAN;
        if (Items.FRIES.containsKey(raw)) return Station.Kind.FRYER;
        if (Items.COOKER.containsKey(raw)) return Station.Kind.COOKER;
        return null;
    }
    static final TextColor GOLD = TextColor.color(0xFFD84A);

    record Order(Items.Recipe recipe, int total, int[] left) {
    }

    private final Music music = new Music();
    private final Random rnd = new Random();
    private final List<Order> orders = new ArrayList<>();
    private final List<BossBar> bars = new ArrayList<>();
    /** A served plate on its way back (dirty) to the return counter of the half it was served from. */
    private record Dirty(int[] left, Station to) {
    }

    private final List<Dirty> dirtyComing = new ArrayList<>();
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

    int score() {
        return score;
    }

    boolean active() {
        return phase != Phase.IDLE;
    }

    /** Set for team arenas: the match shows every team's score and announces the winner at the end. */
    Match match;
    Teams.Team team;
    int finalServed, finalMissed;

    private void served(int served, int missed) {
        finalServed = served;
        finalMissed = missed;
    }

    static final double DEFAULT_JUMP = 0.41999998688697815;

    /** Jumping is off during a round so nobody hops over counters; the kitchens have walk-through gaps instead. */
    static void setJump(Player p, boolean allowed) {
        var jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) jump.setBaseValue(allowed ? DEFAULT_JUMP : 0);
    }

    // ---------------------------------------------------------------- round lifecycle

    private void prepare(Player p, int index) {
        p.teleport(kitchen.spawns.get(index % kitchen.spawns.size()));
        p.setGameMode(GameMode.ADVENTURE);
        p.getInventory().clear();
        p.getInventory().setHeldItemSlot(0);
        p.setFoodLevel(20);
        var hp = p.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) p.setHealth(hp.getValue());
        p.setWalkSpeed(0.24f);
        setJump(p, false);
    }

    /** Drops a player into a round that is already under way (e.g. they joined the server mid-match). */
    void join(Player p) {
        if (phase == Phase.IDLE || players.contains(p)) return;
        players.add(p);
        prepare(p, players.size() - 1);
        for (BossBar b : bars) p.showBossBar(b);
        updateHud();
    }

    void start(Mode mode, Kitchen kitchen, List<Player> who) {
        stop();
        this.mode = mode;
        this.kitchen = kitchen;
        kitchen.build();
        players.addAll(who);
        for (int i = 0; i < players.size(); i++) prepare(players.get(i), i);
        for (Station s : kitchen.stations) {
            if (s.kind == Station.Kind.PLATES) {
                s.count = kitchen.stands.contains(s) ? mode.plates / kitchen.stands.size() : 0;
                s.showStack("plate", 0.0);
            }
        }
        hovering.clear();
        for (Station r : kitchen.returnList) {
            r.count = 0;
            r.showStack("dirty_plate", 0.0);
        }
        orders.clear();
        dirtyComing.clear();
        score = served = missed = ticks = 0;
        timeLeft = (mode.seconds > 0 ? mode.seconds : roundSeconds) * 20;
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

    /** Where cooks go once a round is over (set by the plugin to the lobby spawn). */
    Location lobby;
    static final int RETURN_DELAY = 60;

    /** After the results have been on screen for a moment, sends everyone who played back to the lobby. */
    private void sendHomeLater() {
        if (lobby == null) return;
        List<Player> who = List.copyOf(players);
        Location to = lobby.clone();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : who) {
                if (p.isOnline()) p.teleport(to);
            }
        }, RETURN_DELAY);
    }

    private void finish() {
        sendHomeLater();
        if (match != null) {
            // The match announces the ranking (and plays the jingle) once every arena is done.
            served(served, missed);
            music.stop(players);
            stop();
            return;
        }
        for (Player p : players) {
            p.showTitle(Title.title(
                    Component.text("영업 종료!", GOLD),
                    Component.text("총 점수 " + score + "점", NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofSeconds(1))));
            p.sendMessage(Component.text("── 오늘의 영업 결과 (" + mode.label + ") ──", NamedTextColor.GOLD));
            p.sendMessage(Component.text(" 총 점수: " + score + "점   서빙: " + served + "접시   놓친 주문: " + missed, NamedTextColor.WHITE));
        }
        music.stop(players);
        Jingle.play(players, score > 0 ? Jingle.CLEAR : Jingle.FAIL);
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
            flash("30초 남았어요! 서둘러요!", BAD);
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
                missed++;
                flash(o.recipe().name + " 주문을 놓쳤어요…", BAD);
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
        Iterator<Dirty> it = dirtyComing.iterator();
        while (it.hasNext()) {
            Dirty t = it.next();
            if (--t.left()[0] <= 0) {
                it.remove();
                t.to().count++;
                t.to().showStack("dirty_plate", 0.0);
                sound(t.to().top(), Sound.BLOCK_DECORATED_POT_INSERT, 0.8f, 0.8f);
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
                return List.of((kitchen.stands.contains(s) ? "접시대" : "건조대") + ": 깨끗한 접시 " + s.count + "개");
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
            case COUNTER, BOARD, PAN, FRYER, COOKER -> {
                if (s.onFire) return List.of("불이 났어요!", "소화기를 들고 꾹 눌러 끄세요 (" + s.fireHits + "/" + FIRE_SPRAYS + ")");
                if (s.item == null) return s.kind == Station.Kind.COOKER ? List.of("밥솥", "쌀을 넣으면 밥이 돼요") : null;
                if (Items.isPlate(s.item)) return Items.describePlate(Items.contents(s.item));
                String t = Items.type(s.item);
                List<String> lines = new ArrayList<>();
                Station.Kind cooks = cookerFor(t);
                int cooked = heated(s.kind) ? s.cook : Items.cook(s.item);
                lines.add(Items.name(t) + (cooks != null && cooked > 0
                        ? " (익힘 " + Math.min(99, cooked * 100 / cookTime(cooks)) + "%)" : ""));
                if (Items.CHOPS.containsKey(t)) lines.add("→ 도마에서 썰면: " + Items.name(Items.CHOPS.get(t)));
                else if (cooks == Station.Kind.PAN) lines.add("→ 프라이팬에 구우면: " + Items.name(Items.PAN.get(t)));
                else if (cooks == Station.Kind.FRYER) lines.add("→ 튀김기에 넣으면: " + Items.name(Items.FRIES.get(t)));
                else if (cooks == Station.Kind.COOKER) lines.add("→ 밥솥에 넣으면: " + Items.name(Items.COOKER.get(t)));
                else if ("burnt".equals(t)) lines.add("쓰레기통에 버리세요");
                else if ("dirty_plate".equals(t)) lines.add("싱크대에서 설거지하세요");
                else if ("extinguisher".equals(t)) lines.add("불난 곳에 대고 꾹 누르면 불이 꺼져요");
                if (Items.CHOPS.containsKey(t) && "fish".equals(t)) lines.add("  (다진 생선은 초밥에도 써요)");
                return lines;
            }
            default -> {
                return null;
            }
        }
    }

    private void spawnOrder() {
        double r = rnd.nextDouble();
        Items.Recipe recipe = r < 0.10 ? Items.Recipe.SALAD : r < 0.24 ? Items.Recipe.TOMATO_SALAD
                : r < 0.40 ? Items.Recipe.BURGER : r < 0.56 ? Items.Recipe.SUSHI : r < 0.71 ? Items.Recipe.SPECIAL
                : r < 0.86 ? Items.Recipe.MEAT_SET : Items.Recipe.FISH_SET;
        orders.add(new Order(recipe, recipe.seconds * 20, new int[]{recipe.seconds * 20}));
        playAll(Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.4f);
    }

    private void tickStation(Station s) {
        if (!heated(s.kind)) return;
        Location fx = s.top().add(0, 0.3, 0);
        if (s.onFire) {
            // Flames until someone puts it out; nothing cooks here meanwhile.
            if (ticks % 2 == 0) fx.getWorld().spawnParticle(Particle.FLAME, fx, 8, 0.25, 0.25, 0.25, 0.02);
            if (ticks % 5 == 0) fx.getWorld().spawnParticle(Particle.LARGE_SMOKE, fx.clone().add(0, 0.4, 0), 2, 0.2, 0.1, 0.2, 0.02);
            if (ticks % 20 == 0) sound(fx, Sound.BLOCK_FIRE_AMBIENT, 1f, 1f);
            s.showBar(1f, NamedTextColor.RED, (ticks / 8) % 2 == 0 ? " 불!" : "    ");
            return;
        }
        if (s.item == null) return;
        String t = Items.type(s.item);
        String done = recipeOf(s.kind).get(t);
        int cookTime = cookTime(s.kind), burnTime = burnTime(s.kind);
        if (done != null) {
            s.cook++;
            s.showBar(s.cook / (float) cookTime, NamedTextColor.GREEN, "");
            if (s.cook % 25 == 0) sound(fx, s.kind == Station.Kind.FRYER ? Sound.BLOCK_LAVA_POP
                    : s.kind == Station.Kind.COOKER ? Sound.BLOCK_BUBBLE_COLUMN_BUBBLE_POP : Sound.BLOCK_FIRE_AMBIENT, 0.8f, 1.6f);
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
                // Overcooked: it burns and catches fire.
                s.setItem(Items.of("burnt"));
                s.onFire = true;
                s.fireHits = 0;
                s.cook = 0;
                sound(fx, Sound.ITEM_FIRECHARGE_USE, 1f, 0.8f);
                flash("불이 났어요! 소화기로 끄세요!", BAD);
            }
        } else if (ticks % 6 == 0) {
            fx.getWorld().spawnParticle(Particle.LARGE_SMOKE, fx, 2, 0.15, 0.1, 0.15, 0.01);
        }
    }

    /** One spray from an extinguisher at a station; enough sprays put a fire out. */
    private void spray(Player p, Station s) {
        if (!hitReady(p)) return;
        p.swingMainHand();
        Location eye = p.getEyeLocation(), target = s.top().add(0, 0.3, 0);
        var dir = target.toVector().subtract(eye.toVector());
        for (int i = 1; i <= 6; i++) {
            Location at = eye.clone().add(dir.clone().multiply(i / 6.0));
            at.getWorld().spawnParticle(Particle.CLOUD, at, 2, 0.05, 0.05, 0.05, 0.01);
        }
        target.getWorld().spawnParticle(Particle.SPLASH, target, 12, 0.3, 0.2, 0.3, 0.1);
        sound(target, Sound.ENTITY_GENERIC_SPLASH, 0.6f, 1.6f);
        if (!s.onFire) return;
        s.fireHits++;
        s.showBar(s.fireHits / (float) FIRE_SPRAYS, NamedTextColor.AQUA, "");
        if (s.fireHits >= FIRE_SPRAYS) {
            s.onFire = false;
            s.fireHits = 0;
            s.hideBar();
            sound(target, Sound.BLOCK_FIRE_EXTINGUISH, 1f, 1f);
            target.getWorld().spawnParticle(Particle.CLOUD, target, 20, 0.3, 0.3, 0.3, 0.02);
            flash("불을 껐어요! 탄 음식은 버려 주세요", GOOD);
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
        // An extinguisher sprays at whatever cooking spot you point it at.
        if ("extinguisher".equals(ht) && (heated(s.kind) || s.onFire)) {
            spray(p, s);
            return;
        }
        if (s.onFire) {
            flash("불이 났어요! 소화기로 먼저 끄세요", BAD);
            bad(p);
            return;
        }
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
                    flash("깨끗한 접시가 없어요! 설거지를 해 주세요", BAD);
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
            case PAN, FRYER, COOKER -> cooker(p, s, hand, ht);
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

    /** Pan, fryer and rice cooker: put raw food in, take it out (half-cooked keeps its progress), or plate it. */
    private void cooker(Player p, Station s, ItemStack hand, String ht) {
        Map<String, String> accepts = recipeOf(s.kind);
        if (hand == null) {
            if (s.item == null) {
                bad(p);
                return;
            }
            // Half-cooked food keeps how far it got, so putting it back on continues instead of restarting.
            boolean stillCooking = accepts.containsKey(Items.type(s.item));
            setHand(p, stillCooking ? Items.withCook(s.item, s.cook) : s.item);
            s.setItem(null);
            s.cook = 0;
            s.hideBar();
            good(p, Sound.ENTITY_ITEM_PICKUP);
            return;
        }
        if (s.item == null) {
            if (accepts.containsKey(ht)) {
                s.setItem(hand);
                s.cook = Items.cook(hand);
                setHand(p, null);
                good(p, s.kind == Station.Kind.FRYER ? Sound.BLOCK_LAVA_POP : Sound.BLOCK_FIRE_AMBIENT);
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
                score += o.recipe().points;
                served++;
                orders.remove(o);
                setHand(p, null);
                dirtyComing.add(new Dirty(new int[]{DIRTY_DELAY}, kitchen.returnFor(s)));
                Location fx = s.top().add(0, 0.5, 0);
                fx.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, fx, 12, 0.3, 0.3, 0.3, 0);
                playAll(Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                playAll(Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.5f);
                flash(o.recipe().name + " 완료!  +" + o.recipe().points + "점", GOOD);
                return;
            }
        }
        flash("주문에 없는 요리예요", BAD);
        bad(p);
    }

    // ---------------------------------------------------------------- HUD

    static final TextColor INK = TextColor.color(0x4A3426), INK_SOFT = TextColor.color(0x8A6A50);
    static final TextColor PIP_OFF = TextColor.color(0xD9C9A8);
    /** Message colours, dark enough to read on the cream notice card. */
    static final TextColor GOOD = TextColor.color(0x2E8B2E), BAD = TextColor.color(0xC0392B);

    private String flashText = "";
    private TextColor flashColor = INK;
    private int flashUntil;

    /** Short notice shown on a card in the top-right corner for a couple of seconds. */
    private void flash(String text, TextColor color) {
        flashText = text;
        flashColor = color;
        flashUntil = ticks + 50;
        updateHud();
    }

    private void updateHud() {
        if (bars.isEmpty()) return;
        float left = -guiWidth / 2f + 6, right = guiWidth / 2f - 6;

        // Order tickets top-left (cream cards with the dish, its ingredients and a patience bar);
        // the first row also carries the current notice, right-aligned in the top-right corner.
        for (int i = 0; i < MAX_ORDERS; i++) {
            BossBar bar = bars.get(i);
            Glyphs.Overlay row = new Glyphs.Overlay();
            if (i == 0 && ticks < flashUntil) {
                Glyphs.Line msg = new Glyphs.Line().text(flashText, flashColor);
                Glyphs.Line notice = Glyphs.Line.panel("card", msg.width() + 8);
                row.at(right - notice.width(), notice).at(right - notice.width() + 6, msg);
            }
            if (i >= orders.size()) {
                bar.name(row.build());
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
            bar.name(row.at(left, card).at(left + 6, line).build());
        }

        // Action bar: the score in the middle (every team's score in a match), the big timer on the right.
        int secs = Math.max(0, (timeLeft + 19) / 20);
        Glyphs.Line scoreLine = new Glyphs.Line();
        if (match == null) {
            scoreLine.icon("big_coin").gap(4).digits(String.valueOf(score), GOLD).gap(3).text("점", GOLD);
        } else {
            boolean first = true;
            for (Game g : match.games) {
                if (!first) scoreLine.gap(12);
                first = false;
                scoreLine.text(g.team.label + " ", g.team.color).digits(String.valueOf(g.score), g.team.color);
            }
        }
        Glyphs.Line scorePanel = Glyphs.Line.panel("nav", scoreLine.width() + 10);
        TextColor timeColor = secs <= 30 && secs % 2 == 0 ? TextColor.color(0xFF6A5A) : NamedTextColor.WHITE;
        Glyphs.Line timeLine = new Glyphs.Line().icon("big_clock").gap(4).digits(String.format("%d:%02d", secs / 60, secs % 60), timeColor);
        Glyphs.Line timePanel = Glyphs.Line.panel("nav", timeLine.width() + 10);
        float mid = -scorePanel.width() / 2;
        Component bar = new Glyphs.Overlay()
                .at(mid, scorePanel).at(mid + 7, scoreLine)
                .at(right - timePanel.width(), timePanel).at(right - timePanel.width() + 7, timeLine)
                .build();
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
