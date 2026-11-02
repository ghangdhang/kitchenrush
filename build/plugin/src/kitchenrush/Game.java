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
    static final int PAN_COOK = 120, PAN_BURN = 200, FRY_COOK = 140, FRY_BURN = 220, POT_COOK = 200, POT_BURN = 260;
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

    private Phase phase = Phase.IDLE;
    private Mode mode = Mode.SOLO;
    private Kitchen kitchen;
    private int ticks, timeLeft, score, served, missed, nextOrder;
    int roundSeconds = 180, guiWidth = 480;

    boolean running() {
        return phase == Phase.RUNNING;
    }

    boolean playing(Player p) {
        return phase != Phase.IDLE && players.contains(p);
    }

    Kitchen kitchen() {
        return kitchen;
    }

    /** Side of a split kitchen this player must stay on: -1 west, 1 east, 0 anywhere. */
    int side(Player p) {
        if (mode != Mode.DUO || phase == Phase.IDLE) return 0;
        int i = players.indexOf(p);
        return i == 0 ? -1 : i == 1 ? 1 : 0;
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
        }
        kitchen.plates.count = PLATES;
        kitchen.plates.showStack("plate", 0.03);
        kitchen.returns.count = 0;
        kitchen.returns.showStack("dirty_plate", 0.03);
        orders.clear();
        dirtyComing.clear();
        score = served = missed = ticks = 0;
        timeLeft = roundSeconds * 20;
        phase = Phase.COUNTDOWN;
        for (int i = 0; i < 5; i++) {
            BossBar bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
            bars.add(bar);
            players.forEach(p -> p.showBossBar(bar));
        }
        updateHud();
    }

    void stop() {
        for (Player p : players) {
            for (BossBar b : bars) p.hideBossBar(b);
            if (p.isOnline()) {
                p.getInventory().clear();
                p.setWalkSpeed(0.2f);
            }
        }
        bars.clear();
        players.clear();
        music.stop();
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
            p.playSound(p, stars > 0 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 1f, 1f);
        }
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
                playAll(Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
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
        music.tick(players, timeLeft <= 30 * 20);
        if (timeLeft == 30 * 20) {
            players.forEach(p -> p.sendActionBar(Component.text("30초 남았어요! 서둘러요!", NamedTextColor.RED)));
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
                players.forEach(p -> p.sendActionBar(Component.text(o.recipe().name + " 주문을 놓쳤어요… -10", NamedTextColor.RED)));
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
                kitchen.returns.showStack("dirty_plate", 0.03);
                sound(kitchen.returns.top(), Sound.BLOCK_DECORATED_POT_INSERT, 0.8f, 0.8f);
            }
        }
    }

    private void spawnOrder() {
        double r = rnd.nextDouble();
        Items.Recipe recipe = r < 0.15 ? Items.Recipe.SALAD : r < 0.37 ? Items.Recipe.BURGER : r < 0.57 ? Items.Recipe.SOUP
                : r < 0.8 ? Items.Recipe.SPECIAL : Items.Recipe.FISH_SET;
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
        } else if (s.kind == Station.Kind.POT) {
            if (s.tomatoes == 3 && !s.soupDone && !s.burnt) {
                s.cook++;
                s.showBar(s.cook / (float) POT_COOK, NamedTextColor.GREEN, "");
                if (ticks % 8 == 0) fx.getWorld().spawnParticle(Particle.BUBBLE_POP, fx.clone().add(0, -0.2, 0), 3, 0.2, 0.05, 0.2, 0);
                if (s.cook >= POT_COOK) {
                    s.soupDone = true;
                    s.cook = 0;
                    s.hideBar();
                    s.refresh();
                    sound(fx, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
                }
            } else if (s.soupDone && !s.burnt) {
                s.cook++;
                warnBurn(s, fx, s.cook, POT_BURN);
                if (s.cook >= POT_BURN) {
                    s.burnt = true;
                    s.soupDone = false;
                    s.hideBar();
                    s.refresh();
                    sound(fx, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 0.8f, 1f);
                }
            } else if (s.burnt && ticks % 6 == 0) {
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
                    s.showStack("plate", 0.03);
                    setHand(p, Items.plate(Set.of()));
                    good(p, Sound.BLOCK_DECORATED_POT_INSERT);
                } else if (hand == null) {
                    p.sendActionBar(Component.text("깨끗한 접시가 없어요! 설거지를 해 주세요", NamedTextColor.RED));
                    bad(p);
                } else if (Items.isPlate(hand) && Items.contents(hand).isEmpty()) {
                    s.count++;
                    s.showStack("plate", 0.03);
                    setHand(p, null);
                    good(p, Sound.BLOCK_DECORATED_POT_INSERT);
                } else bad(p);
            }
            case RETURN -> {
                if (hand == null && s.count > 0) {
                    s.count--;
                    s.showStack("dirty_plate", 0.03);
                    setHand(p, Items.of("dirty_plate"));
                    good(p, Sound.BLOCK_DECORATED_POT_INSERT);
                } else if ("dirty_plate".equals(ht)) {
                    s.count++;
                    s.showStack("dirty_plate", 0.03);
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
            case POT -> pot(p, s, hand, ht);
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
            s.showStack("dirty_plate", -0.15);
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
            s.showStack("dirty_plate", -0.15);
            s.hideBar();
            kitchen.plates.count++;
            kitchen.plates.showStack("plate", 0.03);
            sound(fx, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.5f);
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

    private void pot(Player p, Station s, ItemStack hand, String ht) {
        if (hand == null) {
            if (s.burnt) {
                setHand(p, Items.of("burnt"));
                s.resetPot();
                good(p, Sound.ENTITY_ITEM_PICKUP);
            } else bad(p);
            return;
        }
        if ("tomato_chopped".equals(ht)) {
            if (s.tomatoes < 3 && !s.soupDone && !s.burnt) {
                s.tomatoes++;
                s.cook = 0;
                setHand(p, null);
                s.refresh();
                good(p, Sound.ENTITY_GENERIC_SPLASH);
            } else bad(p);
            return;
        }
        if (Items.isPlate(hand) && s.soupDone && Items.canAdd(Items.contents(hand), "soup")) {
            setHand(p, Items.addToPlate(hand, "soup"));
            s.resetPot();
            good(p, Sound.ITEM_BOTTLE_FILL);
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
                Component msg = Component.text(o.recipe().name + " 완료! +" + o.recipe().points + "  팁 +" + tip, NamedTextColor.GREEN);
                players.forEach(x -> x.sendActionBar(msg));
                updateHud();
                return;
            }
        }
        p.sendActionBar(Component.text("주문에 없는 요리예요", NamedTextColor.RED));
        bad(p);
    }

    // ---------------------------------------------------------------- HUD

    private void updateHud() {
        if (bars.isEmpty()) return;
        int secs = Math.max(0, (timeLeft + 19) / 20);
        TextColor timeColor = secs <= 30 ? NamedTextColor.RED : NamedTextColor.WHITE;
        int clean = kitchen.plates == null ? 0 : kitchen.plates.count;
        bars.get(0).name(new Glyphs.Line()
                .icon("clock").gap(3).text(String.format("%d:%02d", secs / 60, secs % 60), timeColor)
                .gap(10).icon("coin").gap(3).text(String.valueOf(score), GOLD)
                .gap(10).icon("plate").gap(3).text(clean + "/" + PLATES, clean == 0 ? NamedTextColor.RED : NamedTextColor.WHITE)
                .leftAligned(guiWidth));
        for (int i = 0; i < MAX_ORDERS; i++) {
            BossBar bar = bars.get(i + 1);
            if (i >= orders.size()) {
                bar.name(Component.empty());
                continue;
            }
            Order o = orders.get(i);
            float frac = o.left()[0] / (float) o.total();
            TextColor c = frac > 0.5f ? NamedTextColor.GREEN : frac > 0.25f ? NamedTextColor.YELLOW : NamedTextColor.RED;
            Glyphs.Line line = new Glyphs.Line().icon(o.recipe().icon).gap(3).text(o.recipe().name, NamedTextColor.WHITE).gap(5);
            for (String h : o.recipe().hint) {
                if (h.equals("+")) line.text("+", NamedTextColor.GRAY);
                else if (h.startsWith("x")) line.text(h, NamedTextColor.GRAY);
                else line.icon(h);
            }
            line.gap(6);
            int pips = 8, full = (int) Math.ceil(frac * pips);
            for (int k = 0; k < pips; k++) line.icon("pip", k < full ? c : NamedTextColor.DARK_GRAY);
            bar.name(line.leftAligned(guiWidth));
        }
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
