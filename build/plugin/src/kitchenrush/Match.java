package kitchenrush;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A /letscook team match: one arena (Game) per team running side by side, then a ranking. */
final class Match {
    final List<Game> games = new ArrayList<>();
    private final List<Player> everyone = new ArrayList<>();
    private boolean announced;

    /** Registers a team's arena and its cooks; a null game just adds late joiners to the announcement list. */
    void add(Game g, List<Player> members) {
        if (g != null) games.add(g);
        everyone.addAll(members);
    }

    boolean over() {
        return announced;
    }

    void tick() {
        if (announced || games.stream().anyMatch(Game::active)) return;
        announced = true;
        List<Game> ranking = new ArrayList<>(games);
        ranking.sort(Comparator.comparingInt(Game::score).reversed());
        int best = ranking.get(0).score();
        List<Game> winners = ranking.stream().filter(g -> g.score() == best).toList();
        Component headline = winners.size() == 1
                ? winners.get(0).team.prefix().append(Component.text(" 우승!", NamedTextColor.GOLD))
                : Component.text("무승부!", NamedTextColor.GOLD);
        var sub = Component.text();
        for (int i = 0; i < ranking.size(); i++) {
            Game g = ranking.get(i);
            if (i > 0) sub.append(Component.text("   "));
            sub.append(Component.text(g.team.label + " " + g.score() + "점", g.team.color));
        }
        Title title = Title.title(headline, sub.build(),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(6), Duration.ofSeconds(1)));
        List<Player> online = everyone.stream().filter(Player::isOnline).toList();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.sendMessage(Component.text("── 팀 대항 요리 대결 결과 ──", NamedTextColor.GOLD));
            for (int i = 0; i < ranking.size(); i++) {
                Game g = ranking.get(i);
                p.sendMessage(Component.text(" " + (i + 1) + "위 ", NamedTextColor.WHITE)
                        .append(g.team.prefix())
                        .append(Component.text("  " + g.score() + "점  (서빙 " + g.finalServed + ", 놓친 주문 " + g.finalMissed + ")", NamedTextColor.WHITE)));
            }
        }
        Jingle.play(online, Jingle.CLEAR);
    }

    void stopAll() {
        games.forEach(Game::stop);
        announced = true;
    }
}
