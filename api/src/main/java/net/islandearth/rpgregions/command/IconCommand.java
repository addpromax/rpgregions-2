package net.islandearth.rpgregions.command;

public record IconCommand(String command, CommandClickType clickType, int cooldown) {

    public enum CommandClickType {
        DISCOVERED,
        UNDISCOVERED
    }
}
