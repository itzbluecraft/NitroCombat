package dev.nitro.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class NitroCombat extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** UUID -> time (ms) when the combat tag expires */
    private final Map<UUID, Long> tagged = new HashMap<>();
    private final Set<String> allowedCommands = new HashSet<>();

    private int duration;
    private boolean killOnQuit;
    private boolean blockCommands;
    private boolean broadcastLog;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getLogger().info("NitroCombat enabled - Nitro Community Plugin");
        Bukkit.getPluginManager().registerEvents(this, this);

        // Update the action bar and check for expired tags every 0.5s
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 10L, 10L);
    }

    @Override
    public void onDisable() {
        tagged.clear();
    }

    private void loadSettings() {
        reloadConfig();
        duration = getConfig().getInt("combat-duration", 15);
        killOnQuit = getConfig().getBoolean("kill-on-quit", true);
        blockCommands = getConfig().getBoolean("block-commands", true);
        broadcastLog = getConfig().getBoolean("broadcast-combatlog", true);

        allowedCommands.clear();
        List<String> list = getConfig().getStringList("allowed-commands");
        for (String s : list) {
            allowedCommands.add(s.toLowerCase().replace("/", ""));
        }
    }

    private Component msg(String key, String... replace) {
        String text = getConfig().getString("messages." + key, "");
        for (int i = 0; i + 1 < replace.length; i += 2) {
            text = text.replace(replace[i], replace[i + 1]);
        }
        return LEGACY.deserialize(text);
    }

    private boolean isTagged(Player p) {
        Long end = tagged.get(p.getUniqueId());
        return end != null && end > System.currentTimeMillis();
    }

    private void tag(Player p) {
        if (p.hasPermission("nitrocombat.bypass")) return;
        boolean wasTagged = isTagged(p);
        tagged.put(p.getUniqueId(), System.currentTimeMillis() + duration * 1000L);
        if (!wasTagged) {
            p.sendMessage(msg("tagged", "%time%", String.valueOf(duration)));
        }
    }

    private void untag(Player p, boolean notify) {
        if (tagged.remove(p.getUniqueId()) != null && notify) {
            p.sendMessage(msg("untagged"));
            p.sendActionBar(Component.empty());
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : new HashMap<>(tagged).entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                tagged.remove(e.getKey());
                continue;
            }
            long left = e.getValue() - now;
            if (left <= 0) {
                untag(p, true);
            } else {
                long secs = (left + 999) / 1000;
                p.sendActionBar(msg("actionbar", "%time%", String.valueOf(secs)));
            }
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (e.getDamager() instanceof Player p) {
            attacker = p;
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null || attacker.equals(victim)) return;

        tag(victim);
        tag(attacker);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!blockCommands) return;
        Player p = e.getPlayer();
        if (!isTagged(p)) return;

        String label = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        int colon = label.indexOf(':');
        if (colon != -1) label = label.substring(colon + 1);

        if (allowedCommands.contains(label)) return;

        e.setCancelled(true);
        p.sendMessage(msg("command-blocked"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!isTagged(p)) return;

        tagged.remove(p.getUniqueId());
        if (killOnQuit) {
            if (broadcastLog) {
                Bukkit.broadcast(msg("combatlog-broadcast", "%player%", p.getName()));
            }
            p.setHealth(0.0); // triggers PlayerDeathEvent + item drops
        }
    }

    @EventHandler
    public void onKick(PlayerKickEvent e) {
        // Server kicks (anticheat, AFK, etc.) are not punished as combat logging
        tagged.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        untag(e.getEntity(), false);
    }

    // --------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("nitrocombat.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            loadSettings();
            sender.sendMessage(msg("reloaded"));
            return true;
        }
        sender.sendMessage(LEGACY.deserialize("&bNitroCombat &8- &7Nitro Community Plugin\n&eUsage: /nitrocombat reload"));
        return true;
    }
}
