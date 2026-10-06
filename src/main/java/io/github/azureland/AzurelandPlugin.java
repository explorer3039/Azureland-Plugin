package io.github.azureland;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.plugin.java.JavaPlugin;

public final class AzurelandPlugin extends JavaPlugin {
    private static final int CONFIG_VERSION = 4;
    private HelpMeCommand helpMe;
    private volatile Double quickFixCost;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            upgradeConfig();
        } catch (IOException | InvalidConfigurationException ex) {
            getLogger().severe("无法读取或升级 config.yml：" + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        helpMe = new HelpMeCommand(this);
        PluginCommand helpCommand = Objects.requireNonNull(getCommand("helpme"),
                "helpme must be registered in plugin.yml");
        helpCommand.setExecutor(helpMe);
        helpCommand.setTabCompleter(helpMe);
        PluginCommand command = Objects.requireNonNull(getCommand("quickfix"),
                "quickfix must be registered in plugin.yml");
        command.setExecutor(this);
        command.setTabCompleter(this);
        WorkstationCommand workstation = new WorkstationCommand();
        for (String name : WorkstationCommand.COMMANDS) {
            PluginCommand workstationCommand = Objects.requireNonNull(getCommand(name),
                    name + " must be registered in plugin.yml");
            workstationCommand.setExecutor(workstation);
            workstationCommand.setTabCompleter(workstation);
        }
    }

    void upgradeConfig() throws IOException, InvalidConfigurationException {
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration current = new YamlConfiguration();
        current.options().parseComments(true);
        current.load(file);
        Object version = current.get("config-version", 0);
        if (!(version instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < 0) {
            throw new InvalidConfigurationException("config-version 必须是非负整数。");
        }
        if (number.intValue() < CONFIG_VERSION) {
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.options().parseComments(true);
            try (InputStreamReader reader = new InputStreamReader(
                    Objects.requireNonNull(getResource("config.yml")), StandardCharsets.UTF_8)) {
                defaults.load(reader);
            }
            for (String key : defaults.getKeys(true)) {
                if (!defaults.isConfigurationSection(key) && !current.contains(key, true)) {
                    current.set(key, defaults.get(key));
                    current.setComments(key, defaults.getComments(key));
                }
            }
            current.set("config-version", CONFIG_VERSION);
            current.set("ai.global-daily-token-limit", null);
            current.set("ai.player-daily-token-limit", null);
            current.save(file);
            getLogger().info("config.yml 已升级到版本 " + CONFIG_VERSION + "，已有配置值保持不变。");
        }
        reloadConfig();
        Object configuredCost = getConfig().get("quickfix.cost", 30);
        if (configuredCost instanceof Number cost && Double.isFinite(cost.doubleValue())
                && cost.doubleValue() >= 0) {
            quickFixCost = cost.doubleValue();
        } else {
            quickFixCost = null;
            getLogger().warning("quickfix.cost 必须是非负有限数值，快速修复已停用。");
        }
    }

    @Override
    public void onDisable() {
        if (helpMe != null) {
            helpMe.close();
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

        Double cost = quickFixCost;
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
            if (!getServer().getPluginManager().isPluginEnabled("Vault")
                    && !getServer().getPluginManager().isPluginEnabled("VaultUnlocked")) {
                player.sendMessage(ChatColor.RED + "未接入 Vault 经济服务，暂时无法付费修复，请联系管理员。");
                return true;
            }
            if (!QuickFixEconomy.withdraw(this, player, cost)) {
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
}
