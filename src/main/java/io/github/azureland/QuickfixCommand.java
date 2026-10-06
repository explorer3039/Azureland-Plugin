package io.github.azureland;

import java.math.BigDecimal;
import java.util.List;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.plugin.RegisteredServiceProvider;

final class QuickfixCommand implements TabExecutor {
    private final AzurelandPlugin plugin;
    private volatile Double cost;

    QuickfixCommand(AzurelandPlugin plugin) {
        this.plugin = plugin;
        loadSettings();
    }

    void loadSettings() {
        Object configuredCost = plugin.getConfig().get("quickfix.cost", 30);
        if (configuredCost instanceof Number cost && Double.isFinite(cost.doubleValue())
                && cost.doubleValue() >= 0) {
            this.cost = cost.doubleValue();
        } else {
            this.cost = null;
            plugin.getLogger().warning("quickfix.cost 必须是非负有限数值，快速修复已停用。");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("azureland.quickfix")) {
            sender.sendMessage(ChatColor.RED + "你没有使用此命令的权限。");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("此命令只能由玩家执行。");
            return true;
        }
        if (args.length != 0) {
            player.sendMessage(ChatColor.RED + "用法：/" + label);
            return true;
        }

        Double cost = this.cost;
        if (cost == null) {
            player.sendMessage(ChatColor.RED + "快速修复费用配置无效，请联系管理员。");
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand().clone();
        if (item.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "请先在主手拿着需要修复的物品。");
            return true;
        }

        ItemMeta meta = item.getItemMeta();
        boolean changed = false;
        if (meta instanceof Damageable damageable && damageable.hasDamage()) {
            damageable.setDamage(0);
            changed = true;
        }
        if (meta instanceof Repairable repairable && repairable.hasRepairCost()) {
            repairable.setRepairCost(0);
            changed = true;
        }
        if (!changed) {
            player.sendMessage(ChatColor.YELLOW + "该物品无需修复，损耗和修复费用均为零。");
            return true;
        }

        if (!item.setItemMeta(meta)) {
            player.sendMessage(ChatColor.RED + "修复失败，无法更新该物品的属性。");
            return true;
        }
        if (cost > 0) {
            if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault")
                    && !plugin.getServer().getPluginManager().isPluginEnabled("VaultUnlocked")) {
                player.sendMessage(ChatColor.RED + "未接入 Vault 经济服务，暂时无法付费修复，请联系管理员。");
                return true;
            }
            if (!EconomyPayment.withdraw(plugin, player, cost)) {
                return true;
            }
        }
        player.getInventory().setItemInMainHand(item);
        String payment = cost > 0 ? "消耗 " + BigDecimal.valueOf(cost).stripTrailingZeros().toPlainString()
                + " 金币。" : "本次免费。";
        player.sendMessage(ChatColor.GREEN + "已修复主手物品，并清除铁砧修复费用。" + payment);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        return List.of();
    }

    // Loaded only for paid repairs when a Vault-compatible bridge is enabled.
    private static final class EconomyPayment {
        static boolean withdraw(AzurelandPlugin plugin, Player player, double cost) {
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
}
