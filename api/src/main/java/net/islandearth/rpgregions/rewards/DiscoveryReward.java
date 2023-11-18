package net.islandearth.rpgregions.rewards;

import net.islandearth.rpgregions.api.IRPGRegionsAPI;
import net.islandearth.rpgregions.gui.GuiEditable;
import net.islandearth.rpgregions.gui.IGuiEditable;
import org.bukkit.entity.Player;

public abstract class DiscoveryReward implements IGuiEditable {

	private final transient IRPGRegionsAPI api;

	@GuiEditable("Always Reward")
	private boolean alwaysAward;

	@GuiEditable("Time Between Reward (s)")
	private int timeBetweenReward;

	private long lastReward;

	public DiscoveryReward(IRPGRegionsAPI api) {
		this.api = api;
	}

	public IRPGRegionsAPI getAPI() {
		return api;
	}

	/**
	 * Awards this reward to the specified player
	 * @param player player to award to
	 */
	public abstract void award(Player player);

	protected void updateAwardTime() {
		this.lastReward = System.currentTimeMillis();
	}

	public boolean isAlwaysAward() {
		return alwaysAward;
	}

	public void setAlwaysAward(boolean alwaysAward) {
		this.alwaysAward = alwaysAward;
	}

	public int getTimeBetweenReward() {
		return timeBetweenReward;
	}

	public void setTimeBetweenReward(int timeBetweenReward) {
		this.timeBetweenReward = timeBetweenReward;
	}

	public boolean canAward() {
		return (System.currentTimeMillis() - lastReward) >= (timeBetweenReward * 1000L);
	}

	public String getPluginRequirement() {
		return null;
	}
}
