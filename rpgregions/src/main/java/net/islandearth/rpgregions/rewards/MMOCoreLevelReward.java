package net.islandearth.rpgregions.rewards;

import net.Indyuce.mmocore.api.player.PlayerData;
import net.islandearth.rpgregions.api.IRPGRegionsAPI;
import org.bukkit.entity.Player;

public class MMOCoreLevelReward extends LevelReward {

    public MMOCoreLevelReward(IRPGRegionsAPI api) {
        super(api);
    }

    public MMOCoreLevelReward(IRPGRegionsAPI api, int level) {
        super(api, level);
    }

    @Override
    public void award(Player player) {
        final PlayerData playerData = PlayerData.get(player.getUniqueId());
        playerData.setLevel(playerData.getLevel() + this.getLevel());
        this.updateAwardTime();
    }

    @Override
    public String getPluginRequirement() {
        return "MMOCore";
    }
}
