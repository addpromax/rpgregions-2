package net.islandearth.rpgregions.managers.data.account;

import net.islandearth.rpgregions.managers.data.region.Discovery;
import net.islandearth.rpgregions.utils.TimeEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RPGRegionsAccount {

    private final UUID uuid;
    private final Map<String, Discovery> discoveredRegions;
    private final List<AccountCooldown> cooldowns;
    private final Map<String, TimeEntry> secondsInRegion;

    public RPGRegionsAccount(UUID uuid, Map<String, Discovery> discoveredRegions) {
        this.uuid = uuid;
        this.discoveredRegions = discoveredRegions;
        this.cooldowns = new ArrayList<>();
        this.secondsInRegion = new HashMap<>();
    }

    public UUID getUuid() {
        return uuid;
    }

    public Map<String, Discovery> getDiscoveredRegions() {
        return discoveredRegions;
    }

    public void addDiscovery(Discovery discovery) {
        discoveredRegions.put(discovery.getRegion(), discovery);
    }

    public List<AccountCooldown> getCooldowns() {
        return cooldowns;
    }

    public Optional<TimeEntry> getTimeEntryInRegion(String region) {
        return Optional.ofNullable(secondsInRegion.getOrDefault(region, null));
    }

    public void addTimeEntryInRegion(String region, long time) {
        secondsInRegion.put(region, new TimeEntry(time));
    }

    public void removeStartTimeInRegion(String region) {
        secondsInRegion.remove(region);
    }

    public enum AccountCooldown {
        ICON_COMMAND,
        TELEPORT
    }
}