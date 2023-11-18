package net.islandearth.rpgregions.listener;

import net.islandearth.rpgregions.RPGRegions;
import net.islandearth.rpgregions.api.events.RegionDiscoverEvent;
import net.islandearth.rpgregions.api.events.RegionsEnterEvent;
import net.islandearth.rpgregions.managers.data.account.RPGRegionsAccount;
import net.islandearth.rpgregions.managers.data.region.ConfiguredRegion;
import net.islandearth.rpgregions.managers.data.region.Discovery;
import net.islandearth.rpgregions.managers.data.region.WorldDiscovery;
import net.islandearth.rpgregions.translation.Translations;
import net.islandearth.rpgregions.utils.RegenUtils;
import net.islandearth.rpgregions.utils.TimeEntry;
import net.islandearth.rpgregions.utils.TitleAnimator;
import net.islandearth.rpgregions.utils.XSound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class RegionListener implements Listener {

    private final RPGRegions plugin;
    private final List<UUID> titleCooldown;
    private final DateTimeFormatter format;

    public RegionListener(RPGRegions plugin) {
        this.plugin = plugin;
        this.titleCooldown = new ArrayList<>();
        this.format = DateTimeFormatter.ofPattern(plugin.getConfig().getString("settings.server.discoveries.date.format"));
    }

    /**
     * Handles region discoveries on enter.
     */
    @EventHandler
    public void onEnter(RegionsEnterEvent event) {
        Player player = event.getPlayer();
        plugin.getManagers().getStorageManager().getAccount(player.getUniqueId()).thenAccept(account -> {
            final Optional<ConfiguredRegion> prioritised = plugin.getManagers().getRegionsCache().getConfiguredRegion(event.getPriority());
            if (prioritised.isPresent() && prioritised.get().disablesPassthrough()) {
                plugin.debug("Checking prioritised region only: " + event.getPriority());
                runRegionCheck(player, prioritised.get(), event, account);
                return;
            }

            for (String region : event.getRegions()) {
                plugin.debug("Checking region: " + region);
                plugin.getManagers().getRegionsCache().getConfiguredRegion(region).ifPresent(configuredRegion -> {
                    runRegionCheck(player, configuredRegion, event, account);
                });
            }
        });
    }

    private void runRegionCheck(Player player, ConfiguredRegion configuredRegion, RegionsEnterEvent event, RPGRegionsAccount account) {
        final String regionId = configuredRegion.getId();
        boolean has = false;
        boolean prioritised = event.getPriority().equals(regionId);
        for (Discovery discoveredRegion : account.getDiscoveredRegions().values()) {
            if (discoveredRegion.getRegion().equals(regionId)) {
                has = true;
                break;
            }
        }
        plugin.debug("Has the player discovered this region? " + has);
        plugin.debug("Is this the prioritised region? " + prioritised);

        plugin.getScheduler().executeOnEntity(player, () -> this.checkEffects(configuredRegion, player));

        if (configuredRegion.alwaysShowTitles() && event.hasChanged() && has && prioritised) {
            this.sendTitles(player, configuredRegion, false);
        }

        if (configuredRegion.showActionbar() && event.hasChanged() && prioritised) {
            plugin.adventure().player(player).sendActionBar(Translations.REGION_ENTER_ACTIONBAR.get(player, configuredRegion.getCustomName()).get(0));
        }

        if (!has && configuredRegion.isDiscoverable() && prioritised) {
            if (configuredRegion.isTimedRegion()) {
                final long currentTimeMillis = System.currentTimeMillis();
                if (account.getTimeEntryInRegion(regionId).isEmpty()) {
                    account.addTimeEntryInRegion(regionId, currentTimeMillis);
                }

                final TimeEntry entry = account.getTimeEntryInRegion(regionId).get();
                long lostTime = System.currentTimeMillis() - entry.getLatestEntry();
                if (lostTime >= 1000) {
                    entry.setStart(entry.getStart() + lostTime);

                    plugin.debug("Lost time from standing still: " + lostTime);
                }

                long time = TimeUnit.MILLISECONDS.toSeconds(currentTimeMillis - entry.getStart());
                entry.setLatestEntry(currentTimeMillis);

                final int secondsInsideToDiscover = configuredRegion.getSecondsInsideToDiscover();
                if (time < secondsInsideToDiscover) {
                    plugin.debug(String.format("Unable to discover region for %s because they have not reached the time requirement (c: %d, e: %d, t: %d).", player.getName(), secondsInsideToDiscover, entry, time));
                    return;
                }
            }

            // Remove as we are now discovering it.
            account.removeStartTimeInRegion(regionId);

            plugin.debug("Discovering region.");
            LocalDateTime date = LocalDateTime.now();
            String formattedDate = date.format(format);
            Discovery discovery = new WorldDiscovery(formattedDate, regionId);
            account.addDiscovery(discovery);
            Bukkit.getPluginManager().callEvent(new RegionDiscoverEvent(player, configuredRegion, discovery));
        } else if (prioritised && configuredRegion.isDiscoverable() && has) {
            if (configuredRegion.getRewards() != null) configuredRegion.getRewards().forEach(reward -> {
                if (reward.isAlwaysAward() && reward.canAward()) {
                    reward.award(player);
                }
            });
        }
    }

    @EventHandler
    public void onDiscover(RegionDiscoverEvent rde) {
        Player player = rde.getPlayer();
        ConfiguredRegion region = rde.getRegion();
        this.sendTitles(player, region, true);

        if (region.getSound() == null) {
            player.playSound(
                    player.getLocation(),
                    XSound.valueOf(plugin.getConfig().getString("settings.server.discoveries.discovered.sound.name")).parseSound(),
                    1,
                    plugin.getConfig().getInt("settings.server.discoveries.discovered.sound.pitch")
            );
        } else {
            player.playSound(
                    player.getLocation(),
                    region.getSound(),
                    1,
                    plugin.getConfig().getInt("settings.server.discoveries.discovered.sound.pitch")
            );
        }

        if (region.getRewards() != null) region.getRewards().forEach(reward -> reward.award(player));

        if (region.getRegenerate() != null
                && region.getRegenerate().isOnDiscover()) {
            RegenUtils.regenerate(region);
        }
    }

    private void sendTitles(Player player, ConfiguredRegion configuredRegion, boolean discovered) {
        if (titleCooldown.contains(player.getUniqueId())) {
            plugin.debug("Player is on title cooldown");
            return;
        }
        
        plugin.debug("Added to title cooldown");
        titleCooldown.add(player.getUniqueId());
        plugin.getScheduler().executeDelayed(() -> {
            plugin.debug("Removed from title cooldown");
            titleCooldown.remove(player.getUniqueId());
        }, plugin.getConfig().getLong("settings.server.discoveries.discovered.title.cooldown"));
        if (!discovered) {
            List<Component> discoveredTitle = configuredRegion.getDiscoveredTitle(player);
            List<Component> discoveredSubtitle = configuredRegion.getDiscoveredSubtitle(player);
            plugin.debug("Sending 'player has already discovered region' titles! " + discoveredTitle + ":" + discoveredSubtitle);
            new TitleAnimator(player,
                    plugin,
                    discoveredTitle,
                    discoveredSubtitle,
                    plugin.getConfig().getInt("settings.server.discoveries.discovered.title.animation_speed"));
            return;
        }

        List<Component> title = configuredRegion.getTitle(player);
        List<Component> subtitle = configuredRegion.getSubtitle(player);
        plugin.debug("Sending 'player has just discovered region' titles! " + title + ":" + subtitle);
        new TitleAnimator(player,
                plugin,
                title,
                subtitle,
                plugin.getConfig().getInt("settings.server.discoveries.discovered.title.animation_speed"));
    }

    private void checkEffects(ConfiguredRegion configuredRegion, Player player) {
        if (configuredRegion.getEffects() != null) {
            plugin.debug("Checking effects");
            configuredRegion.getEffects().forEach(regionEffect -> {
                boolean canEffect = true;
                if (regionEffect.isWearingRequired()) {
                    for (ItemStack itemStack : player.getInventory().getArmorContents()) {
                        if (regionEffect.shouldIgnore(itemStack)) {
                            canEffect = false;
                            break;
                        }
                    }
                    plugin.debug("Wearing required, canEffect? " + canEffect);
                    if (canEffect) regionEffect.effect(player);
                    return;
                }

                for (ItemStack itemStack : player.getInventory()) {
                    if (regionEffect.shouldIgnore(itemStack)) {
                        canEffect = false;
                        break;
                    }
                }

                plugin.debug("Wearing not required, canEffect? " + canEffect);
                if (canEffect) regionEffect.effect(player);
            });
        }
    }
}
