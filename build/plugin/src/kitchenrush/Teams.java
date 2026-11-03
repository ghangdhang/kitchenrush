package kitchenrush;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The four yut-nori teams: membership, name tags, tab list names and chat prefixes. */
final class Teams {
    enum Team {
        DO("도팀", TextColor.color(0xFFFF55)),
        GAE("개팀", TextColor.color(0x9CFF4A)),
        GEOL("걸팀", TextColor.color(0xFF8FD0)),
        YUT("윷팀", TextColor.color(0x55FFFF));

        final String label;
        final TextColor color;

        Team(String label, TextColor color) {
            this.label = label;
            this.color = color;
        }

        Component prefix() {
            return Component.text("[" + label + "]", color);
        }

        String scoreboardName() {
            return "kitchen_" + name().toLowerCase();
        }
    }

    /** Member uuid (players and dummy mannequins) -> team, in assignment order. */
    private final Map<UUID, Team> members = new LinkedHashMap<>();

    Team of(UUID id) {
        return members.get(id);
    }

    boolean isEmpty() {
        return members.isEmpty();
    }

    /** Teams that have at least one member, in DO, GAE, GEOL, YUT order. */
    List<Team> activeTeams() {
        List<Team> out = new ArrayList<>();
        for (Team t : Team.values()) if (members.containsValue(t)) out.add(t);
        return out;
    }

    List<UUID> membersOf(Team team) {
        List<UUID> out = new ArrayList<>();
        members.forEach((id, t) -> {
            if (t == team) out.add(id);
        });
        return out;
    }

    /** Shuffles everyone and deals them out to the first `count` teams as evenly as possible. */
    Map<Team, List<String>> distribute(List<Entity> everyone, int count) {
        clear();
        List<Entity> shuffled = new ArrayList<>(everyone);
        Collections.shuffle(shuffled);
        Map<Team, List<String>> result = new EnumMap<>(Team.class);
        for (int i = 0; i < shuffled.size(); i++) {
            Team t = Team.values()[i % count];
            Entity e = shuffled.get(i);
            members.put(e.getUniqueId(), t);
            apply(e);
            result.computeIfAbsent(t, k -> new ArrayList<>()).add(nameOf(e));
        }
        return result;
    }

    /** Takes someone out of their team (e.g. they left the server). */
    Team remove(Entity e) {
        Team t = members.remove(e.getUniqueId());
        if (t != null) apply(e);
        return t;
    }

    /** Puts a newcomer into the currently smallest of the teams in play. */
    Team joinSmallest(Entity e) {
        List<Team> active = activeTeams();
        if (active.isEmpty() || members.containsKey(e.getUniqueId())) return members.get(e.getUniqueId());
        Team best = active.get(0);
        for (Team t : active) if (membersOf(t).size() < membersOf(best).size()) best = t;
        members.put(e.getUniqueId(), best);
        apply(e);
        return best;
    }

    /** Shows the team prefix in the tab list, above the head and (via the chat renderer) in chat. */
    void apply(Entity e) {
        Team t = members.get(e.getUniqueId());
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team any : Team.values()) {
            org.bukkit.scoreboard.Team st = board.getTeam(any.scoreboardName());
            if (st != null) entries(e).forEach(st::removeEntry);
        }
        if (t == null) {
            if (e instanceof Player p) p.playerListName(null);
            if (e instanceof Mannequin m) m.customName(Component.text(nameOf(m)));
            return;
        }
        org.bukkit.scoreboard.Team st = board.getTeam(t.scoreboardName());
        if (st == null) {
            st = board.registerNewTeam(t.scoreboardName());
            st.prefix(t.prefix());
        }
        entries(e).forEach(st::addEntry);
        if (e instanceof Player p) p.playerListName(t.prefix().append(Component.text(p.getName())));
        if (e instanceof Mannequin m) {
            m.customName(t.prefix().append(Component.text(nameOf(m))));
            m.setCustomNameVisible(true);
        }
    }

    void clear() {
        List<UUID> ids = new ArrayList<>(members.keySet());
        members.clear();
        for (UUID id : ids) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) apply(e);
        }
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : Team.values()) {
            org.bukkit.scoreboard.Team st = board.getTeam(t.scoreboardName());
            if (st != null) st.unregister();
        }
    }

    /**
     * Scoreboard entries for an entity: a player's name, or for a dummy both its uuid (name tag above the
     * mannequin) and its display name (its tab-list row, which is a player entry with that name).
     */
    private static List<String> entries(Entity e) {
        return e instanceof Player p ? List.of(p.getName()) : List.of(e.getUniqueId().toString(), nameOf(e));
    }

    static String nameOf(Entity e) {
        if (e instanceof Player p) return p.getName();
        String tag = e.getScoreboardTags().stream().filter(s -> s.startsWith(Dummies.NAME_TAG)).findFirst().orElse("");
        return tag.isEmpty() ? "더미" : tag.substring(Dummies.NAME_TAG.length());
    }
}
