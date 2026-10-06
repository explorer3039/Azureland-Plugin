package io.github.azureland;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

// Loaded only for paid repairs when a Vault-compatible bridge is enabled.
final class QuickFixEconomy {
    static boolean withdraw(JavaPlugin plugin, Player player, double cost) {
        RegisteredServiceProvider<Economy> registration = plugin.getServer().getServicesManager()
                .getRegistration(Economy.class);
        if (registration == null) {
            player.sendMessage(ChatColor.RED + "未接入经济插件，暂时无法付费修复，请联系管理员。");
            return false;
        }
        Economy economy = registration.getProvider();
        if (!economy.has(player, cost)) {
            player.sendMessage(ChatColor.RED + "金币不足，修复需要 " + economy.format(cost) + "。");
            return false;
        }
        EconomyResponse response = economy.withdrawPlayer(player, cost);
        if (!response.transactionSuccess()) {
            player.sendMessage(ChatColor.RED + "扣费失败，未修复物品，请稍后重试。");
            plugin.getLogger().warning("quickfix 扣费失败（" + player.getUniqueId() + "）："
                    + response.errorMessage);
            return false;
        }
        return true;
    }
}
