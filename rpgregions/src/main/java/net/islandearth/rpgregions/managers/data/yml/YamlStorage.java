package net.islandearth.rpgregions.managers.data.yml;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import net.islandearth.rpgregions.RPGRegions;
import net.islandearth.rpgregions.managers.data.IStorageManager;
import net.islandearth.rpgregions.managers.data.account.RPGRegionsAccount;
import net.islandearth.rpgregions.managers.data.region.Discovery;
import net.islandearth.rpgregions.managers.data.region.WorldDiscovery;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class YamlStorage implements IStorageManager {

    private final AsyncCache<UUID, RPGRegionsAccount> cachedAccounts;

    private final RPGRegions plugin;

    public YamlStorage(RPGRegions plugin) {
        this.plugin = plugin;
        File dataFile = new File(plugin.getDataFolder() + "/accounts/");
        dataFile.mkdirs();
        this.cachedAccounts = Caffeine.newBuilder()
                .initialCapacity(Bukkit.getMaxPlayers())
                .maximumSize(1_000) // Realistically no server can support higher than this, even Folia
                .scheduler(Scheduler.systemScheduler())
                .expireAfterAccess(plugin.getConfig().getInt("settings.storage.cache-expiry-time", 180), TimeUnit.SECONDS)
                .removalListener((k, v, c) -> {
                    plugin.debug("Removed user from cache, cause: " + c.name());
                    // If the user was manually removed, don't save, let other code handle how it wants
                    if (v == null || c == RemovalCause.EXPLICIT) return;
                    saveAccount(((RPGRegionsAccount) v));
                })
                .buildAsync((key, executor) -> getAccount(key));
    }

    @Override
    public CompletableFuture<RPGRegionsAccount> getAccount(UUID uuid) {
        // Check if cached
        final CompletableFuture<RPGRegionsAccount> possibly = cachedAccounts.getIfPresent(uuid);
        if (possibly != null) {
            plugin.debug("Using cached user: " + uuid);
            return possibly;
        }

        // Add a check to ensure accounts aren't taking a long time
        long startTime = System.currentTimeMillis();
        CompletableFuture<RPGRegionsAccount> future = new CompletableFuture<>();
        future.thenAccept(account -> {
            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;
            timing(totalTime);
        });

        File file = new File(plugin.getDataFolder() + "/accounts/" + uuid.toString() + ".yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        Map<String, Discovery> regions = new HashMap<>();
        for (String results : config.getStringList("Discoveries")) {
            String[] data = results.split(";");
            String time = data[0];
            String region = data[1];
            regions.put(region, new WorldDiscovery(time, region));
        }

        plugin.debug("Created user account: " + uuid);
        RPGRegionsAccount account = new RPGRegionsAccount(uuid, regions);
        cachedAccounts.put(uuid, CompletableFuture.completedFuture(account));
        future.complete(account);
        return future;
    }

    @Override
    public AsyncCache<UUID, RPGRegionsAccount> getCachedAccounts() {
        return cachedAccounts;
    }

    @Override
    public void clearDiscoveries(UUID uuid) {
        getAccount(uuid).thenAccept(account -> {
            account.getDiscoveredRegions().clear();
        }).exceptionally(t -> {
            t.printStackTrace();
            return null;
        });

        File file = new File(plugin.getDataFolder() + "/accounts/" + uuid.toString() + ".yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("Discoveries", null);
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void clearDiscovery(UUID uuid, String regionId) {
        getAccount(uuid).thenAccept(account -> {
            account.getDiscoveredRegions().remove(regionId);
        }).exceptionally(t -> {
            t.printStackTrace();
            return null;
        });

        File file = new File(plugin.getDataFolder() + "/accounts/" + uuid.toString() + ".yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        Map<String, Discovery> regions = new HashMap<>();
        for (String results : config.getStringList("Discoveries")) {
            String[] data = results.split(";");
            String time = data[0];
            String region = data[1];
            regions.put(region, new WorldDiscovery(time, region));
        }

        regions.remove(regionId);

        List<String> newData = config.getStringList("Discoveries");
        newData.clear();
        for (Discovery region : regions.values()) {
            newData.add(region.getDate() + ";" + region.getRegion());
        }

        config.set("Discoveries", newData);
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void deleteAccount(UUID uuid) {
        this.clearDiscoveries(uuid);
        File file = new File(plugin.getDataFolder() + "/accounts/" + uuid.toString() + ".yml");
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        cachedAccounts.synchronous().invalidate(uuid);
    }

    @Override
    public CompletableFuture<Void> removeCachedAccount(UUID uuid, boolean save) {
        if (!save) return CompletableFuture.completedFuture(null);
        final Cache<UUID, RPGRegionsAccount> synchronous = cachedAccounts.synchronous();
        RPGRegionsAccount account = synchronous.getIfPresent(uuid);
        synchronous.invalidate(uuid);
        if (account != null) return saveAccount(account);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> saveAccount(RPGRegionsAccount account) {
        File file = new File(plugin.getDataFolder() + "/accounts/" + account.getUuid().toString() + ".yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        List<String> newData = new ArrayList<>();
        for (Discovery region : account.getDiscoveredRegions().values()) {
            newData.add(region.getDate() + ";" + region.getRegion());
        }

        config.set("Discoveries", newData);
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return CompletableFuture.completedFuture(null);
    }
}
