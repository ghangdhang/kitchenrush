package kitchenrush;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The resource pack itself is published and sent to players by ResourcepackBuilder (server folder "pack"). */
public final class KitchenRush extends JavaPlugin implements Listener {
    /** Team arenas sit side by side east of the solo/duo kitchens, one per team. */
    private static final int TEAM_ARENA_X = 200, TEAM_ARENA_STEP = 70;

    private World world;
    private Kitchen solo, duo;
    private final List<Kitchen> arenas = new ArrayList<>();
    private Game game;
    private Match match;
    private final Teams teams = new Teams();
    private final Dummies dummies = new Dummies();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            Items.init(this);
            Glyphs.load(this);
            Music.load(this);
        } catch (Exception e) {
            getLogger().severe("Failed to start: " + e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        world = Bukkit.getWorlds().get(0);
        solo = new Kitchen("1인 주방", world, Kitchen.SOLO, 0, 100, 0, new double[][]{{6.5, 6.5}});
        duo = new Kitchen("2인 주방", world, Kitchen.DUO, 100, 100, 0,
                new double[][]{{4.5, 5.5}, {13.5, 5.5}, {4.5, 3.5}, {13.5, 3.5}});
        for (Teams.Team t : Teams.Team.values()) {
            arenas.add(new Kitchen(t.label + " 주방", world, Kitchen.TEAM,
                    TEAM_ARENA_X + t.ordinal() * TEAM_ARENA_STEP, 100, 0, Kitchen.TEAM_SPAWNS));
        }
        game = newGame();
        Kitchen.prepareWorld(world, solo.spawns.get(0));
        solo.build();
        duo.build();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("Kitchens ready.");
    }

    private Game newGame() {
        Game g = new Game(this);
        g.roundSeconds = getConfig().getInt("round-seconds", 180);
        g.guiWidth = getConfig().getInt("hud-gui-width", 480);
        return g;
    }

    private void tick() {
        game.tick(world);
        if (match != null) {
            for (Game g : match.games) g.tick(world);
            match.tick();
        }
    }

    @Override
    public void onDisable() {
        if (game != null) game.stop();
        if (match != null) match.stopAll();
        if (solo != null) solo.clear();
        if (duo != null) duo.clear();
        arenas.forEach(Kitchen::clear);
    }

    private List<Game> allGames() {
        List<Game> all = new ArrayList<>();
        all.add(game);
        if (match != null) all.addAll(match.games);
        return all;
    }

    private Game gameOf(Player p) {
        for (Game g : allGames()) if (g.playing(p)) return g;
        return null;
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName()) {
            case "팀분배" -> teamSplit(sender, args);
            case "전체초기화" -> resetAll(sender);
            case "letscook" -> letsCook(sender);
            case "fp" -> fakePlayers(sender, args);
            default -> cook(sender, args);
        };
    }

    private boolean teamSplit(CommandSender sender, String[] args) {
        int count;
        try {
            count = args.length == 0 ? 2 : Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            count = -1;
        }
        if (count < 2 || count > 4) {
            sender.sendMessage(Component.text("/팀분배 <2|3|4>  (도·개·걸·윷 팀으로 나눠요)", NamedTextColor.GRAY));
            return true;
        }
        if (match != null && !match.over()) {
            sender.sendMessage(Component.text("경기 중에는 팀을 나눌 수 없어요.", NamedTextColor.RED));
            return true;
        }
        List<Entity> everyone = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getGameMode() != GameMode.SPECTATOR) everyone.add(p);
        everyone.addAll(Dummies.all(world));
        if (everyone.size() < count) {
            sender.sendMessage(Component.text("사람이 " + count + "명 이상 있어야 " + count + "팀으로 나눌 수 있어요. (/fp player <인원> 으로 더미 추가)", NamedTextColor.RED));
            return true;
        }
        Map<Teams.Team, List<String>> result = teams.distribute(everyone, count);
        Bukkit.broadcast(Component.text("── 팀 분배 결과 (" + count + "팀) ──", NamedTextColor.GOLD));
        result.forEach((t, names) -> Bukkit.broadcast(t.prefix()
                .append(Component.text(" " + names.size() + "명: " + String.join(", ", names), NamedTextColor.WHITE))));
        Bukkit.broadcast(Component.text("OP가 /letscook 을 입력하면 팀 대항전이 시작돼요!", NamedTextColor.AQUA));
        return true;
    }

    private boolean resetAll(CommandSender sender) {
        game.stop();
        if (match != null) match.stopAll();
        match = null;
        teams.clear();
        int removed = dummies.removeAll(world);
        arenas.forEach(Kitchen::clear);
        solo.build();
        duo.build();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.teleport(lobby());
            p.getInventory().clear();
            Game.setJump(p, true);
            p.setWalkSpeed(0.2f);
            p.sendActionBar(Component.empty());
        }
        Bukkit.broadcast(Component.text("전체 초기화 완료! (팀 해제, 경기 종료, 더미 " + removed + "명 제거)", NamedTextColor.YELLOW));
        return true;
    }

    private boolean letsCook(CommandSender sender) {
        List<Teams.Team> active = teams.activeTeams();
        if (active.size() < 2) {
            sender.sendMessage(Component.text("먼저 /팀분배 <2|3|4> 로 팀을 나눠 주세요.", NamedTextColor.RED));
            return true;
        }
        game.stop();
        if (match != null) match.stopAll();
        match = new Match();
        for (Teams.Team t : active) {
            Kitchen arena = arenas.get(t.ordinal());
            List<Player> members = new ArrayList<>();
            List<Entity> stand = new ArrayList<>();
            for (UUID id : teams.membersOf(t)) {
                Entity e = Bukkit.getEntity(id);
                if (e instanceof Player p && p.isOnline()) members.add(p);
                else if (e != null) stand.add(e);
            }
            Game g = newGame();
            g.match = match;
            g.team = t;
            match.add(g, members);
            g.start(Game.Mode.TEAM, arena, members);
            // Dummies just stand in their team's kitchen, spread over the free floor so each one is visible.
            List<Location> floor = arena.floorSpots();
            for (int i = 0; i < stand.size(); i++) {
                stand.get(i).teleport(floor.get((int) ((i + 0.5) * floor.size() / stand.size()) % floor.size()));
            }
            Component hello = t.prefix().append(Component.text(" 주방에 오신 걸 환영해요! 5분 동안 최대한 많은 점수를 모으세요.", NamedTextColor.AQUA));
            members.forEach(p -> p.sendMessage(hello));
            Bukkit.broadcast(t.prefix().append(Component.text(" " + (members.size() + stand.size()) + "명 (플레이어 "
                    + members.size() + ", 더미 " + stand.size() + ")", NamedTextColor.WHITE)));
        }
        Bukkit.broadcast(Component.text("팀 대항 요리 대결 시작! (" + active.size() + "팀, 5분)", NamedTextColor.GOLD));
        return true;
    }

    private boolean fakePlayers(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("플레이어만 소환할 수 있어요.");
            return true;
        }
        int count;
        try {
            count = args.length >= 2 && args[0].equalsIgnoreCase("player") ? Integer.parseInt(args[1]) : -1;
        } catch (NumberFormatException e) {
            count = -1;
        }
        if (count < 1 || count > 40) {
            sender.sendMessage(Component.text("/fp player <인원(1~40)>  내 스킨을 입은 테스트용 더미를 그 인원만큼 맞춰요", NamedTextColor.GRAY));
            return true;
        }
        // Sets the total rather than adding, so repeated tests don't pile up extra dummies.
        int old = dummies.removeAll(world);
        dummies.spawn(p, count);
        sender.sendMessage(Component.text("더미를 " + count + "명으로 맞췄어요" + (old > 0 ? " (기존 " + old + "명 정리)" : "")
                + ". /팀분배 에 함께 포함돼요. (/전체초기화 로 제거)", NamedTextColor.GREEN));
        return true;
    }

    private boolean cook(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        switch (sub) {
            case "solo", "start" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("플레이어만 시작할 수 있어요.");
                    return true;
                }
                game.start(Game.Mode.SOLO, solo, List.of(p));
            }
            case "duo" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("플레이어만 시작할 수 있어요.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("/cook duo <같이 할 플레이어> [플레이어...]  (최대 4명)", NamedTextColor.GRAY));
                    return true;
                }
                List<Player> team = new ArrayList<>(List.of(p));
                for (int i = 1; i < args.length; i++) {
                    Player mate = Bukkit.getPlayerExact(args[i]);
                    if (mate == null || team.contains(mate)) {
                        sender.sendMessage(Component.text("같이 할 플레이어를 찾을 수 없어요: " + args[i], NamedTextColor.RED));
                        return true;
                    }
                    team.add(mate);
                }
                if (team.size() > duo.spawns.size()) {
                    sender.sendMessage(Component.text("최대 " + duo.spawns.size() + "명까지 함께할 수 있어요.", NamedTextColor.RED));
                    return true;
                }
                game.start(Game.Mode.DUO, duo, team);
                Component tip = Component.text("역할은 자유! 가운데 조리대 사이 통로로 오갈 수 있어요. (게임 중 점프 불가)", NamedTextColor.AQUA);
                team.forEach(x -> x.sendMessage(tip));
            }
            case "stop" -> {
                Kitchen k = game.kitchen();
                game.stop();
                if (k != null) k.build();
                if (match != null) match.stopAll();
                sender.sendMessage(Component.text("영업을 중단했어요.", NamedTextColor.YELLOW));
            }
            case "hud" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("/cook hud <GUI 가로 픽셀>  (1920x1080 자동=480, 배율3=640, 배율2=960)", NamedTextColor.GRAY));
                    return true;
                }
                try {
                    int w = Integer.parseInt(args[1]);
                    game.guiWidth = w;
                    if (match != null) match.games.forEach(g -> g.guiWidth = w);
                    getConfig().set("hud-gui-width", w);
                    saveConfig();
                    sender.sendMessage(Component.text("HUD 기준 폭을 " + w + "으로 바꿨어요.", NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("숫자를 입력해 주세요.", NamedTextColor.RED));
                }
            }
            default -> {
                sender.sendMessage(Component.text("/cook solo  1인 영업 시작", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("/cook duo <플레이어...>  다인 영업 시작", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("/팀분배 <2|3|4> → /letscook  팀 대항전", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("/fp player <인원>  테스트 더미 소환,  /전체초기화", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("/cook stop   영업 중단,  /cook hud <폭>  HUD 위치 맞추기", NamedTextColor.GOLD));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        switch (command.getName()) {
            case "팀분배" -> {
                return args.length == 1 ? List.of("2", "3", "4") : List.of();
            }
            case "fp" -> {
                if (args.length == 1) return List.of("player");
                return args.length == 2 ? List.of("5", "10", "20") : List.of();
            }
            case "cook" -> {
                if (args.length == 1) return List.of("solo", "duo", "stop", "hud");
                if (args.length == 2 && args[0].equalsIgnoreCase("hud")) return List.of("480", "640", "960");
                if (args.length >= 2 && args[0].equalsIgnoreCase("duo")) {
                    return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> !n.equals(sender.getName())).toList();
                }
                return List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    // ---------------------------------------------------------------- events

    private Location lobby() {
        return solo.spawns.get(0);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        p.teleport(lobby());
        if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.ADVENTURE);
        p.getInventory().clear();
        Game.setJump(p, true);
        dummies.showTab(p);
        p.sendMessage(Component.text("주방 대소동에 오신 걸 환영해요! OP라면 /cook 으로 명령어를 확인하세요.", NamedTextColor.GOLD));
        // Teams are in play: put the newcomer in the smallest team, and straight into its kitchen mid-match.
        Teams.Team t = teams.joinSmallest(p);
        if (t == null) return;
        Bukkit.broadcast(t.prefix().append(Component.text(" " + p.getName() + "님이 팀에 합류했어요!", NamedTextColor.WHITE)));
        if (match != null && !match.over()) {
            for (Game g : match.games) {
                if (g.team == t && g.active()) {
                    g.join(p);
                    match.add(null, List.of(p));
                    p.sendMessage(t.prefix().append(Component.text(" 주방에 바로 투입됐어요! 남은 시간 동안 힘내요.", NamedTextColor.AQUA)));
                }
            }
        }
    }

    /** Chat as "[도팀]name: message" with the team tag in its colour. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent e) {
        Teams.Team t = teams.of(e.getPlayer().getUniqueId());
        if (t == null) return;
        e.renderer((source, displayName, message, viewer) -> Component.text()
                .append(t.prefix())
                .append(Component.text(source.getName() + ": ", NamedTextColor.WHITE))
                .append(message.colorIfAbsent(NamedTextColor.WHITE))
                .build());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (game.playing(p)) game.stop();
        // Leaving mid-game drops you from your team (team games just carry on without you).
        Teams.Team t = teams.remove(p);
        if (t != null) {
            Bukkit.broadcast(t.prefix().append(Component.text(" " + p.getName() + "님이 나가서 팀에서 빠졌어요.", NamedTextColor.GRAY)));
        }
    }

    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Station s = solo.at(e.getClickedBlock());
        if (s == null) s = duo.at(e.getClickedBlock());
        for (int i = 0; s == null && i < arenas.size(); i++) s = arenas.get(i).at(e.getClickedBlock());
        if (s == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Game g = gameOf(e.getPlayer());
        if (g != null) g.interact(e.getPlayer(), s);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (gameOf(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (gameOf(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        if (gameOf(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && gameOf(p) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
                Game g = gameOf(p);
                p.teleport(g != null ? g.kitchen().spawns.get(0) : lobby());
            }
        }
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        e.setCancelled(true);
    }
}
