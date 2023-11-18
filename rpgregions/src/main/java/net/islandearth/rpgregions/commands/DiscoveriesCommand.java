package net.islandearth.rpgregions.commands;

import cloud.commandframework.annotations.Argument;
import cloud.commandframework.annotations.CommandDescription;
import cloud.commandframework.annotations.CommandMethod;
import cloud.commandframework.annotations.CommandPermission;
import net.islandearth.rpgregions.RPGRegions;
import net.islandearth.rpgregions.api.events.RegionDiscoverEvent;
import net.islandearth.rpgregions.gui.DiscoveryGUI;
import net.islandearth.rpgregions.managers.data.region.ConfiguredRegion;
import net.islandearth.rpgregions.managers.data.region.WorldDiscovery;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DiscoveriesCommand {

    private final RPGRegions plugin;

    public DiscoveriesCommand(final RPGRegions plugin) {
        this.plugin = plugin;
    }

    @CommandDescription("Opens the discovery GUI")
    @CommandPermission("rpgregions.list")
    @CommandMethod("discoveries|discovery")
    public void onDefault(Player player) {
        new DiscoveryGUI(plugin, player).open();
    }

    @CommandDescription("Discovers a region for a player")
    @CommandPermission("rpgregions.discover")
    @CommandMethod("discoveries|discovery discover <region> <player>")
    public void onDiscover(CommandSender sender,
                           @Argument("region") ConfiguredRegion configuredRegion,
                           @Argument("player") OfflinePlayer target) {
        plugin.getManagers().getStorageManager().getAccount(target.getUniqueId()).thenAccept(account -> {
            LocalDateTime date = LocalDateTime.now();
            DateTimeFormatter format = DateTimeFormatter.ofPattern(plugin.getConfig().getString("settings.server.discoveries.date.format"));

            String formattedDate = date.format(format);
            final WorldDiscovery worldDiscovery = new WorldDiscovery(formattedDate, configuredRegion.getId());
            account.addDiscovery(worldDiscovery);
            if (target.getPlayer() != null) {
                Player player = target.getPlayer();
                player.sendMessage(ChatColor.GREEN + "An administrator added a discovery to your account.");
                Bukkit.getPluginManager().callEvent(new RegionDiscoverEvent(player, configuredRegion, worldDiscovery));
            }
            plugin.getManagers().getStorageManager().removeCachedAccount(target.getUniqueId());

            sender.sendMessage(ChatColor.GREEN + "The player " + target.getName() + " has had the discovery added.");
        });
    }
}
