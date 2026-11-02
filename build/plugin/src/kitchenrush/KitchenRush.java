package kitchenrush;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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

import java.util.List;

/** The resource pack itself is published and sent to players by ResourcepackBuilder (server folder "pack"). */
public final class KitchenRush extends JavaPlugin implements Listener {
    private World world;
    private Kitchen solo, duo;
    private Game game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            Items.init(this);
            Glyphs.load(this);
        } catch (Exception e) {
            getLogger().severe("Failed to start: " + e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        world = Bukkit.getWorlds().get(0);
        solo = new Kitchen("1인 주방", world, Kitchen.SOLO, 0, 100, 0, new double[][]{{6.5, 6.5}});
        duo = new Kitchen("2인 주방", world, Kitchen.DUO, 100, 100, 0,
                new double[][]{{4.5, 5.5}, {13.5, 5.5}, {4.5, 3.5}, {13.5, 3.5}});
        game = new Game(this);
        game.roundSeconds = getConfig().getInt("round-seconds", 180);
        game.guiWidth = getConfig().getInt("hud-gui-width", 480);
        Kitchen.prepareWorld(world, solo.spawns.get(0));
        solo.build();
        duo.build();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> game.tick(world), 1L, 1L);
        getLogger().info("Kitchens ready.");
    }

    @Override
    public void onDisable() {
        if (game != null) game.stop();
        if (solo != null) solo.clear();
        if (duo != null) duo.clear();
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
                List<Player> team = new java.util.ArrayList<>(List.of(p));
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
                sender.sendMessage(Component.text("영업을 중단했어요.", NamedTextColor.YELLOW));
            }
            case "hud" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("/cook hud <GUI 가로 픽셀>  (1920x1080 자동=480, 배율3=640, 배율2=960)", NamedTextColor.GRAY));
                    return true;
                }
                try {
                    game.guiWidth = Integer.parseInt(args[1]);
                    getConfig().set("hud-gui-width", game.guiWidth);
                    saveConfig();
                    sender.sendMessage(Component.text("HUD 기준 폭을 " + game.guiWidth + "으로 바꿨어요.", NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("숫자를 입력해 주세요.", NamedTextColor.RED));
                }
            }
            default -> {
                sender.sendMessage(Component.text("/cook solo  1인 영업 시작", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("/cook duo <플레이어>  2인 영업 시작", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("/cook stop   영업 중단", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("/cook hud <폭>  HUD 위치 맞추기", NamedTextColor.GOLD));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("solo", "duo", "stop", "hud");
        if (args.length == 2 && args[0].equalsIgnoreCase("hud")) return List.of("480", "640", "960");
        if (args.length >= 2 && args[0].equalsIgnoreCase("duo")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> !n.equals(sender.getName())).toList();
        }
        return List.of();
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
        p.sendMessage(Component.text("주방 대소동에 오신 걸 환영해요! OP라면 /cook solo 또는 /cook duo <플레이어...> 로 시작하세요.", NamedTextColor.GOLD));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (game.playing(e.getPlayer())) game.stop();
    }

    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Station s = solo.at(e.getClickedBlock());
        if (s == null) s = duo.at(e.getClickedBlock());
        if (s == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        game.interact(e.getPlayer(), s);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (game.playing(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (game.playing(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        if (game.playing(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && game.playing(p)) e.setCancelled(true);
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
                Kitchen k = game.playing(p) ? game.kitchen() : solo;
                p.teleport(k.spawns.get(0));
            }
        }
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        e.setCancelled(true);
    }
}
