package net.islandearth.rpgregions.managers.data.region;

public record WorldDiscovery(String date, String region) implements Discovery {

    @Override
    public String getDate() {
        return date;
    }

    @Override
    public String getRegion() {
        return region;
    }
}
