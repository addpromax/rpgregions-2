package net.islandearth.rpgregions.managers.data;

import co.aikar.idb.DB;
import co.aikar.idb.DatabaseOptions;
import co.aikar.idb.DbRow;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import net.islandearth.rpgregions.RPGRegions;
import net.islandearth.rpgregions.managers.data.account.RPGRegionsAccount;
import net.islandearth.rpgregions.managers.data.region.Discovery;
import net.islandearth.rpgregions.managers.data.region.WorldDiscovery;
import org.bukkit.Bukkit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.intellij.lang.annotations.Language;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public abstract class SQLCommonStorage implements IStorageManager {

    protected static final String SELECT_REGION = "SELECT * FROM rpgregions_discoveries WHERE uuid = ?";
    protected static final String INSERT_DISCOVERY = "INSERT INTO rpgregions_discoveries (uuid, region, time) VALUES (?, ?, ?)";
    protected static final String DELETE_DISCOVERIES = "DELETE * FROM rpgregions_discoveries WHERE uuid = ?";
    protected static final String DELETE_DISCOVERY = "DELETE * FROM rpgregions_discoveries WHERE uuid = ? AND region = ?";

    private final AsyncCache<UUID, RPGRegionsAccount> cachedAccounts;

    private final RPGRegions plugin;
    private final DatabaseOptions options;

    public SQLCommonStorage(RPGRegions plugin, DatabaseOptions options) {
        this.options = options;
        this.plugin = plugin;

        migrate();

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

    private void migrate() {
        // Time to migrate!
        // Create the Flyway instance and point it to the database
        Flyway flyway = Flyway.configure(plugin.getClass().getClassLoader())
                .baselineOnMigrate(true)
                .dataSource("jdbc:" + options.getDsn(), options.getUser(), options.getPass()).load();
        // Start the migration
        flyway.migrate();
        final ValidateResult result = flyway.validateWithResult();
        if (!result.validationSuccessful) {
            panic(result);
            throw new IllegalStateException("Could not migrate the database!");
        }
    }

    private void panic(ValidateResult result) {
        plugin.getLogger().severe("=== UNABLE TO MIGRATE DATABASE, ERROR AS FOLLOWS ===");
        plugin.getLogger().severe("=== BASIC INFO ===");
        plugin.getLogger().severe("Flyway Version: " + result.flywayVersion);
        plugin.getLogger().severe("Plugin Version: " + plugin.getDescription().getVersion());
        plugin.getLogger().severe("Server Version: " + Bukkit.getServer().getVersion());
        plugin.getLogger().severe("=== MIGRATION INFO ===");
        plugin.getLogger().severe("Operation: " + result.operation);
        plugin.getLogger().severe("Error messages (combined): " + result.getAllErrorMessages());
        plugin.getLogger().severe("Error code: " + result.errorDetails.errorCode);
        plugin.getLogger().severe("Error message: " + result.errorDetails.errorMessage);
        plugin.getLogger().severe("=== INVALID MIGRATIONS ===");
        for (ValidateOutput invalidMigration : result.invalidMigrations) {
            plugin.getLogger().severe("-");
            plugin.getLogger().severe("Error code: " + invalidMigration.errorDetails.errorCode);
            plugin.getLogger().severe("Error message: " + invalidMigration.errorDetails.errorMessage);
            plugin.getLogger().severe("Description: " + invalidMigration.description);
            plugin.getLogger().severe("Version: " + invalidMigration.version);
            plugin.getLogger().severe("Path: " + invalidMigration.filepath);
            plugin.getLogger().severe("-");
        }
        plugin.getLogger().severe("=== MIGRATION WARNINGS ===");
        for (String warning : result.warnings) {
            plugin.getLogger().warning(warning);
        }
        plugin.getLogger().severe("=== END ERROR, EXITING ===");
    }

    protected DatabaseOptions getDatabaseOptions() {
        return options;
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

        cachedAccounts.put(uuid, CompletableFuture.supplyAsync(() -> {
            final List<DbRow> results = DB.getResultsAsync(SELECT_REGION, getDatabaseUuid(uuid)).join();
            Map<String, Discovery> regions = new HashMap<>();
            for (DbRow row : results) {
                String region = row.getString("region");
                regions.put(region, new WorldDiscovery(row.getString("time"), region));
            }

            plugin.debug("Created user account: " + uuid);
            final RPGRegionsAccount account = new RPGRegionsAccount(uuid, regions);
            future.complete(account);
            return account;
        }).exceptionally(t -> {
            t.printStackTrace();
            return null;
        }));
        return future;
    }

    @Override
    public AsyncCache<UUID, RPGRegionsAccount> getCachedAccounts() {
        return cachedAccounts;
    }

    @Override
    public void clearDiscoveries(UUID uuid) {
        getAccount(uuid).thenAccept(account -> account.getDiscoveredRegions().clear()).exceptionally(t -> {
            t.printStackTrace();
            return null;
        });

        DB.executeUpdateAsync(DELETE_DISCOVERIES, getDatabaseUuid(uuid));
    }

    @Override
    public void clearDiscovery(UUID uuid, String regionId) {
        getAccount(uuid).thenAccept(account -> account.getDiscoveredRegions().remove(regionId)).exceptionally(t -> {
            t.printStackTrace();
            return null;
        });

        DB.executeUpdateAsync(DELETE_DISCOVERY, getDatabaseUuid(uuid), regionId);
    }

    @Override
    public void deleteAccount(UUID uuid) {
        this.clearDiscoveries(uuid);
        cachedAccounts.synchronous().invalidate(uuid);
    }

    @Override
    public CompletableFuture<Void> removeCachedAccount(UUID uuid, boolean save) {
        if (!save) {
            cachedAccounts.synchronous().invalidate(uuid);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            final RPGRegionsAccount account = cachedAccounts.synchronous().getIfPresent(uuid);
            if (account == null) return null;
            saveAccount(account).join();
            cachedAccounts.synchronous().invalidate(uuid);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> saveAccount(RPGRegionsAccount account) {
        final UUID uuid = account.getUuid();
        return DB.getResultsAsync(SELECT_REGION, getDatabaseUuid(uuid)).thenAccept(results -> {
            List<String> current = new ArrayList<>();
            for (DbRow row : results) {
                current.add(row.getString("region"));
            }

            for (Discovery region : account.getDiscoveredRegions().values()) {
                if (!current.contains(region.getRegion())) {
                    executeInsert(INSERT_DISCOVERY, getDatabaseUuid(uuid), region.getRegion(), region.getDate());
                }
            }
        }).exceptionally(t -> {
            t.printStackTrace();
            return null;
        });
    }

    protected void executeInsert(@Language("SQL") String query, Object... params) {
        try {
            DB.executeInsert(query, params);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
